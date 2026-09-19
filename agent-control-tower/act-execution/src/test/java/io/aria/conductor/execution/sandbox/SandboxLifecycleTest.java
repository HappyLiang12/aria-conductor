package io.aria.conductor.execution.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionResult;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxRenewResponse;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;
import com.sun.net.httpserver.HttpServer;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SandboxLifecycle}, the shared OpenSandbox lifecycle extracted from
 * {@code OpenCodeSandboxManager} (C0.5).
 *
 * <p>Pins the frozen behavior the opencode provider relies on: the 30-minute sandbox TTL,
 * {@code skipHealthCheck=true} creation (the server reports scheme-less endpoints), the
 * 3-attempt create retry with 2s/4s exponential backoff on transient start errors,
 * depth-3 / 4 MiB workspace upload caps with mode 644, endpoint scheme completion,
 * renewal, kill, blocking and background (virtual thread) command launch and the
 * server-level health probe.
 *
 * <p>SDK interactions are mocked at the {@code Sandbox} boundary (static {@code builder()}
 * plus the sandbox instance), mirroring {@code OpenCodeSandboxManagerTest}; the health
 * probe runs against a real loopback {@link HttpServer} because it uses
 * {@code java.net.http.HttpClient} directly.
 */
class SandboxLifecycleTest {

    private static final String SANDBOX_ID = "sb-1";
    private static final String IMAGE = "test-image";
    private static final String SERVER_URL = "http://localhost:8080";

    // ---- create -------------------------------------------------------------------

    @Test
    void createSandbox_returnsSandboxId_andForwardsEnvToBuilder() {
        UUID owner = UUID.randomUUID();
        Map<String, String> env = Map.of("DEEPSEEK_API_KEY", "secret-key");

        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);

            String id = newLifecycle().createSandbox(owner, IMAGE, env);

