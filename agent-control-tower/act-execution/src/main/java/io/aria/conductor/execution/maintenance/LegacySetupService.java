package io.aria.conductor.execution.maintenance;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.security.ActorPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit built-in setup (spec 7.3, plan section 2.1): creates the
 * system-seeded role agents that are missing, on the supported
 * {@code opencode} core in {@code SANDBOX} mode.
 *
 * <p>This is the explicit replacement for the old startup repointing block,
 * and it is deliberately <em>not</em> a silent replacement for the scoped
 * retirement: it only ever creates rows that do not exist, never re-points,
 * edits or deletes an existing agent, and it runs only through the operator
 * endpoint ({@code POST /api/v1/maintenance/initialize-builtins}) or the CI
 * setup step that calls it. Nothing calls it at startup.
 *
 * <p>The built-in identities are the ones the V42 seed used, so a deployment
 * that cleared those rows is restored to the same ids, names and roles the
 * development-workflow template resolves by role.
 */
@Slf4j
@Service
public class LegacySetupService {

    /** The supported core every created built-in uses; a removed core is never re-created. */
    public static final String BUILTIN_CORE = "opencode";

    /** Built-in SANDBOX placement for every created agent. */
    public static final ExecutionMode BUILTIN_MODE = ExecutionMode.SANDBOX;

    /** Built-ins seeded by V42__seed_sdd_role_agents.sql (id, name, role). */
    public static final List<BuiltinAgent> BUILTIN_AGENTS = List.of(
            new BuiltinAgent(UUID.fromString("ba000000-0000-0000-0000-000000000001"), "SDD BA Agent", "ba"),
            new BuiltinAgent(UUID.fromString("de000000-0000-0000-0000-000000000002"), "SDD DEV Agent", "dev"),
            new BuiltinAgent(UUID.fromString("aa000000-0000-0000-0000-000000000003"), "SDD QA Agent", "qa"));

    private final AgentRepository agentRepository;
    private final Clock clock;

    @Autowired
    public LegacySetupService(AgentRepository agentRepository) {
        this(agentRepository, Clock.systemUTC());
    }

    /** Full seam: explicit clock for deterministic test fixtures. */
    public LegacySetupService(AgentRepository agentRepository, Clock clock) {
        this.agentRepository = agentRepository;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /**
     * Creates exactly the missing built-ins, and nothing else.
     *
     * @param actor the authenticated operator; a worker principal is refused
     * @return the created and already-present built-in ids, so the caller can
     *         report what setup actually did
     * @throws SecurityException when the caller is not the operator
     */
    @Transactional
    public SetupReceipt initializeMissingBuiltins(ActorPrincipal actor) {
        Objects.requireNonNull(actor, "ActorPrincipal is required").requireOperator();
        List<UUID> created = new ArrayList<>();
        List<UUID> present = new ArrayList<>();
        for (BuiltinAgent builtin : BUILTIN_AGENTS) {
            if (agentRepository.findById(builtin.id()).isPresent()) {
                // Never re-point or edit an existing row: setup is create-only.
                present.add(builtin.id());
                continue;
            }
            Agent agent = Agent.builder()
                    .id(builtin.id())
                    .name(builtin.name())
                    .role(builtin.role())
                    .agentType(AgentType.NATIVE)
                    .adkProvider(BUILTIN_CORE)
                    .executionMode(BUILTIN_MODE)
                    .healthStatus(HealthStatus.HEALTHY)
                    .createdAt(clock.instant())
                    .build();
            agentRepository.save(agent);
            created.add(builtin.id());
        }
        log.info("Built-in setup: created={} alreadyPresent={}", created, present);
        return new SetupReceipt(created, present);
    }

    /** One built-in identity: the V42 seed's id, name and role. */
    public record BuiltinAgent(UUID id, String name, String role) {
    }

    /**
     * What the setup actually did.
     *
     * @param createdAgentIds built-ins created by this call, in seed order
     * @param existingAgentIds built-ins that already existed and were left untouched
     */
    public record SetupReceipt(List<UUID> createdAgentIds, List<UUID> existingAgentIds) {
    }
}
