package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.AgentResponse;
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
