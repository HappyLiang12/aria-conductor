package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The run coordinator's truthfulness: a control state is persisted only after
 * the matching verified acknowledgement, a prompt completion is never a stop
 * proof, the run-owned handle/session is reused on resume, held permission
 * decisions are delivered only after a resume re-validates them, and the
 * finalization order is the one {@link RunFinalizer} owns.
 */
class CoreExecutionServiceTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID APPROVAL = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final Instant NOW = Instant.now();
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(45));
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentExecutionSettings SETTINGS =
            new AgentExecutionSettings("qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT, "C:/work", null);
    /** The frozen settings snapshot exactly as the binding row records it (Task 2's pinned serialization). */
    private static final String SETTINGS_JSON = "{\"coreId\":\"qoder\",\"executionMode\":\"HOST\","
            + "\"workspaceMode\":\"DIRECT\",\"workspacePath\":\"C:/work\",\"workspaceBaseRef\":null}";

    private final List<String> recordedSteps = new ArrayList<>();
    private final PermissionCoordinator permissions = mock(PermissionCoordinator.class);
    private final RuntimeCredentialService credentials = mock(RuntimeCredentialService.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final RunExecutionBindingRepository bindings = mock(RunExecutionBindingRepository.class);
    private final RunRuntimeRegistry runtimes = new RunRuntimeRegistry();

    private RecordingBackend backend;
    private RecordingWorkspace workspaces;
    private RecordingSession session;
    private RecordingAdapter adapter;
    private RunExecutionBinding binding;
    private CoreExecutionService service;

    // ------------------------------------------------------------------ doubles

    private final class RecordingBackend implements ExecutionBackend {
        final Deque<StopProof> proofs = new ArrayDeque<>();
        final Deque<ControlAck> pauseAcks = new ArrayDeque<>();
        final Deque<ControlAck> resumeAcks = new ArrayDeque<>();
        final List<StopProof> stopProofs = new ArrayList<>();
        int launchCalls;
        boolean destroyed;
        Runnable duringStopWriters;

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
            recordedSteps.add("prepare");
            return new PreparedEnvironment(spec.runId(), ExecutionMode.HOST, "env-1",
                    "C:/work", "C:/runtime/runs/" + spec.runId(), URI.create("http://127.0.0.1:9311/"));
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
            recordedSteps.add("launch");
            launchCalls++;
            return new RuntimeHandle(RUN, ExecutionMode.HOST, environment.environmentId(),
                    "run-owner-identity", environment.endpoint());
        }

        @Override
        public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(pauseAcks.isEmpty()
                    ? new ControlAck(ControlState.RUNNING, false) : pauseAcks.removeFirst());
        }

        @Override
        public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(resumeAcks.isEmpty()
                    ? new ControlAck(ControlState.RUNNING, true) : resumeAcks.removeFirst());
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
            recordedSteps.add("stop-writers");
            StopProof proof = proofs.isEmpty() ? new StopProof(RUN, true) : proofs.removeFirst();
            stopProofs.add(proof);
            if (duringStopWriters != null) {
                duringStopWriters.run();
            }
            return proof;
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            recordedSteps.add("export");
        }

        @Override
        public void destroy(RuntimeHandle handle) {
            recordedSteps.add("destroy");
            destroyed = true;
        }
    }

    private final class RecordingWorkspace implements WorkspaceService {
        final ArtifactBundle bundle = new ArtifactBundle(Path.of("results", RUN.toString()), "sha256-manifest", true);
        StopProof capturedProof;
        StopProof releasedProof;

        @Override
        public WorkspaceLease acquire(ExecutionSpec spec) {
            recordedSteps.add("acquire");
            return new WorkspaceLease(LEASE, spec.runId(), WorkspaceKind.DIRECT,
                    Path.of("C:/work"), Path.of("C:/work"), "C:/runtime/runs/" + spec.runId(), null);
        }

        @Override
        public ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend,
                RuntimeHandle handle, StopProof proof) {
            recordedSteps.add("capture");
            capturedProof = proof;
            return bundle;
        }

        @Override
        public void release(WorkspaceLease lease, StopProof proof) {
            recordedSteps.add("release");
            releasedProof = proof;
        }
    }

    private final class RecordingSession implements CoreSession {
        CoreTask prompted;
        final List<CoreEvent> events = new ArrayList<>();
        final List<PermissionReply> decided = new ArrayList<>();
        final Deque<ControlAck> pauseAcks = new ArrayDeque<>();
        final Deque<ControlAck> resumeAcks = new ArrayDeque<>();
        RuntimeException promptFailure;
        Runnable duringPrompt;

        @Override
        public String sessionId() {
            return "session-1";
        }

        @Override
        public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> consumer) {
            recordedSteps.add("prompt");
            prompted = task;
            events.forEach(consumer);
            if (duringPrompt != null) {
                duringPrompt.run();
            }
            if (promptFailure != null) {
                return CompletableFuture.failedFuture(promptFailure);
            }
            return CompletableFuture.completedFuture(new CoreResult("session-1", "final output",
                    new UsageSnapshot(12L, 7L, null, "efficient"), false));
        }

        @Override
        public CompletionStage<ControlAck> pause(Instant deadline) {
            recordedSteps.add("session-pause");
            return CompletableFuture.completedFuture(pauseAcks.isEmpty()
                    ? new ControlAck(ControlState.PAUSED, true) : pauseAcks.removeFirst());
        }

        @Override
        public CompletionStage<ControlAck> resume(Instant deadline) {
            recordedSteps.add("session-resume");
            return CompletableFuture.completedFuture(resumeAcks.isEmpty()
                    ? new ControlAck(ControlState.RUNNING, true) : resumeAcks.removeFirst());
        }

        @Override
        public CompletionStage<ControlAck> cancel(Instant deadline) {
            recordedSteps.add("session-cancel");
            return CompletableFuture.completedFuture(new ControlAck(ControlState.STOPPED, true));
        }

        @Override
        public CompletionStage<Void> decide(PermissionReply reply) {
            recordedSteps.add("decide");
            decided.add(reply);
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
            recordedSteps.add("launch-profile");
            return new LaunchProfile(List.of("qodercli", "--acp"),
                    Map.of("QODER_PERSONAL_ACCESS_TOKEN", "secret"), environment.workingDirectory());
        }

        @Override
        public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle creds) {
            recordedSteps.add("open-session");
            return session;
        }
    }

    /** A test clock the class advances itself; the service never reads the wall clock through it. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * The deadline scheduler as a deterministic double: {@code schedule} captures
     * the armed run-deadline target (and its delay) instead of racing a real
     * timer, so a test fires the target exactly when the run's deadline elapses.
     */
    private static final class ManualDeadlineScheduler extends ScheduledThreadPoolExecutor {
        private Runnable target;
        private long delayMillis;
        private volatile boolean cancelled;

        ManualDeadlineScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            this.target = command;
            this.delayMillis = unit.toMillis(delay);
            return new ScheduledFuture<Object>() {
                @Override
                public long getDelay(TimeUnit unit) {
                    return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
                }

                @Override
                public int compareTo(Delayed other) {
                    return 0;
                }

                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    cancelled = true;
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }

                @Override
                public boolean isDone() {
                    return cancelled;
                }

                @Override
                public Object get() {
                    return null;
                }

                @Override
                public Object get(long timeout, TimeUnit unit) {
                    return null;
                }
            };
        }

        /** Runs the captured run-deadline target: the timer firing as scheduled. */
        void fireDeadline() {
            cancelled = false;
            target.run();
        }
    }

    // ------------------------------------------------------------------ fixture

    @BeforeEach
    void setUp() {
        recordedSteps.clear();
        backend = new RecordingBackend();
        workspaces = new RecordingWorkspace();
        session = new RecordingSession();
        adapter = new RecordingAdapter();
        binding = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .settingsJson(SETTINGS_JSON)
                .workspaceKind(WorkspaceKind.DIRECT).workspaceRoot("C:/work").workspaceLeaseId(LEASE)
                .credentialRef("qoder:operator").configurationRevision("rev-1")
                .deadline(DEADLINE).build();
        when(bindings.findById(RUN)).thenReturn(Optional.of(binding));
        when(credentials.resolve("qoder:operator")).thenReturn(new SecretBundle("qoder:operator",
                Map.of("QODER_PERSONAL_ACCESS_TOKEN", "secret")));
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5));
    }

    private ExecutionSpec spec() {
        return new ExecutionSpec(RUN, AGENT, "qoder", ExecutionMode.HOST, SETTINGS,
                "qoder:operator", "rev-1", DEADLINE);
    }

    private CoreTask task() {
        return new CoreTask("system prompt", List.of(LlmMessage.user("first"), LlmMessage.assistant("answer")),
                "do the work");
    }

    /** The pre-prompt half of {@link CoreExecutionService#execute}: a live run-owned runtime. */
    private void runOwnedInProcess() {
        service.openRuntime(spec(), adapter);
    }

    // ------------------------------------------------------------------ execute

    @Test
    void executePreparesLaunchesPromptsWithTheFullHistoryAndFinalizesInOrder() {
        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(result.cancelled()).isFalse();
        assertThat(result.usage()).isEqualTo(new UsageSnapshot(12L, 7L, null, "efficient"));
        // The whole accepted history is handed to the core -- context is not replayed action by action.
        assertThat(session.prompted).isEqualTo(new CoreTask("system prompt",
                List.of(LlmMessage.user("first"), LlmMessage.assistant("answer")), "do the work"));
        assertThat(workspaces.capturedProof).isSameAs(backend.stopProofs.get(0));
        assertThat(workspaces.releasedProof).isSameAs(backend.stopProofs.get(0));
        assertThat(backend.launchCalls).isEqualTo(1);
        // The terminal state carries the chosen control strategy; worker tokens are revoked.
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
        assertThat(binding.getRuntimeEnvironmentId()).isEqualTo("env-1");
        assertThat(binding.getRuntimeOwnershipIdentity()).isEqualTo("run-owner-identity");
        assertThat(binding.getRuntimeEndpoint()).isEqualTo("http://127.0.0.1:9311/");
        assertThat(binding.getUsageInputTokens()).isEqualTo(12L);
        assertThat(binding.getUsageOutputTokens()).isEqualTo(7L);
        assertThat(binding.getObservedModel()).isEqualTo("efficient");
        verify(actorTokens).revokeRun(RUN);
        verify(permissions, never()).manualPause(RUN);
        assertThat(service.activeRuns(AGENT)).isEmpty();
        assertThat(service.writersStopped(RUN)).isTrue();
        // The terminal path records the finalized bookkeeping Task 14/18 read.
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
    }

    @Test
    void aRunWithoutAFrozenBindingIsRefusedBeforeAnythingIsPrepared() {
        when(bindings.findById(RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " has no frozen execution binding; refusing to launch"
                        + " without an immutable binding");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    // ------------------------------------------------------------------ frozen workspace/settings match

    @Test
    void aMatchingFrozenWorkspaceAndSettingsSnapshotAreAccepted() {
        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(binding.getWorkspaceLeaseId()).isEqualTo(LEASE);
        assertThat(binding.getWorkspaceRoot()).isEqualTo("C:/work");
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
    }

    @Test
    void aFrozenWorkspaceKindMismatchIsRefusedBeforeAnythingIsAcquired() {
        binding.setWorkspaceKind(WorkspaceKind.WORKTREE);

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with workspace kind WORKTREE but the attempt requests"
                        + " DIRECT; the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aFrozenWorkspaceRootMismatchIsRefusedBeforeAnythingIsAcquired() {
        binding.setWorkspaceRoot("C:/somewhere/else");

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with workspace root C:/somewhere/else but the attempt"
                        + " requests C:/work; the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aFrozenSettingsSnapshotMismatchIsRefusedBeforeAnythingIsAcquired() {
        binding.setSettingsJson(SETTINGS_JSON.replace("C:/work", "C:/a-different-selection"));

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with settings AgentExecutionSettings[coreId=qoder,"
                        + " executionMode=HOST, workspaceMode=DIRECT, workspacePath=C:/a-different-selection,"
                        + " workspaceBaseRef=null] but the attempt requests AgentExecutionSettings[coreId=qoder,"
                        + " executionMode=HOST, workspaceMode=DIRECT, workspacePath=C:/work, workspaceBaseRef=null];"
                        + " the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void anUnreadableFrozenSettingsSnapshotIsRefusedInsteadOfSkipped() {
        binding.setSettingsJson("{not the frozen AgentExecutionSettings snapshot");

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Run " + RUN + " carries an unreadable frozen settings snapshot")
                .hasMessageContaining("the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).isEmpty();
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aFrozenWorkspaceLeaseMismatchIsRefusedAfterTheAttemptsOwnAcquisition() {
        UUID frozenLease = UUID.fromString("00000000-0000-0000-0000-0000000000af");
        binding.setWorkspaceLeaseId(frozenLease);

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " was frozen with workspace lease " + frozenLease
                        + " but the attempt acquired " + LEASE
                        + "; the immutable binding is never re-resolved from mutable settings");

        assertThat(recordedSteps).containsExactly("acquire");
        assertThat(backend.launchCalls).isZero();
    }

    @Test
    void aPromptFailureStillStopsCapturesDestroysAndReleases() {
        session.promptFailure = new IllegalStateException("the bridge event stream failed");

        assertThatThrownBy(() -> service.execute(spec(), adapter, task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + RUN + " failed: the bridge event stream failed");

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release");
        assertThat(binding.getRuntimeState()).isEqualTo("FAILED/BACKEND_SUSPEND");
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
        verify(actorTokens).revokeRun(RUN);
    }

    // ------------------------------------------------------------------ pause / resume

    @Test
    void anUnverifiedPauseIsNeverPersistedAndCarriesTheExactFailureReason() {
        runOwnedInProcess();
        session.pauseAcks.add(new ControlAck(ControlState.RUNNING, false));

        ControlAck ack = service.pause(RUN).toCompletableFuture().join();

        assertThat(ack).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(binding.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.PAUSED, NOW,
                "Pause refused for run " + RUN + ": the core reported RUNNING (verified=false)"));
        verify(permissions, never()).manualPause(RUN);
    }

    @Test
    void aVerifiedPauseIsPersistedOnceAndHoldsPermissionDelivery() {
        runOwnedInProcess();
        session.pauseAcks.add(new ControlAck(ControlState.PAUSED, true));

        ControlAck ack = service.pause(RUN).toCompletableFuture().join();

        assertThat(ack).isEqualTo(new ControlAck(ControlState.PAUSED, true));
        assertThat(binding.getRuntimeState()).isEqualTo("PAUSED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).isEmpty();
        verify(permissions).manualPause(RUN);
    }

    @Test
    void resumeReusesTheSameHandleAndDeliversHeldDecisionsOnlyAfterRevalidating() {
        // A native permission ask registered while the run was live, then a manual pause.
        session.events.add(new CoreEvent("permission.request", RUN, "session-1", "7",
                "{\"requestId\":7,\"toolName\":\"write_file\",\"options\":"
                        + "[{\"optionId\":\"proceed_once\",\"kind\":\"allow_once\"},"
                        + "{\"optionId\":\"cancel\",\"kind\":\"reject_once\"}]}"));
        when(permissions.register(any())).thenReturn(APPROVAL);
        // While manually paused the decision is recorded, not delivered: only resume releases it.
        when(permissions.deliverPending(APPROVAL)).thenReturn(Optional.empty());
        PermissionReply heldReply = new PermissionReply(RUN, "session-1", "7", "proceed_once");
        session.duringPrompt = () -> {
            assertThat(service.pause(RUN).toCompletableFuture().join())
                    .isEqualTo(new ControlAck(ControlState.PAUSED, true));
            when(permissions.deliverPending(APPROVAL)).thenReturn(Optional.of(heldReply));
            assertThat(service.resume(RUN).toCompletableFuture().join())
                    .isEqualTo(new ControlAck(ControlState.RUNNING, true));
        };

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(session.decided).containsExactly(heldReply);
        assertThat(backend.launchCalls).isEqualTo(1); // the same run-owned handle/session, never relaunched
        verify(permissions).manualPause(RUN);
        verify(permissions).manualResume(RUN);
        assertThat(binding.getRuntimeState()).isEqualTo("COMPLETED/BACKEND_SUSPEND");
    }

    @Test
    void aDecidedNativeReplyIsHandedToTheRunOwnedSessionAndNowhereElse() {
        runOwnedInProcess();

        PermissionReply reply = new PermissionReply(RUN, "session-1", "7", "proceed_once");
        service.deliver(reply);

        // The direct (non-held) decision reaches the one session waiting for it.
        assertThat(session.decided).containsExactly(reply);
        verify(permissions, never()).deliverPending(any());
    }

    @Test
    void aDecidedReplyForARunThisProcessDoesNotOwnIsDroppedInsteadOfRouted() {
        // No run-owned runtime in this process: there is no session to hand the
        // reply to, and it is never routed to another run or fabricated.
        service.deliver(new PermissionReply(RUN, "session-1", "7", "proceed_once"));

        assertThat(session.decided).isEmpty();
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelPersistsCancelledOnlyAfterTheMatchingVerifiedStopProof() {
        runOwnedInProcess();
        backend.proofs.add(new StopProof(RUN, false));

        ControlAck refused = service.cancel(RUN).toCompletableFuture().join();

        assertThat(refused).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(binding.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW,
                "Stop refused for run " + RUN + ": the backend reported allWritersStopped=false"));
        assertThat(backend.destroyed).isFalse();
        verify(actorTokens, never()).revokeRun(RUN);

        backend.proofs.add(new StopProof(RUN, true));
        ControlAck cancelled = service.cancel(RUN).toCompletableFuture().join();

        assertThat(cancelled).isEqualTo(new ControlAck(ControlState.STOPPED, true));
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).isEmpty();
        assertThat(service.writersStopped(RUN)).isTrue();
        assertThat(service.activeRuns(AGENT)).isEmpty();
        // A verified cancel stops the writers but is not a finalization: capture/destroy/release is pending.
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isFalse();
        verify(actorTokens).revokeRun(RUN);
    }

    @Test
    void aForeignStopProofNeverPersistsACancellation() {
        runOwnedInProcess();
        UUID otherRun = UUID.fromString("00000000-0000-0000-0000-0000000000a8");
        backend.proofs.add(new StopProof(otherRun, true));
        backend.proofs.add(new StopProof(otherRun, true));

        ControlAck ack = service.cancel(RUN).toCompletableFuture().join();

        assertThat(ack).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(binding.getRuntimeState()).isEqualTo("RUNNING/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW,
                "Stop refused for run " + RUN + ": the backend returned a stop proof for run " + otherRun
                        + ", not for this run"));
        verify(actorTokens, never()).revokeRun(RUN);
        assertThat(backend.destroyed).isFalse();
    }

    @Test
    void aRefusedCancelOfAPausedRunAcksTheFrozenPausedStateNeverRunning() {
        runOwnedInProcess();
        session.pauseAcks.add(new ControlAck(ControlState.PAUSED, true));
        assertThat(service.pause(RUN).toCompletableFuture().join())
                .isEqualTo(new ControlAck(ControlState.PAUSED, true));
        backend.proofs.add(new StopProof(RUN, false));

        ControlAck refused = service.cancel(RUN).toCompletableFuture().join();

        // The frozen verified state is PAUSED, so the refusal acknowledges PAUSED (verified=false):
        // the ack never claims the paused run is RUNNING, and nothing was persisted.
        assertThat(refused).isEqualTo(new ControlAck(ControlState.PAUSED, false));
        assertThat(binding.getRuntimeState()).isEqualTo("PAUSED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW,
                "Stop refused for run " + RUN + ": the backend reported allWritersStopped=false"));
        assertThat(service.writersStopped(RUN)).isFalse();
        verify(actorTokens, never()).revokeRun(RUN);
        assertThat(backend.destroyed).isFalse();
    }

    @Test
    void aCancelledPromptFinishesThroughTheFinalizerAndReportsCancellation() {
        session.duringPrompt = () -> assertThat(service.cancel(RUN).toCompletableFuture().join())
                .isEqualTo(new ControlAck(ControlState.STOPPED, true));

        CoreResult result = service.execute(spec(), adapter, task());

        assertThat(result.cancelled()).isTrue();
        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        // The cancel's own stop, then the finalizer's re-stop before capture/destroy/release.
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "session-cancel", "stop-writers", "stop-writers",
                "capture", "destroy", "release");
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true), new StopProof(RUN, true));
        assertThat(workspaces.capturedProof).isEqualTo(new StopProof(RUN, true));
        // Once at the verified cancel, once on the terminal path; revocation is idempotent.
        verify(actorTokens, times(2)).revokeRun(RUN);
    }

    @Test
    void theRunDeadlineTimerCancelsAnInFlightPromptThroughTheVerifiedStopPath() {
        ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler();
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, permissions, credentials, actorTokens, bindings,
                CLOCK, Duration.ofMinutes(5), scheduler);
        // The frozen deadline elapses while the prompt is in flight: the timer target fires now.
        session.duringPrompt = scheduler::fireDeadline;

        CoreResult result = service.execute(spec(), adapter, task());

        // The timer target drove exactly the operator cancel path: cooperative session cancel,
        // then a verified stop proof, then the finalizer's re-stop before capture/destroy/release.
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "prompt", "session-cancel", "stop-writers", "stop-writers",
                "capture", "destroy", "release");
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, true), new StopProof(RUN, true));
        assertThat(result.cancelled()).isTrue();
        assertThat(binding.getRuntimeState()).isEqualTo("CANCELLED/BACKEND_SUSPEND");
        assertThat(service.pendingControl(RUN)).isEmpty();
        assertThat(runtimes.find(RUN).orElseThrow().finalized()).isTrue();
        verify(actorTokens, times(2)).revokeRun(RUN);
        // The task was armed from the injected clock and the frozen deadline, then cancelled in the finally.
        assertThat(scheduler.delayMillis).isEqualTo(Duration.ofMinutes(45).toMillis());
        assertThat(scheduler.cancelled).isTrue();
    }

    @Test
    void anAbortedLaunchRetriesOnlyInsideItsOneHoistedCleanupWindow() {
        MutableClock advancing = new MutableClock(NOW);
        CoreExecutionService abortService = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)),
                workspaces, new RunFinalizer(workspaces, advancing), runtimes, permissions, credentials,
                actorTokens, bindings, advancing, Duration.ofMinutes(5));
        abortService.openRuntime(spec(), adapter);
        RunRuntimeRegistry.RunRuntime runtime = runtimes.find(RUN).orElseThrow();
        // The stop stays refused, and the first attempt burns the whole window: a window
        // recomputed per attempt would mint a fresh 5 minutes and retry outside the bound
        // abortLaunch was entered with. The clock is advanced by the stop itself.
        backend.proofs.add(new StopProof(RUN, false));
        backend.duringStopWriters = () -> advancing.advance(Duration.ofMinutes(6));

        abortService.abortLaunch(runtime, runtime.lease());

        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session", "stop-writers"); // exactly one attempt, inside the hoisted window
        assertThat(backend.stopProofs).containsExactly(new StopProof(RUN, false));
        assertThat(backend.destroyed).isFalse();
        assertThat(workspaces.releasedProof).isNull();
        assertThat(runtimes.find(RUN)).isPresent(); // the fail-closed record outlives the refused abort
        assertThat(abortService.pendingControl(RUN)).hasValue(new RunRuntimeRegistry.ControlRequest(
                ControlState.STOPPED, NOW.plus(Duration.ofMinutes(6)),
                "Launch abort for run " + RUN + ": the writers were not verified stopped, so the environment"
                        + " and the workspace lease are left in place (destroy after a refused stop is terminal)"));
    }

    // ------------------------------------------------------------------ events

    @Test
    void everyCoreEventIsForwardedToTheObserverInOrder() {
        session.events.add(new CoreEvent("session.update", RUN, "session-1", "1", "{\"seq\":1}"));
        session.events.add(new CoreEvent("prompt.result", RUN, "session-1", "1", "{\"stopReason\":\"end\"}"));
        List<CoreEvent> observed = new ArrayList<>();
        service.observe(RUN, observed::add);

        service.execute(spec(), adapter, task());

        assertThat(observed).containsExactly(
                new CoreEvent("session.update", RUN, "session-1", "1", "{\"seq\":1}"),
                new CoreEvent("prompt.result", RUN, "session-1", "1", "{\"stopReason\":\"end\"}"));
    }

    // ------------------------------------------------------------------ recovery

    private RuntimeRecoveryCoordinator recovery() {
        return new RuntimeRecoveryCoordinator(new ExecutionBackendRegistry(List.of(backend)), runtimes);
    }

    @Test
    void recoveryReapsOnlyARunOwnedRuntimeWithRunStoreEvidence() {
        RunExecutionBinding evidence = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .runtimeEnvironmentId("env-1").runtimeOwnershipIdentity("run-owner-identity")
                .runtimeEndpoint("http://127.0.0.1:9311/").workspaceKind(WorkspaceKind.DIRECT)
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision decision = recovery().reconcile(evidence);

        assertThat(decision.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.REAPED);
        assertThat(decision.reason()).isEqualTo("Destroyed the run-owned runtime of run " + RUN
                + "; its Direct workspace lease stays held (recovery holds no verified stop proof)");
        assertThat(recordedSteps).containsExactly("destroy");
    }

    @Test
    void recoveryHoldsAnUnresolvedRecordInsteadOfActing() {
        RunExecutionBinding noOwnership = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision held = recovery().reconcile(noOwnership);

        assertThat(held.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.HELD);
        assertThat(held.reason()).isEqualTo("Run store records no verifiable ownership identity for run " + RUN
                + "; nothing is adopted or reaped and the workspace lease stays held");
        RuntimeRecoveryCoordinator.RecoveryDecision noEvidence = recovery().reconcile(null);
        assertThat(noEvidence).isEqualTo(new RuntimeRecoveryCoordinator.RecoveryDecision(
                RuntimeRecoveryCoordinator.Decision.HELD,
                "No run-store evidence for a reconstructed record; nothing is adopted or reaped"));
        assertThat(recordedSteps).isEmpty();
    }

    @Test
    void recoveryAdoptsAnAlreadyLiveRuntimeOfTheSameRunOwnership() {
        runOwnedInProcess();
        RunExecutionBinding evidence = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.HOST)
                .runtimeEnvironmentId("env-1").runtimeOwnershipIdentity("run-owner-identity")
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision decision = recovery().reconcile(evidence);

        assertThat(decision.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.ADOPTED);
        assertThat(decision.reason()).isEqualTo("Adopted the live run-owned runtime of run " + RUN
                + " (ownership identity run-owner-identity verified against the run store)");
        assertThat(recordedSteps).containsExactly("acquire", "prepare", "launch-profile", "launch",
                "open-session");
        assertThat(runtimes.activeRuns(AGENT)).containsExactly(RUN);
    }

    @Test
    void recoveryHoldsARecordWhoseModeHasNoRegisteredBackendInsteadOfAborting() {
        // The sweep must hold (fail closed), like the null-mode case, instead of throwing
        // from the backend lookup and aborting every remaining record.
        RunExecutionBinding sandboxEvidence = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("qoder").executionMode(ExecutionMode.SANDBOX)
                .runtimeEnvironmentId("env-1").runtimeOwnershipIdentity("run-owner-identity")
                .runtimeEndpoint("http://127.0.0.1:9311/").workspaceKind(WorkspaceKind.DIRECT)
                .settingsJson("{}").build();

        RuntimeRecoveryCoordinator.RecoveryDecision held = recovery().reconcile(sandboxEvidence);

        assertThat(held.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.HELD);
        assertThat(held.reason()).isEqualTo("Run store records execution mode SANDBOX for run " + RUN
                + ", but this process has no registered backend for that mode; nothing is adopted or reaped"
                + " and the workspace lease stays held");
        assertThat(recordedSteps).isEmpty();
    }
}
