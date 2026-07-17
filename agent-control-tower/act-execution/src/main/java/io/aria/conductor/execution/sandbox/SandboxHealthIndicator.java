package io.aria.conductor.execution.sandbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sandbox.health.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SandboxHealthIndicator implements HealthIndicator {
    private final SandboxRunner sandboxRunner;
    @Override
    public Health health() {
        if (sandboxRunner.isSandboxAvailable()) return Health.up().withDetail("runtime", sandboxRunner.getRuntime()).build();
        return Health.down().withDetail("error", "No container runtime detected").build();
    }
}
