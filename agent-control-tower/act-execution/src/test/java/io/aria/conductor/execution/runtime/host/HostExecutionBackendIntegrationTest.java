package io.aria.conductor.execution.runtime.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-process integration test of the Host execution backend and its verified
 * ownership control (task 9, spec 5.1/5.3).
 *
 * <p>Every run is a real Task 7 mock core process launched through
 * {@link HostExecutionBackend} (fix round 2b: inside a bridge-shaped endpoint
 * shim that serves the run's authenticated bridge contract, so the launch has
 * to pass the backend's per-run endpoint authentication exactly as a production
 * bridge launch must), and every assertion about a writer is a real process
 * liveness or real file-byte observation -- no mocked boolean replaces a
 * process state. The background-writer scenario deliberately leaves a
 * descendant writer alive after the prompt-completion event (Task 7 fixture
 * contract), so the stop proof can only be satisfied by terminating the owned
 * descendant tree.
 */
@Timeout(240)
class HostExecutionBackendIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PEER_CONTROL_TOKEN = "host-backend-peer-control-token-0001";
    private static final String WRITER_LOG = "ticks.log";
    private static final String WRITER_PROMPT =
            "Run this exact command with the Bash tool: node spawn-writer.mjs ticks.log. Then reply with the exact text DONE";

    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception ignored) {
                // best-effort cleanup of a failed test
            }
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void stopWritersCoversTheOwnedDescendantTreeAndFreezesWriterBytes() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();

        Path ticks = run.workspace().resolve(WRITER_LOG);
        long atPromptCompletion = sizeOf(ticks);
        Thread.sleep(300);
        assertThat(sizeOf(ticks))
                .as("the fixture writer must keep writing after the prompt-completion event")
                .isGreaterThan(atPromptCompletion);

        Process sentinel = startSentinel();

        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());

        assertThat(proof.runId()).isEqualTo(run.handle().runId());
        assertThat(proof.allWritersStopped()).isEqualTo(true);
        assertThat(isAlive(run.owned().rootPid())).as("the owned root must be terminated").isEqualTo(false);
        assertThat(isAlive(writer.launcherPid())).as("the owned launcher must be terminated").isEqualTo(false);
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false))
                .as("the owned descendant writer must be terminated").isEqualTo(false);
        assertThat(sentinel.isAlive()).as("an unrelated process must survive").isEqualTo(true);

        Thread.sleep(250);
        long bytesAfterAck = sizeOf(ticks);
        String digestAfterAck = sha256(ticks);
        Thread.sleep(600);
        assertThat(sizeOf(ticks)).as("writer bytes must stay frozen after the stop ack").isEqualTo(bytesAfterAck);
        assertThat(sha256(ticks)).as("writer bytes must stay frozen after the stop ack").isEqualTo(digestAfterAck);
    }

    @Test
    void theHandleCarriesADurableOwnershipIdentityNotABarePid() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);

        OwnedProcess owned = run.owned();
        assertThat(owned.runId()).isEqualTo(run.runId());
        assertThat(owned.ownershipNonce()).as("a run ownership nonce is required").isNotBlank();
        assertThat(owned.rootPid()).isEqualTo(run.peerPid());
        assertThat(owned.rootCreationIdentity()).as("a creation identity is required, not a bare pid").isNotBlank();
        assertThat(owned.rootCreationIdentity()).isNotEqualTo(String.valueOf(owned.rootPid()));
        assertThat(owned.supervisorIdentity()).as("the OS supervisor identity must be recorded").isNotBlank();
        assertThat(owned.technique()).as("the verified OS technique must be recorded").isNotBlank();
        assertThat(owned.liveProcess()).isSameAs(run.peer());

        OwnedProcess persisted = OwnedProcess.parse(run.handle().ownershipIdentity());
        assertThat(persisted.runId()).isEqualTo(owned.runId());
        assertThat(persisted.ownershipNonce()).isEqualTo(owned.ownershipNonce());
        assertThat(persisted.rootPid()).isEqualTo(owned.rootPid());
        assertThat(persisted.rootCreationIdentity()).isEqualTo(owned.rootCreationIdentity());
        assertThat(persisted.supervisorIdentity()).isEqualTo(owned.supervisorIdentity());
        assertThat(persisted.technique()).isEqualTo(owned.technique());
        assertThat(persisted.liveProcess()).as("a persisted ownership record has no live handle").isNull();

        assertThat(OwnedProcess.parse(owned.ownershipIdentity())).isEqualTo(persisted);

        assertThat(run.backend().ownedProcess(run.handle())).contains(owned);
        assertThat(OwnedProcess.ownershipIdentityOf(owned)).isEqualTo(run.handle().ownershipIdentity());

        run.backend().stopWriters(run.handle(), run.deadline());
        assertThat(isAlive(writer.writerPid())).isFalse();
    }

    /**
     * Fix round 2b: the launched runtime is authenticated per run, not by name.
     * The run's endpoint must prove it holds the bridge control secret the
     * backend minted -- a listener of any process name that does not answer the
     * authenticated session route with this run's binding is refused, and the
     * runtime the backend launched is terminated. The impostor here is a real
     * loopback listener serving the bridge's route contract with a response that
     * is not this run's proof: the executable name the launch profile named
     * (node) is exactly what the OS reports, so only the authenticated call can
     * tell the difference.
     */
    @Test
    void aListenerWithoutTheRunSecretIsRefused() throws Exception {
        Path root = canonical(Files.createTempDirectory("host-endpoint-auth"));
        cleanups.add(() -> deleteTree(root));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runtimeRoot = Files.createDirectories(root.resolve("runtime"));
        Path runtimeScript = workspace.resolve("runtime.mjs");
        // A bridge-shaped runtime has already spawned its core child when its
        // endpoint can be refused; the script reproduces that shape with a
        // detached long-lived child so the refusal's tree termination is
        // observable, not just the root's death. The child records its pid
        // before the impostor answers, so the sweep provably saw it.
        Files.writeString(runtimeScript, """
                import { spawn } from 'node:child_process';
                import { writeFileSync } from 'node:fs';
                const childCode = 'require("fs").writeFileSync(process.argv[1], String(process.pid));'
                  + 'setInterval(() => {}, 1000);';
                spawn(process.execPath, ['-e', childCode, process.argv[3]], { detached: true, stdio: 'ignore' });
                writeFileSync(process.argv[2], String(process.pid));
                setInterval(() => {}, 1000);
                """);
        Path pidFile = root.resolve("runtime.pid");
        Path childPidFile = root.resolve("runtime-child.pid");

        AtomicReference<String> presentedSecret = new AtomicReference<>();
        AtomicInteger probes = new AtomicInteger();
        HttpServer impostor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cleanups.add(() -> impostor.stop(0));
        impostor.createContext("/health", exchange -> answer(exchange, 200, "{\"status\":\"ok\"}"));
        impostor.createContext("/session", exchange -> {
            String provided = exchange.getRequestHeaders().getFirst("x-bridge-control-secret");
            if (provided == null) {
                answer(exchange, 401, "{\"error\":{\"code\":\"E_UNAUTHORIZED\"}}");
                return;
            }
            // An authenticated attempt: hold the answer until the launched
            // runtime and the child it spawned have recorded their pids, so the
            // refusal provably observed an identified, live tree rather than a
            // process that never started.
            presentedSecret.set(provided);
            probes.incrementAndGet();
            awaitFile(pidFile, Duration.ofSeconds(30));
            awaitFile(childPidFile, Duration.ofSeconds(30));
            answer(exchange, 401, "{\"error\":{\"code\":\"E_UNAUTHORIZED\"}}");
        });
        impostor.start();
        URI endpoint = URI.create("http://127.0.0.1:" + impostor.getAddress().getPort() + "/");
        assertThat(get(endpoint.resolve("session")).statusCode())
                .as("the impostor must refuse an unauthenticated call before the launch")
                .isEqualTo(401);

        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = spec(runId, workspace, Instant.now().plusSeconds(600));
        WorkspaceLease lease = lease(runId, workspace, runtimeRoot);
        HostExecutionBackend backend = new HostExecutionBackend(OwnedProcessController.forCurrentPlatform(),
                () -> endpoint);
        PreparedEnvironment environment = backend.prepare(spec, lease);
        LaunchProfile profile = new LaunchProfile(
                List.of(nodeExecutable(), runtimeScript.toString(), pidFile.toString(), childPidFile.toString()),
                Map.of(), workspace.toString());
        cleanups.add(() -> ProcessHandle.of(readPidQuietly(pidFile)).ifPresent(ProcessHandle::destroyForcibly));
        cleanups.add(() -> ProcessHandle.of(readPidQuietly(childPidFile)).ifPresent(ProcessHandle::destroyForcibly));

        assertThatThrownBy(() -> backend.launch(environment, profile))
                .as("a listener that does not hold the run's control secret must never be declared ready")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not authenticate");

        long launchedPid = awaitPid(pidFile);
        long launchedChildPid = awaitPid(childPidFile);
        awaitDead(launchedPid);
        awaitDead(launchedChildPid);
        assertThat(isAlive(launchedPid))
                .as("a refused runtime must be terminated, not left unsupervised")
                .isFalse();
        assertThat(isAlive(launchedChildPid))
                .as("the refused launch must terminate the whole tree it started, not just its root")
                .isFalse();
        assertThat(presentedSecret.get())
                .as("the backend must authenticate with a minted per-run secret")
                .isNotNull()
                .isNotBlank()
                .hasSizeGreaterThanOrEqualTo(32);
        assertThat(probes.get())
                .as("a listener that answers without the run's proof is refused at once, not retried")
                .isEqualTo(1);
        assertThat(get(endpoint.resolve("health")).statusCode())
                .as("a refused launch must never touch the listener it did not authenticate")
                .isEqualTo(200);
    }

    @Test
    void stopTerminatesAnOwnedTreeThatIgnoresTheCooperativeCancel() throws Exception {
        PeerRun run = new PeerRun("non-cooperative");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();

        run.sendCancel();
        run.awaitDiagnostic("peer.cancel_ignored", 20_000);

        Path ticks = run.workspace().resolve(WRITER_LOG);
        long before = sizeOf(ticks);
        Thread.sleep(300);
        assertThat(sizeOf(ticks)).as("the core ignored cancellation; the writer keeps writing").isGreaterThan(before);

        Process sentinel = startSentinel();
        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());

        assertThat(proof.runId()).isEqualTo(run.runId());
        assertThat(proof.allWritersStopped()).isEqualTo(true);
        assertThat(isAlive(run.owned().rootPid())).isEqualTo(false);
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isEqualTo(false);
        assertThat(sentinel.isAlive()).isEqualTo(true);
    }

    @Test
    void stopCoversTheRunWhileAPermissionRequestIsStillPending() throws Exception {
        PeerRun run = new PeerRun("cancel-pending");
        run.handshake();
        run.prompt("Create probe-cancel-pending.txt with the fixture text");
        JsonNode permission = run.awaitServerRequest(30_000);
        assertThat(permission.at("/params/toolCall/kind").asText()).isEqualTo("edit");
        Path target = run.workspace().resolve("probe-cancel-pending.txt");

        Process sentinel = startSentinel();
        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());

        assertThat(proof.allWritersStopped()).isEqualTo(true);
        assertThat(isAlive(run.owned().rootPid())).isEqualTo(false);
        assertThat(Files.exists(target)).as("an unanswered permission request must not write").isFalse();
        assertThat(sentinel.isAlive()).isEqualTo(true);
    }

    @Test
    void pauseSuspendsTheWholeOwnedTreeAndResumeReusesTheSameRuntime() throws Exception {
        PeerRun run = new PeerRun("pause-resume");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        Path ticks = run.workspace().resolve(WRITER_LOG);
        long before = sizeOf(ticks);
        Thread.sleep(300);
        assertThat(sizeOf(ticks)).isGreaterThan(before);

        ControlAck paused = run.backend().pauseWriters(run.handle(), run.deadline())
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
        assertThat(paused.verified()).isTrue();
        assertThat(paused.state()).isEqualTo(ControlState.PAUSED);

        Thread.sleep(200);
        long frozen = sizeOf(ticks);
        Thread.sleep(600);
        assertThat(sizeOf(ticks)).as("no write may occur after a verified pause acknowledgment").isEqualTo(frozen);

        assertThat(isAlive(run.owned().rootPid())).as("the paused tree keeps its ownership identity").isTrue();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isTrue();

        ControlAck resumed = run.backend().resumeWriters(run.handle(), run.deadline())
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
        assertThat(resumed.verified()).isTrue();
        assertThat(resumed.state()).isEqualTo(ControlState.RUNNING);

        Thread.sleep(700);
        assertThat(sizeOf(ticks)).as("the resumed owned writer continues on the same runtime").isGreaterThan(frozen);

        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());
        assertThat(proof.allWritersStopped()).isEqualTo(true);
        assertThat(isAlive(writer.writerPid())).isFalse();
    }

    @Test
    void anElapsedDeadlineYieldsAnUnverifiedProofWithoutTouchingTheOwnedTree() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();

        Process sentinel = startSentinel();
        Instant elapsed = Instant.now().minusSeconds(5);

        StopProof proof = run.backend().stopWriters(run.handle(), elapsed);
        assertThat(proof.runId()).isEqualTo(run.runId());
        assertThat(proof.allWritersStopped()).as("an elapsed window cannot support a stop proof").isFalse();
        assertThat(isAlive(run.owned().rootPid())).isTrue();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isTrue();
        assertThat(sentinel.isAlive()).isTrue();

        ControlAck pause = run.backend().pauseWriters(run.handle(), elapsed)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(pause.verified()).as("an unavailable window must not report a state change").isFalse();
        assertThat(pause.state()).isEqualTo(ControlState.RUNNING);

        StopProof after = run.backend().stopWriters(run.handle(), run.deadline());
        assertThat(after.allWritersStopped()).isTrue();
        assertThat(isAlive(run.owned().rootPid())).isFalse();
    }

    /**
     * Fix round 2b: a record reconstructed from the persisted ownership identity
     * after a restart authorizes nothing. The ownership nonce is verified only
     * against the launching controller's in-memory binding, so a parsed record
     * must be refused by every control path -- fail closed -- no matter how well
     * its pid, creation identity and handle agree. Only the controller that
     * launched the run may control it; adopting or reaping a surviving run after
     * a restart is the recovery coordinator's job, with run-store evidence.
     */
    @Test
    void aRestartedControllerRefusesAPersistedOwnershipRecordAndLeavesTheRunAlone() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();

        OwnedProcess persisted = OwnedProcess.parse(run.handle().ownershipIdentity());
        assertThat(persisted.liveProcess()).isNull();

        Process sentinel = startSentinel();
        OwnedProcessController restarted = OwnedProcessController.forCurrentPlatform();
        cleanups.add(restarted::close);
        assertThat(restarted.owns(persisted))
                .as("a record reconstructed after a restart has no in-memory binding and must never pass ownership")
                .isFalse();

        StopProof refused = restarted.stop(persisted, run.deadline());
        assertThat(refused.runId()).isEqualTo(run.handle().runId());
        assertThat(refused.allWritersStopped())
                .as("no stop proof may be claimed from a record the restarted controller never bound")
                .isFalse();
        assertThat(isAlive(run.owned().rootPid())).as("the refused stop must leave the run untouched").isTrue();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isTrue();

        Path ticks = run.workspace().resolve(WRITER_LOG);
        long before = sizeOf(ticks);
        Thread.sleep(300);
        assertThat(sizeOf(ticks))
                .as("the refused stop terminated nothing: the owned writer keeps writing")
                .isGreaterThan(before);
        assertThat(sentinel.isAlive()).as("an unrelated process must survive").isTrue();

        // The backend that still holds the live binding can stop the run as usual.
        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());
        assertThat(proof.allWritersStopped()).isTrue();
        assertThat(isAlive(run.owned().rootPid())).isFalse();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void stopNeverTouchesAProcessTheRunDoesNotOwn() throws Exception {
        Process sentinel = startSentinel();
        long unrelatedPid = sentinel.pid();
        OwnedProcess unrelated = new OwnedProcess(UUID.randomUUID(), UUID.randomUUID().toString(),
                unrelatedPid, "1", "1@1", "identity-validated-descendant-tree", null);

        OwnedProcessController controller = OwnedProcessController.forCurrentPlatform();
        assertThat(controller.owns(unrelated)).as("a stale creation identity must not pass ownership").isFalse();

        StopProof proof = controller.stop(unrelated, Instant.now().plusSeconds(30));
        assertThat(proof.runId()).isEqualTo(unrelated.runId());
        assertThat(proof.allWritersStopped()).isFalse();
        assertThat(sentinel.isAlive()).as("a refused stop must never touch an unrelated process").isTrue();

        controller.release(unrelated);
        assertThat(sentinel.isAlive()).isTrue();
    }

    @Test
    void prepareAllocatesALoopbackEndpointAndFailsExplicitlyOnBindFailure() throws Exception {
        Path root = canonical(Files.createTempDirectory("host-backend-prepare"));
        cleanups.add(() -> deleteTree(root));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runtimeRoot = Files.createDirectories(root.resolve("runtime"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = spec(runId, workspace, Instant.now().plusSeconds(600));
        WorkspaceLease lease = lease(runId, workspace, runtimeRoot);

        HostExecutionBackend backend = HostExecutionBackend.forCurrentPlatform();
        PreparedEnvironment environment = backend.prepare(spec, lease);

        assertThat(environment.mode()).isEqualTo(ExecutionMode.HOST);
        assertThat(environment.runId()).isEqualTo(runId);
        assertThat(environment.endpoint().getScheme()).isEqualTo("http");
        assertThat(environment.endpoint().getHost()).isEqualTo("127.0.0.1");
        assertThat(environment.endpoint().getPort()).isPositive();
        assertThat(environment.workingDirectory()).isEqualTo(workspace.toString());
        assertThat(Path.of(environment.configurationDirectory())).startsWith(runtimeRoot);
        assertThat(Files.isDirectory(Path.of(environment.configurationDirectory()))).isTrue();

        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", environment.endpoint().getPort()), 300);
            }
        }).as("prepare must never connect to or fall back to an unrelated service")
                .isInstanceOf(IOException.class);

        HostExecutionBackend failing = new HostExecutionBackend(OwnedProcessController.forCurrentPlatform(),
                () -> {
                    throw new IOException("loopback bind refused by the harness");
                });
        assertThatThrownBy(() -> failing.prepare(spec, lease))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void anUnavailableControlTechniqueFailsExplicitly() throws Exception {
        Path root = canonical(Files.createTempDirectory("host-backend-technique"));
        cleanups.add(() -> deleteTree(root));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        LaunchProfile profile = new LaunchProfile(List.of(nodeExecutable(), "--version"), Map.of(),
                workspace.toString());
        UUID runId = UUID.randomUUID();

        if (isWindows()) {
            WindowsProcessController controller =
                    new WindowsProcessController(root.resolve("missing-powershell.exe"));
            assertThatThrownBy(() -> controller.start(runId, profile))
                    .as("a missing OS supervisor must fail explicitly instead of PID signalling")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("supervisor");
        } else {
            PosixProcessController controller =
                    new PosixProcessController(root.resolve("missing-signal-host"));
            assertThatThrownBy(() -> controller.start(runId, profile))
                    .as("a missing signal host must fail explicitly instead of PID signalling")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("signal host");
        }
    }

    /**
     * The adversarial window of the POSIX stop proof (Task 9 review C1): the
     * recorded root dies while the sweep is in flight, before the sweep itself
     * killed it. A descendant it spawned in that window is orphaned and a dead
     * root can never anchor an enumeration again, so a proof that only says "the
     * enumeration is empty" is a lie. The signal host is the fixture shim
     * (documented in {@code signal-host-shim.mjs}): it kills the recorded root
     * once before its first delivery and then delivers the requested signal for
     * real. The enumeration, the identity checks and the proof decision are the
     * production ones.
     */
    @Test
    void stopRefusesWhenTheRootVanishesDuringTheSweep() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-vanishing-root"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path writer = workspace.resolve("shim-writer.mjs");
        Files.writeString(writer, """
                import { appendFileSync, writeFileSync } from 'node:fs';
                writeFileSync(process.argv[3], String(process.pid));
                setInterval(() => appendFileSync(process.argv[2], "tick\\n"), 20);
                """);
        Path rootScript = workspace.resolve("shim-root.mjs");
        // The descendant is spawned detached: it must survive the root's death
        // (the situation the stop proof has to handle), and on Windows it must
        // not be attached to the root's console, which dies with the root and
        // would take the descendant down as a fixture artifact.
        Files.writeString(rootScript, """
                import { spawn } from 'node:child_process';
                spawn(process.execPath, process.argv.slice(2), { stdio: 'ignore', detached: true });
                setInterval(() => {}, 1000);
                """);
        Path writerLog = workspace.resolve("shim-writer.log");
        Path writerPidFile = workspace.resolve("shim-writer.pid");
        Path rootPidFile = dir.resolve("root.pid");

        PosixProcessController controller = new PosixProcessController(signalHostShim(dir));
        UUID runId = UUID.randomUUID();
        LaunchProfile profile = new LaunchProfile(List.of(nodeExecutable(), rootScript.toString(),
                writer.toString(), writerLog.toString(), writerPidFile.toString()), Map.of(),
                workspace.toString());
        OwnedProcess owned = controller.start(runId, profile);
        cleanups.add(() -> {
            controller.release(owned);
            controller.close();
        });
        long writerPid = awaitPid(writerPidFile);
        cleanups.add(() -> destroyPid(writerPid));
        cleanups.add(() -> destroyPid(owned.rootPid()));
        cleanups.add(() -> {
            controller.release(owned);
            controller.close();
        });
        // Registered last: the tree must be gone before the directory deletion
        // (the detached writer's working directory is the workspace).
        cleanups.add(() -> deleteTree(dir));
        Files.writeString(rootPidFile, Long.toString(owned.rootPid()));
        Process sentinel = startSentinel();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        assertThat(proof.runId()).isEqualTo(runId);
        assertThat(proof.allWritersStopped())
                .as("a root that vanishes before the sweep killed it can hide an orphaned descendant;"
                        + " the stop must refuse instead of returning a proof")
                .isFalse();
        assertThat(isAlive(writerPid))
                .as("a refused stop must leave the owned tree as it found it")
                .isTrue();
        assertThat(sentinel.isAlive()).as("an unrelated process must survive").isTrue();
    }

    /**
     * The adversarial window of the Windows pause ledger (Task 9 review C2): an
     * enumerated record exits between the tree enumeration and the suspend batch.
     * The supervisor still applies the rest of the batch, so the Java side must
     * record every applied suspension before it reports the partial batch as
     * unverified -- otherwise a later resume claims RUNNING/verified while part
     * of the tree is still suspended.
     */
    @Test
    void aVerifiedResumeCoversEverySuspensionAPartialPauseBatchApplied() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-partial-suspend"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");

        WindowsProcessController controller = new WindowsProcessController(fakeSupervisorHost(dir, protocolLog));
        UUID runId = UUID.randomUUID();
        LaunchProfile profile = new LaunchProfile(List.of(nodeExecutable(), "-e", "setInterval(() => {}, 1000);"),
                Map.of(), workspace.toString());
        OwnedProcess owned = controller.start(runId, profile);
        cleanups.add(() -> {
            Process live = owned.liveProcess();
            if (live != null && live.isAlive()) {
                live.destroyForcibly();
                live.waitFor(10, TimeUnit.SECONDS);
            }
            controller.release(owned);
            controller.close();
        });
        // Registered last: the launched runtime must be gone before the deletion.
        cleanups.add(() -> deleteTree(dir));
        Instant deadline = Instant.now().plusSeconds(30);

        ControlAck paused = controller.pause(owned, deadline).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(paused.state()).isEqualTo(ControlState.RUNNING);
        assertThat(paused.verified())
                .as("a suspend batch that lost a record is a partial state, never a verified pause")
                .isFalse();

        ControlAck resumed = controller.resume(owned, deadline).toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(resumed.state()).isEqualTo(ControlState.RUNNING);
        assertThat(Files.readAllLines(protocolLog))
                .as("the fixture must have applied part of the batch or the test is vacuous")
                .anyMatch(line -> line.startsWith("applied: "));
        assertThat(suspensionsWithoutAResume(protocolLog))
                .as("a verified resume must cover every suspension the supervisor actually applied")
                .isEmpty();
        assertThat(resumed.verified()).isTrue();
    }

    @Test
    void exportRequiresAVerifiedStopAndDestroyRemovesOnlyGeneratedConfiguration() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        run.handshake();
        run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();
        Path destination = Files.createDirectories(run.root().resolve("export"));

        assertThatThrownBy(() -> run.backend().exportWorkspace(run.handle(), destination,
                new StopProof(run.runId(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Writers are not stopped");
        assertThat(entriesOf(destination)).isEmpty();

        assertThatThrownBy(() -> run.backend().exportWorkspace(run.handle(), destination,
                new StopProof(UUID.randomUUID(), true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(entriesOf(destination)).isEmpty();

        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());
        assertThat(proof.allWritersStopped()).isTrue();
        Path generated = Path.of(run.environment().configurationDirectory());
        assertThat(Files.isDirectory(generated))
                .as("stopping writers must not delete the run's generated configuration")
                .isTrue();

        run.backend().exportWorkspace(run.handle(), destination, proof);
        assertThat(Files.readAllBytes(destination.resolve(WRITER_LOG)))
                .isEqualTo(Files.readAllBytes(run.workspace().resolve(WRITER_LOG)));

        run.backend().destroy(run.handle());
        assertThat(Files.exists(generated)).as("destroy removes the generated run configuration").isFalse();
        assertThat(Files.exists(run.workspace().resolve(WRITER_LOG)))
                .as("destroy never deletes the workspace")
                .isTrue();
    }

    /**
     * Fix round 2a: the run's hard deadline bounds execution, not cleanup. Once it
     * has elapsed the writers are still the writers, so the caller must be able to
     * stop them with a cleanup window it supplies itself, and the stop must leave
     * the handle usable: the order is stop, export, destroy. Under the old clamp
     * every fresh window was folded back into the elapsed deadline, no stop could
     * ever succeed again, and destroying first (the only way left) removed the
     * environment {@code exportWorkspace} needs -- the run could be neither
     * stopped nor exported.
     */
    @Test
    void stopAndExportAcceptAFreshCleanupWindowAfterTheRunDeadlineElapsed() throws Exception {
        PeerRun run = new PeerRun("background-writer", Instant.now().plusSeconds(2));
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();

        awaitPast(run.deadline());
        Path ticks = run.workspace().resolve(WRITER_LOG);
        long atDeadline = sizeOf(ticks);
        Thread.sleep(300);
        assertThat(sizeOf(ticks))
                .as("the elapsed run deadline stops nothing on its own: the writers keep writing")
                .isGreaterThan(atDeadline);
        assertThat(isAlive(run.owned().rootPid())).isTrue();

        StopProof proof = run.backend().stopWriters(run.handle(), Instant.now().plusSeconds(60));

        assertThat(proof.allWritersStopped())
                .as("cleanup after the run deadline must honour the caller's own window")
                .isTrue();
        assertThat(isAlive(run.owned().rootPid())).isFalse();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();

        // The stop must not have destroyed the handle: export is what consumes the
        // proof, and it must still work right now, with destroy still to come.
        Path destination = Files.createDirectories(run.root().resolve("export-after-deadline"));
        run.backend().exportWorkspace(run.handle(), destination, proof);
        assertThat(Files.readAllBytes(destination.resolve(WRITER_LOG)))
                .as("the export handle must survive the stop until the capture completes")
                .isEqualTo(Files.readAllBytes(run.workspace().resolve(WRITER_LOG)));

        run.backend().destroy(run.handle());
        assertThat(Files.exists(Path.of(run.environment().configurationDirectory())))
                .as("destroy after the capture removes the generated configuration")
                .isFalse();
        assertThat(Files.exists(run.workspace().resolve(WRITER_LOG)))
                .as("destroy never deletes the workspace")
                .isTrue();
    }

    /**
     * Fix round 2a: pause and resume are cleanup-window operations too. After the
     * run deadline elapsed, a fresh caller-supplied window must still verify the
     * owned tree paused and resumed -- the deadline governs the run's execution,
     * not whether its writers can be frozen.
     */
    @Test
    void pauseAndResumeAcceptAFreshCleanupWindowAfterTheRunDeadlineElapsed() throws Exception {
        PeerRun run = new PeerRun("background-writer", Instant.now().plusSeconds(2));
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();
        awaitPast(run.deadline());

        ControlAck paused = run.backend().pauseWriters(run.handle(), Instant.now().plusSeconds(60))
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
        assertThat(paused.verified())
                .as("a fresh cleanup window must still pause the owned tree after the run deadline")
                .isTrue();
        assertThat(paused.state()).isEqualTo(ControlState.PAUSED);

        Path ticks = run.workspace().resolve(WRITER_LOG);
        long frozen = sizeOf(ticks);
        Thread.sleep(400);
        assertThat(sizeOf(ticks))
                .as("a verified pause freezes the writers regardless of the run deadline")
                .isEqualTo(frozen);

        ControlAck resumed = run.backend().resumeWriters(run.handle(), Instant.now().plusSeconds(60))
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
        assertThat(resumed.verified()).isTrue();
        assertThat(resumed.state()).isEqualTo(ControlState.RUNNING);
        Thread.sleep(500);
        assertThat(sizeOf(ticks)).as("the resumed owned writer continues").isGreaterThan(frozen);

        StopProof proof = run.backend().stopWriters(run.handle(), Instant.now().plusSeconds(60));
        assertThat(proof.allWritersStopped()).isTrue();
        assertThat(isAlive(writer.writerPid())).isFalse();
    }

    /**
     * Fix round 2a: the run's supervision must be attached before the core can
     * exist. The supervisor's script creates the job object (with kill-on-close)
     * before its ready line, the ready line is awaited before launch, and
     * membership is requested the moment CreateProcess returns -- so the residual
     * unsupervised window is only CreateProcess-to-assign, which the
     * identity-validated enumerated sweep covers. Both creation identities are
     * read from the same OS clock (FILETIME ticks), so "supervisor first" is a
     * fact the OS records, not a claim in a comment.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theSupervisorAndItsJobExistBeforeTheCoreIsLaunched() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        OwnedProcess owned = run.owned();

        assertThat(creationTicks(owned.supervisorIdentity()))
                .as("the supervisor (and the job it creates before the ready line, awaited before launch)"
                        + " must be created before the core; both ticks come from the same OS clock")
                .isLessThan(creationTicks(owned.rootCreationIdentity()));
    }

    /**
     * Fix round 2a: the job is created with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE},
     * the technique Task 1's committed harness validated, so a JVM or supervisor
     * that dies hard cannot leave an unsupervised writer behind. The observation
     * here is a terminated member after the last supervision channel closes; the
     * fixture asserts the premise (the OS accepted the assignment) rather than
     * passing vacuously when job membership was refused.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void closingTheLastSupervisionChannelKillsAStillRunningJobMember() throws Exception {
        Path root = canonical(Files.createTempDirectory("host-job-backstop"));
        cleanups.add(() -> deleteTree(root));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        WindowsProcessController controller = new WindowsProcessController();
        UUID runId = UUID.randomUUID();
        LaunchProfile profile = new LaunchProfile(List.of(nodeExecutable(), "-e", "setInterval(() => {}, 1000);"),
                Map.of(), workspace.toString());
        OwnedProcess owned = controller.start(runId, profile);
        Process live = owned.liveProcess();
        cleanups.add(() -> {
            if (live.isAlive()) {
                live.destroyForcibly();
                live.waitFor(10, TimeUnit.SECONDS);
            }
            controller.release(owned);
            controller.close();
        });

        assertThat(owned.technique())
                .as("the backstop cannot be observed when the OS refused job membership")
                .contains("job-backstop=assign:ok");
        assertThat(live.isAlive()).isTrue();

        controller.close();

        assertThat(live.waitFor(30, TimeUnit.SECONDS))
                .as("kill-on-close must take a surviving member down with the last supervision channel"
                        + " (the backstop for a hard JVM death)")
                .isTrue();
    }

    // ------------------------------------------------------------------ harness

    /** One real mock-core run launched through the backend under test. */
    private final class PeerRun implements AutoCloseable {

        private final Path root;
        private final Path workspace;
        private final Path runtimeRoot;
        private final UUID runId;
        private final Instant deadline;
        private final HostExecutionBackend backend;
        private final PreparedEnvironment environment;
        private final RuntimeHandle handle;
        private final OwnedProcess owned;
        private final Process peer;
        private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final BlockingQueue<JsonNode> serverRequests = new LinkedBlockingQueue<>();
        private final BlockingQueue<JsonNode> diagnostics = new LinkedBlockingQueue<>();
        private final List<JsonNode> diagnosticLog = new CopyOnWriteArrayList<>();
        private long nextId = 1;
        private long promptId;
        private CompletableFuture<JsonNode> promptResult;
        private String sessionId;

        PeerRun(String scenario) throws IOException, InterruptedException {
            this(scenario, Instant.now().plus(Duration.ofMinutes(10)));
        }

        PeerRun(String scenario, Instant deadline) throws IOException, InterruptedException {
            root = canonical(Files.createTempDirectory("host-backend-it"));
            workspace = Files.createDirectories(root.resolve("workspace"));
            runtimeRoot = Files.createDirectories(root.resolve("runtime"));
            runId = UUID.randomUUID();
            this.deadline = deadline;
            backend = HostExecutionBackend.forCurrentPlatform();
            environment = backend.prepare(spec(runId, workspace, deadline), lease(runId, workspace, runtimeRoot));
            // The launched runtime is bridge-shaped: the endpoint shim serves the
            // run's authenticated bridge contract from the secret the backend
            // delivers into its environment, and pipes the mock core's stdio, so
            // the launch passes the backend's per-run endpoint authentication
            // exactly as a production bridge launch must.
            LaunchProfile profile = new LaunchProfile(
                    List.of(nodeExecutable(), endpointShim().toString(),
                            "--port", String.valueOf(environment.endpoint().getPort()),
                            "--run-id", runId.toString(),
                            "--workspace", workspace.toString(),
                            "--",
                            peerScript().toString(),
                            "--scenario", scenario, "--workspace", workspace.toString()),
                    Map.of("ARIA_PEER_CONTROL_TOKEN", PEER_CONTROL_TOKEN),
                    workspace.toString());
            handle = backend.launch(environment, profile);
            owned = backend.ownedProcess(handle)
                    .orElseThrow(() -> new IllegalStateException("the backend returned no owned process"));
            peer = owned.liveProcess();
            startReader("peer-stdout", peer.getInputStream(), this::routeFrame);
            startReader("peer-stderr", peer.getErrorStream(), this::routeDiagnostic);
            cleanups.add(this);
            // The fixture asserts the delivery part of the chain explicitly: the
            // shim reads the control secret from its environment only, so this
            // diagnostic is false when the backend did not deliver a minted
            // secret -- and the launch would then have been refused already.
            JsonNode shimReady = awaitDiagnostic("shim.ready", 30_000);
            assertThat(shimReady.path("secretDelivered").asBoolean())
                    .as("the backend must deliver the minted per-run control secret into the runtime environment")
                    .isTrue();
        }

        Path root() {
            return root;
        }

        Path workspace() {
            return workspace;
        }

        UUID runId() {
            return runId;
        }

        Instant deadline() {
            return deadline;
        }

        HostExecutionBackend backend() {
            return backend;
        }

        PreparedEnvironment environment() {
            return environment;
        }

        RuntimeHandle handle() {
            return handle;
        }

        OwnedProcess owned() {
            return owned;
        }

        Process peer() {
            return peer;
        }

        long peerPid() {
            return peer.pid();
        }

        void handshake() throws Exception {
            JsonNode initialized = request("initialize",
                    Map.of("protocolVersion", 1, "clientCapabilities", Map.of()));
            assertThat(initialized.get("protocolVersion").asInt()).isEqualTo(1);
            JsonNode created = request("session/new",
                    Map.of("cwd", workspace.toString(), "mcpServers", List.of()));
            sessionId = created.get("sessionId").asText();
            assertThat(sessionId).isNotBlank();
        }

        Writer promptAndApproveWriter(String prompt) throws Exception {
            prompt(prompt);
            JsonNode permission = awaitServerRequest(30_000);
            assertThat(permission.path("method").asText()).isEqualTo("session/request_permission");
            assertThat(permission.at("/params/toolCall/kind").asText()).isEqualTo("execute");
            answerPermission(permission.path("id").asLong(), "proceed_once");

            JsonNode child = awaitDiagnostic("peer.child_started", 30_000);
            long writerPid = child.path("pid").asLong();
            long launcherPid = child.path("launcherPid").asLong();
            assertThat(writerPid).isPositive();
            assertThat(launcherPid).isPositive();
            return new Writer(writerPid, launcherPid);
        }

        void prompt(String text) throws IOException {
            promptId = nextId++;
            promptResult = new CompletableFuture<>();
            pending.put(promptId, promptResult);
            ObjectNode frame = JSON.createObjectNode();
            frame.put("jsonrpc", "2.0").put("id", promptId).put("method", "session/prompt");
            frame.set("params", JSON.valueToTree(Map.of(
                    "sessionId", sessionId,
                    "prompt", List.of(Map.of("type", "text", "text", text)))));
            write(frame);
        }

        JsonNode awaitPromptResult() throws Exception {
            assertThat(promptResult).as("a prompt must have been sent").isNotNull();
            JsonNode response = promptResult.get(60, TimeUnit.SECONDS);
            assertThat(response.has("error")).as("prompt error: " + response).isFalse();
            return response.get("result");
        }

        void sendCancel() throws IOException {
            ObjectNode frame = JSON.createObjectNode();
            frame.put("jsonrpc", "2.0").put("method", "session/cancel");
            frame.set("params", JSON.valueToTree(Map.of("sessionId", sessionId)));
            write(frame);
        }

        JsonNode awaitServerRequest(long timeoutMillis) throws InterruptedException {
            JsonNode frame = serverRequests.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            assertThat(frame).as("no server request arrived").isNotNull();
            return frame;
        }

        JsonNode awaitDiagnostic(String type, long timeoutMillis) throws InterruptedException {
            Instant limit = Instant.now().plusMillis(timeoutMillis);
            while (Instant.now().isBefore(limit)) {
                JsonNode frame = diagnostics.poll(Math.max(1, Duration.between(Instant.now(), limit).toMillis()),
                        TimeUnit.MILLISECONDS);
                if (frame == null) {
                    break;
                }
                if (type.equals(frame.path("type").asText())) {
                    return frame;
                }
            }
            throw new AssertionError("no '" + type + "' diagnostic arrived; recorded: " + diagnosticLog);
        }

        private void answerPermission(long requestId, String optionId) throws IOException {
            ObjectNode frame = JSON.createObjectNode();
            frame.put("jsonrpc", "2.0").put("id", requestId);
            frame.set("result", JSON.valueToTree(Map.of(
                    "outcome", Map.of("outcome", "selected", "optionId", optionId))));
            write(frame);
        }

        private JsonNode request(String method, Map<String, Object> params) throws Exception {
            long id = nextId++;
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            ObjectNode frame = JSON.createObjectNode();
            frame.put("jsonrpc", "2.0").put("id", id).put("method", method);
            frame.set("params", JSON.valueToTree(params));
            write(frame);
            JsonNode response = future.get(60, TimeUnit.SECONDS);
            assertThat(response.has("error")).as(method + " error: " + response).isFalse();
            return response.get("result");
        }

        private synchronized void write(JsonNode frame) throws IOException {
            OutputStream out = peer.getOutputStream();
            out.write(JSON.writeValueAsBytes(frame));
            out.write('\n');
            out.flush();
        }

        private void routeFrame(JsonNode frame) {
            JsonNode id = frame.get("id");
            if (id != null && frame.has("method")) {
                serverRequests.offer(frame);
                return;
            }
            if (id != null && (frame.has("result") || frame.has("error"))) {
                CompletableFuture<JsonNode> future = pending.remove(id.asLong());
                if (future != null) {
                    future.complete(frame);
                }
                return;
            }
            if (id == null && frame.has("method")) {
                // streaming notifications are observed through the diagnostics/test assertions
                diagnosticLog.add(frame);
            }
        }

        private void routeDiagnostic(JsonNode frame) {
            diagnosticLog.add(frame);
            diagnostics.offer(frame);
        }

        @Override
        public void close() {
            destroyLaunchedTree();
            try {
                backend.destroy(handle);
            } catch (RuntimeException ignored) {
                // supervision already released
            }
            try {
                deleteTree(root);
            } catch (RuntimeException | IOException ignored) {
                // temp dir cleanup is best effort
            }
        }

        /**
         * Terminates the whole launched tree. The fixture's root is the endpoint
         * shim and the mock core is its child: a plain root kill would orphan the
         * core (POSIX) or leave it running outside the Windows job, and the temp
         * workspace could not be removed. Tests that stop writers first have
         * already emptied the tree; this is the cleanup for the rest.
         */
        private void destroyLaunchedTree() {
            List<ProcessHandle> descendants = peer.toHandle().descendants().toList();
            for (ProcessHandle descendant : descendants) {
                descendant.destroyForcibly();
            }
            peer.destroyForcibly();
            try {
                peer.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Real pids of the owned writer tree the mock core left behind. */
    private record Writer(long writerPid, long launcherPid) {
    }

    private static void startReader(String name, java.io.InputStream stream, java.util.function.Consumer<JsonNode> consumer) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        consumer.accept(JSON.readTree(line));
                    } catch (IOException ignored) {
                        // a non-JSON line carries no fixture record
                    }
                }
            } catch (IOException ignored) {
                // the stream closes with the process
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private Process startSentinel() throws IOException {
        Process sentinel = new ProcessBuilder(nodeExecutable(), "-e", "setInterval(() => {}, 1000);")
                .redirectErrorStream(true)
                .start();
        cleanups.add(() -> {
            if (sentinel.isAlive()) {
                sentinel.destroyForcibly();
            }
        });
        return sentinel;
    }

    /** Terminates one fixture process and waits for the OS to reap it. */
    private static void destroyPid(long pid) {
        ProcessHandle.of(pid).ifPresent(handle -> {
            handle.destroyForcibly();
            try {
                handle.onExit().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort: a process that cannot be observed is already gone
            }
        });
    }

    private static long awaitPid(Path pidFile) throws Exception {
        Instant limit = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(limit)) {
            if (Files.isRegularFile(pidFile)) {
                String text = Files.readString(pidFile).trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the fixture process did not record its pid in " + pidFile);
    }

    /**
     * A part of the fixture contract that is not on the classpath is a broken
     * test host, never a silently skipped assertion.
     */
    private static Path hostTestResource(String name) {
        var url = HostExecutionBackendIntegrationTest.class
                .getResource("/io/aria/conductor/execution/runtime/host/" + name);
        if (url == null || !"file".equals(url.getProtocol())) {
            throw new IllegalStateException("the host test fixture " + name + " is not on the classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("unresolvable host test fixture " + name, e);
        }
    }

    /**
     * The signal host for {@link #stopRefusesWhenTheRootVanishesDuringTheSweep}:
     * the same contract the controller gives {@code /bin/sh} ({@code -c <script>
     * <pid>}), fulfilled on this host by {@code signal-host-shim.mjs} -- which
     * kills the recorded root once before its first delivery (the deterministic
     * form of "the root dies while the sweep is in flight") and then delivers
     * the signal for real (taskkill on Windows, kill on POSIX).
     */
    private static Path signalHostShim(Path dir) throws IOException {
        Path script = hostTestResource("signal-host-shim.mjs");
        if (isWindows()) {
            Path cmd = dir.resolve("signal-host.cmd");
            Files.writeString(cmd, "@echo off\r\n" + nodeCommand() + " \"" + script + "\" --here \"%~dp0.\" %*\r\n");
            return cmd;
        }
        Path sh = dir.resolve("signal-host.sh");
        Files.writeString(sh, "#!/bin/sh\n"
                + "here=\"$(dirname \"$0\")\"\n"
                + "script=\"$2\"\n"
                + "pid=\"$3\"\n"
                + "sig=\"\"\n"
                + "case \"$script\" in\n"
                + "  *\" -s KILL \"*) sig=KILL ;;\n"
                + "  *\" -s STOP \"*) sig=STOP ;;\n"
                + "  *\" -s CONT \"*) sig=CONT ;;\n"
                + "esac\n"
                + "if [ ! -f \"$here/injected\" ]; then\n"
                + "  : > \"$here/injected\"\n"
                + "  kill -9 \"$(cat \"$here/root.pid\")\" 2>/dev/null || true\n"
                + "fi\n"
                + "if [ \"$sig\" != \"\" ]; then kill -s \"$sig\" \"$pid\" 2>/dev/null || true; fi\n"
                + "exit 0\n");
        Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
        return sh;
    }

    /**
     * The fake supervisor host for
     * {@link #aVerifiedResumeCoversEverySuspensionAPartialPauseBatchApplied}:
     * the controller starts it exactly like PowerShell, and the fixture speaks
     * the same line protocol deterministically.
     */
    private static Path fakeSupervisorHost(Path dir, Path protocolLog) throws IOException {
        Path script = hostTestResource("fake-host-supervisor.mjs");
        if (isWindows()) {
            Path cmd = dir.resolve("fake-host-supervisor.cmd");
            Files.writeString(cmd, "@echo off\r\n" + nodeCommand() + " \"" + script + "\" --log \""
                    + protocolLog + "\" %*\r\n");
            return cmd;
        }
        Path sh = dir.resolve("fake-host-supervisor.sh");
        Files.writeString(sh, "#!/bin/sh\nexec " + nodeCommand() + " \"" + script + "\" --log \""
                + protocolLog + "\" \"$@\"\n");
        Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
        return sh;
    }

    /** The pinned node executable as a shell-safe command word for the fixtures. */
    private static String nodeCommand() {
        String node = nodeExecutable();
        return node.contains("/") || node.contains("\\") || node.contains(" ") ? "\"" + node + "\"" : node;
    }

    /** Records the fake supervisor applied a suspension for and never received a resume. */
    private static List<String> suspensionsWithoutAResume(Path protocolLog) throws IOException {
        Map<String, Integer> outstanding = new LinkedHashMap<>();
        for (String line : Files.readAllLines(protocolLog)) {
            if (line.startsWith("applied: ")) {
                outstanding.merge(line.substring("applied: ".length()).trim(), 1, Integer::sum);
            } else if (line.startsWith("resumed: ")) {
                String record = line.substring("resumed: ".length()).trim();
                Integer count = outstanding.get(record);
                if (count != null) {
                    if (count == 1) {
                        outstanding.remove(record);
                    } else {
                        outstanding.put(record, count - 1);
                    }
                }
            }
        }
        return List.copyOf(outstanding.keySet());
    }

    /** Answer one loopback probe of the endpoint-auth fixture with a JSON body. */
    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** One direct, bounded GET of the endpoint-auth fixture (the fixture's own contract check). */
    private static HttpResponse<String> get(URI uri) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Waits for a fixture file to exist (bounded); absence alone is not an error here. */
    private static void awaitFile(Path path, Duration window) {
        Instant limit = Instant.now().plus(window);
        while (!Files.isRegularFile(path) && Instant.now().isBefore(limit)) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Pid recorded by a fixture process, or 0 when it has not recorded one. */
    private static long readPidQuietly(Path pidFile) {
        try {
            String text = Files.isRegularFile(pidFile) ? Files.readString(pidFile).trim() : "";
            return text.isEmpty() ? 0L : Long.parseLong(text);
        } catch (IOException | NumberFormatException e) {
            return 0L;
        }
    }

    /** Waits (bounded) for the OS to report a pid as no longer alive. */
    private static void awaitDead(long pid) {
        Instant limit = Instant.now().plusSeconds(20);
        while (isAlive(pid) && Instant.now().isBefore(limit)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ExecutionSpec spec(UUID runId, Path workspace, Instant deadline) {
        return new ExecutionSpec(runId, UUID.randomUUID(), "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT,
                        workspace.toString(), null),
                "credential-ref-1", "rev-1", deadline);
    }

    private static WorkspaceLease lease(UUID runId, Path workspace, Path runtimeRoot) {
        return new WorkspaceLease(UUID.randomUUID(), runId, WorkspaceKind.DIRECT,
                workspace, workspace, runtimeRoot.toString(), null);
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Waits for the run's original hard deadline to be in the past (bounded). */
    private static void awaitPast(Instant deadline) throws InterruptedException {
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis > 0) {
            Thread.sleep(millis + 250);
        }
        assertThat(Instant.now()).as("the fixture deadline must have elapsed by now").isAfter(deadline);
    }

    /** The OS creation tick of a {@code pid@creation} record (FILETIME ticks on Windows). */
    private static long creationTicks(String record) {
        int at = record.indexOf('@');
        return Long.parseLong(record.substring(at + 1));
    }

    private static long sizeOf(Path path) throws IOException {
        return Files.exists(path) ? Files.size(path) : 0L;
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> entriesOf(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    private static Path canonical(Path path) throws IOException {
        return path.toRealPath();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Pinned Node executable: the mock cores in this suite are Node programs. */
    private static String nodeExecutable() {
        String configured = System.getenv("NODE_BIN");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "node";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * The bridge-shaped endpoint shim this suite launches as the runtime's root:
     * it authenticates the run's loopback endpoint with the control secret the
     * backend delivers into its environment and pipes the mock core's stdio
     * (see the fixture's own documentation).
     */
    private static Path endpointShim() {
        return hostTestResource("bridge-endpoint-shim.mjs");
    }

    /** The Task 7 mock Qoder peer inside this repository's test resources. */
    private static Path peerScript() {
        String configured = System.getProperty("host.peer.script");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        Path relative = Path.of("agent-control-tower", "act-app", "src", "test", "resources",
                "e2e", "peers", "mock-qoder.mjs");
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && directory != null; depth++) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                try {
                    return canonical(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Task 7 mock Qoder peer not found below "
                + System.getProperty("user.dir") + " (set -Dhost.peer.script=<path>)");
    }
}
