package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.agent.dto.UpdateAgentRequest;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.event.AgentCreatedEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentTool;
import io.aria.conductor.common.model.AgentToolId;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.service.SkillContextProvider;
import io.aria.conductor.common.model.SkillContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final AgentToolRepository agentToolRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final SkillContextProvider skillProvider;

    public AgentService(AgentRepository agentRepository,
                        ApplicationEventPublisher eventPublisher,
                        ObjectMapper objectMapper,
                        AgentToolRepository agentToolRepository,
                        ToolDefinitionRepository toolDefinitionRepository,
                        SkillContextProvider skillProvider) {
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.agentToolRepository = agentToolRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.skillProvider = skillProvider;
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
                .adkProvider(request.getAdkProvider() != null ? request.getAdkProvider() : "langchain")
                .config(serializeConfig(request.getConfig()))
                .healthStatus(HealthStatus.HEALTHY)
                .build();

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
        if (request.getConfig() != null) agent.setConfig(serializeConfig(request.getConfig()));

        Agent saved = agentRepository.save(agent);
        return toResponse(saved);
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
    public void assignTool(UUID agentId, String toolId) {
        Agent agent = findAgentOrThrow(agentId);
        ToolDefinition tool = toolDefinitionRepository.findById(toolId)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", toolId));
        // Governance: only APPROVED + enabled tools may be assigned to an agent.
        if (!tool.isEnabled()) {
            throw new IllegalStateException(
                    "Tool '" + tool.getName() + "' is not approved/enabled and cannot be assigned");
        }
        AgentToolId id = new AgentToolId(agent.getId().toString(), toolId);
        if (!agentToolRepository.existsById(id)) {
            agentToolRepository.save(AgentTool.builder()
                    .id(id)
                    .assignedBy("user")
                    .assignedAt(Instant.now())
                    .build());
            log.info("Assigned tool {} to agent {}", toolId, agentId);
        }
    }

    @Transactional
    public void unassignTool(UUID agentId, String toolId) {
        Agent agent = findAgentOrThrow(agentId);
        AgentToolId id = new AgentToolId(agent.getId().toString(), toolId);
        if (agentToolRepository.existsById(id)) {
            agentToolRepository.deleteById(id);
            log.info("Unassigned tool {} from agent {}", toolId, agentId);
        }
    }

    @Transactional(readOnly = true)
    public List<ToolDefinition> getAgentTools(UUID agentId) {
        findAgentOrThrow(agentId);
        List<String> toolIds = agentToolRepository.findToolIdsByAgentId(agentId.toString());
        if (toolIds.isEmpty()) return List.of();
        return toolDefinitionRepository.findAllById(toolIds);
    }

    Agent findAgentOrThrow(UUID id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", id));
    }

    private AgentResponse toResponse(Agent agent) {
        AgentResponse response = AgentResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .agentType(agent.getAgentType())
                .role(agent.getRole())
                .model(agent.getModel())
                .provider(agent.getProvider())
                .adkProvider(agent.getAdkProvider())
                .config(agent.getConfig())
                .healthStatus(agent.getHealthStatus())
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .retiredAt(agent.getRetiredAt())
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

        // Populate tools
        try {
            List<String> toolIds = agentToolRepository.findToolIdsByAgentId(agent.getId().toString());
            if (!toolIds.isEmpty()) {
                List<String> toolNames = toolDefinitionRepository.findAllById(toolIds).stream()
                        .filter(ToolDefinition::isEnabled)
                        .map(ToolDefinition::getName)
                        .sorted()
                        .toList();
                response.setTools(toolNames);
            }
        } catch (Exception e) {
            log.warn("Failed to load tools for agent {}: {}", agent.getId(), e.getMessage());
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
