package io.aria.conductor.execution.runtime.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SandboxLifecycle.OpenSandboxSdk#upload}: the sandbox core is
 * created with {@code skipHealthCheck=true}, so its execd may still be warming
 * up when the workspace is uploaded and the direct endpoint answers EOF /
 * connection-reset. The upload must absorb those transient failures within its
 * wall-clock window (default {@link SandboxLifecycle.OpenSandboxSdk#DEFAULT_UPLOAD_WINDOW_MS},
 * covering the observed Windows/WSL published-port relay warm-up) instead of
 * failing the run outright (observed: "unexpected end of stream on
 * http://localhost:.../proxy/..." 69ms after create), and an exhausted window
 * must still fail as loudly as the former fixed attempt budget did.
 */
class OpenSandboxSdkTest {

    private static final String SANDBOX_ID = "sb-1";
    private static final String EXEC_NOT_READY_MESSAGE =
            "Network connectivity error: unexpected end of stream on http://localhost:47279/proxy/1/...";

    private SandboxLifecycle.OpenSandboxSdk newSdk() {
        return new SandboxLifecycle.OpenSandboxSdk("http://localhost:8080", null);
    }

    /**
     * The SDK over the deterministic upload-window seam: the fake clock advances
     * only by the recorded sleeps, so the window is exercised without real waiting
     * and every backoff is assertable.
     */
    private SandboxLifecycle.OpenSandboxSdk seamSdk(FakeTime fake, long windowMs) {
        return new SandboxLifecycle.OpenSandboxSdk("http://localhost:8080", null, windowMs,
                fake::getAsLong, fake::sleep);
    }

    /** Monotonic-clock stand-in of the upload seam: time passes only when it sleeps. */
    private static final class FakeTime {
        private long elapsedNanos;
        private final List<Long> sleepsMs = new ArrayList<>();

        long getAsLong() {
            return elapsedNanos;
        }

        void sleep(long millis) {
            sleepsMs.add(millis);
            elapsedNanos += Duration.ofMillis(millis).toNanos();
        }

        List<Long> sleeps() {
            return sleepsMs;
        }
    }

    private Sandbox createTrackedSandbox(SandboxLifecycle.OpenSandboxSdk sdk) {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);
            sdk.create("test-image", Map.of());
            return sandbox;
        }
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

    private static List<WriteEntry> entries() {
        return List.of(WriteEntry.builder()
                .path("/workspace/task.md")
                .data("hello")
                .mode(644)
                .build());
    }

    private static RuntimeException execdNotReady() {
        return new RuntimeException(EXEC_NOT_READY_MESSAGE);
    }

    @Test
    void upload_retriesTransientExecdFailure_thenSucceeds() {
        FakeTime fake = new FakeTime();
        SandboxLifecycle.OpenSandboxSdk sdk = seamSdk(fake, SandboxLifecycle.OpenSandboxSdk.DEFAULT_UPLOAD_WINDOW_MS);
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);
        doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doNothing()
                .when(files).write(any());

        sdk.upload(SANDBOX_ID, entries());

        verify(files, times(3)).write(any());
        verifyNoMoreInteractions(files);
        assertThat(fake.sleeps()).containsExactly(500L, 1000L);
    }

    /**
     * The recurrence this window exists for: a relay warm-up that outlives the
     * former five-attempt (~18s) budget. Eight transient failures spend 27.5s of
     * backoff before the ninth attempt succeeds; the old budget failed live runs
     * 969ee0f0 / 4d2b6768 / d39d8625 in exactly this shape.
     */
    @Test
    void upload_absorbsALateSuccessWithinTheExtendedWindow() {
        FakeTime fake = new FakeTime();
        SandboxLifecycle.OpenSandboxSdk sdk = seamSdk(fake, SandboxLifecycle.OpenSandboxSdk.DEFAULT_UPLOAD_WINDOW_MS);
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);
        doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doNothing()
                .when(files).write(any());

        sdk.upload(SANDBOX_ID, entries());

        verify(files, times(9)).write(any());
        verifyNoMoreInteractions(files);
        assertThat(fake.sleeps())
                .containsExactly(500L, 1000L, 2000L, 4000L, 5000L, 5000L, 5000L, 5000L);
    }

    /**
     * The window is a budget, not a target: once spent, exhaustion still throws
     * the same loud SANDBOX_UNAVAILABLE naming the last observed cause, so a
     * genuinely unreachable relay keeps failing the run (nothing invented, no
     * silent success).
     */
    @Test
    void upload_surfacesFailureAfterTheWindowIsSpent() {
        FakeTime fake = new FakeTime();
        SandboxLifecycle.OpenSandboxSdk sdk = seamSdk(fake, 20_000L);
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);
        doThrow(execdNotReady()).when(files).write(any());

        assertThatThrownBy(() -> sdk.upload(SANDBOX_ID, entries()))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("Workspace upload failed for sandbox sb-1: " + EXEC_NOT_READY_MESSAGE)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));

        verify(files, times(8)).write(any());
        verifyNoMoreInteractions(files);
        assertThat(fake.sleeps()).containsExactly(500L, 1000L, 2000L, 4000L, 5000L, 5000L, 5000L);
    }

    /** The success path uploads once, never backs off and never repeats the write. */
    @Test
    void upload_successPath_writesOnceAndNeverBacksOff() {
        FakeTime fake = new FakeTime();
        SandboxLifecycle.OpenSandboxSdk sdk = seamSdk(fake, SandboxLifecycle.OpenSandboxSdk.DEFAULT_UPLOAD_WINDOW_MS);
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);

        sdk.upload(SANDBOX_ID, entries());

        verify(files, times(1)).write(any());
        verifyNoMoreInteractions(files);
        assertThat(fake.sleeps()).isEmpty();
    }

    @Test
    void upload_failsImmediatelyOnPermanentError() {
        SandboxLifecycle.OpenSandboxSdk sdk = newSdk();
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);
        // A permanent error (size cap, invalid path, ...) never matches the
        // transient keywords and must fail the upload immediately.
        doThrow(new RuntimeException("WriteEntry /workspace/task.md is too large"))
                .when(files).write(any());

        assertThatThrownBy(() -> sdk.upload(SANDBOX_ID, entries()))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("Workspace upload failed for sandbox sb-1");

        verify(files, times(1)).write(any());
    }

    // ---- IPv4 loopback normalization ----------------------------------------------

    /**
     * The direct execd connection domain must never carry a "localhost" host: it
     * resolves to the IPv6 loopback on this host, and the JDK client dials the
     * first resolved address -- an intermittent
     * "Failed to connect to localhost/[0:0:0:0:0:0:0:1]:<port>" observed on real
     * uploads regardless of the configured yml value. Any loopback name is pinned
     * to the IPv4 literal; a 127.x domain is unchanged.
     */
    @Test
    void loopbackConnectionDomainNormalizesToTheIpv4Literal() {
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf("http://localhost:8090"))
                .isEqualTo("127.0.0.1:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf("http://localhost"))
                .isEqualTo("127.0.0.1");
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf("http://[::1]:8090"))
                .isEqualTo("127.0.0.1:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf("http://[0:0:0:0:0:0:0:1]:8090"))
                .isEqualTo("127.0.0.1:8090");
        // Regression: a name merely starting with "localhost" is not the loopback host.
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf("http://localhost.localdomain:8090"))
                .isEqualTo("localhost.localdomain:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf("http://127.0.0.1:8090"))
                .isEqualTo("127.0.0.1:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.loopbackDomainOf(null))
                .isEqualTo("127.0.0.1:8080");
    }

    /** A server-reported endpoint host is normalized the same way. */
    @Test
    void reportedEndpointLoopbackNormalizesToTheIpv4Literal() {
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("localhost:59948/proxy/4096"))
                .isEqualTo("127.0.0.1:59948/proxy/4096");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("localhost"))
                .isEqualTo("127.0.0.1");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("localhost.localdomain:8090"))
                .isEqualTo("localhost.localdomain:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("http://localhost:47279/proxy/1/..."))
                .isEqualTo("http://127.0.0.1:47279/proxy/1/...");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("[::1]:8090"))
                .isEqualTo("127.0.0.1:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("[0:0:0:0:0:0:0:1]:8090"))
                .isEqualTo("127.0.0.1:8090");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback("127.0.0.1:47279/proxy/4096"))
                .isEqualTo("127.0.0.1:47279/proxy/4096");
        assertThat(SandboxLifecycle.OpenSandboxSdk.ipv4Loopback(null)).isNull();
    }

    // ---- Server URL scheme ---------------------------------------------------------

    /**
     * Regression: the connection config hardcoded "http" and silently downgraded a
     * configured https server URL (TLS-fronted sandbox deployment) on the wire.
     */
    @Test
    void connectionConfigKeepsTheConfiguredHttpsScheme() {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = stubBuilder(sandboxStatic);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(SANDBOX_ID);

            new SandboxLifecycle.OpenSandboxSdk("https://opensandbox.example.com:443", null)
                    .create("test-image", Map.of());

            ArgumentCaptor<ConnectionConfig> captor = ArgumentCaptor.forClass(ConnectionConfig.class);
            verify(builder).connectionConfig(captor.capture());
            assertThat(captor.getValue().getProtocol()).isEqualTo("https");
            assertThat(captor.getValue().getDomain()).isEqualTo("opensandbox.example.com:443");
        }
    }

    @Test
    void protocolFollowsTheConfiguredScheme() {
        assertThat(SandboxLifecycle.OpenSandboxSdk.protocolOf("https://opensandbox.example.com:443"))
                .isEqualTo("https");
        assertThat(SandboxLifecycle.OpenSandboxSdk.protocolOf("http://localhost:8090")).isEqualTo("http");
        assertThat(SandboxLifecycle.OpenSandboxSdk.protocolOf(null)).isEqualTo("http");
        assertThat(SandboxLifecycle.OpenSandboxSdk.protocolOf("")).isEqualTo("http");
    }
}