            assertThat(id).isEqualTo(SANDBOX_ID);
            verify(builder).env(env);
        }
    }

    @Test
    void createSandbox_nullEnv_skipsSdkEnvCall() {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);

            newLifecycle().createSandbox(UUID.randomUUID(), IMAGE, null);

            verify(builder, never()).env(anyMap());
        }
    }

    @Test
    void createSandbox_emptyEnv_skipsSdkEnvCall() {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);

            newLifecycle().createSandbox(UUID.randomUUID(), IMAGE, Map.of());

            verify(builder, never()).env(anyMap());
        }
    }

    @Test
    void createSandbox_pinsThirtyMinuteTtl_andSkipsSdkHealthCheck() {
        // The server reports execd endpoints without a scheme, so the SDK's built-in
        // health check must stay off (readiness is verified against the completed URL).
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);

            newLifecycle().createSandbox(UUID.randomUUID(), IMAGE, null);

            ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(builder).timeout(timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(Duration.ofMinutes(30));
            verify(builder).skipHealthCheck(true);
        }
    }

    @Test
    void createSandbox_retriesTransientStartError_withBackoff_thenSucceeds() {
        // DOCKER::SANDBOX_START_FAILED (Windows excluded port range) is transient:
        // the next attempt must happen after the 2s backoff.
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build())
                    .thenThrow(new RuntimeException("DOCKER::SANDBOX_START_FAILED: port 40369 excluded"))
                    .thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);

            long startNanos = System.nanoTime();
            String id = newLifecycle().createSandbox(UUID.randomUUID(), IMAGE, null);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            assertThat(id).isEqualTo(SANDBOX_ID);
            verify(builder, times(2)).build();
            assertThat(elapsedMs)
                    .as("the retry must wait for the 2s backoff")
                    .isGreaterThanOrEqualTo(1_500L);
        }
    }

    @Test
    void createSandbox_givesUpAfterThreeAttempts_withExponentialBackoff() {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            when(builder.build())
                    .thenThrow(new RuntimeException("SANDBOX_START_FAILED: excluded port range"))
                    .thenThrow(new RuntimeException("SANDBOX_START_FAILED: excluded port range"))
                    .thenThrow(new RuntimeException("SANDBOX_START_FAILED: excluded port range"));

            SandboxLifecycle lifecycle = newLifecycle();
            long startNanos = System.nanoTime();

            assertThatThrownBy(() -> lifecycle.createSandbox(UUID.randomUUID(), IMAGE, null))
                    .isInstanceOf(TaskExecutionException.class)
                    .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                            .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            verify(builder, times(3)).build();
            assertThat(elapsedMs)
                    .as("three attempts must sleep 2s + 4s between them")
                    .isGreaterThanOrEqualTo(5_000L);
        }
    }

    @Test
    void createSandbox_doesNotRetryNonTransientError() {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            when(builder.build()).thenThrow(new RuntimeException("invalid API key: authentication failed"));

            assertThatThrownBy(() -> newLifecycle().createSandbox(UUID.randomUUID(), IMAGE, null))
                    .isInstanceOf(TaskExecutionException.class)
                    .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                            .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
            verify(builder, times(1)).build();
        }
    }

    // ---- workspace upload ---------------------------------------------------------

    @Test
    void uploadWorkspace_uploadsTextFilesWithMode644UnderWorkspaceRoot(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("AGENTS.md"), "hello");
        Files.createDirectories(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("nested/opencode.json"), "{}");
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);

        lifecycle.uploadWorkspace(owner, workspace);

        List<WriteEntry> entries = capturedWriteEntries(files);
        assertThat(entries).extracting(WriteEntry::getPath)
                .containsExactly("/workspace/AGENTS.md", "/workspace/nested/opencode.json");
        assertThat(entries).extracting(entry -> String.valueOf(entry.getData()))
                .containsExactly("hello", "{}");
        assertThat(entries).extracting(WriteEntry::getMode).containsOnly(644);
    }

    @Test
    void uploadWorkspace_skipsFilesDeeperThanThreeLevels(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("l1/l2/l3"));
        Files.writeString(workspace.resolve("l1/l2/l3/kept.txt"), "kept");
        Files.createDirectories(workspace.resolve("l1/l2/l3/l4"));
        Files.writeString(workspace.resolve("l1/l2/l3/l4/dropped.txt"), "dropped");
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);

        lifecycle.uploadWorkspace(owner, workspace);

        assertThat(capturedWriteEntries(files)).extracting(WriteEntry::getPath)
                .containsExactly("/workspace/l1/l2/l3/kept.txt");
    }

    @Test
    void uploadWorkspace_skipsOversizedFiles(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("small.txt"), "small");
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'a');
        Files.write(workspace.resolve("big.txt"), oversized);
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);

        lifecycle.uploadWorkspace(owner, workspace);

        assertThat(capturedWriteEntries(files)).extracting(WriteEntry::getPath)
                .containsExactly("/workspace/small.txt");
    }

    @Test
    void uploadWorkspace_skipsBinaryFiles(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("text.txt"), "text");
        Files.write(workspace.resolve("binary.bin"), new byte[] {'a', 0, 'b'});
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);

        lifecycle.uploadWorkspace(owner, workspace);

        assertThat(capturedWriteEntries(files)).extracting(WriteEntry::getPath)
                .containsExactly("/workspace/text.txt");
    }

    @Test
    void uploadWorkspace_missingDirectory_writesNothing() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);

        lifecycle.uploadWorkspace(owner, Path.of("does/not/exist"));

        verify(files, never()).write(any());
    }

    @Test
    void uploadWorkspace_unknownOwner_throws() {
        SandboxLifecycle lifecycle = newLifecycle();

        assertThatThrownBy(() -> lifecycle.uploadWorkspace(UUID.randomUUID(), Path.of(".")))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    // ---- endpoint resolution ------------------------------------------------------

    @Test
    void getSandboxUrl_prependsHttpSchemeWhenMissing() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        SandboxEndpoint endpoint = mock(SandboxEndpoint.class);
        when(sandbox.getEndpoint(4096)).thenReturn(endpoint);
        when(endpoint.getEndpoint()).thenReturn("127.0.0.1:40369/proxy/4096");

        assertThat(lifecycle.getSandboxUrl(SANDBOX_ID, 4096))
                .isEqualTo("http://127.0.0.1:40369/proxy/4096");
    }

    @Test
    void getSandboxUrl_keepsExistingScheme() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        SandboxEndpoint endpoint = mock(SandboxEndpoint.class);
        when(sandbox.getEndpoint(4096)).thenReturn(endpoint);
        when(endpoint.getEndpoint()).thenReturn("http://192.168.1.10:4096");

        assertThat(lifecycle.getSandboxUrl(SANDBOX_ID, 4096)).isEqualTo("http://192.168.1.10:4096");
    }

    @Test
    void getSandboxUrl_unknownSandbox_throws() {
        assertThatThrownBy(() -> newLifecycle().getSandboxUrl("missing", 4096))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    // ---- renewal ------------------------------------------------------------------

    @Test
    void renewSandbox_delegatesToSdk() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        when(sandbox.renew(any(Duration.class)))
                .thenReturn(new SandboxRenewResponse(OffsetDateTime.now().plusMinutes(30)));

        lifecycle.renewSandbox(SANDBOX_ID, Duration.ofMinutes(30));

        verify(sandbox).renew(Duration.ofMinutes(30));
    }

    @Test
    void renewSandbox_unknownId_throws() {
        assertThatThrownBy(() -> newLifecycle().renewSandbox("missing", Duration.ofMinutes(30)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    @Test
    void renewSandbox_sdkFailure_throwsSandboxUnavailable() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        when(sandbox.renew(any(Duration.class))).thenThrow(new RuntimeException("renew rejected"));

        assertThatThrownBy(() -> lifecycle.renewSandbox(SANDBOX_ID, Duration.ofMinutes(30)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    // ---- kill ---------------------------------------------------------------------

    @Test
    void killSandbox_killsSdkSandbox_andUntracksIt() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);

        lifecycle.killSandbox(SANDBOX_ID);

        verify(sandbox).kill();
        assertThatThrownBy(() -> lifecycle.renewSandbox(SANDBOX_ID, Duration.ofMinutes(30)))
                .as("a killed sandbox must no longer be tracked")
                .isInstanceOf(TaskExecutionException.class);
    }

    @Test
    void killSandbox_nullId_isNoOp() {
        assertThatCode(() -> newLifecycle().killSandbox(null)).doesNotThrowAnyException();
    }

    @Test
    void killSandbox_sdkFailure_isSwallowed() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        doThrow(new RuntimeException("kill failed")).when(sandbox).kill();

        assertThatCode(() -> lifecycle.killSandbox(SANDBOX_ID)).doesNotThrowAnyException();
    }

    @Test
    void sandbox_returnsTrackedHandle_andThrowsForUnknownId() {
        // Escape hatch for provider-specific diagnostics (e.g. reading sandbox metrics).
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, UUID.randomUUID());

        assertThat(lifecycle.sandbox(SANDBOX_ID)).isSameAs(sandbox);
        assertThatThrownBy(() -> lifecycle.sandbox("missing"))
                .isInstanceOf(TaskExecutionException.class);
    }

    // ---- blocking command ---------------------------------------------------------

    @Test
    void runCommand_returnsCombinedStdoutAndResultText() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Commands commands = mock(Commands.class);
        when(sandbox.commands()).thenReturn(commands);
        Execution exec = mock(Execution.class);
        when(exec.getLogs()).thenReturn(new ExecutionLogs(List.of(
                new OutputMessage("part1\n", 0L, false),
                new OutputMessage("part2\n", 1L, false)), List.of()));
        when(exec.getResult()).thenReturn(List.of(new ExecutionResult("exit=0", 2L, Map.of())));
        when(commands.run("echo hi")).thenReturn(exec);

        assertThat(lifecycle.runCommand(SANDBOX_ID, "echo hi")).isEqualTo("part1\npart2\nexit=0");
    }

    @Test
    void runCommand_sdkFailure_throwsSandboxUnavailable() {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Commands commands = mock(Commands.class);
        when(sandbox.commands()).thenReturn(commands);
        when(commands.run(anyString())).thenThrow(new RuntimeException("execd refused connection"));

        assertThatThrownBy(() -> lifecycle.runCommand(SANDBOX_ID, "true"))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    @Test
    void runCommand_unknownSandbox_throws() {
        assertThatThrownBy(() -> newLifecycle().runCommand("missing", "true"))
                .isInstanceOf(TaskExecutionException.class);
    }

    // ---- background command -------------------------------------------------------

    @Test
    void runBackgroundCommand_runsOnVirtualThread_withoutBlockingCaller() throws Exception {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Commands commands = mock(Commands.class);
        when(sandbox.commands()).thenReturn(commands);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(commands.run("sleep 600")).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        });

        try {
            long startNanos = System.nanoTime();
            lifecycle.runBackgroundCommand(SANDBOX_ID, "sleep 600", null);
            long returnMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("a long-running command must be launched on its own thread")
                    .isTrue();
            assertThat(returnMs)
                    .as("the caller must not block on the background command")
                    .isLessThan(1_000L);
            assertThat(worker.get()).as("background launch must use a virtual thread").isNotNull();
            assertThat(worker.get().isVirtual()).isTrue();
            verify(commands).run("sleep 600");
        } finally {
            release.countDown();
        }
    }

    @Test
    void runBackgroundCommand_forwardsEnvViaRunCommandRequest() throws Exception {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Commands commands = mock(Commands.class);
        when(sandbox.commands()).thenReturn(commands);
        CountDownLatch launched = new CountDownLatch(1);
        when(commands.run(any(RunCommandRequest.class))).thenAnswer(invocation -> {
            launched.countDown();
            return null;
        });

        lifecycle.runBackgroundCommand(SANDBOX_ID, "opencode serve --port 4096",
                Map.of("ARIA_MCP_TOKEN", "test-worker-token"));

        assertThat(launched.await(5, TimeUnit.SECONDS)).isTrue();
        ArgumentCaptor<RunCommandRequest> captor = ArgumentCaptor.forClass(RunCommandRequest.class);
        verify(commands).run(captor.capture());
        assertThat(captor.getValue().getCommand()).isEqualTo("opencode serve --port 4096");
        assertThat(captor.getValue().getEnvs()).containsEntry("ARIA_MCP_TOKEN", "test-worker-token");
    }

    @Test
    void runBackgroundCommand_unknownSandbox_throwsSynchronously() {
        assertThatThrownBy(() -> newLifecycle().runBackgroundCommand("missing", "sleep 600", null))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    @Test
    void runBackgroundCommand_failureDoesNotPropagateToCaller() throws Exception {
        UUID owner = UUID.randomUUID();
        SandboxLifecycle lifecycle = newLifecycle();
        Sandbox sandbox = createTrackedSandbox(lifecycle, owner);
        Commands commands = mock(Commands.class);
        when(sandbox.commands()).thenReturn(commands);
        CountDownLatch attempted = new CountDownLatch(1);
        when(commands.run(anyString())).thenAnswer(invocation -> {
            attempted.countDown();
            throw new RuntimeException("opencode serve exited");
        });

        assertThatCode(() -> lifecycle.runBackgroundCommand(SANDBOX_ID, "opencode serve", null))
                .doesNotThrowAnyException();
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(commands).run("opencode serve");
    }

    // ---- server health probe ------------------------------------------------------

    @Test
    void isServerHealthy_trueOn2xx() throws IOException {
        HttpServer server = httpServer(200);
        try {
            SandboxLifecycle lifecycle = new SandboxLifecycle(loopbackUrl(server), null);
            assertThat(lifecycle.isServerHealthy()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isServerHealthy_falseOnNon2xx() throws IOException {
        HttpServer server = httpServer(503);
        try {
            SandboxLifecycle lifecycle = new SandboxLifecycle(loopbackUrl(server), null);
            assertThat(lifecycle.isServerHealthy()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isServerHealthy_falseWhenUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        SandboxLifecycle lifecycle =
                new SandboxLifecycle("http://127.0.0.1:" + closedPort, null);

        assertThat(lifecycle.isServerHealthy()).isFalse();
    }

    // ---- helpers ------------------------------------------------------------------

    private static SandboxLifecycle newLifecycle() {
        return new SandboxLifecycle(SERVER_URL, null);
    }

    /** Mocks {@code Sandbox.builder()} and returns a builder mock with fluent stubs wired. */
    private static Sandbox.Builder stubBuilder(MockedStatic<Sandbox> sandboxStatic) {
        Sandbox.Builder builder = mock(Sandbox.Builder.class);
        sandboxStatic.when(Sandbox::builder).thenReturn(builder);
        when(builder.connectionConfig(any())).thenReturn(builder);
        when(builder.image(anyString())).thenReturn(builder);
        when(builder.timeout(any(Duration.class))).thenReturn(builder);
        when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
        return builder;
    }

    /** Creates a sandbox through the lifecycle (statically mocked SDK) and returns the sandbox mock. */
    private static Sandbox createTrackedSandbox(SandboxLifecycle lifecycle, UUID owner) {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);
            lifecycle.createSandbox(owner, IMAGE, null);
            return sandbox;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<WriteEntry> capturedWriteEntries(Filesystem files) {
        ArgumentCaptor<List<WriteEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(files).write(captor.capture());
        return captor.getValue();
    }

    /** Starts a loopback HTTP server whose {@code /health} responds with {@code status}. */
    private static HttpServer httpServer(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "probe".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String loopbackUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
