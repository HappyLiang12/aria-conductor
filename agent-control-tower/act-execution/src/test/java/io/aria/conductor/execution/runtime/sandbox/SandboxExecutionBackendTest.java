package io.aria.conductor.execution.runtime.sandbox;

import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the run-owned Sandbox backend and its SDK lifecycle.
 *
 * <p>Every sandbox-side action runs through {@link RecordingSdk}, a recording
 * test double of the {@link SandboxLifecycle.SandboxSdk} seam. Its
 * <em>operations</em> are state-changing sandbox actions and append the literal
 * operation names the ruling pins ({@code create}, {@code upload},
 * {@code launch}, {@code stop-writers}, {@code export}, {@code kill}); read-only
 * lookups (the endpoint resolution) are recorded separately, so the operation
 * ordering stays exact without pretending a lookup is a mutation.
 *
 * <p>The real OpenSandbox boundary (image launcher, container writer tree,
 * export facility) is covered by {@code e2e/agent-core/sandbox-lifecycle.test.mjs}
 * against a real container runtime; a process-only fake is not that result.
 */
class SandboxExecutionBackendTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000a10");
    private static final UUID OTHER_RUN = UUID.fromString("00000000-0000-0000-0000-000000000a11");
    private static final String IMAGE = "aria-conductor/opencode-sandbox:1.1";
    private static final int CORE_PORT = 4096;
    private static final List<String> SERVE_ARGV =
            List.of("opencode", "serve", "--hostname", "0.0.0.0", "--port", String.valueOf(CORE_PORT));

    // ------------------------------------------------------------------ ordering / proofs

    @Test
    void exportWithoutAStopProofIsRefusedAndNeverExports() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        Path destination = Files.createDirectories(fixture.temp.resolve("results"));

        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination,
                new StopProof(handle.runId(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Writers are not stopped");

        assertThat(fixture.sdk.operations).doesNotContain("export");
        assertThat(Files.list(destination)).isEmpty();
    }

    @Test
    void exportRefusesAnAbsentOrForeignProof() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        Path destination = Files.createDirectories(fixture.temp.resolve("results"));

        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Writers are not stopped");

        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination,
                new StopProof(OTHER_RUN, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to run " + handle.runId());

        assertThat(fixture.sdk.operations).doesNotContain("export");
    }

    @Test
    void exportRefusesAStaleProofAfterTheWritersResumed() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        StopProof proof = fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30));
        assertThat(proof.allWritersStopped()).isTrue();

        ControlAck resumed = fixture.backend.resumeWriters(handle, Instant.now().plusSeconds(30))
                .toCompletableFuture().join();
        assertThat(resumed).isEqualTo(new ControlAck(ControlState.RUNNING, true));

        Path destination = Files.createDirectories(fixture.temp.resolve("results"));
        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination, proof))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No current verified stop for run " + RUN_ID);

        assertThat(fixture.sdk.operations).doesNotContain("export");
        assertThat(Files.list(destination)).isEmpty();
    }

    @Test
    void exportRefusesAProofAnEarlierLaunchHasInvalidated() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        StopProof proof = fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30));
        assertThat(proof.allWritersStopped()).isTrue();

        // A relaunch starts a new writer tree: the earlier verified stop is no longer current.
        fixture.lifecycle.launch(handle.runId());

        Path destination = Files.createDirectories(fixture.temp.resolve("results"));
        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination, proof))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No current verified stop for run " + RUN_ID);
        assertThat(fixture.sdk.operations).doesNotContain("export");
    }

    @Test
    void exportRefusesAMintedProofThatNoVerifiedStopBacks() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        Path destination = Files.createDirectories(fixture.temp.resolve("results"));

        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination,
                new StopProof(handle.runId(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No current verified stop for run " + RUN_ID);

        assertThat(fixture.sdk.operations).doesNotContain("export");
        assertThat(Files.list(destination)).isEmpty();
    }

    @Test
    void theLifecycleRunsCreateUploadLaunchStopWritersExportKillInOrder() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        Path destination = fixture.temp.resolve("results");

        StopProof proof = fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30));
        assertThat(proof).isEqualTo(new StopProof(handle.runId(), true));
        // Stopping the writers must not destroy the sandbox: export access stays alive.
        assertThat(fixture.sdk.operations).doesNotContain("kill");

        fixture.backend.exportWorkspace(handle, destination, proof);
        assertThat(Files.readString(destination.resolve("notes.md"), StandardCharsets.UTF_8))
                .isEqualTo("sandbox export bytes\n");
        assertThat(Files.readString(destination.resolve("src/main.js"), StandardCharsets.UTF_8))
                .isEqualTo("console.log('exported');\n");
        assertThat(fixture.sdk.operations).doesNotContain("kill");

        fixture.backend.destroy(handle);

        assertThat(fixture.sdk.operations).containsExactly("create", "upload", "launch",
                "stop-writers", "export", "kill");
    }

    @Test
    void aPausedSandboxKeepsItsFilesystemAndExportFacility() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);

        ControlAck paused = fixture.backend.pauseWriters(handle, Instant.now().plusSeconds(30))
                .toCompletableFuture().join();
        assertThat(paused).isEqualTo(new ControlAck(ControlState.PAUSED, true));
        assertThat(fixture.sdk.operations).doesNotContain("kill");

        ControlAck resumed = fixture.backend.resumeWriters(handle, Instant.now().plusSeconds(30))
                .toCompletableFuture().join();
        assertThat(resumed).isEqualTo(new ControlAck(ControlState.RUNNING, true));

        StopProof proof = fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30));
        Path destination = fixture.temp.resolve("results");
        fixture.backend.exportWorkspace(handle, destination, proof);

        assertThat(Files.isRegularFile(destination.resolve("notes.md"))).isTrue();
        assertThat(fixture.sdk.operations).doesNotContain("kill");
    }

    @Test
    void anIncompleteExportIsReportedAsIncompleteInsteadOfAStableDiff() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        fixture.sdk.failExportWith = new IllegalStateException("execd read failed");
        Path destination = fixture.temp.resolve("results");
        // A genuine stop: a minted proof alone no longer opens the export gate.
        StopProof proof = fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30));

        ArtifactBundle bundle = fixture.lifecycle.export(handle.runId(), destination, proof);

        assertThat(bundle.complete()).isFalse();
        assertThat(bundle.manifestSha256()).isNull();
        assertThat(bundle.directory()).isEqualTo(destination.toAbsolutePath().normalize());

        assertThatThrownBy(() -> fixture.backend.exportWorkspace(handle, destination, proof))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void anUnverifiedStopIsNeverReportedAsAStopProof() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        fixture.sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false,"
                + "\"remaining\":[4242]}\n";

        StopProof proof = fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30));

        assertThat(proof.runId()).isEqualTo(handle.runId());
        assertThat(proof.allWritersStopped()).isFalse();

        fixture.sdk.stopWritersOutput = "not json at all";
        assertThat(fixture.backend.stopWriters(handle, Instant.now().plusSeconds(30)).allWritersStopped())
                .isFalse();
    }

    @Test
    void anUnverifiedSuspensionIsNeverAcknowledgedAsPaused() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        fixture.sdk.pauseWritersOutput = "{\"action\":\"suspend\",\"suspended\":false}\n";
        fixture.sdk.resumeWritersOutput = "{\"action\":\"resume\",\"resumed\":false}\n";

        assertThat(fixture.backend.pauseWriters(handle, Instant.now().plusSeconds(30))
                .toCompletableFuture().join()).isEqualTo(new ControlAck(ControlState.RUNNING, false));
        assertThat(fixture.backend.resumeWriters(handle, Instant.now().plusSeconds(30))
                .toCompletableFuture().join()).isEqualTo(new ControlAck(ControlState.PAUSED, false));
        assertThat(fixture.sdk.operations).containsExactly("create", "upload", "launch",
                "pause-writers", "resume-writers");
    }

    @Test
    void anElapsedDeadlineIsRefusedInsteadOfWidened() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);

        assertThatThrownBy(() -> fixture.backend.stopWriters(handle, Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline has elapsed");
        assertThat(fixture.sdk.operations).doesNotContain("stop-writers");
    }

    // ------------------------------------------------------------------ preparation

    @Test
    void prepareRefusesAMismatchedWorkspaceAnUnknownCoreAndAForeignMode() throws IOException {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.backend.prepare(fixture.spec(RUN_ID), fixture.lease(OTHER_RUN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspace lease run");

        Fixture unknownCore = new Fixture("unregistered-core");
        assertThatThrownBy(() -> unknownCore.backend.prepare(unknownCore.spec(RUN_ID), unknownCore.lease(RUN_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No sandbox image is configured for core unregistered-core");

        assertThatThrownBy(() -> fixture.backend.prepare(
                fixture.spec(RUN_ID, ExecutionMode.HOST), fixture.lease(RUN_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Host execution is not this backend");

        assertThat(fixture.sdk.operations).isEmpty();
    }

    @Test
    void prepareAdmitsOnlySandboxSnapshotWorkspaceLeases() throws IOException {
        Fixture fixture = new Fixture();

        for (WorkspaceKind kind : List.of(WorkspaceKind.SCRATCH, WorkspaceKind.WORKTREE, WorkspaceKind.DIRECT)) {
            assertThatThrownBy(() -> fixture.backend.prepare(fixture.spec(RUN_ID), fixture.lease(RUN_ID, kind)))
                    .as("a %s lease must not be admitted", kind)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The sandbox backend admits only SANDBOX_SNAPSHOT workspace leases, got " + kind);
        }
        assertThat(fixture.sdk.operations).isEmpty();
    }

    @Test
    void prepareCreatesTheSandboxOnceAndResolvesTheEndpointFromTheSdk() throws IOException {
        Fixture fixture = new Fixture();

        PreparedEnvironment environment = fixture.backend.prepare(fixture.spec(RUN_ID), fixture.lease(RUN_ID));

        assertThat(environment.runId()).isEqualTo(RUN_ID);
        assertThat(environment.mode()).isEqualTo(ExecutionMode.SANDBOX);
        assertThat(environment.workingDirectory()).isEqualTo(SandboxLifecycle.DEFAULT_WORKSPACE_ROOT);
        assertThat(environment.endpoint()).isEqualTo(URI.create("http://127.0.0.1:40369/proxy/4096"));
        assertThat(fixture.sdk.operations).containsExactly("create");
        assertThat(fixture.sdk.lookups).containsExactly("endpoint");
        assertThat(fixture.sdk.createdImages).containsExactly(IMAGE);

        assertThatThrownBy(() -> fixture.backend.prepare(fixture.spec(RUN_ID), fixture.lease(RUN_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already owns run");
    }

    @Test
    void launchUploadsTheSnapshotAndTheTrustedManifestInOneWriteBeforeLaunching() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);

        assertThat(handle.runId()).isEqualTo(RUN_ID);
        assertThat(handle.mode()).isEqualTo(ExecutionMode.SANDBOX);
        assertThat(handle.endpoint()).isEqualTo(URI.create("http://127.0.0.1:40369/proxy/4096"));
        assertThat(handle.ownershipIdentity()).contains(RUN_ID.toString());

        assertThat(fixture.sdk.operations).containsExactly("create", "upload", "launch");
        List<WriteEntry> entries = fixture.sdk.uploadedEntries;
        assertThat(entries).extracting(WriteEntry::getPath)
                .containsExactlyInAnyOrder(
                        SandboxLifecycle.DEFAULT_WORKSPACE_ROOT + "/notes.md",
                        SandboxLifecycle.DEFAULT_CONTROL_ROOT + "/" + RUN_ID + "/launch-manifest.json");
        Map<String, Object> manifest = json(entries.stream()
                .filter(entry -> entry.getPath().endsWith("launch-manifest.json"))
                .findFirst().orElseThrow().getData().toString());
        assertThat(manifest).containsEntry("runId", RUN_ID.toString());
        assertThat(manifest).containsEntry("schemaVersion", 1);
        assertThat(manifest).containsEntry("port", CORE_PORT);
        assertThat(manifest).containsEntry("workingDirectory", SandboxLifecycle.DEFAULT_WORKSPACE_ROOT);
        assertThat(manifest).containsEntry("argv", SERVE_ARGV);
        assertThat(manifest).containsEntry("env", Map.of("OPENCODE_MODEL", "efficient"));
        // The manifest carries the core environment, so its only copy is the run-owned
        // one uploaded into the sandbox control directory, mode 600.
        assertThat(entries.stream()
                .filter(entry -> entry.getPath().endsWith("launch-manifest.json"))
                .findFirst().orElseThrow().getMode()).isEqualTo(600);
        // No host-side copy of the credential-bearing manifest is written.
        assertThat(Files.exists(Path.of(fixture.environment.configurationDirectory())
                .resolve("launch-manifest.json"))).isFalse();
    }

    @Test
    void aManifestThatCouldReachAShellIsRefusedBeforeUpload() throws IOException {
        List<List<String>> unsafe = List.of(
                List.of("opencode", "serve\necho injected"),
                List.of("opencode", "serve\0truncated"),
                List.of("sh", "-c", "opencode serve --port 4096"),
                List.of("bash", "-lc", "opencode serve"));
        for (List<String> argv : unsafe) {
            assertThatThrownBy(() -> new SandboxLifecycle.LaunchManifest(RUN_ID, CORE_PORT,
                    SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, argv, Map.of()))
                    .as("unsafe argv must be refused before any upload: %s", argv)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // The refusal happens at the manifest, before the snapshot write: no upload ever reaches the SDK.
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.prepareAndLaunch(RUN_ID, new LaunchProfile(
                List.of("opencode", "serve\necho injected"), Map.of(), SandboxLifecycle.DEFAULT_WORKSPACE_ROOT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.sdk.operations).doesNotContain("upload");
    }

    @Test
    void aManifestWithInjectionShapedEnvironmentIsRefused() throws IOException {
        assertThatThrownBy(() -> new SandboxLifecycle.LaunchManifest(RUN_ID, CORE_PORT,
                SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, SERVE_ARGV, Map.of("PATH", "/tmp/hijack")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PATH");
        assertThatThrownBy(() -> new SandboxLifecycle.LaunchManifest(RUN_ID, CORE_PORT,
                SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, SERVE_ARGV, Map.of("OPENCODE-MODEL", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxLifecycle.LaunchManifest(RUN_ID, CORE_PORT,
                SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, SERVE_ARGV, Map.of("MODEL", "a\nb")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxLifecycle.LaunchManifest(RUN_ID, CORE_PORT,
                SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, SERVE_ARGV, Map.of("LD_PRELOAD", "/tmp/x.so")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LD_PRELOAD");
        assertThatThrownBy(() -> new SandboxLifecycle.LaunchManifest(RUN_ID, CORE_PORT,
                SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, SERVE_ARGV, Map.of("NODE_OPTIONS", "--require /tmp/x.js")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NODE_OPTIONS");

        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.prepareAndLaunch(RUN_ID, new LaunchProfile(
                SERVE_ARGV, Map.of("PATH", "/tmp/hijack"), SandboxLifecycle.DEFAULT_WORKSPACE_ROOT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.sdk.operations).doesNotContain("upload");
    }

    /**
     * The sandbox is created during prepare, so a launch that fails afterwards must
     * destroy it: an orphaned container left by a refused manifest (or a failed
     * upload) was found running on the host by the first real qoder/SANDBOX attempt.
     */
    @Test
    void aLaunchThatFailsAfterPrepareDestroysTheSandbox() throws IOException {
        Fixture fixture = new Fixture();
        List<String> oversized = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            oversized.add("argv-" + index);
        }

        assertThatThrownBy(() -> fixture.prepareAndLaunch(RUN_ID, new LaunchProfile(
                oversized, Map.of(), SandboxLifecycle.DEFAULT_WORKSPACE_ROOT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argv");

        assertThat(fixture.sdk.operations)
                .as("the created sandbox never survives a failed launch")
                .contains("create", "kill");
    }

    /**
     * A relay-dead sandbox cannot be healed by waiting: when the upload window
     * exhausts with the relay-never-established classification
     * ({@link SandboxLifecycle#isRelayNeverEstablished(Throwable)}), the sandbox
     * is destroyed and ONE fresh sandbox is created and re-uploaded against, so
     * a published-port relay that never accepted a connection does not fail the
     * run.
     */
    @Test
    void aRelayDeadUploadRecreatesTheSandboxOnceAndReuploads() throws IOException {
        Fixture fixture = new Fixture();
        fixture.sdk.uploadFailures.add(relayDeadUploadFailure("sandbox-1"));

        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);

        // The relay-dead sandbox is destroyed, a fresh one is created and the
        // whole upload sequence runs again against it before the launch.
        assertThat(fixture.sdk.operations).containsExactly(
                "create", "upload", "kill", "create", "upload", "launch");
        assertThat(fixture.sdk.createdImages)
                .as("the recreate uses the same configured image")
                .containsExactly(IMAGE, IMAGE);
        assertThat(fixture.sdk.lookups)
                .as("the endpoint of the fresh sandbox is re-resolved like prepare does")
                .containsExactly("endpoint", "endpoint");
        assertThat(fixture.sdk.killedSandboxIds)
                .as("only the relay-dead sandbox is destroyed")
                .containsExactly("sandbox-1");
        assertThat(fixture.sdk.uploadSandboxIds)
                .as("the re-upload targets the fresh sandbox")
                .containsExactly("sandbox-1", "sandbox-2");
        assertThat(handle.environmentId())
                .as("the run's environment references the new sandbox id")
                .isEqualTo("sandbox-2");
        assertThat(handle.ownershipIdentity()).isEqualTo("sandbox:" + RUN_ID + ":sandbox-2");
        assertThat(fixture.lifecycle.sandboxId(RUN_ID)).contains("sandbox-2");
        // The re-upload carried the workspace snapshot and the trusted manifest again.
        assertThat(fixture.sdk.uploadedEntries).extracting(WriteEntry::getPath)
                .contains(SandboxLifecycle.DEFAULT_WORKSPACE_ROOT + "/notes.md",
                        SandboxLifecycle.DEFAULT_CONTROL_ROOT + "/" + RUN_ID + "/launch-manifest.json");
    }

    /**
     * Only the relay-never-established class earns a recreate: any other upload
     * failure fails the run exactly as before -- one create, no second upload,
     * and the sandbox is destroyed by the unchanged failure path.
     */
    @Test
    void aNonRelayUploadFailureNeverRecreatesTheSandbox() throws IOException {
        Fixture fixture = new Fixture();
        fixture.sdk.uploadFailures.add(new TaskExecutionException(
                TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Workspace upload failed for sandbox sandbox-1: unexpected end of stream",
                new RuntimeException("unexpected end of stream")));

        assertThatThrownBy(() -> fixture.prepareAndLaunch(RUN_ID))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("unexpected end of stream");

        assertThat(fixture.sdk.operations)
                .as("a non-relay failure never recreates: exactly one create, no second upload")
                .containsExactly("create", "upload", "kill");
        assertThat(fixture.sdk.createdImages).containsExactly(IMAGE);
        assertThat(fixture.sdk.lookups).containsExactly("endpoint");
        assertThat(fixture.sdk.killedSandboxIds).containsExactly("sandbox-1");
        assertThat(fixture.lifecycle.owns(RUN_ID)).isFalse();
    }

    /**
     * The recreate is bounded to exactly ONE fresh sandbox: when the fresh
     * sandbox is relay-dead as well, the run fails instead of recreating
     * forever, and the unchanged failure path destroys the fresh sandbox too.
     */
    @Test
    void aSecondRelayDeadSandboxFailsTheRunAfterExactlyOneRecreate() throws IOException {
        Fixture fixture = new Fixture();
        fixture.sdk.uploadFailures.add(relayDeadUploadFailure("sandbox-1"));
        fixture.sdk.uploadFailures.add(relayDeadUploadFailure("sandbox-2"));

        assertThatThrownBy(() -> fixture.prepareAndLaunch(RUN_ID))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("Failed to connect to localhost/127.0.0.1:59217");

        assertThat(fixture.sdk.operations)
                .as("one recreate only: the second relay-dead upload fails the run")
                .containsExactly("create", "upload", "kill", "create", "upload", "kill");
        assertThat(fixture.sdk.createdImages).containsExactly(IMAGE, IMAGE);
        assertThat(fixture.sdk.killedSandboxIds)
                .as("both the relay-dead sandbox and its relay-dead replacement are destroyed")
                .containsExactly("sandbox-1", "sandbox-2");
        assertThat(fixture.sdk.lookups).containsExactly("endpoint", "endpoint");
        assertThat(fixture.lifecycle.owns(RUN_ID)).isFalse();
    }

    /** The upload-exhaustion failure shape the real SDK throws for a relay that never accepted a connection. */
    private static TaskExecutionException relayDeadUploadFailure(String sandboxId) {
        String cause = "Failed to connect to localhost/127.0.0.1:59217";
        return new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Workspace upload failed for sandbox " + sandboxId + ": Network connectivity error: " + cause,
                new RuntimeException(cause));
    }

    @Test
    void launchUploadsTheRunOwnedGovernedConfigurationIntoTheSandboxControlDirectory() throws IOException {
        Fixture fixture = new Fixture();
        WorkspaceLease lease = fixture.lease(RUN_ID);
        Files.writeString(lease.localRoot().resolve("notes.md"), "snapshot bytes\n");
        PreparedEnvironment environment = fixture.backend.prepare(fixture.spec(RUN_ID), lease);
        Path stagingConfig = Path.of(lease.runtimeRoot()).resolve("sandbox").resolve("config").resolve("opencode");
        Files.createDirectories(stagingConfig);
        Files.writeString(stagingConfig.resolve("opencode.json"), "{\"permission\":{\"*\":\"deny\"}}\n");

        fixture.backend.launch(environment, new LaunchProfile(SERVE_ARGV, Map.of(),
                SandboxLifecycle.DEFAULT_WORKSPACE_ROOT));

        // The run-owned governed configuration must land in the sandbox run
        // control directory, where the launch manifest's XDG_CONFIG_HOME points.
        assertThat(fixture.sdk.uploadedEntries).extracting(WriteEntry::getPath)
                .contains("/home/aria/run/" + RUN_ID + "/config/opencode/opencode.json");
        assertThat(fixture.sdk.uploadedEntries).extracting(entry -> String.valueOf(entry.getData()))
                .anyMatch(data -> data.contains("\"deny\""));
    }

    /**
     * The governed SANDBOX configuration is delivered over two hops -- the launch
     * profile exports the XDG roots and the backend uploads the staging subtree
     * into the paths those roots name. Each hop has its own test, so this one
     * binds them with the profile the opencode adapter really builds for the
     * sandbox environment: the governed file must exist exactly where the
     * exported {@code XDG_CONFIG_HOME} points, and the upload must be the only
     * copy. A drift between the two paths is "opencode starts without the
     * governed policy", the failure the control tree exists to prevent.
     */
    @Test
    void theProfileXdgRootsAndTheUploadedGovernedConfigurationNameTheSameSandboxPath() throws IOException {
        Fixture fixture = new Fixture();
        WorkspaceLease lease = fixture.lease(RUN_ID);
        Files.writeString(lease.localRoot().resolve("notes.md"), "snapshot bytes\n");
        PreparedEnvironment environment = fixture.backend.prepare(fixture.spec(RUN_ID), lease);

        RunMcpWiring runMcp = mock(RunMcpWiring.class);
        when(runMcp.forRun(any(ExecutionSpec.class), any(PreparedEnvironment.class)))
                .thenReturn(Optional.empty());
        OpenCodeCoreAdapter adapter = new OpenCodeCoreAdapter(
                new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(), Map.of(), "1.14.31", "efficient"),
                runMcp);
        LaunchProfile profile = adapter.launchProfile(fixture.spec(RUN_ID), environment,
                new SecretBundle(null, Map.of()));

        fixture.backend.launch(environment, profile);

        String configHome = profile.env().get("XDG_CONFIG_HOME");
        assertThat(configHome).isNotBlank();
        String governed = configHome + "/opencode/opencode.json";
        List<String> uploaded = fixture.sdk.uploadedEntries.stream().map(WriteEntry::getPath).toList();
        assertThat(uploaded)
                .as("the governed configuration opencode is pointed at is the uploaded one")
                .contains(governed);
        assertThat(uploaded)
                .filteredOn(path -> path.endsWith("/opencode/opencode.json"))
                .as("the governed configuration exists exactly once, at the exported XDG_CONFIG_HOME")
                .containsExactly(governed);
    }

    /** The uploaded subtree name is a plain directory name, never a path fragment. */
    @Test
    void uploadingARunConfigurationRefusesAPathShapedDirectoryName() throws IOException {
        Fixture fixture = new Fixture();
        WorkspaceLease lease = fixture.lease(RUN_ID);
        fixture.backend.prepare(fixture.spec(RUN_ID), lease);

        for (String name : List.of("", "..", "a/b", "/etc", "a\\b")) {
            assertThatThrownBy(() -> fixture.lifecycle.uploadRunConfiguration(
                    RUN_ID, Path.of(lease.runtimeRoot()), name))
                    .as("a path-shaped subtree name must be refused: '%s'", name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("plain directory name");
        }
        assertThat(fixture.sdk.operations)
                .as("a refused name must never reach the upload")
                .containsExactly("create");
    }

    // ------------------------------------------------------------------ ownership / renewal

    @Test
    void renewalRunsWhileTheRunIsAliveAndStopsForTeardown() throws Exception {
        Fixture fixture = new Fixture();
        fixture.lifecycle.setRenewalPlan(Duration.ofMillis(20), Duration.ofMinutes(30));
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);
        assertThat(fixture.sdk.renewed.get()).isZero();
        assertThat(fixture.sdk.renewalLatch.await(5, TimeUnit.SECONDS)).isTrue();

        fixture.backend.destroy(handle);
        int atTeardown = renewals(fixture);
        assertThat(atTeardown).isGreaterThanOrEqualTo(1);
        Thread.sleep(150);
        assertThat(renewals(fixture))
                .as("no renewal may continue after teardown")
                .isEqualTo(atTeardown);
    }

    @Test
    void destroyingOneRunNeverTouchesAnotherRunsSandbox() throws IOException {
        Fixture fixture = new Fixture();
        fixture.prepareAndLaunch(RUN_ID);
        fixture.prepareAndLaunch(OTHER_RUN);

        fixture.lifecycle.destroy(RUN_ID);

        assertThat(fixture.sdk.killedSandboxIds).containsExactly("sandbox-1");
        assertThat(fixture.sdk.operations).contains("kill");
    }

    @Test
    void destroyIsIdempotentAndReleasesTheRunKey() throws IOException {
        Fixture fixture = new Fixture();
        RuntimeHandle handle = fixture.prepareAndLaunch(RUN_ID);

        fixture.backend.destroy(handle);
        fixture.backend.destroy(handle);

        assertThat(fixture.sdk.killedSandboxIds).containsExactly("sandbox-1");
        assertThat(fixture.lifecycle.owns(RUN_ID)).isFalse();
    }

    // ------------------------------------------------------------------ fixture

    private static int renewals(Fixture fixture) {
        return (int) fixture.sdk.operations.stream().filter("renew"::equals).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One backend + lifecycle + recording SDK, wired like production. */
    private static final class Fixture {

        final Path temp;
        final RecordingSdk sdk = new RecordingSdk();
        final SandboxLifecycle lifecycle;
        final SandboxExecutionBackend backend;
        final String coreId;

        PreparedEnvironment environment;

        Fixture() {
            this("opencode");
        }

        Fixture(String coreId) {
            this.coreId = coreId;
            try {
                this.temp = Files.createTempDirectory("aria-sandbox-backend-test");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            this.lifecycle = new SandboxLifecycle(sdk);
            this.backend = new SandboxExecutionBackend(lifecycle, requested -> switch (requested == null ? "" : requested) {
                case "opencode", "qoder" -> new SandboxExecutionBackend.SandboxProfile(IMAGE, CORE_PORT);
                default -> null;
            });
        }

        ExecutionSpec spec(UUID runId) {
            return spec(runId, ExecutionMode.SANDBOX);
        }

        ExecutionSpec spec(UUID runId, ExecutionMode mode) {
            return new ExecutionSpec(runId, UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                    coreId, mode, new AgentExecutionSettings(coreId, mode, WorkspaceMode.DIRECT,
                    null, null), "credential-ref-1", "configuration-revision-1",
                    Instant.now().plus(Duration.ofMinutes(45)));
        }

        WorkspaceLease lease(UUID runId) throws IOException {
            return lease(runId, WorkspaceKind.SANDBOX_SNAPSHOT);
        }

        WorkspaceLease lease(UUID runId, WorkspaceKind kind) throws IOException {
            Path localRoot = Files.createDirectories(temp.resolve("workspace-" + runId));
            Path runtimeRoot = Files.createDirectories(temp.resolve("runtime-" + runId));
            return new WorkspaceLease(UUID.randomUUID(), runId, kind, localRoot, null, runtimeRoot.toString(), null);
        }

        RuntimeHandle prepareAndLaunch(UUID runId) throws IOException {
            return prepareAndLaunch(runId, new LaunchProfile(SERVE_ARGV,
                    Map.of("OPENCODE_MODEL", "efficient"), SandboxLifecycle.DEFAULT_WORKSPACE_ROOT));
        }

        RuntimeHandle prepareAndLaunch(UUID runId, LaunchProfile profile) throws IOException {
            WorkspaceLease lease = lease(runId);
            Files.writeString(lease.localRoot().resolve("notes.md"), "snapshot bytes\n");
            this.environment = backend.prepare(spec(runId), lease);
            return backend.launch(environment, profile);
        }
    }

    /**
     * Recording SDK double: state-changing sandbox operations append their
     * literal names in call order; endpoint resolution is a read-only lookup and
     * is recorded separately.
     */
    private static final class RecordingSdk implements SandboxLifecycle.SandboxSdk {

        final List<String> operations = new CopyOnWriteArrayList<>();
        final List<String> lookups = new CopyOnWriteArrayList<>();
        final List<String> createdImages = new CopyOnWriteArrayList<>();
        final List<String> killedSandboxIds = new CopyOnWriteArrayList<>();
        final List<String> uploadSandboxIds = new CopyOnWriteArrayList<>();
        final CountDownLatch renewalLatch = new CountDownLatch(1);
        final AtomicInteger renewed = new AtomicInteger();
        final AtomicInteger created = new AtomicInteger();
        volatile List<WriteEntry> uploadedEntries = List.of();
        /** Injected upload failures: every upload call consumes the next one and throws it, then succeeds. */
        final Queue<RuntimeException> uploadFailures = new ConcurrentLinkedQueue<>();
        volatile String stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":true,\"remaining\":[]}\n";
        volatile String pauseWritersOutput = "{\"action\":\"suspend\",\"suspended\":true}\n";
        volatile String resumeWritersOutput = "{\"action\":\"resume\",\"resumed\":true}\n";
        volatile RuntimeException failExportWith;

        @Override
        public String create(String image, Map<String, String> env) {
            operations.add("create");
            createdImages.add(image);
            return "sandbox-" + created.incrementAndGet();
        }

        @Override
        public void upload(String sandboxId, List<WriteEntry> entries) {
            operations.add("upload");
            uploadSandboxIds.add(sandboxId);
            RuntimeException failure = uploadFailures.poll();
            if (failure != null) {
                throw failure;
            }
            this.uploadedEntries = new ArrayList<>(entries);
        }

        @Override
        public String endpoint(String sandboxId, int port) {
            lookups.add("endpoint");
            return "127.0.0.1:40369/proxy/" + port;
        }

        @Override
        public void launch(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("launch");
        }

        @Override
        public String pauseWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("pause-writers");
            return pauseWritersOutput;
        }

        @Override
        public String resumeWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("resume-writers");
            return resumeWritersOutput;
        }

        @Override
        public String stopWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("stop-writers");
            return stopWritersOutput;
        }

        @Override
        public void renew(String sandboxId, Duration extension) {
            operations.add("renew");
            renewed.incrementAndGet();
            renewalLatch.countDown();
        }

        @Override
        public List<SandboxLifecycle.SandboxSdk.RemoteFile> export(String sandboxId, String remoteRoot) {
            operations.add("export");
            if (failExportWith != null) {
                throw failExportWith;
            }
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put(remoteRoot + "/notes.md", "sandbox export bytes\n".getBytes(StandardCharsets.UTF_8));
            files.put(remoteRoot + "/src/main.js", "console.log('exported');\n".getBytes(StandardCharsets.UTF_8));
            return files.entrySet().stream()
                    .map(entry -> new SandboxLifecycle.SandboxSdk.RemoteFile(
                            entry.getKey().substring(remoteRoot.length() + 1), entry.getValue()))
                    .toList();
        }

        @Override
        public void kill(String sandboxId) {
            operations.add("kill");
            killedSandboxIds.add(sandboxId);
        }
    }
}
