package io.aria.conductor.app;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreAdapters;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreCatalog;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.DefaultAgentExecutionPolicy;
import io.aria.conductor.execution.runtime.ExecutionBackend;
import io.aria.conductor.execution.runtime.ExecutionBackendRegistry;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RunFinalizer;
import io.aria.conductor.execution.runtime.RunRuntimeRegistry;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.TaskDeadlineProperties;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.WorkspaceService;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run-owned binding row of one coordinated attempt against the real
 * {@code run_execution_bindings} store (real H2, real Flyway schema, real
 * {@code @Version}): the binding is written once when the launcher freezes it,
 * and every runtime-state transition of the owning run afterwards updates only
 * the runtime half of that row and advances its version by exactly one.
 *
 * <p>The defect this class pins (a coordinated run failed before any approval
 * ask with {@code ObjectOptimisticLockingFailureException} on
 * {@code RunExecutionBinding#<runId>}): the coordinator kept the binding
 * instance read once at launch and re-saved it for every state transition.
 * Hibernate's merge (what the repository's {@code save} runs for an entity with
 * an assigned id) advances the version of the managed copy it loads, never of
 * the argument, so the second save carried the version from before the
 * coordinator's own first write and was refused as stale -- the run failed on
 * its own record. The unit lane could not see it because it mocks the binding
 * repository.
 *
 * <p>The stubs of this class mirror {@code CoreExecutionServiceTest}'s
 * recording doubles; only the binding repository is real, and the launcher and
 * the coordinator are the production classes with no test seam.
 */
class CoreBindingPersistIntegrationTest extends BaseH2IntegrationTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-00000000b1a1");
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-00000000b1a2");
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-00000000b1a3");
    /** A whole-second instant: the freeze truncates the deadline to seconds (TIMESTAMP column). */
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant AGENT_UPDATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** The deadline freeze exact value: now + the documented 45 minute default, to the second. */
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(45));
    /** The frozen settings snapshot exactly as the launcher serializes it (Task 2's pinned format). */
    private static final String SETTINGS_JSON = "{\"coreId\":\"qoder\",\"executionMode\":\"HOST\","
            + "\"workspaceMode\":\"DIRECT\",\"workspacePath\":\"C:/work\",\"workspaceBaseRef\":null}";

    @Autowired RunExecutionBindingRepository bindings;

    /** Each test freezes its own row: the store carries no leftover binding of a previous case. */
    @BeforeEach
    void cleanBindings() {
        bindings.deleteAll();
    }

    private final PermissionCoordinator permissions = mock(PermissionCoordinator.class);
    private final CoreCredentialService credentials = mock(CoreCredentialService.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final RunRuntimeRegistry runtimes = new RunRuntimeRegistry();
    private final RecordingBackend backend = new RecordingBackend();
    private final RecordingWorkspace workspaces = new RecordingWorkspace();
    private final RecordingSession session = new RecordingSession();
    private final RecordingAdapter adapter = new RecordingAdapter();

    private CoreExecutionService coordinator() {
        when(credentials.resolve("qoder:operator")).thenReturn(new SecretBundle("qoder:operator",
                Map.of("QODER_PERSONAL_ACCESS_TOKEN", "synthetic-secret")));
        return new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens,
                bindings, CLOCK, Duration.ofMinutes(5));
    }

    private CoreRunLauncher launcher(CoreExecutionService coordinator) {
        CoreCatalog catalog = new CoreCatalog(Map.of("qoder", Set.of(ExecutionMode.HOST)));
        return new CoreRunLauncher(new DefaultAgentExecutionPolicy(catalog), new CoreAdapters(List.of(adapter)),
                coordinator, bindings, new TaskDeadlineProperties(), CLOCK);
    }

    private static Agent qoderHostAgent() {
        return Agent.builder().id(AGENT).name("e2e-core-binding-agent").adkProvider("qoder")
                .executionMode(ExecutionMode.HOST).workspaceMode(WorkspaceMode.DIRECT)
                .workspacePath("C:/work").updatedAt(AGENT_UPDATED_AT).build();
    }

    private static Run run() {
        return Run.builder().id(RUN).agentId(AGENT).build();
    }

    /** The agent's configuration revision as the freeze records it. */
    private static String configurationRevision() {
        return AGENT + "@" + AGENT_UPDATED_AT;
    }

    // ------------------------------------------------------------------ tests

    /**
     * RED on the pre-fix code: the terminal transition re-saved the instance read
     * at launch and the store refused it as stale
     * ({@code ObjectOptimisticLockingFailureException}).
     */
    @Test
    void theRunOwnedStateTransitionsUpdateOnlyTheRuntimeHalfAndAdvanceTheVersionByOne() {
        CoreRunLauncher launcher = launcher(coordinator());

        CoreResult result = launcher.execute(run(), qoderHostAgent(), new CoreTask("system", List.of(), "do work"));

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(backend.destroyed).isTrue();

        RunExecutionBinding stored = bindings.findById(RUN).orElseThrow();
        // One write at freeze (version 0), one for the RUNNING transition, one for the terminal transition.
        assertThat(stored.getVersion()).isEqualTo(2L);
        // The runtime half is the observed state of the attempt.
        assertThat(stored.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
        assertThat(stored.getRuntimeEnvironmentId()).isEqualTo("env-1");
        assertThat(stored.getRuntimeOwnershipIdentity()).isEqualTo("run-owner-identity");
        assertThat(stored.getRuntimeEndpoint()).isEqualTo("http://127.0.0.1:9311/");
        assertThat(stored.getUsageInputTokens()).isEqualTo(12L);
        assertThat(stored.getUsageOutputTokens()).isEqualTo(7L);
        assertThat(stored.getObservedModel()).isEqualTo("efficient");
        assertThat(stored.getUpdatedAt()).isNotNull();
        // The frozen half is exactly the freeze: never re-resolved, never re-frozen.
        assertThat(stored.getAgentId()).isEqualTo(AGENT);
        assertThat(stored.getCoreId()).isEqualTo("qoder");
        assertThat(stored.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(stored.getSettingsJson()).isEqualTo(SETTINGS_JSON);
        assertThat(stored.getCredentialRef()).isEqualTo("qoder:operator");
        assertThat(stored.getConfigurationRevision()).isEqualTo(configurationRevision());
        assertThat(stored.getDeadline()).isEqualTo(DEADLINE);
        assertThat(stored.getWorkspaceKind()).isNull();
        assertThat(stored.getWorkspaceLeaseId()).isNull();
        assertThat(stored.getWorkspaceRoot()).isNull();
        assertThat(stored.getWorkspaceSourceRoot()).isNull();
        assertThat(stored.getWorkspaceBaseCommit()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    /**
     * A frozen field moved by another writer is refused loudly: the run never
     * rewrites the frozen half of its row (no silent re-freeze), so the refusal
     * states the field and both values.
     */
    @Test
    void aFrozenRowThatMovedUnderTheRunIsRefusedInsteadOfBeingRewritten() {
        CoreRunLauncher launcher = launcher(coordinator());
        Instant movedDeadline = DEADLINE.plus(Duration.ofHours(5));
        session.duringPrompt = () -> {
            RunExecutionBinding row = bindings.findById(RUN).orElseThrow();
            row.setDeadline(movedDeadline);
            bindings.save(row);
        };

        assertThatThrownBy(() -> launcher.execute(run(), qoderHostAgent(), new CoreTask("system", List.of(), "do work")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with deadline " + DEADLINE
                        + " but its binding row now carries " + movedDeadline
                        + "; the frozen half of the binding is never rewritten by the run");

        RunExecutionBinding stored = bindings.findById(RUN).orElseThrow();
        assertThat(stored.getDeadline()).isEqualTo(movedDeadline);
        assertThat(stored.getCoreId()).isEqualTo("qoder");
        // The external write is the last write of the row: the run's own terminal
        // transition was refused, so the version stays where the external writer left it.
        assertThat(stored.getVersion()).isEqualTo(2L);
        assertThat(stored.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
    }

    /**
     * A control write issued on another thread while the attempt is live is
     * serialized with the attempt's own transitions: every write advances the
     * row's version by exactly one and none is refused as stale, so the row
     * ends at the version the number of its own writes implies and the terminal
     * state is the one written last.
     */
    @Test
    void aControlWriteFromAnotherThreadSerializesWithTheAttemptsOwnTransitions() {
        CoreExecutionService coordinator = coordinator();
        CoreRunLauncher launcher = launcher(coordinator);
        session.duringPrompt = () -> {
            Thread control = new Thread(() -> coordinator.cancel(RUN).toCompletableFuture().join(),
                    "core-binding-control");
            control.start();
            try {
                control.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        CoreResult result = launcher.execute(run(), qoderHostAgent(), new CoreTask("system", List.of(), "do work"));

        assertThat(result.cancelled()).isTrue();
        RunExecutionBinding stored = bindings.findById(RUN).orElseThrow();
        // freeze (0) -> RUNNING (1) -> the control write (2) -> the terminal write (3)
        assertThat(stored.getVersion()).isEqualTo(3L);
        assertThat(stored.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        assertThat(stored.getUsageInputTokens()).isEqualTo(12L);
        assertThat(stored.getUsageOutputTokens()).isEqualTo(7L);
        assertThat(stored.getObservedModel()).isEqualTo("efficient");
        assertThat(stored.getCoreId()).isEqualTo("qoder");
        assertThat(stored.getSettingsJson()).isEqualTo(SETTINGS_JSON);
        assertThat(stored.getDeadline()).isEqualTo(DEADLINE);
    }

    // ------------------------------------------------------------------ doubles

    private final class RecordingBackend implements ExecutionBackend {

        final Deque<StopProof> proofs = new ArrayDeque<>();

        boolean destroyed;

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
            return new PreparedEnvironment(spec.runId(), ExecutionMode.HOST, "env-1",
                    "C:/work", "C:/runtime/runs/" + spec.runId(), URI.create("http://127.0.0.1:9311/"));
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
            return new RuntimeHandle(RUN, ExecutionMode.HOST, environment.environmentId(),
                    "run-owner-identity", environment.endpoint());
        }

        @Override
        public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(new ControlAck(ControlState.PAUSED, true));
        }

        @Override
        public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, true));
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
            StopProof proof = proofs.isEmpty() ? new StopProof(RUN, true) : proofs.removeFirst();
            return proof;
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            // no export in the HOST/direct shape of this fixture
        }

        @Override
        public void destroy(RuntimeHandle handle) {
            destroyed = true;
        }
    }

    private final class RecordingWorkspace implements WorkspaceService {

        final ArtifactBundle bundle = new ArtifactBundle(Path.of("results", RUN.toString()), "sha256-manifest", true);

        @Override
        public WorkspaceLease acquire(ExecutionSpec spec) {
            return new WorkspaceLease(LEASE, spec.runId(), WorkspaceKind.DIRECT,
                    Path.of("C:/work"), Path.of("C:/work"), "C:/runtime/runs/" + spec.runId(), null);
        }

        @Override
        public ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend,
                RuntimeHandle handle, StopProof proof) {
            return bundle;
        }

        @Override
        public void release(WorkspaceLease lease, StopProof proof) {
            // nothing held outside the fixture
        }
    }

    private final class RecordingSession implements CoreSession {

        final List<CoreEvent> events = new ArrayList<>();

        Runnable duringPrompt;

        @Override
        public String sessionId() {
            return "session-1";
        }

        @Override
        public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> consumer) {
            events.forEach(consumer);
            if (duringPrompt != null) {
                duringPrompt.run();
            }
            return CompletableFuture.completedFuture(new CoreResult("session-1", "final output",
                    new UsageSnapshot(12L, 7L, null, "efficient"), false));
        }

        @Override
        public CompletionStage<ControlAck> pause(Instant deadline) {
            return CompletableFuture.completedFuture(new ControlAck(ControlState.PAUSED, true));
        }

        @Override
        public CompletionStage<ControlAck> resume(Instant deadline) {
            return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, true));
        }

        @Override
        public CompletionStage<ControlAck> cancel(Instant deadline) {
            return CompletableFuture.completedFuture(new ControlAck(ControlState.STOPPED, true));
        }

        @Override
        public CompletionStage<Void> decide(io.aria.conductor.execution.approval.PermissionReply reply) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private final class RecordingAdapter implements CoreAdapter {

        @Override
        public String coreId() {
            return "qoder";
        }

        @Override
        public CoreCapabilities capabilities(ExecutionMode mode) {
            return new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true);
        }

        @Override
        public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment, SecretBundle creds) {
            return new LaunchProfile(List.of("qodercli", "--acp"),
                    Map.of("QODER_PERSONAL_ACCESS_TOKEN", "synthetic-secret"), environment.workingDirectory());
        }

        @Override
        public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
            return session;
        }
    }
}
