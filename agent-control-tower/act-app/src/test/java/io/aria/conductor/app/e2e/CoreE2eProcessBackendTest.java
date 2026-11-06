package io.aria.conductor.app.e2e;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.host.OwnedProcess;
import io.aria.conductor.execution.runtime.host.OwnedProcessController;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Focused coverage of the harness stop-proof fallback in
 * {@link CoreE2eProcessBackend#stopWriters}.
 *
 * <p>The harness mock session cancels a run by terminating the mock peer
 * itself, so the controller can be asked to stop a root that vanished without
 * a kill from that stop. The production controller correctly refuses that
 * (it can no longer attribute whatever the root orphaned); the harness
 * transport must treat the already-gone single-process mock peer as a verified
 * stopped writer tree, but only when the root is genuinely gone and the
 * controller no longer owns it.
 */
class CoreE2eProcessBackendTest {

    @TempDir
    Path temp;

    @Test
    void anAlreadyExitedPeerRootIsAVerifiedStop() throws Exception {
        CoreE2eProcessBackend.State state = newState();
        try {
            CoreE2eProcessBackend backend = new CoreE2eProcessBackend(ExecutionMode.HOST, state);
            UUID runId = UUID.randomUUID();
            PreparedEnvironment environment = backend.prepare(spec(runId), lease(runId));
            RuntimeHandle handle = backend.launch(environment,
                    shortLivedPeerProfile(temp.resolve("peer-work")));

            // The mock session terminates its peer outside the controller (the
            // production scenario this fallback exists for): wait until the OS
            // reports the root PID gone, exactly the predicate the fallback keys on.
            OwnedProcess owned = state.ownedProcess(runId);
            assertThat(owned).isNotNull();
            assertThat(owned.liveProcess().waitFor(30, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> ProcessHandle.of(owned.rootPid()).isEmpty());

            StopProof proof = backend.stopWriters(handle, Instant.now().plusSeconds(30));

            assertThat(proof.runId()).isEqualTo(runId);
            assertThat(proof.allWritersStopped())
                    .as("a gone mock peer root with no controller binding is a verified stop")
                    .isTrue();
            backend.destroy(handle);
        } finally {
            state.close();
        }
    }

    @Test
    void aLiveRootRefusalIsKeptUnchanged() {
        CoreE2eProcessBackend.State state = newState();
        try {
            CoreE2eProcessBackend backend = new CoreE2eProcessBackend(ExecutionMode.HOST, state);
            UUID runId = UUID.randomUUID();
            // A live root the controller does not own (no launch binding): the
            // controller refuses, and the fallback must not turn that refusal into
            // a proof while the process is still alive. The current JVM stands in
            // for a live peer so no fixture depends on PID reuse or timing.
            long livePid = ProcessHandle.current().pid();
            OwnedProcess unowned = new OwnedProcess(runId, UUID.randomUUID().toString(), livePid,
                    "foreign-creation-identity", "test-supervisor", "test-technique", null);
            RuntimeHandle handle = new RuntimeHandle(runId, ExecutionMode.HOST, "e2e-host-" + runId,
                    unowned.ownershipIdentity(), URI.create("http://127.0.0.1:1/"));

            StopProof proof = backend.stopWriters(handle, Instant.now().plusSeconds(5));

            assertThat(proof.runId()).isEqualTo(runId);
            assertThat(proof.allWritersStopped())
                    .as("a live root must never be certified stopped by the harness fallback")
                    .isFalse();
        } finally {
            state.close();
        }
    }

    private static CoreE2eProcessBackend.State newState() {
        return new CoreE2eProcessBackend.State(OwnedProcessController.forCurrentPlatform(),
                new CoreE2eWorkerTokens(new ActorTokenService()));
    }

    private static ExecutionSpec spec(UUID runId) {
        return new ExecutionSpec(runId, UUID.randomUUID(), "opencode", ExecutionMode.HOST,
                new AgentExecutionSettings("opencode", ExecutionMode.HOST, WorkspaceMode.DIRECT,
                        null, null),
                null, null, null);
    }

    private WorkspaceLease lease(UUID runId) {
        return new WorkspaceLease(UUID.randomUUID(), runId, WorkspaceKind.DIRECT,
                temp, temp, temp.resolve("runtime").toString(), null);
    }

    /**
     * A real short-lived process that stays alive long enough for the controller
     * to record its ownership, then exits without a stop from that controller.
     */
    private LaunchProfile shortLivedPeerProfile(Path workingDirectory) throws IOException {
        Files.createDirectories(workingDirectory);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String systemRoot = System.getenv("SystemRoot");
            Map<String, String> env = new LinkedHashMap<>();
            if (systemRoot != null) {
                env.put("SystemRoot", systemRoot);
                env.put("windir", systemRoot);
            }
            Path powershell = Path.of(systemRoot == null ? "C:\\Windows" : systemRoot,
                    "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            return new LaunchProfile(List.of(powershell.toString(), "-NoProfile", "-NonInteractive",
                    "-Command", "Start-Sleep -Seconds 3"), env, workingDirectory.toString());
        }
        return new LaunchProfile(List.of("/bin/sleep", "3"), Map.of(), workingDirectory.toString());
    }
}
