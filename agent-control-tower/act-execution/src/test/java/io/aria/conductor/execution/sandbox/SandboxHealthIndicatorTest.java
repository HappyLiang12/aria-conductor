package io.aria.conductor.execution.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Health mapping tests for {@link SandboxHealthIndicator}: an available runtime
 * maps to UP (exposing which runtime was detected), an unavailable one maps to
 * DOWN with a diagnostic error detail.
 */
@ExtendWith(MockitoExtension.class)
class SandboxHealthIndicatorTest {

    @Mock private SandboxRunner sandboxRunner;

    @Test
    void health_sandboxAvailable_reportsUpWithRuntimeDetail() {
        when(sandboxRunner.isSandboxAvailable()).thenReturn(true);
        when(sandboxRunner.getRuntime()).thenReturn("docker");

        Health health = new SandboxHealthIndicator(sandboxRunner).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("runtime", "docker");
        assertThat(health.getDetails()).doesNotContainKey("error");
    }

    @Test
    void health_sandboxUnavailable_reportsDownWithErrorDetail() {
        when(sandboxRunner.isSandboxAvailable()).thenReturn(false);

        Health health = new SandboxHealthIndicator(sandboxRunner).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("error", "No container runtime detected")
                .doesNotContainKey("runtime");
    }
}
