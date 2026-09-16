package io.aria.conductor.execution.health;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Keeps {@code agents.health_status} honest by probing each agent's runtime on a
 * fixed cadence.
 *
 * <p>Before this existed the column was only ever written by assumption (the
 * {@code @PrePersist} default, {@code createAgent}, the V42 seed literal), so an
 * operator read "HEALTHY" / "Online" for a runtime that had been dead for hours.
 *
 * <p>It probes through {@link AdkProvider#probeRuntimeHealth} rather than
 * {@code isHealthy(UUID)} — the latter counts failures and tears the sandbox down
 * at the threshold, so a per-tick read would let transient blips kill runtimes.
 * It never creates a sandbox either; bringing a runtime up stays the job of
 * {@code AriaDefaultAgentInitializer.recoverDegradedAria()}.
 */
@Slf4j
@Component
public class AgentHealthReconciler {

    private static final Set<String> SKIPPED_PROFILES = Set.of("test", "noop-llm");

    private final AgentRepository agentRepository;
    private final AdkProviderRegistry providerRegistry;
    private final Environment environment;

    public AgentHealthReconciler(AgentRepository agentRepository,
                                 AdkProviderRegistry providerRegistry,
                                 Environment environment) {
        this.agentRepository = agentRepository;
        this.providerRegistry = providerRegistry;
        this.environment = environment;
    }

    @Scheduled(fixedRateString = "${adk.health.reconcile-interval-ms:60000}")
    public void reconcile() {
        if (isSkippedProfile()) {
            return;
        }
        for (Agent agent : agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)) {
            try {
                applyProbe(agent, providerRegistry.resolve(agent).probeRuntimeHealth(agent.getId()));
            } catch (Exception e) {
                log.warn("Health reconcile failed for agent {}: {}", agent.getId(), e.getMessage());
            }
        }
    }

    private void applyProbe(Agent agent, AdkProvider.RuntimeHealth probed) {
        if (probed == null || probed == AdkProvider.RuntimeHealth.NOT_STARTED) {
            // "Cannot report" and "no runtime to judge" are the same answer: leave the
            // stamp untouched. Stamping UNHEALTHY here would reject dispatch — both
            // RunService.createRun and kanban pickup refuse an UNHEALTHY agent — for
            // providers that simply do not implement the probe (the interface default
            // is NOT_STARTED, and a Mockito mock of the interface yields null).
            return;
        }
        HealthStatus target = probed == AdkProvider.RuntimeHealth.REACHABLE
                ? HealthStatus.HEALTHY
                : HealthStatus.UNHEALTHY;
        if (agent.getHealthStatus() != target) {
            log.info("Agent {} health reconciled {} -> {}", agent.getId(), agent.getHealthStatus(), target);
        }
        agentRepository.reconcileHealth(agent.getId(), target, Instant.now());
    }

    private boolean isSkippedProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (SKIPPED_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }
}
