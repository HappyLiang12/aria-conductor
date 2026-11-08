package io.aria.conductor.execution.runtime.host;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.ExecutionBackendRegistry;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RunRuntimeRegistry;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.RuntimeRecoveryCoordinator;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

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
 * shim that serves the run's authenticated bridge contract, and with a declared
 * run-owned control-secret file -- the production bridge profile shape -- so the
 * launch has to pass the backend's per-run endpoint authentication exactly as a
 * production bridge launch must), and every assertion about a writer is a real
 * process liveness or real file-byte observation -- no mocked boolean replaces a
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
     * tell the difference. The profile declares a run-owned control-secret file,
     * as a production bridge launch does, so the launch is a bridge launch and
     * therefore gated on that authenticated call.
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
        // Bridge launch: the declared run-owned file keeps the authenticated
        // endpoint gate in force against this impostor.
        Path controlSecretFile = Path.of(environment.configurationDirectory())
                .resolve("bridge-control.secret");
        LaunchProfile profile = new LaunchProfile(
                List.of(nodeExecutable(), runtimeScript.toString(), pidFile.toString(), childPidFile.toString()),
                Map.of(), workspace.toString(), controlSecretFile.toString());
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

    /**
     * R7 (review round 2): the sampler is what attributes a child that outlives
     * its root. The fake supervisor reports the grandchild {@code 7001@7001}
     * exactly once -- to the sampler's enumeration -- and never from the root's
     * tree again, while {@code pids} keeps listing it as a live job member. A stop
     * that trusted the enumeration alone would leave a live writer behind and fail
     * closed; the proof must instead kill the observed identity and be satisfied.
     * Reverting the sampler makes this test fail (the grandchild is never killed
     * and the job inventory fails the proof closed).
     */
    @Test
    void theStopProofKillsAGrandchildTheSamplerSawBeforeTheRootDroppedIt() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-attribution"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);

        // Two enumerations prove the sampler completed one pass: the first carries
        // the grandchild, the second does not (the intermediate is gone).
        awaitProtocolLines(protocolLog, "tree: ", 2, 30_000);
        String sampledTree = firstProtocolLine(protocolLog, "sampled: ");
        assertThat(sampledTree).isEqualTo("sampled: 7001@7001");
        assertThat(protocolLogLines(protocolLog))
                .as("the root's enumeration must stop reaching the grandchild, or the test is vacuous")
                .anyMatch(line -> line.startsWith("tree: ") && !line.contains("7001@7001"));
        assertThat(protocolLogLines(protocolLog))
                .as("the grandchild must not have been signalled before the stop")
                .noneMatch(line -> line.equals("killed: 7001@7001"));

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(proof.runId()).isEqualTo(owned.runId());
        assertThat(proof.allWritersStopped())
                .as("the grandchild the sampler saw is this run's writer: the proof must kill it by"
                        + " identity instead of failing closed on an unreachable job member")
                .isTrue();
        assertThat(protocolLogLines(protocolLog))
                .as("the stop must signal the observed grandchild identity")
                .contains("killed: 7001@7001");
        assertThat(protocolLogLines(protocolLog))
                .as("the stop must never signal an identity it cannot verify")
                .noneMatch(line -> line.startsWith("kill-refused: "));
    }

    /**
     * Release-blocking live finding (run 8af5e2a6): a WAITING_INPUT run's core
     * exits on its own -- the assistant's clarification question was delivered and
     * the bridge is gone -- and the operator's later cancel must still finalize.
     * The controller's binding is intact (nonce, root pid and creation identity all
     * match the launch it performed); only the root's OS identity is gone. The
     * {@code owns} assertion is the control: it is exactly the gate the pre-fix
     * stop returned {@code false} at, in ~160 ms, without killing or
     * verdict-checking the sampler's records -- and pause/resume must keep
     * requiring a live root. The stop itself must sweep the (now empty) root tree,
     * kill the observed child by identity and prove the run stopped.
     */
    @Test
    void aStopProofKillsTheObservedChildWhenTheRootExitedOnItsOwn() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-dead-root-child"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "dead-root-child"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        assertThat(awaitObservedRecord(controller, owned.runId(), "7001@7001", 30_000))
                .as("the sampler must have observed the child, or the test is vacuous").isTrue();
        assertThat(controller.owns(owned))
                .as("control: the root no longer re-verifies through the OS, so the pre-fix ownership gate"
                        + " refused this stop outright -- and pause/resume keep refusing it")
                .isFalse();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(proof.runId()).isEqualTo(owned.runId());
        assertThat(proof.allWritersStopped())
                .as("a bound run whose root exited on its own still has a writer: the observed child is"
                        + " killed by identity and the proof over it is true")
                .isTrue();
        assertThat(protocolLogLines(protocolLog))
                .as("the observed child is signalled only after the OS verified its identity")
                .contains("killed: 7001@7001")
                .noneMatch(line -> line.startsWith("kill-refused: "));
    }

    /**
     * The same dead-root shape with nothing left behind: the root answered "gone"
     * before the stop, no child was ever observed and the job lists no member. The
     * proof has nothing to kill and must still be able to verify that instead of
     * refusing at the ownership gate.
     */
    @Test
    void aStopProofHoldsWhenTheRootExitedOnItsOwnAndNoChildRemains() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-dead-root-empty"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "dead-root-no-children"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "root-dead: ", 30_000);
        assertThat(controller.owns(owned))
                .as("control: the root is gone, so only the binding still identifies this run").isFalse();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(proof.allWritersStopped())
                .as("a bound run whose root exited leaves nothing to stop: the proof is true")
                .isTrue();
        assertThat(protocolLogLines(protocolLog))
                .as("nothing is signalled when nothing is left")
                .noneMatch(line -> line.startsWith("killed: ") || line.startsWith("kill-refused: "));
    }

    /**
     * Fail-closed control for the dead-root path: the sampler's observed child is
     * a breakaway writer (the job never listed it) whose identity the OS cannot
     * read at stop time. Unknown is not absent -- the proof must fail closed,
     * name the identity it could not verify and never signal it, even though the
     * root being gone is no longer a reason to refuse the proof.
     */
    @Test
    void anUnverifiableObservedChildFailsTheStopProofClosedWhenTheRootIsGone() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-dead-root-unverifiable"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller = new WindowsProcessController(
                fakeSupervisorHost(dir, protocolLog, "dead-root-unverifiable-child"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        assertThat(awaitObservedRecord(controller, owned.runId(), "7001@7001", 30_000))
                .as("the sampler must have observed the child, or the test is vacuous").isTrue();
        ListAppender<ILoggingEvent> logs = captureControllerLogs();
        try {
            StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

            assertThat(proof.allWritersStopped())
                    .as("an observed identity the OS cannot verify may be a live writer: the proof"
                            + " fails closed even when the root is gone")
                    .isFalse();
            assertThat(protocolLogLines(protocolLog))
                    .as("no signal is sent to a record whose identity was never verified")
                    .noneMatch(line -> line.startsWith("killed: ") || line.startsWith("kill-refused: ")
                            || line.startsWith("applied: "));
            List<String> warnings = logs.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings)
                    .as("a dead-root refusal must say why it failed: the unverifiable identity is named")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message)
                            .contains(owned.runId().toString())
                            .contains("unverifiable")
                            .contains("7001@7001"));
        } finally {
            detachControllerLogs(logs);
        }
    }

    /**
     * Safety pin for the dead-root path: the recorded root's pid was recycled by an
     * unrelated process. The binding still matches, so the proof must proceed on
     * identity evidence alone -- but a tree sweep anchored on that pid would
     * enumerate the stranger's tree and kill its child by identity. Only a live,
     * identity-validated root may anchor the sweep; a recycled or gone root
     * contributes no tree, and the proof over this run's own records is still true.
     */
    @Test
    void aRecycledRootPidIsNeverAnchoredForTheTreeSweep() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-recycled-root"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "recycled-root"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "root-recycled: ", 30_000);
        assertThat(controller.owns(owned))
                .as("control: the pid now carries another process's identity, so the run is not owned")
                .isFalse();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(protocolLogLines(protocolLog))
                .as("a sweep anchored on a recycled pid would enumerate a stranger's tree: it is never"
                        + " read, so the stranger's child is never killed")
                .noneMatch(line -> line.startsWith("tree-of-recycled: "))
                .noneMatch(line -> line.startsWith("killed: ") || line.startsWith("kill-refused: "));
        assertThat(proof.allWritersStopped())
                .as("the run's own records are gone or recycled and the job has no member: the proof is true")
                .isTrue();
    }

    /**
     * The other side of the distinction: a record that is not this controller's
     * binding (a foreign ownership nonce, or a controller that never bound the run)
     * is refused without touching anything, and the refusal is diagnosable. The
     * live failure had no diagnostic at all, so a silent false proof is the
     * regression this test pins; the WARN must name the path (binding mismatch) and
     * distinguish it from a bound run whose root is gone.
     */
    @Test
    void aStopWithoutABindingIsRefusedWithoutASignalAndIsLogged() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-stop-binding-mismatch"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 1, 30_000);
        String rootRecord = owned.rootPid() + "@" + owned.rootCreationIdentity();
        OwnedProcess foreignNonce = new OwnedProcess(owned.runId(), UUID.randomUUID().toString(),
                owned.rootPid(), owned.rootCreationIdentity(), owned.supervisorIdentity(), owned.technique(), null);
        WindowsProcessController unbound =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(unbound::close);
        ListAppender<ILoggingEvent> logs = captureControllerLogs();
        try {
            assertThat(controller.stop(foreignNonce, Instant.now().plusSeconds(30)).allWritersStopped())
                    .as("a foreign ownership nonce is never this controller's binding").isFalse();
            assertThat(unbound.stop(owned, Instant.now().plusSeconds(30)).allWritersStopped())
                    .as("a record no launch bound is refused even when its pid and creation identity match")
                    .isFalse();

            assertThat(protocolLogLines(protocolLog))
                    .as("neither refusal may signal anything: the record is not this controller's run")
                    .noneMatch(line -> line.startsWith("killed: ") || line.startsWith("kill-refused: ")
                            || line.startsWith("applied: "));
            assertThat(protocolLogLines(protocolLog).stream().filter(line -> line.startsWith("ready: ")).count())
                    .as("the unbound controller must not build a supervision channel to refuse a foreign record")
                    .isEqualTo(1);
            List<String> warnings = logs.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings)
                    .as("every refused binding is logged, and the path is named")
                    .hasSize(2)
                    .allSatisfy(message -> assertThat(message)
                            .contains(owned.runId().toString())
                            .contains("binding mismatch")
                            .contains(rootRecord));
        } finally {
            detachControllerLogs(logs);
        }
    }

    /**
     * R7 (review round 2): a job member that no enumerated tree of any run
     * contains still fails the proof closed, and the failure is diagnosable: the
     * WARN must name the run, the inventory and the unaccounted identities, and it
     * must describe them accurately -- a member of another supervised run whose
     * own root no longer matches is not "reachable from no live run", it is simply
     * not enumerable at proof time.
     */
    @Test
    void anUnaccountedJobMemberFailsTheProofClosedAndIsLogged() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-unaccounted-member"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "unaccounted-member"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        ListAppender<ILoggingEvent> logs = captureControllerLogs();
        try {
            StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

            assertThat(proof.allWritersStopped())
                    .as("a live job member no run can account for must fail the proof closed")
                    .isFalse();
            assertThat(protocolLogLines(protocolLog))
                    .as("an unaccounted member may belong to another run: it is never signalled")
                    .noneMatch(line -> line.equals("killed: 6001@666"));
            List<String> warnings = logs.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings)
                    .as("a failed proof must be diagnosable from the inventory it logs")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message)
                            .contains(owned.runId().toString())
                            .contains("unaccounted")
                            .contains("6001@666")
                            .doesNotContain("reachable from no live run"));
        } finally {
            detachControllerLogs(logs);
        }
    }

    /**
     * R7 (review round 2): the completeness net's other side. A member that
     * belongs to another supervised run is only excludable while that run's root
     * still re-verifies. Once it does not, the member is an unaccounted identity
     * and this run's proof must fail closed rather than assume it away -- the WARN
     * text must not claim it is unreachable from any live run.
     */
    @Test
    void aForeignRunsUnverifiableJobMemberFailsTheProofClosed() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-foreign-member"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "foreign-member"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess first = startOnFakeSupervisor(controller, workspace);
        OwnedProcess second = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "foreign-root-dead: ", 30_000);

        ListAppender<ILoggingEvent> logs = captureControllerLogs();
        try {
            StopProof proof = controller.stop(first, Instant.now().plusSeconds(30));

            assertThat(proof.runId()).isEqualTo(first.runId());
            assertThat(proof.allWritersStopped())
                    .as("a member of a supervised run whose root no longer re-verifies cannot be"
                            + " excluded, so this run's proof must fail closed")
                    .isFalse();
            assertThat(protocolLogLines(protocolLog))
                    .as("the other run's child is never signalled by this run's stop")
                    .noneMatch(line -> line.equals("killed: 7002@7002"));
            List<String> warnings = logs.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings).isNotEmpty()
                    .allSatisfy(message -> assertThat(message).contains("7002@7002"));
            assertThat(second.runId()).isNotEqualTo(first.runId());
        } finally {
            detachControllerLogs(logs);
        }
    }

    /**
     * R7 (review round 2): the straggler scan and the job-inventory phases are
     * round trips too, so an elapsed window must be re-checked there and not only
     * in the tree sweep. The fake delays the grandchild's straggler verdict past
     * the caller's window: the stop must refuse instead of signalling on after the
     * window elapsed.
     */
    @Test
    void theStopProofRechecksItsDeadlineAfterTheStragglerScan() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-deadline-stragglers"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "deadline-stragglers"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 2, 30_000);

        StopProof proof = controller.stop(owned, Instant.now().plusMillis(1500));

        assertThat(proof.allWritersStopped())
                .as("an elapsed window cannot support a stop proof, not even after the tree sweep finished")
                .isFalse();
        assertThat(protocolLogLines(protocolLog))
                .as("the straggler scan must have been reached, or the test is vacuous")
                .contains("straggler-probe: 7001@7001");
        assertThat(protocolLogLines(protocolLog))
                .as("nothing may be signalled after the window elapsed")
                .noneMatch(line -> line.equals("killed: 7001@7001"));
    }

    /**
     * R7 (review round 3, P3): the final job inventory and its unaccounted-member
     * classification are round trips too, and they issue no signal, so an elapsed
     * window must be re-checked around them as every earlier phase does. The fake
     * delays only the second "pids" (the final inventory) past the caller's
     * window; the tree is already empty, so without the re-check the stop would
     * return a true proof after the window elapsed.
     */
    @Test
    void theStopProofRechecksItsDeadlineAfterTheFinalJobInventory() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-deadline-inventory"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "deadline-inventory"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 1, 30_000);

        StopProof proof = controller.stop(owned, Instant.now().plusMillis(1500));

        assertThat(protocolLogLines(protocolLog))
                .as("the final job inventory must have been reached, or the test is vacuous")
                .anyMatch(line -> line.startsWith("inventory-probe: "));
        assertThat(proof.allWritersStopped())
                .as("an inventory round trip that outlived the window cannot support a true proof")
                .isFalse();
    }

    /**
     * R7 (review round 2): the sampler runs off the controller's scheduled thread,
     * so it must snapshot a live supervisor and never build one. A supervisor
     * constructed from the sampler after {@code release()}/{@code close()} is
     * never closed (it leaks its process, job handle and temp script), and the
     * construction can block for the whole start window inside the supervisor
     * lock. The fake exits after its first sampled tree; the next tick must find
     * nothing to sample, not a replacement channel.
     */
    @Test
    void theSamplerNeverRecreatesADeadSupervisor() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-dead-channel"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "dead-after-first-sample"));
        cleanups.add(() -> deleteTree(dir));
        startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 1, 30_000);

        Thread.sleep(2000); // four sampler ticks: the fake is gone after ~300 ms

        assertThat(protocolLogLines(protocolLog).stream().filter(line -> line.startsWith("ready: ")).toList())
                .as("the sampler must never recreate the supervision channel")
                .hasSize(1);
    }

    /**
     * R7 (review round 2): {@code close()} ends supervision for good. A later
     * {@code start} is refused, and neither {@code owns} nor {@code stop} may
     * recreate a supervisor from the closed state -- a channel created after the
     * close is never closed by anyone.
     */
    @Test
    void aClosedControllerRefusesANewStartAndCreatesNoSupervisor() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-closed-controller"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        cleanups.add(controller::close);
        awaitProtocolLines(protocolLog, "tree: ", 1, 30_000);
        controller.close();

        assertThat(controller.owns(owned)).as("a closed controller holds no binding").isFalse();
        assertThat(controller.stop(owned, Instant.now().plusSeconds(30)).allWritersStopped()).isFalse();
        Thread.sleep(1200);

        assertThat(protocolLogLines(protocolLog).stream().filter(line -> line.startsWith("ready: ")).toList())
                .as("neither owns nor stop may build a supervision channel after close")
                .hasSize(1);
        assertThatThrownBy(() -> controller.start(UUID.randomUUID(), new LaunchProfile(
                List.of(nodeExecutable(), "-e", "setInterval(() => {}, 1000);"), Map.of(), workspace.toString())))
                .as("a closed controller must not supervise a new run")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    /**
     * R7 (review round 3, P3): the closed check is not atomic with the bind. A
     * start that passed the check before {@code close()} must not return success
     * for a run the kill-on-close job then terminates, and it must not leave a
     * binding, a remembered population or a live process behind. The seam fires
     * exactly in that window: the run is bound and {@code close()} has already
     * cleared the maps when the terminal state is re-checked.
     */
    @Test
    void aStartThatRacesCloseRollsBackInsteadOfBindingIntoAClosedController() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-start-races-close"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(() -> deleteTree(dir));
        cleanups.add(controller::close);
        controller.afterBind = owned -> controller.close();

        UUID runId = UUID.randomUUID();
        LaunchProfile profile = new LaunchProfile(
                List.of(nodeExecutable(), "-e", "setInterval(() => {}, 1000);"), Map.of(), workspace.toString());

        assertThatThrownBy(() -> controller.start(runId, profile))
                .as("a run bound after the controller closed must be refused, not reported as supervised")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");

        assertThat(controller.observedRecordCount(runId))
                .as("the refused start must roll its binding back").isZero();
        assertThat(controller.observedPeak(runId)).isZero();

        // The launched runtime is terminated with the rollback: a refused start
        // never leaves an unsupervised core behind.
        long launchedPid = Long.parseLong(awaitProtocolLine(protocolLog, "assigned: ", 30_000)
                .substring("assigned: ".length()).trim());
        cleanups.add(() -> destroyPid(launchedPid));
        awaitDead(launchedPid);
        assertThat(isAlive(launchedPid))
                .as("a refused start must terminate the process it already launched")
                .isFalse();
    }

    /**
     * R7 (review round 3, P3): {@code close()} is terminal for channel creation
     * too. {@code close()} clears {@code started} first, so {@code owns()} and
     * {@code stop()} return at the binding look-up and never reach the guard; it
     * is therefore asserted directly through the seam. A supervisor created after
     * the close would be closed by nobody, leaking its process, job handle and
     * temp script.
     */
    @Test
    void aClosedControllerRefusesToCreateASupervisionChannel() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-closed-channel-guard"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(() -> deleteTree(dir));
        cleanups.add(controller::close);
        controller.close();

        assertThatThrownBy(controller::supervisorSelfIdentity)
                .as("no supervision channel may be created after close")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(protocolLogLines(protocolLog))
                .as("the refused creation must not have started a supervisor")
                .noneMatch(line -> line.startsWith("ready: "));
    }

    /**
     * R7 (review round 2): the remembered population is attribution evidence, and
     * it must not grow without bound across a run. The fake reports 700 one-off
     * grandchildren that all answer "gone"; the sampler's population must stay
     * within its documented bound while the live root stays attributable.
     *
     * <p>Review round 3, P3: the bound is asserted by a bounded poll, never a fixed
     * sleep -- the probe budget is per pass, so a slow runner needs several passes
     * and a fixed 1.5 s window could observe 513 records. The pre-prune peak is
     * asserted too, so the bound is exercised rather than merely never reached.
     */
    @Test
    void theSamplersRememberedPopulationIsBounded() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-memory-bound"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "many-exited-records"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "sampled-many: ", 30_000);

        assertThat(awaitObservedPeak(controller, owned.runId(), 701, 30_000))
                .as("the 700 one-off children plus the live root must have been observed before pruning")
                .isGreaterThanOrEqualTo(701);

        int settled = awaitObservedCountAtMost(controller, owned.runId(),
                WindowsProcessController.MAX_OBSERVED_RECORDS, 30_000);
        assertThat(settled)
                .as("exited identities must be pruned, not accumulated for the life of the run")
                .isLessThanOrEqualTo(WindowsProcessController.MAX_OBSERVED_RECORDS);
        assertThat(controller.observedRecordContains(owned.runId(),
                owned.rootPid() + "@" + owned.rootCreationIdentity()))
                .as("the live root is never shed: pruning only removes identities the OS reports gone")
                .isTrue();
        assertThat(controller.owns(owned)).as("the live root stays attributable").isTrue();
    }

    /**
     * R7 (review round 3, P2): the cap is a bound on dead records only. The fake
     * reports 700 synthetic grandchildren that all answer "match" -- live, and
     * unreachable from the root's tree afterwards -- while "pids" never lists them
     * (the documented best-effort job configuration: the assignment refused and
     * breakaway children invisible). A sampler that shed them to honour the cap
     * would destroy the only attribution evidence for a live writer, and the stop
     * proof could then return {@code allWritersStopped=true} over a live writer.
     * The proof may only be true because the retained population was killed by
     * identity.
     */
    @Test
    void anOverCapLivePopulationIsNeverShedAndTheProofKillsItByIdentity() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-over-cap-live"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "over-cap-live-records"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "sampled-over-cap: ", 30_000);

        assertThat(awaitObservedPeak(controller, owned.runId(), 701, 30_000))
                .as("the fixture must have served an over-cap population, or the test is vacuous")
                .isGreaterThanOrEqualTo(701);
        assertThat(awaitObservedCount(controller, owned.runId(), 701, 10_000))
                .as("a record the OS reports live is the only attribution evidence for a child"
                        + " unreachable from the root; it must be retained over the cap, never shed")
                .isEqualTo(701);

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        assertThat(protocolLogLines(protocolLog).stream()
                .filter(line -> line.equals("killed: 10000@5001")).count())
                .as("the retained live record must be signalled by identity before the proof")
                .isEqualTo(1);
        assertThat(protocolLogLines(protocolLog).stream()
                .filter(line -> line.startsWith("killed: ")).count())
                .as("every retained live identity and the root must be killed, so no true proof covers"
                        + " a live writer")
                .isGreaterThanOrEqualTo(701);
        assertThat(protocolLogLines(protocolLog))
                .as("every retained identity is verified by the OS before it is signalled")
                .noneMatch(line -> line.startsWith("kill-refused: "));
        assertThat(proof.allWritersStopped())
                .as("the retained population is this run's own writers: the proof may hold after killing them")
                .isTrue();
    }

    /**
     * R7 (review round 3, P3-c): the rollback in {@code start}'s catch is what
     * removes the binding, so the test must not also close the controller -- the
     * round-2 test closes inside the seam, and {@code close()} clears the same
     * maps, so deleting the rollback block left it green. Here the seam throws
     * <em>without</em> closing: the catch block is the only cleaner left, and the
     * launched runtime is left with no supervision at all.
     */
    @Test
    void aStartWhoseBindFailsRollsBackTheMapsAndTheLaunchedProcess() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-start-bind-fails"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "sampler-attribution"));
        cleanups.add(() -> deleteTree(dir));
        cleanups.add(controller::close);
        AtomicReference<OwnedProcess> bound = new AtomicReference<>();
        controller.afterBind = owned -> {
            bound.set(owned);
            throw new IllegalStateException("bind seam failure");
        };

        UUID runId = UUID.randomUUID();
        LaunchProfile profile = new LaunchProfile(
                List.of(nodeExecutable(), "-e", "setInterval(() => {}, 1000);"), Map.of(), workspace.toString());

        assertThatThrownBy(() -> controller.start(runId, profile))
                .as("a bind step that fails never reports the run as supervised")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bind seam failure");

        assertThat(bound.get()).as("the run was bound before the failure, or the test is vacuous").isNotNull();
        assertThat(controller.observedRecordCount(runId))
                .as("the catch rollback -- not a concurrent close -- must clear the remembered population")
                .isZero();
        assertThat(controller.observedPeak(runId)).isZero();
        assertThat(controller.owns(bound.get()))
                .as("a start that rolled back leaves no binding: owns must not re-verify the record")
                .isFalse();

        long launchedPid = Long.parseLong(awaitProtocolLine(protocolLog, "assigned: ", 30_000)
                .substring("assigned: ".length()).trim());
        cleanups.add(() -> destroyPid(launchedPid));
        awaitDead(launchedPid);
        assertThat(isAlive(launchedPid))
                .as("a failed start must terminate the process it already launched")
                .isFalse();
    }

    /**
     * R7 (review round 3, P3-a): the prune budget is per pass, and a pass must not
     * always spend it on the same head of the population. The fake declares the
     * first 32 distinct identities it is probed about live and every later one
     * gone -- 32+ live records at the iteration head with the dead ones queued
     * behind them. A fixed-head pass probes the same 32 live records forever and
     * leaves every dead one in place; a rotating cursor reaches the tail, evicts
     * the dead records and the population falls back under the cap.
     */
    @Test
    void theSamplerRotatesItsProbeBudgetPastAWallOfLiveRecords() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-probe-rotation"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "head-live-tail-gone"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "sampled-head-live-tail-gone: ", 30_000);

        assertThat(awaitObservedPeak(controller, owned.runId(), 701, 30_000))
                .as("the fixture must have served an over-cap population, or the test is vacuous")
                .isGreaterThanOrEqualTo(701);
        int settled = awaitObservedCountAtMost(controller, owned.runId(),
                WindowsProcessController.MAX_OBSERVED_RECORDS, 30_000);
        assertThat(settled)
                .as("a dead record behind a wall of live ones must be reached and evicted across"
                        + " passes; a fixed-head probe budget never leaves the head")
                .isLessThanOrEqualTo(WindowsProcessController.MAX_OBSERVED_RECORDS);

        List<String> declaredLive = protocolLogLines(protocolLog).stream()
                .filter(line -> line.startsWith("declared-live: "))
                .map(line -> line.substring("declared-live: ".length()))
                .toList();
        assertThat(declaredLive)
                .as("the fixture's premise is 32 live records at the head before any dead one")
                .hasSize(32);
        assertThat(declaredLive)
                .as("a live record is never shed, however deep the population")
                .allSatisfy(record -> assertThat(controller.observedRecordContains(owned.runId(), record)).isTrue());
        String evicted = protocolLogLines(protocolLog).stream()
                .filter(line -> line.startsWith("verdict: ") && line.endsWith(":gone"))
                .map(line -> line.substring("verdict: ".length(), line.lastIndexOf(":gone")))
                .findFirst()
                .orElse(null);
        assertThat(evicted)
                .as("an identity beyond the live head must have been probed and reported gone")
                .isNotNull();
        assertThat(controller.observedRecordContains(owned.runId(), evicted))
                .as("the probed dead identity is evicted: a gone identity accounts for no live writer")
                .isFalse();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        assertThat(protocolLogLines(protocolLog).stream().filter(line -> line.startsWith("killed: ")).count())
                .as("every retained live identity and the root are killed by identity")
                .isGreaterThanOrEqualTo(33);
        assertThat(proof.allWritersStopped())
                .as("the retained population is this run's own writers: the proof holds after killing them")
                .isTrue();
    }

    /**
     * R7 (review round 4, P3-b): "unverifiable" is not "gone". The fake reports the
     * grandchild the sampler saw as unverifiable -- the OS query failed for a reason
     * other than a definite absence -- and "pids" never lists it, so the inventory
     * cannot decide it either. The proof must fail closed: no signal without a
     * verified identity, and the identity stays in the remembered population as the
     * only attribution evidence there is.
     */
    @Test
    void anUnverifiableRecordIsRetainedAndFailsTheStopProofClosed() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-unverifiable-record"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "unverifiable-record"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 2, 30_000);

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(proof.allWritersStopped())
                .as("a record the OS cannot verify may be a live writer: the proof must fail closed")
                .isFalse();
        String rootRecord = owned.rootPid() + "@" + owned.rootCreationIdentity();
        assertThat(protocolLogLines(protocolLog))
                .as("the root's own verified tree is still killed: the refusal is about the one identity")
                .contains("killed: " + rootRecord)
                .noneMatch(line -> line.equals("killed: 7001@7001"));
        assertThat(protocolLogLines(protocolLog))
                .as("no kill is attempted without a verified identity")
                .noneMatch(line -> line.startsWith("kill-refused: "));
        assertThat(controller.observedRecordContains(owned.runId(), "7001@7001"))
                .as("an unverifiable identity is retained: it is the only attribution evidence for it")
                .isTrue();
    }

    /**
     * R7 (review round 3, P3-b): the cap is a bound on dead evidence only, and an
     * unverifiable record is not dead. The fake answers every one of 700 over-cap
     * identities with "unverifiable": a sampler that folded unknown into gone would
     * shed 32 of them per pass and destroy the only attribution evidence for the
     * children behind them.
     */
    @Test
    void anOverCapPopulationOfUnverifiableRecordsIsRetained() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-over-cap-unverifiable"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "over-cap-unverifiable"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "sampled-over-cap-unverifiable: ", 30_000);

        assertThat(awaitObservedPeak(controller, owned.runId(), 701, 30_000))
                .as("the fixture must have served an over-cap population, or the test is vacuous")
                .isGreaterThanOrEqualTo(701);
        // Several sampler passes must have spent their probe budget before the count is read.
        awaitProtocolLines(protocolLog, "tree: ", 4, 30_000);

        assertThat(controller.observedRecordCount(owned.runId()))
                .as("unknown is not absent: an unverifiable record is never shed to honour the cap")
                .isEqualTo(701);
    }

    /**
     * R7 (review round 3, P3-b): the job inventory has the same rule. "pids" reports
     * {@code unverifiable:5002}, a live job member whose identity the OS could not
     * read. Reading that as absence would drop it from the inventory and let the
     * proof certify a workspace the member can still mutate; it is an unaccounted
     * identity and the proof fails closed.
     */
    @Test
    void aJobMemberWithAnUnverifiableIdentityFailsTheProofClosed() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-unverifiable-member"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "unverifiable-member"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        ListAppender<ILoggingEvent> logs = captureControllerLogs();
        try {
            StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

            assertThat(proof.allWritersStopped())
                    .as("an inventory member whose identity is unknown cannot be dismissed: fail closed")
                    .isFalse();
            assertThat(protocolLogLines(protocolLog))
                    .as("an unverifiable member is never signalled")
                    .noneMatch(line -> line.startsWith("killed: unverifiable"));
            List<String> warnings = logs.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings)
                    .as("the failed proof names the identity it could not classify")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message).contains("unverifiable:5002"));
        } finally {
            detachControllerLogs(logs);
        }
    }

    /**
     * R7 (review round 3, P3-d): {@code stop} copies the run's observed set before
     * its root sweep, and the sampler can add a record in that window. The fake's
     * {@code 12001@5001} is a live breakaway writer -- never in the root's tree and
     * never in the job inventory -- so the sampler's record is its only attribution
     * evidence. A proof that scans only its first copy never probes it, never kills
     * it, and still returns true; the seam adds it exactly at that point, and the
     * re-read is what makes the straggler scan reach it.
     */
    @Test
    void theStopProofRereadsTheSamplersPopulationAfterTheRootSweep() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-late-sampled-record"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "late-sampled-record"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 1, 30_000);
        controller.afterRootSweep = records -> records.add("12001@5001");

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(protocolLogLines(protocolLog).stream()
                .filter(line -> line.equals("killed: 12001@5001")).count())
                .as("a record the sampler remembered mid-proof is a writer of this run: it is killed"
                        + " by identity instead of surviving behind a proof that never probed it")
                .isEqualTo(1);
        assertThat(protocolLogLines(protocolLog))
                .as("the late record is signalled only after the OS verified its identity")
                .noneMatch(line -> line.startsWith("kill-refused: "));
        assertThat(proof.allWritersStopped())
                .as("the writer was killed, so the proof over it is true")
                .isTrue();
    }

    /**
     * R7 (review round 4, P2): the population re-read before the straggler scan is not
     * the proof's last word -- the per-record verdicts, the own-survivor kill and the
     * final job inventory are round trips after it, and the inventory cannot see a
     * breakaway writer. A record the sampler adds in that last window was never
     * scanned, killed or verdict-checked, so a proof that returns true after it
     * certifies a live writer as stopped. The seam adds the fixture's live breakaway
     * writer exactly after the final inventory and the proof must fail closed. The
     * control run of the same fixture, without the injection, proves true: the false
     * below is the last-window record, not the fixture.
     */
    @Test
    void theStopProofFailsClosedWhenARecordIsSampledAfterTheFinalInventory() throws Exception {
        Path controlDir = canonical(Files.createTempDirectory("host-last-window-control"));
        Path controlWorkspace = Files.createDirectories(controlDir.resolve("workspace"));
        Path controlLog = controlDir.resolve("supervisor-protocol.log");
        WindowsProcessController control = new WindowsProcessController(
                fakeSupervisorHost(controlDir, controlLog, "late-sampled-record"));
        cleanups.add(() -> deleteTree(controlDir));
        OwnedProcess controlOwned = startOnFakeSupervisor(control, controlWorkspace);
        awaitProtocolLines(controlLog, "tree: ", 1, 30_000);
        assertThat(control.stop(controlOwned, Instant.now().plusSeconds(30)).allWritersStopped())
                .as("control: the same fixture without a record in the last window proves true")
                .isTrue();

        Path dir = canonical(Files.createTempDirectory("host-last-window-record"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "late-sampled-record"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLines(protocolLog, "tree: ", 1, 30_000);
        controller.afterFinalInventory = records -> records.add("12001@5001");

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(30));

        assertThat(controller.observedRecordContains(owned.runId(), "12001@5001"))
                .as("the sampler's population gained the record inside the proof's last window")
                .isTrue();
        assertThat(proof.allWritersStopped())
                .as("a record first seen after the final inventory was never scanned, killed or"
                        + " verdict-checked, and the inventory cannot see a breakaway writer: the"
                        + " proof must fail closed instead of certifying a live writer as stopped")
                .isFalse();
        assertThat(protocolLogLines(protocolLog))
                .as("the proof fails closed without signalling a record it never verified")
                .noneMatch(line -> line.equals("killed: 12001@5001"));
    }

    /**
     * R7 (review round 3): a mismatched identity is a recycled PID -- it can never
     * account for a live writer -- so the sampler evicts it. The fake answers every
     * over-cap identity with a mismatch: an eviction branch that ignored mismatch
     * would hold all 700 recycled identities for the life of the run.
     */
    @Test
    void mismatchedIdentitiesAreEvictedFromTheSamplersPopulation() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-mismatch"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "mismatch-records"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "sampled-mismatch-records: ", 30_000);

        assertThat(awaitObservedPeak(controller, owned.runId(), 701, 30_000))
                .as("the fixture must have served an over-cap population, or the test is vacuous")
                .isGreaterThanOrEqualTo(701);
        int settled = awaitObservedCountAtMost(controller, owned.runId(),
                WindowsProcessController.MAX_OBSERVED_RECORDS, 30_000);
        assertThat(settled)
                .as("recycled identities must be pruned, not accumulated for the life of the run")
                .isLessThanOrEqualTo(WindowsProcessController.MAX_OBSERVED_RECORDS);

        String probed = protocolLogLines(protocolLog).stream()
                .filter(line -> line.startsWith("verdict: ") && line.contains(":mismatch:"))
                .map(line -> line.substring("verdict: ".length(), line.indexOf(":mismatch:")))
                .findFirst()
                .orElse(null);
        assertThat(probed).as("a mismatched identity must have been probed").isNotNull();
        assertThat(controller.observedRecordContains(owned.runId(), probed))
                .as("the probed mismatched identity is evicted")
                .isFalse();
    }

    /**
     * R7 (review round 3): the mixed population the review asked for -- 700
     * over-cap identities of which every tenth is gone and the rest are live. The
     * sampler may only drain the 70 dead ones; the 630 live ones plus the root stay
     * attributable and the stop kills exactly them.
     */
    @Test
    void aMixedLiveAndDeadOverCapPopulationDrainsOnlyTheDeadRecords() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-sampler-mixed"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path protocolLog = dir.resolve("supervisor-protocol.log");
        WindowsProcessController controller =
                new WindowsProcessController(fakeSupervisorHost(dir, protocolLog, "over-cap-mixed"));
        cleanups.add(() -> deleteTree(dir));
        OwnedProcess owned = startOnFakeSupervisor(controller, workspace);
        awaitProtocolLine(protocolLog, "sampled-mixed: ", 30_000);

        assertThat(awaitObservedPeak(controller, owned.runId(), 701, 30_000))
                .as("the fixture must have served an over-cap population, or the test is vacuous")
                .isGreaterThanOrEqualTo(701);
        assertThat(awaitObservedCountEquals(controller, owned.runId(), 631, 30_000))
                .as("the 70 gone identities drain across passes while the 630 live ones (plus the"
                        + " root) survive over the cap")
                .isEqualTo(631);

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        assertThat(protocolLogLines(protocolLog).stream().filter(line -> line.startsWith("killed: ")).count())
                .as("every retained live identity and the root are killed by identity")
                .isGreaterThanOrEqualTo(631);
        assertThat(protocolLogLines(protocolLog))
                .as("no identity is signalled unchecked")
                .noneMatch(line -> line.startsWith("kill-refused: "));
        assertThat(proof.allWritersStopped())
                .as("the retained population is this run's own writers: the proof holds after killing them")
                .isTrue();
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

    /**
     * R7 (review round 4, P3-b): the fake supervisor can only prove what the Java
     * proof does with an {@code unverifiable} verdict, not that the embedded script
     * produces one -- so a revert of the script's classification left the suite
     * green and restored the fail-open reading of an unreadable query. This drives
     * the real script over its real line protocol (the same PowerShell process,
     * staged script and command channel every control action uses). PID 4 (System)
     * is a live process whose {@code PROCESS_QUERY_LIMITED_INFORMATION} open is
     * refused with access denied (5), not with the definite absence of a dead PID
     * (87), so a record that still names PID 4 must answer {@code unverifiable:open:5}
     * -- never {@code gone}. A stale record whose pid was recycled by PID 4 is a
     * different case: the handle-free creation-time probe proves the recorded process
     * is gone and answers {@code mismatch} (F32, pinned by
     * {@code WindowsProcessControllerOrphanAttributionTest}). The positive controls pin
     * both sides of the remaining distinction: a live identity the OS can read is
     * verified and matches, and a real absence is still gone, so a script that always
     * answers either way fails here.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theEmbeddedSupervisorClassifiesARefusedOpenAsUnverifiableNotGone() throws Exception {
        WindowsProcessController controller = new WindowsProcessController();
        cleanups.add(controller::close);

        String self = controller.supervisorCommand("self");
        assertThat(self)
                .as("the supervisor's own record names the live supervision channel")
                .startsWith(controller.supervisorPid() + "@");
        assertThat(controller.supervisorCommand("record " + self))
                .as("a readable identity still compares as a match")
                .isEqualTo("match");

        Process exited = new ProcessBuilder("cmd.exe", "/d", "/c", "exit", "0").start();
        assertThat(exited.waitFor(30, TimeUnit.SECONDS)).isTrue();
        // The exited Process object keeps its pid reserved, so this record names a
        // definite absence rather than a recycled live process.
        assertThat(controller.supervisorCommand("record " + exited.pid() + "@1"))
                .as("a definite absence is still classified gone")
                .isEqualTo("gone");

        // F32: the record must still name PID 4 itself. A record with stale ticks would
        // now (correctly) resolve to mismatch through the handle-free creation-time probe,
        // which is pinned by WindowsProcessControllerOrphanAttributionTest; this test
        // keeps the raw refused-open classification of the process the record names.
        String refused = controller.supervisorCommand(
                "record 4@" + ProtectedProcessFixture.systemCreationTicks());
        assertThat(refused)
                .as("a refused OS query is not a definite absence: reading it as gone is the"
                        + " fail-open classification the stop proof must never inherit")
                .startsWith("unverifiable:open:")
                .isNotEqualTo("gone");
        assertThat(Integer.parseInt(refused.substring("unverifiable:open:".length())))
                .as("the failure under test is a non-87 OpenProcess error (access denied)")
                .isNotEqualTo(87);
    }

    /**
     * The per-run isolation ruling's exact point (Task 13 fix round 1, C1): two runs of
     * one controller share its supervision channel and therefore one job object, but a
     * per-run stop acts per run. Stopping run A must stop A's own tree and must neither
     * kill nor be decided by run B's writer -- and B's own stop proof must stay truthful
     * afterwards.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void stoppingOneRunLeavesAnotherRunsWriterAliveAndBothProofsTruthful() throws Exception {
        WindowsProcessController controller = new WindowsProcessController();
        cleanups.add(controller::close);
        HostExecutionBackend shared = new HostExecutionBackend(controller);
        PeerRun runA = new PeerRun("background-writer", shared);
        PeerRun runB = new PeerRun("background-writer", shared);
        runA.handshake();
        Writer writerA = runA.promptAndApproveWriter(WRITER_PROMPT);
        runA.awaitPromptResult();
        runB.handshake();
        Writer writerB = runB.promptAndApproveWriter(WRITER_PROMPT);
        runB.awaitPromptResult();

        Process sentinel = startSentinel();

        StopProof proofA = shared.stopWriters(runA.handle(), runA.deadline());

        assertThat(proofA.runId()).isEqualTo(runA.runId());
        assertThat(proofA.allWritersStopped()).as("run A's own tree is stopped").isTrue();
        assertThat(isAlive(runA.owned().rootPid())).as("run A's runtime is stopped").isFalse();
        assertThat(ProcessHandle.of(writerA.writerPid()).map(ProcessHandle::isAlive).orElse(false))
                .as("run A's writer is stopped").isFalse();

        assertThat(isAlive(runB.owned().rootPid())).as("run B's runtime must survive run A's stop").isTrue();
        assertThat(ProcessHandle.of(writerB.writerPid()).map(ProcessHandle::isAlive).orElse(false))
                .as("run B's writer must survive run A's stop").isTrue();
        Path ticksB = runB.workspace().resolve(WRITER_LOG);
        long before = sizeOf(ticksB);
        Thread.sleep(300);
        assertThat(sizeOf(ticksB))
                .as("run B's writer keeps writing: run A's stop never touched it")
                .isGreaterThan(before);

        StopProof proofB = shared.stopWriters(runB.handle(), runB.deadline());

        assertThat(proofB.runId()).isEqualTo(runB.runId());
        assertThat(proofB.allWritersStopped()).as("run B's own tree is stopped").isTrue();
        assertThat(isAlive(runB.owned().rootPid())).isFalse();
        assertThat(ProcessHandle.of(writerB.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(sentinel.isAlive()).as("an unrelated process must survive").isTrue();
    }

    /**
     * F25, high: the Windows form of the T9 completeness net (Task 13 fix round 1, C1).
     * A writer whose intermediate parent died before the first enumeration is
     * unreachable from the root's tree walk while it keeps mutating the workspace;
     * the enumerated sweep alone would report {@code allWritersStopped=true} for an
     * empty tree while that live writer keeps writing. The live failure (run
     * 4bd06a41) was the fail-closed side of the same shape: the shared job inventory
     * saw the orphan and could not say whose writer it was. While exactly one run is
     * supervised, a job member created at or after that run's root cannot belong to
     * anyone else, so the proof must attribute it to the run, kill it by identity and
     * verdict-check it -- and the writer is then really gone, not left mutating.
     *
     * <p>The ambiguity that must stay fail-closed is a member that a concurrent run
     * could own, or one older than this run's root: those keep the inventory path
     * closed and are covered by
     * {@link #aForeignRunsUnverifiableJobMemberFailsTheProofClosed()} and
     * {@link #anUnaccountedJobMemberFailsTheProofClosedAndIsLogged()}. This test also
     * asserts the premise non-vacuously: the writer is a real job member, so only the
     * job-attributed kill can take it down (a non-member orphan would survive the
     * proof and fail the {@code isAlive} assertion below).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void stopAttributesAnOrphanedJobMemberToTheOnlySupervisedRunAndStopsIt() throws Exception {
        Path dir = canonical(Files.createTempDirectory("host-orphaned-job-member"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path writerLog = workspace.resolve("orphan-writer.log");
        Path writerPidFile = dir.resolve("orphan-writer.pid");
        Path intermediatePidFile = dir.resolve("orphan-intermediate.pid");
        Path writerScript = workspace.resolve("orphan-writer.mjs");
        Files.writeString(writerScript, """
                import { appendFileSync, writeFileSync } from 'node:fs';
                writeFileSync(process.argv[2], String(process.pid));
                setInterval(() => appendFileSync(process.argv[3], 'tick\\n'), 20);
                """);
        // The intermediate spawns the writer detached (its own console on Windows, so the
        // console deaths below cannot take it down) and exits without holding a handle to
        // it: the writer is then a live, orphaned process whose PPID names a PID the OS no
        // longer lists, which is what makes it unreachable from the root's tree walk.
        Path intermediateScript = workspace.resolve("orphan-intermediate.mjs");
        Files.writeString(intermediateScript, """
                import { spawn } from 'node:child_process';
                import { writeFileSync } from 'node:fs';
                const writer = spawn(process.execPath, [process.argv[2], process.argv[3], process.argv[4]],
                        { stdio: 'ignore', detached: true });
                writer.unref();
                writeFileSync(process.argv[5], String(process.pid));
                """);
        Path rootScript = workspace.resolve("orphan-root.mjs");
        Files.writeString(rootScript, """
                import { spawn } from 'node:child_process';
                spawn(process.execPath, [process.argv[2], process.argv[3], process.argv[4],
                        process.argv[5], process.argv[6]], { stdio: 'ignore' });
                setInterval(() => {}, 1000);
                """);

        WindowsProcessController controller = new WindowsProcessController();
        UUID runId = UUID.randomUUID();
        LaunchProfile profile = new LaunchProfile(List.of(nodeExecutable(), rootScript.toString(),
                intermediateScript.toString(), writerScript.toString(), writerPidFile.toString(),
                writerLog.toString(), intermediatePidFile.toString()), Map.of(), workspace.toString());
        OwnedProcess owned = controller.start(runId, profile);
        cleanups.add(() -> {
            destroyPid(owned.rootPid());
            controller.release(owned);
            controller.close();
        });
        long writerPid = awaitPid(writerPidFile);
        cleanups.add(() -> destroyPid(writerPid));
        long intermediatePid = awaitPid(intermediatePidFile);
        awaitDead(intermediatePid);
        // Registered last: the tree must be gone before the directory deletion (the
        // detached writer's working directory is the workspace).
        cleanups.add(() -> deleteTree(dir));
        Process sentinel = startSentinel();

        assertThat(owned.technique())
                .as("the orphan premise cannot be observed when the OS refused job membership")
                .contains("job-backstop=assign:ok");
        assertThat(isAlive(writerPid)).as("the unreachable writer must be alive").isTrue();
        long before = sizeOf(writerLog);
        Thread.sleep(300);
        assertThat(sizeOf(writerLog)).as("the orphaned writer must keep writing").isGreaterThan(before);

        // The premise is asserted before the stop, not inferred from a later liveness check: the
        // writer is a real job member (it inherited the job through the intermediate) and the
        // sampler never saw it, so the job inventory is the only thing that can attribute it and
        // only this run's job-attributed kill can take it down.
        String writerRecord = controller.supervisorCommand("identity " + writerPid);
        assertThat(writerRecord)
                .as("the orphan's OS identity must be readable so the premise is a real record")
                .startsWith(writerPid + "@");
        assertThat(controller.jobMemberRecords())
                .as("the writer must really be a member of the shared job, or the job-attributed kill"
                        + " this test exercises cannot stop it and the premise would be vacuous")
                .contains(writerRecord);
        assertThat(controller.observedRecordContains(runId, writerRecord))
                .as("the sampler must never have seen the writer, or the job attribution is not what"
                        + " stopped it")
                .isFalse();

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        assertThat(proof.runId()).isEqualTo(runId);
        assertThat(proof.allWritersStopped())
                .as("F25: a job member created after the only supervised run's root is attributable to"
                        + " that run -- the proof must kill it by identity and be true over it")
                .isTrue();
        assertThat(isAlive(owned.rootPid())).as("the enumerated tree is stopped").isFalse();
        assertThat(isAlive(writerPid))
                .as("the attributed orphan is stopped by identity and verdict-checked, not left alive"
                        + " behind the proof as it was on the failing live run")
                .isFalse();
        long atStop = sizeOf(writerLog);
        Thread.sleep(300);
        assertThat(sizeOf(writerLog))
                .as("a stopped writer can no longer mutate the workspace it was proved stopped over")
                .isEqualTo(atStop);
        assertThat(sentinel.isAlive()).as("an unrelated process must survive").isTrue();
    }

    /**
     * I3 (Task 13 fix round 1): a reap after a restart must record what its destroy dispatch
     * actually ended. The Host destroy never signals a runtime it did not bind (T9's
     * fail-closed property) and removes only what this process prepared, so a fresh process
     * reaping a run-owned runtime it never prepared destroys and signals nothing -- and the
     * record must say exactly that instead of claiming a destruction. The run's live root,
     * writer and generated configuration all survive the reap untouched.
     */
    @Test
    void aHostReapAfterARestartRecordsThatNothingWasDestroyedOrSignalled() throws Exception {
        PeerRun run = new PeerRun("background-writer");
        run.handshake();
        Writer writer = run.promptAndApproveWriter(WRITER_PROMPT);
        run.awaitPromptResult();

        // The restarted process: a fresh backend and registry that hold nothing for the run.
        HostExecutionBackend restarted = HostExecutionBackend.forCurrentPlatform();
        RuntimeRecoveryCoordinator recovery = new RuntimeRecoveryCoordinator(
                new ExecutionBackendRegistry(List.of(restarted)), new RunRuntimeRegistry());
        RunExecutionBinding evidence = RunExecutionBinding.builder()
                .runId(run.runId()).agentId(UUID.randomUUID()).coreId("qoder")
                .executionMode(ExecutionMode.HOST).settingsJson("{}")
                .runtimeEnvironmentId(run.environment().environmentId())
                .runtimeOwnershipIdentity(run.handle().ownershipIdentity())
                .runtimeEndpoint(run.environment().endpoint().toString())
                .workspaceKind(WorkspaceKind.DIRECT)
                .build();
        Path generated = Path.of(run.environment().configurationDirectory());
        Path ticks = run.workspace().resolve(WRITER_LOG);
        Process sentinel = startSentinel();

        RuntimeRecoveryCoordinator.RecoveryDecision decision = recovery.reconcile(evidence);

        assertThat(decision.decision()).isEqualTo(RuntimeRecoveryCoordinator.Decision.REAPED);
        assertThat(decision.reason()).isEqualTo("Issued the HOST destroy for run " + run.runId()
                + "; this process held no run-owned runtime for it, so nothing was destroyed or signalled"
                + " (a restart-reconstructed record is never signalled)"
                + "; its Direct workspace lease stays held (recovery holds no verified stop proof)");
        assertThat(isAlive(run.owned().rootPid()))
                .as("a restart-reconstructed record is never signalled").isTrue();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false))
                .as("the run's writer is never signalled").isTrue();
        assertThat(Files.isDirectory(generated))
                .as("a process that never prepared the run destroys nothing of its placement").isTrue();
        long before = sizeOf(ticks);
        Thread.sleep(300);
        assertThat(sizeOf(ticks)).as("the untouched writer keeps writing after the reap record").isGreaterThan(before);
        assertThat(sentinel.isAlive()).isTrue();

        // The process that still holds the live binding can stop the run as usual.
        StopProof proof = run.backend().stopWriters(run.handle(), run.deadline());
        assertThat(proof.allWritersStopped()).isTrue();
        assertThat(isAlive(run.owned().rootPid())).isFalse();
        assertThat(ProcessHandle.of(writer.writerPid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
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
            this(scenario, Instant.now().plus(Duration.ofMinutes(10)), null);
        }

        PeerRun(String scenario, Instant deadline) throws IOException, InterruptedException {
            this(scenario, deadline, null);
        }

        /**
         * A run on an explicitly shared backend: two of these exercise one
         * controller's shared supervision channel and job object.
         */
        PeerRun(String scenario, HostExecutionBackend sharedBackend) throws IOException, InterruptedException {
            this(scenario, Instant.now().plus(Duration.ofMinutes(10)), sharedBackend);
        }

        private PeerRun(String scenario, Instant deadline, HostExecutionBackend sharedBackend)
                throws IOException, InterruptedException {
            root = canonical(Files.createTempDirectory("host-backend-it"));
            workspace = Files.createDirectories(root.resolve("workspace"));
            runtimeRoot = Files.createDirectories(root.resolve("runtime"));
            runId = UUID.randomUUID();
            this.deadline = deadline;
            backend = sharedBackend != null ? sharedBackend : HostExecutionBackend.forCurrentPlatform();
            environment = backend.prepare(spec(runId, workspace, deadline), lease(runId, workspace, runtimeRoot));
            // The launched runtime is bridge-shaped: the endpoint shim serves the
            // run's authenticated bridge contract from the secret the backend
            // delivers into its environment, and pipes the mock core's stdio, so
            // the launch passes the backend's per-run endpoint authentication
            // exactly as a production bridge launch must. The fixture therefore
            // declares a control-secret file inside the run-owned configuration
            // directory, as the production Qoder bridge profile does: the backend
            // writes the minted secret there, delivers it to the shim's
            // environment, and keeps the authenticated launch gate covered.
            Path controlSecretFile = Path.of(environment.configurationDirectory())
                    .resolve("bridge-control.secret");
            LaunchProfile profile = new LaunchProfile(
                    List.of(nodeExecutable(), endpointShim().toString(),
                            "--port", String.valueOf(environment.endpoint().getPort()),
                            "--run-id", runId.toString(),
                            "--workspace", workspace.toString(),
                            "--",
                            peerScript().toString(),
                            "--scenario", scenario, "--workspace", workspace.toString()),
                    Map.of("ARIA_PEER_CONTROL_TOKEN", PEER_CONTROL_TOKEN),
                    workspace.toString(),
                    controlSecretFile.toString());
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
     * {@link #aVerifiedResumeCoversEverySuspensionAPartialPauseBatchApplied}
     * (its default scenario): the controller starts it exactly like PowerShell,
     * and the fixture speaks the same line protocol deterministically.
     */
    private static Path fakeSupervisorHost(Path dir, Path protocolLog) throws IOException {
        return fakeSupervisorHost(dir, protocolLog, "partial-suspend");
    }

    /**
     * The fake supervisor host in a named scenario (see the fixture's own
     * documentation): the controller starts it exactly like PowerShell and the
     * fixture speaks the line protocol deterministically.
     */
    private static Path fakeSupervisorHost(Path dir, Path protocolLog, String scenario) throws IOException {
        Path script = hostTestResource("fake-host-supervisor.mjs");
        if (isWindows()) {
            Path cmd = dir.resolve("fake-host-supervisor.cmd");
            Files.writeString(cmd, "@echo off\r\n" + nodeCommand() + " \"" + script + "\" --log \""
                    + protocolLog + "\" --scenario " + scenario + " %*\r\n");
            return cmd;
        }
        Path sh = dir.resolve("fake-host-supervisor.sh");
        Files.writeString(sh, "#!/bin/sh\nexec " + nodeCommand() + " \"" + script + "\" --log \""
                + protocolLog + "\" --scenario " + scenario + " \"$@\"\n");
        Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
        return sh;
    }

    /** Starts one owned runtime through a fake-supervisor controller and registers its cleanup. */
    private OwnedProcess startOnFakeSupervisor(WindowsProcessController controller, Path workspace) {
        LaunchProfile profile = new LaunchProfile(
                List.of(nodeExecutable(), "-e", "setInterval(() => {}, 1000);"), Map.of(), workspace.toString());
        OwnedProcess owned = controller.start(UUID.randomUUID(), profile);
        cleanups.add(() -> {
            Process live = owned.liveProcess();
            if (live != null && live.isAlive()) {
                live.destroyForcibly();
                live.waitFor(10, TimeUnit.SECONDS);
            }
            controller.release(owned);
            controller.close();
        });
        return owned;
    }

    /** Every line the fake supervisor wrote so far (empty while the log does not exist yet). */
    private static List<String> protocolLogLines(Path protocolLog) {
        try {
            return Files.isRegularFile(protocolLog) ? Files.readAllLines(protocolLog) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String firstProtocolLine(Path protocolLog, String prefix) {
        return protocolLogLines(protocolLog).stream()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElse(null);
    }

    /** Waits (bounded) for the first protocol log line with this prefix. */
    private static String awaitProtocolLine(Path protocolLog, String prefix, long timeoutMillis)
            throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        while (Instant.now().isBefore(limit)) {
            String found = firstProtocolLine(protocolLog, prefix);
            if (found != null) {
                return found;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no protocol log line '" + prefix + "' arrived; recorded: "
                + protocolLogLines(protocolLog));
    }

    /** Waits (bounded) for {@code count} protocol log lines with this prefix. */
    private static void awaitProtocolLines(Path protocolLog, String prefix, int count, long timeoutMillis)
            throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        while (Instant.now().isBefore(limit)) {
            if (protocolLogLines(protocolLog).stream().filter(line -> line.startsWith(prefix)).count() >= count) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("fewer than " + count + " protocol log lines '" + prefix + "' arrived; recorded: "
                + protocolLogLines(protocolLog));
    }

    /** Bounded poll of the sampler's remembered population for one identity. */
    private static boolean awaitObservedRecord(WindowsProcessController controller, UUID runId, String record,
            long timeoutMillis) throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        while (Instant.now().isBefore(limit)) {
            if (controller.observedRecordContains(runId, record)) {
                return true;
            }
            Thread.sleep(25);
        }
        return controller.observedRecordContains(runId, record);
    }

    /**
     * Bounded poll of the sampler's remembered population: waits until it reaches
     * {@code atLeast} and returns the last observed count. Never a fixed sleep --
     * the sampler's per-pass probe budget makes the number of passes a slow runner
     * needs nondeterministic.
     */
    private static int awaitObservedCount(WindowsProcessController controller, UUID runId, int atLeast,
            long timeoutMillis) throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        int count = controller.observedRecordCount(runId);
        while (count < atLeast && Instant.now().isBefore(limit)) {
            Thread.sleep(25);
            count = controller.observedRecordCount(runId);
        }
        return count;
    }

    /** Bounded poll of the sampler's remembered population: waits until it falls to {@code atMost}. */
    private static int awaitObservedCountAtMost(WindowsProcessController controller, UUID runId, int atMost,
            long timeoutMillis) throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        int count = controller.observedRecordCount(runId);
        while (count > atMost && Instant.now().isBefore(limit)) {
            Thread.sleep(25);
            count = controller.observedRecordCount(runId);
        }
        return count;
    }

    /** Bounded poll of the sampler's remembered population: waits until it equals {@code expected}. */
    private static int awaitObservedCountEquals(WindowsProcessController controller, UUID runId, int expected,
            long timeoutMillis) throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        int count = controller.observedRecordCount(runId);
        while (count != expected && Instant.now().isBefore(limit)) {
            Thread.sleep(25);
            count = controller.observedRecordCount(runId);
        }
        return count;
    }

    /** Bounded poll of the size the sampler held before pruning: waits until the peak reaches the bound. */
    private static int awaitObservedPeak(WindowsProcessController controller, UUID runId, int atLeast,
            long timeoutMillis) throws InterruptedException {
        Instant limit = Instant.now().plusMillis(timeoutMillis);
        int peak = controller.observedPeak(runId);
        while (peak < atLeast && Instant.now().isBefore(limit)) {
            Thread.sleep(25);
            peak = controller.observedPeak(runId);
        }
        return peak;
    }

    /** Captures what {@link WindowsProcessController} logs for the duration of one test. */
    private static ListAppender<ILoggingEvent> captureControllerLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(WindowsProcessController.class)).addAppender(appender);
        return appender;
    }

    private static void detachControllerLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(WindowsProcessController.class)).detachAppender(appender);
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
