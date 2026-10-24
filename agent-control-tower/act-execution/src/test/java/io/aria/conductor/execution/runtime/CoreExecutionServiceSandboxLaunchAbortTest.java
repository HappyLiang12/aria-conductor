package io.aria.conductor.execution.runtime;

import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.runtime.sandbox.LaunchObservation;
import io.aria.conductor.execution.runtime.sandbox.SandboxExecutionBackend;
import io.aria.conductor.execution.runtime.sandbox.SandboxLifecycle;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The launch-abort decision consuming the launcher's proven verdict (S-2's leak).
 *
 * <p>These run the <em>real</em> {@link SandboxExecutionBackend} and
 * {@link SandboxLifecycle} over a scripted SDK — the leak was a coordination failure
 * between those two and the coordinator, and a mocked placement could not reproduce it.
 * The two sides of the new boundary are pinned together: a launch the image refused
 * before spawning destroys and releases, and a launch that reported a spawn and then
 * refused to stop still holds the environment and the lease exactly as it did before.
 */
class CoreExecutionServiceSandboxLaunchAbortTest {

    private static final UUID RUN = UUID.fromString("0e75d7f4-0000-0000-0000-000000000004");
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final Instant NOW = Instant.now();
    private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(45));
    /** Wall clock: the control deadline a bounded sandbox command is checked against is real. */
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String PRE_SPAWN_REFUSAL =
            "{\"event\":\"supervisor.fault\",\"name\":\"LaunchFault\",\"message\":"
                    + "\"Entrypoint name is not allowlisted by this image: "
                    + "C:\\\\Users\\\\operator\\\\opencode.exe\"}\n";
    private static final String SPAWN_LINE =
            "{\"event\":\"supervisor.started\",\"runId\":\"0e75d7f4\",\"childPid\":41}\n";

    @TempDir
    Path temp;

    private ScriptedSdk sdk;
    private SandboxExecutionBackend backend;
    private RecordingWorkspace workspaces;
    private RunRuntimeRegistry runtimes;
    private CoreExecutionService service;
    private RunExecutionBinding binding;

    @BeforeEach
    void setUp() {
        workspaces = new RecordingWorkspace();
        runtimes = new RunRuntimeRegistry();
        binding = RunExecutionBinding.builder()
                .runId(RUN).agentId(AGENT).coreId("opencode").executionMode(ExecutionMode.SANDBOX)
                .settingsJson(null)
                .workspaceKind(WorkspaceKind.SANDBOX_SNAPSHOT)
                .workspaceLeaseId(LEASE)
                .deadline(DEADLINE).build();
    }

    // ------------------------------------------------------------------ S-2: the leak

    /**
     * The container the Lead found still {@code Up} nine minutes after its run ended
     * FAILED: the launcher never reached the spawn, so {@code stop-writers} could only
     * refuse, and the refusal left the environment and the lease in place with nothing
     * to re-drive them.
     */
    @Test
    void aLaunchTheImageRefusedBeforeSpawningIsDestroyedAndReleasedWithoutAskingTheScript() {
        given(Script.PRE_SPAWN_REFUSAL);
        // What stop-writers.mjs really answers when the launch record does not exist
        // (stop-writers.mjs:189-193, ControlFault exit 66): a refusal, and the leak.
        sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false,"
                + "\"error\":\"ControlFault: launch record is unreadable in /home/aria/run/0e75d7f4\"}\n";

        assertThatThrownBy(() -> service.openRuntime(spec(), new StubAdapter(false)))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("Entrypoint name is not allowlisted by this image");

        assertThat(sdk.operations)
                .as("no writer can exist, so the in-image script is never asked to stop one")
                .doesNotContain("stop-writers");
        assertThat(sdk.operations).contains("kill");
        // Exactly one kill: destroying an environment the launch path may already have
        // destroyed goes through the idempotent run-keyed form (SandboxLifecycle.destroy
        // removes the run before it kills), so a never-spawned cleanup never kills a second
        // sandbox for the same run.
        assertThat(sdk.killed).containsExactly(sdk.createdSandboxId);
        assertThat(workspaces.releasedProof).isEqualTo(new StopProof(RUN, true));
        assertThat(service.pendingControl(RUN))
                .as("nothing is left pending: the cleanup is complete, not terminal-refused")
                .isEmpty();
        assertThat(runtimes.find(RUN)).isEmpty();
    }

    /**
     * The run exactly as the Lead reproduced it: the launcher's message arrived after the
     * launch had already returned a handle, the session therefore never opened, and the
     * abort found a {@code stop-writers} that could only answer "no launch record". With
     * the launcher's verdict kept, the abort knows a record was never writable and destroys
     * and releases instead of holding both forever.
     */
    @Test
    void aLateNeverSpawnedRefusalDestroysAndReleasesWithoutAskingTheScript() {
        given(Script.LATE_PRE_SPAWN_REFUSAL);
        sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false,"
                + "\"error\":\"ControlFault: launch record is unreadable in /home/aria/run/0e75d7f4\"}\n";
        StubAdapter adapter = new StubAdapter(true);
        adapter.beforeOpen = sdk::refuseLate;

        assertThatThrownBy(() -> service.openRuntime(spec(), adapter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the session never opened");

        assertThat(sdk.operations).doesNotContain("stop-writers");
        assertThat(sdk.operations).contains("kill");
        // Exactly one kill: destroying an environment the launch path may already have
        // destroyed goes through the idempotent run-keyed form (SandboxLifecycle.destroy
        // removes the run before it kills), so a never-spawned cleanup never kills a second
        // sandbox for the same run.
        assertThat(sdk.killed).containsExactly(sdk.createdSandboxId);
        assertThat(workspaces.releasedProof).isEqualTo(new StopProof(RUN, true));
        assertThat(service.pendingControl(RUN)).isEmpty();
        assertThat(runtimes.find(RUN)).isEmpty();
    }

    // ------------------------------------------------- the boundary must not move

    /**
     * The negative of that boundary: a launcher that reported a spawn has a live writer,
     * so a refused stop still leaves the environment in place, the lease held, and the
     * pending control recorded — the cancel of such a run is answered 409 rather than
     * committing CANCELLED over a core that is still running (pinned at the HTTP edge by
     * {@code DeferredRunStopTest} and at the coordinator by the pending-control record).
     */
    @Test
    void aLaunchThatReportedASpawnAndRefusedToStopStillHoldsTheEnvironmentAndTheLease() {
        given(Script.SPAWNED_THEN_SESSION_OPEN_FAILS);
        sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false,\"remaining\":[41]}\n";

        assertThatThrownBy(() -> service.openRuntime(spec(), new StubAdapter(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the session never opened");

        assertThat(sdk.operations).contains("stop-writers");
        assertThat(sdk.operations).doesNotContain("kill");
        assertThat(workspaces.releasedProof).isNull();
        assertThat(service.pendingControl(RUN)).isPresent();
        assertThat(service.pendingControl(RUN).orElseThrow().failureReason())
                .contains("the environment and the workspace lease are left in place");
        assertThat(runtimes.find(RUN)).isPresent();
    }

    /**
     * The second negative: an unreadable launcher outcome is not knowledge, and the
     * abort must not destroy on it.
     */
    @Test
    void anUnreadableLauncherOutcomeKeepsTodayRefusal() {
        given(Script.BROKEN_CAPTURE);
        sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false}\n";

        assertThatThrownBy(() -> service.openRuntime(spec(), new StubAdapter(true)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sdk.operations).contains("stop-writers");
        assertThat(sdk.operations).doesNotContain("kill");
        assertThat(workspaces.releasedProof).isNull();
        assertThat(service.pendingControl(RUN)).isPresent();
    }

    // ------------------------------------------------------------------ fixture

    private void given(Script script) {
        sdk = new ScriptedSdk(script);
        SandboxLifecycle lifecycle = new SandboxLifecycle(sdk);
        backend = new SandboxExecutionBackend(lifecycle, coreId ->
                new SandboxExecutionBackend.SandboxProfile("opencode-sandbox:1.1", 4096));
        RunExecutionBindingRepository bindings = mock(RunExecutionBindingRepository.class);
        when(bindings.findById(RUN)).thenReturn(java.util.Optional.of(binding));
        RunInputCoordinator coordinator = new RunInputCoordinator(noEvents());
        service = new CoreExecutionService(new ExecutionBackendRegistry(List.of(backend)), workspaces,
                new RunFinalizer(workspaces, CLOCK), runtimes, mock(PermissionCoordinator.class),
                mock(CoreCredentialService.class), mock(ActorTokenService.class), bindings, CLOCK,
                Duration.ofMinutes(5), Executors.newSingleThreadScheduledExecutor(), coordinator,
                noEvents(), new TaskDeadlineProperties());
    }

    /** The coordinator and the service both publish; nothing here asserts on those events. */
    private static org.springframework.context.ApplicationEventPublisher noEvents() {
        return new org.springframework.context.ApplicationEventPublisher() {
            @Override
            public void publishEvent(org.springframework.context.ApplicationEvent event) {
            }

            @Override
            public void publishEvent(Object event) {
            }
        };
    }

    private ExecutionSpec spec() {
        return new ExecutionSpec(RUN, AGENT, "opencode", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, WorkspaceMode.DIRECT, null, null),
                null, null, DEADLINE);
    }

    /** The launch profile the sandbox backend needs; the session is what these tests vary. */
    private final class StubAdapter implements CoreAdapter {

        private final boolean failOpen;
        Runnable beforeOpen;

        StubAdapter(boolean failOpen) {
            this.failOpen = failOpen;
        }

        @Override
        public String coreId() {
            return "opencode";
        }

        @Override
        public CoreCapabilities capabilities(ExecutionMode mode) {
            return new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, false);
        }

        @Override
        public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
                SecretBundle credentials) {
            return new LaunchProfile(List.of("opencode", "serve", "--port", "4096"), Map.of(), "/workspace");
        }

        @Override
        public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials) {
            if (beforeOpen != null) {
                // The serve-readiness wait is what runs here in production: the launcher's
                // verdict can arrive inside it, after the launch itself returned.
                beforeOpen.run();
            }
            if (failOpen) {
                throw new IllegalStateException("the session never opened");
            }
            throw new IllegalStateException("unreachable: the launch itself was refused");
        }
    }

    /** Records the release the coordinator is supposed to make, and what proof it carried. */
    private static final class RecordingWorkspace implements WorkspaceService {
        StopProof releasedProof;

        @Override
        public WorkspaceLease acquire(ExecutionSpec spec) {
            return new WorkspaceLease(LEASE, spec.runId(), WorkspaceKind.SANDBOX_SNAPSHOT,
                    Path.of("C:/work"), null, "C:/runtime/runs/" + spec.runId(), null);
        }

        @Override
        public ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend,
                RuntimeHandle handle, StopProof proof) {
            return new ArtifactBundle(Path.of("results"), null, false);
        }

        @Override
        public void release(WorkspaceLease lease, StopProof proof) {
            releasedProof = proof;
        }
    }

    private enum Script {
        PRE_SPAWN_REFUSAL,
        /** Silent while the launch waits; refuses while the session open is still running. */
        LATE_PRE_SPAWN_REFUSAL,
        SPAWNED_THEN_SESSION_OPEN_FAILS,
        BROKEN_CAPTURE
    }

    /** The launcher outcomes, delivered through the SDK seam exactly as production does. */
    private static final class ScriptedSdk implements SandboxLifecycle.SandboxSdk {

        final List<String> operations = new CopyOnWriteArrayList<>();
        final List<String> killed = new CopyOnWriteArrayList<>();
        final Script script;
        volatile String stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":true}\n";
        volatile String createdSandboxId;
        volatile LaunchObservation lastObservation;

        ScriptedSdk(Script script) {
            this.script = script;
        }

        @Override
        public String create(String image, Map<String, String> env) {
            operations.add("create");
            createdSandboxId = "sandbox-6fd4dcfa";
            return createdSandboxId;
        }

        @Override
        public void upload(String sandboxId, List<WriteEntry> entries) {
            operations.add("upload");
        }

        @Override
        public String endpoint(String sandboxId, int port) {
            return "127.0.0.1:40369/proxy/" + port;
        }

        @Override
        public void launch(String sandboxId, String runDirectory, Duration timeout, LaunchObservation observation) {
            operations.add("launch");
            lastObservation = observation;
            switch (script) {
                case PRE_SPAWN_REFUSAL -> {
                    observation.recordStderr(PRE_SPAWN_REFUSAL);
                    observation.commandCompleted(78);
                }
                case LATE_PRE_SPAWN_REFUSAL -> {
                    // Silent for the whole launch window: the handle exists before the verdict does.
                }
                case SPAWNED_THEN_SESSION_OPEN_FAILS -> observation.recordStdout(SPAWN_LINE);
                case BROKEN_CAPTURE -> observation.commandAborted();
            }
        }

        /** The launcher's message, arriving after the launch already returned. */
        void refuseLate() {
            lastObservation.recordStderr(PRE_SPAWN_REFUSAL);
            lastObservation.commandCompleted(78);
        }

        @Override
        public String pauseWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("pause-writers");
            return "{\"action\":\"suspend\",\"suspended\":true}\n";
        }

        @Override
        public String resumeWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("resume-writers");
            return "{\"action\":\"resume\",\"resumed\":true}\n";
        }

        @Override
        public String stopWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("stop-writers");
            return stopWritersOutput;
        }

        @Override
        public void renew(String sandboxId, Duration extension) {
            operations.add("renew");
        }

        @Override
        public List<SandboxLifecycle.SandboxSdk.RemoteFile> export(String sandboxId, String remoteRoot) {
            operations.add("export");
            return List.of();
        }

        @Override
        public void kill(String sandboxId) {
            operations.add("kill");
            killed.add(sandboxId);
        }
    }
}
