package io.aria.conductor.execution.runtime.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SandboxLifecycle.OpenSandboxSdk#upload}: the sandbox core is
 * created with {@code skipHealthCheck=true}, so its execd may still be warming
 * up when the workspace is uploaded and the direct endpoint answers EOF /
 * connection-reset. The upload must absorb those transient failures with a
 * bounded retry instead of failing the run outright (observed: "unexpected end
 * of stream on http://localhost:.../proxy/..." 69ms after create).
 */
class OpenSandboxSdkTest {

    private static final String SANDBOX_ID = "sb-1";

    private SandboxLifecycle.OpenSandboxSdk newSdk() {
        return new SandboxLifecycle.OpenSandboxSdk("http://localhost:8080", null);
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
        return new RuntimeException(
                "Network connectivity error: unexpected end of stream on http://localhost:47279/proxy/1/...");
    }

    @Test
    void upload_retriesTransientExecdFailure_thenSucceeds() {
        SandboxLifecycle.OpenSandboxSdk sdk = newSdk();
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);
        doThrow(execdNotReady())
                .doThrow(execdNotReady())
                .doNothing()
                .when(files).write(any());

        sdk.upload(SANDBOX_ID, entries());

        verify(files, times(3)).write(any());
    }

    @Test
    void upload_surfacesFailureAfterRetryBudget() {
        SandboxLifecycle.OpenSandboxSdk sdk = newSdk();
        Sandbox sandbox = createTrackedSandbox(sdk);
        Filesystem files = mock(Filesystem.class);
        when(sandbox.files()).thenReturn(files);
        doThrow(execdNotReady()).when(files).write(any());

        assertThatThrownBy(() -> sdk.upload(SANDBOX_ID, entries()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));

        verify(files, times(SandboxLifecycle.OpenSandboxSdk.MAX_UPLOAD_ATTEMPTS)).write(any());
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
}