package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.ApplyRuntimeResult;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.event.AgentCreatedEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.AgentSkill;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.RoleSkillTemplateRepository;
import io.aria.conductor.common.runtime.AgentExecutionPolicy;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.service.SkillContextProvider;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.agent.dto.RoleDefaultsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SkillContextProvider skillProvider;
    private final AgentSkillRepository agentSkillRepository;
    private final RoleSkillTemplateRepository roleSkillTemplateRepository;
    private final AgentPickupEligibility eligibility;
    /**
     * The shared admission policy (Task 18 fix round 1): the write path refuses a
     * selection admission would refuse instead of persisting it for the run time
     * to discover. The fields are still stored exactly as supplied -- only the
     * refusal is added, so admission keeps applying the documented defaults.
     */
    private final AgentExecutionPolicy executionPolicy;
    /**
     * Fleet runtime uniformity enforcement (fleet runtime governance): ON for
     * every production deployment. The deterministic core E2E harness turns it
     * OFF -- its purpose is exercising BOTH cores side by side, which the
     * uniformity contract forbids production configurations to mix.
     */
    @org.springframework.beans.factory.annotation.Value("${aria.runtime.enforce-uniformity:true}")
    private boolean enforceUniformity;

    public AgentService(AgentRepository agentRepository,
                        ApplicationEventPublisher eventPublisher,
                        ObjectMapper objectMapper,
                        SkillContextProvider skillProvider,
                        AgentSkillRepository agentSkillRepository,
                        RoleSkillTemplateRepository roleSkillTemplateRepository,
                        AgentPickupEligibility eligibility,
                        AgentExecutionPolicy executionPolicy) {
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.skillProvider = skillProvider;
        this.agentSkillRepository = agentSkillRepository;
        this.roleSkillTemplateRepository = roleSkillTemplateRepository;
        this.eligibility = eligibility;
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
    }

    @Transactional
    public AgentResponse createAgent(CreateAgentRequest request) {
        log.info("Creating agent: name={}, type={}", request.getName(), request.getAgentType());

        // Fleet runtime uniformity (2026-10-11): a create must not introduce a second
        // (core, mode) pair. Omitted selection fields are normalized to the documented
        // defaults inside the guard (the row itself is still stored as supplied below).
        assertNoRuntimeMix(request.getAdkProvider(), request.getExecutionMode());

        Agent agent = Agent.builder()
                .name(request.getName())
                .description(request.getDescription())
                .agentType(request.getAgentType())
                .role(request.getRole())
                .model(request.getModel())
                .provider(request.getProvider())
                .adkProvider(request.getAdkProvider() != null ? request.getAdkProvider() : "opencode")
                // Task 18 cutover: the execution selection is persisted as supplied. A
                // missing selection stays null, so admission applies the documented
                // defaults (opencode + SANDBOX) and never invents a stored mode.
                .executionMode(request.getExecutionMode())
                .workspaceMode(request.getWorkspaceMode())
                .workspacePath(request.getWorkspacePath())
                .workspaceBaseRef(request.getWorkspaceBaseRef())
                .config(serializeConfig(request.getConfig()))
                .healthStatus(HealthStatus.HEALTHY)
                .pickupEnabled(Boolean.TRUE)
                .build();

        // Write-time admission (Task 18 fix round 1): a selection the policy refuses
        // (unknown/removed core, unsupported mode, contradictory workspace fields) is
        // rejected here with the policy's exact message instead of being persisted for
        // the run to discover. The stored fields themselves stay exactly as supplied.
        validateExecutionSelection(agent);

        Agent saved = agentRepository.save(agent);
        log.info("Agent created: id={}", saved.getId());

        eventPublisher.publishEvent(
                new AgentCreatedEvent(this, saved.getId(), saved.getName(), saved.getAgentType().name()));

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> listAgents() {
        return agentRepository.findByHealthStatusNot(HealthStatus.RETIRED)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AgentResponse getAgent(UUID id) {
        Agent agent = findAgentOrThrow(id);
        return toResponse(agent);
    }

    @Transactional
    public AgentResponse updateAgent(UUID id, UpdateAgentRequest request) {
        Agent agent = findAgentOrThrow(id);
        log.info("Updating agent: id={}", id);

        if (request.getName() != null) agent.setName(request.getName());
        if (request.getDescription() != null) agent.setDescription(request.getDescription());
        if (request.getRole() != null) agent.setRole(request.getRole());
        if (request.getModel() != null) agent.setModel(request.getModel());
        if (request.getProvider() != null) agent.setProvider(request.getProvider());
        if (request.getAdkProvider() != null) agent.setAdkProvider(request.getAdkProvider());
        if (request.getExecutionMode() != null) agent.setExecutionMode(request.getExecutionMode());
        if (request.getWorkspaceMode() != null) agent.setWorkspaceMode(request.getWorkspaceMode());
        if (request.getWorkspacePath() != null) agent.setWorkspacePath(request.getWorkspacePath());
        if (request.getWorkspaceBaseRef() != null) agent.setWorkspaceBaseRef(request.getWorkspaceBaseRef());
        if (request.getConfig() != null) agent.setConfig(serializeConfig(request.getConfig()));
        if (request.getPickupEnabled() != null) {
            agent.setPickupEnabled(request.getPickupEnabled());
        }

        // Fleet runtime uniformity (2026-10-11): the null selection fields above kept the
        // agent's current values, so a partial update is judged on the runtime the row will
        // actually carry (nulls normalized to the documented defaults inside the guard) --
        // and every per-agent write on an already-mixed fleet is refused (the batch runtime
        // endpoint is the only converging action). Runs before admission and persistence;
        // retirement is exempt because retireAgent never reaches this.
        assertNoRuntimeMix(agent.getAdkProvider(), agent.getExecutionMode());

        // Write-time admission for a selection change (Task 18 fix round 1): only when
        // this request touches the execution selection, so an unrelated edit of a row
        // stored on a removed core is never blocked by a selection the row already carried.
        if (touchesExecutionSelection(request)) {
            validateExecutionSelection(agent);
        }

        Agent saved = agentRepository.save(agent);
        return toResponse(saved);
    }

    /**
     * Runs the shared admission policy over the agent's stored selection; a refusal
     * propagates as {@link IllegalArgumentException} with the policy's exact message
     * (the exception handler maps it to 400). The result is discarded on purpose: the
     * row keeps the supplied values and admission keeps applying its defaults later.
     */
    private void validateExecutionSelection(Agent agent) {
        executionPolicy.normalize(new AgentExecutionSettings(agent.getAdkProvider(),
                agent.getExecutionMode(), agent.getWorkspaceMode(), agent.getWorkspacePath(),
                agent.getWorkspaceBaseRef()));
    }

    /**
     * Fleet runtime uniformity (2026-10-11 fleet runtime governance): the fleet
     * runs ONE (core, mode) pair. A write that would introduce a second pair is
     * refused; on an already-mixed fleet every per-agent write is refused -- the
     * batch runtime endpoint is the only converging action. Retirement is exempt.
     *
     * <p>Both sides of the comparison are normalized to the documented defaults
     * ({@code opencode} + {@code SANDBOX}, the same defaults the admission policy
     * applies at run time): a create/update that omits the selection would
     * otherwise store a row whose raw pair (e.g. {@code opencode/null}) never
     * matches the effective pair it will actually run on. The emergent runtime is
     * the distinct set of effective (adkProvider, executionMode) over non-retired
     * agents, mirroring how the fleet is listed/filtered everywhere else
     * ({@code findByHealthStatusNot(HealthStatus.RETIRED)}, pickup eligibility).
     * An empty fleet (brand-new deployment) allows any first core/mode.
     */
    void assertNoRuntimeMix(String adkProvider, ExecutionMode executionMode) {
        if (!enforceUniformity) {
            return; // the deterministic core E2E harness exercises both cores side by side
        }
        // The guard's fleet read shares the convergence monitor: without this, an
        // apply's findAll/saveAll could interleave with this read and the create
        // would land on the pre-apply fleet.
        synchronized (FLEET_RUNTIME_LOCK) {
            doAssertNoRuntimeMix(adkProvider, executionMode);
        }
    }

    /** Monitor shared by the guard read and the convergence write (see applyRuntimeToAll). */
    private static final Object FLEET_RUNTIME_LOCK = new Object();

    private void doAssertNoRuntimeMix(String adkProvider, ExecutionMode executionMode) {
        String incoming = (adkProvider != null ? adkProvider : "opencode")
                + "/" + (executionMode != null ? executionMode : ExecutionMode.SANDBOX);
        Set<String> runtimes = agentRepository.findAll().stream()
                .filter(a -> a.getHealthStatus() != HealthStatus.RETIRED)
                .map(a -> (a.getAdkProvider() != null ? a.getAdkProvider() : "opencode")
                        + "/" + (a.getExecutionMode() != null ? a.getExecutionMode() : ExecutionMode.SANDBOX))
                .collect(Collectors.toSet());
        if (runtimes.size() > 1) {
            throw new IllegalStateException(
                    "The fleet runtime is mixed; converge it first via PUT /api/v1/agents/runtime");
        }
        if (!runtimes.isEmpty() && !runtimes.contains(incoming)) {
            throw new IllegalStateException("Runtime mixing is not allowed: the fleet runs "
                    + runtimes.iterator().next() + "; use PUT /api/v1/agents/runtime to change it fleet-wide");
        }
    }

    /**
     * Fleet runtime governance (2026-10-11): the ONE converging action for the
     * fleet's single (core, mode) pair (spec section 3). Deliberately bypasses
     * {@link #assertNoRuntimeMix}: the guard refuses any per-agent write that
     * would introduce a second pair and directs the operator to THIS endpoint --
     * running the guard here would refuse the very action that cures a mixed
     * fleet. Do NOT "unify" the two; they are intentionally different
     * (per-agent guard vs the batch convergence write).
     *
     * <p>Provider-inventory validation comes first: the requested pair must be
     * declared by the production core catalog behind {@code GET /api/v1/adk/providers}
     * -- enforced through the shared {@link AgentExecutionPolicy} admission whose
     * production implementation validates against that same catalog. An undeclared
     * core or unsupported mode is refused with {@link IllegalArgumentException}
     * (400) before any agent row is read or written. The policy's normalized
     * settings are then applied EXPLICITLY to every non-retired agent (no null
     * selections are stored, so the uniformity guard's default normalization
     * never has to interpret a batched row), and retired agents are neither
     * touched nor counted (same retired predicate as the guard:
     * {@code healthStatus != RETIRED}). One transaction: all agents converge
     * atomically or not at all.
     */
    /**
     * Single-JVM mutual exclusion for the convergence write: two concurrent
     * applies are last-writer-wins uniform either way, but an apply racing a
     * per-agent create could interleave findAll/save into a mixed fleet. This
     * monolith runs as one process (no distributed lock exists anywhere), so the
     * convergence write and the guard's fleet read SHARE the monitor below — a
     * create's guard read can no longer interleave with an apply's read/write
     * phase. RESIDUAL WINDOW, stated honestly: the Spring transaction commits
     * AFTER this method returns and the monitor is released, so a create whose
     * guard read lands inside that commit gap still sees the pre-commit fleet;
     * the operator UI is single-writer, which makes the gap negligible here. A
     * multi-instance deployment would need a DB-level guard first.
     */
    @Transactional
    public ApplyRuntimeResult applyRuntimeToAll(String adkProvider, ExecutionMode executionMode) {
        // Shared monitor with the guard's fleet read (see assertNoRuntimeMix).
        synchronized (FLEET_RUNTIME_LOCK) {
            AgentExecutionSettings applied = executionPolicy.normalize(
                    new AgentExecutionSettings(adkProvider, executionMode, null, null, null));

            List<Agent> fleet = agentRepository.findAll().stream()
                    .filter(a -> a.getHealthStatus() != HealthStatus.RETIRED)
                    .toList();
            for (Agent agent : fleet) {
                agent.setAdkProvider(applied.coreId());
                agent.setExecutionMode(applied.executionMode());
            }
            agentRepository.saveAll(fleet);

            // Audit trail: the convergence rewrites every agent's governed runtime
            // selection at once — exactly the action the review required to be
            // observable alongside the per-agent audit events.
            eventPublisher.publishEvent(new io.aria.conductor.common.event.AuditLogEvent(
                    this, "AGENT_RUNTIME_APPLIED", "AGENT", "fleet", "apply-runtime",
                    "core=" + applied.coreId() + ", mode=" + applied.executionMode()
                            + ", agents=" + fleet.size(), null));
            log.info("Fleet runtime applied: core={}, mode={}, updated={} agent(s) (retired agents untouched)",
                    applied.coreId(), applied.executionMode(), fleet.size());
            return new ApplyRuntimeResult(applied.coreId(), applied.executionMode(), fleet.size());
        }
    }

    /** Whether this update request carries any execution-selection field. */
    private static boolean touchesExecutionSelection(UpdateAgentRequest request) {
        return request.getAdkProvider() != null || request.getExecutionMode() != null
                || request.getWorkspaceMode() != null || request.getWorkspacePath() != null
                || request.getWorkspaceBaseRef() != null;
    }

    @Transactional
    public AgentResponse retireAgent(UUID id) {
        Agent agent = findAgentOrThrow(id);
        log.info("Retiring agent: id={}", id);

        agent.setHealthStatus(HealthStatus.RETIRED);
        agent.setRetiredAt(Instant.now());

        Agent saved = agentRepository.save(agent);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Map<HealthStatus, Long> countByStatus() {
        Map<HealthStatus, Long> counts = new java.util.EnumMap<>(HealthStatus.class);
        for (HealthStatus status : HealthStatus.values()) {
            counts.put(status, agentRepository.countByHealthStatus(status));
        }
        return counts;
    }

    @Transactional
    public void assignSkill(UUID agentId, String skillId) {
        Agent agent = findAgentOrThrow(agentId);
        // Governance: only enabled + SKILL-stage skills may be assigned (mirrors tool governance).
        if (skillProvider.getEnabledSkillsByIds(List.of(skillId)).isEmpty()) {
            throw new IllegalStateException(
                    "Skill '" + skillId + "' is not an approved/enabled SKILL and cannot be assigned");
        }
        AgentSkillId id = new AgentSkillId(agent.getId().toString(), skillId);
        if (!agentSkillRepository.existsById(id)) {
            agentSkillRepository.save(AgentSkill.builder().id(id).build());
            log.info("Assigned skill {} to agent {}", skillId, agentId);
        }
    }

    @Transactional
    public void unassignSkill(UUID agentId, String skillId) {
        Agent agent = findAgentOrThrow(agentId);
        AgentSkillId id = new AgentSkillId(agent.getId().toString(), skillId);
        if (agentSkillRepository.existsById(id)) {
            agentSkillRepository.deleteById(id);
            log.info("Unassigned skill {} from agent {}", skillId, agentId);
        }
    }

    @Transactional(readOnly = true)
    public List<SkillContext> getAgentSkills(UUID agentId) {
        findAgentOrThrow(agentId);
        return skillProvider.getEnabledSkillsByIds(
                agentSkillRepository.findSkillIdsByAgentId(agentId.toString()));
    }

    /** Idempotent replace: sets the agent's skills to exactly the given ids (governance-checked). */
    @Transactional
    public List<SkillContext> setSkills(UUID agentId, List<String> skillIds) {
        Agent agent = findAgentOrThrow(agentId);
        List<String> ids = skillIds == null ? List.of() : skillIds.stream().distinct().toList();
        // Validate all ids first: every id must resolve to an enabled SKILL-stage skill.
        if (skillProvider.getEnabledSkillsByIds(ids).size() != ids.size()) {
            throw new IllegalStateException(
                    "One or more skills are not approved/enabled SKILL-stage skills and cannot be assigned");
        }
        agentSkillRepository.deleteByAgentId(agent.getId().toString());
        for (String skillId : ids) {
            agentSkillRepository.save(AgentSkill.builder()
                    .id(new AgentSkillId(agent.getId().toString(), skillId))
                    .build());
        }
        log.info("Set {} skills on agent {}", ids.size(), agentId);
        return getAgentSkills(agentId);
    }

    /** Recommended default skills for a role (rule-based), with WORKER fallback. */
    @Transactional(readOnly = true)
    public RoleDefaultsResponse getRoleDefaults(String role) {
        String effectiveRole = (role == null || role.isBlank()) ? "WORKER" : role;
        List<String> skillIds = roleSkillTemplateRepository.findDefaultSkillIdsByRole(effectiveRole);
        if (skillIds.isEmpty() && !"WORKER".equals(effectiveRole)) {
            skillIds = roleSkillTemplateRepository.findDefaultSkillIdsByRole("WORKER");
        }
        return new RoleDefaultsResponse(skillProvider.getEnabledSkillsByIds(skillIds));
    }

    Agent findAgentOrThrow(UUID id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", id));
    }

    AgentResponse toResponse(Agent agent) {
        AgentPickupEligibility.Evaluation pickup = eligibility.evaluate(agent);
        AgentResponse response = AgentResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .agentType(agent.getAgentType())
                .role(agent.getRole())
                .model(agent.getModel())
                .provider(agent.getProvider())
                .adkProvider(agent.getAdkProvider())
                .executionMode(agent.getExecutionMode())
                .workspaceMode(agent.getWorkspaceMode())
                .workspacePath(agent.getWorkspacePath())
                .workspaceBaseRef(agent.getWorkspaceBaseRef())
                .config(agent.getConfig())
                .healthStatus(agent.getHealthStatus())
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .retiredAt(agent.getRetiredAt())
                .pickupEligible(pickup.eligible())
                .pickupIneligibleReasons(pickup.reasons().stream().map(Enum::name).toList())
                .lastProbedAt(agent.getLastProbedAt())
                .build();

        // Populate skills via SkillContextProvider (cycle-safe seam in act-common)
        try {
            List<String> skillNames = skillProvider.getEnabledSkillsForAgent(agent.getId().toString())
                    .stream()
                    .map(SkillContext::name)
                    .sorted()
                    .toList();
            response.setSkills(skillNames);
        } catch (Exception e) {
            log.warn("Failed to load skills for agent {}: {}", agent.getId(), e.getMessage());
        }

        return response;
    }

    private String serializeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize config, storing as toString()", e);
            return config.toString();
        }
    }
}
