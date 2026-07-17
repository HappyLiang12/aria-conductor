package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.common.model.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AgentTemplateService {

    private static final Map<String, CreateAgentRequest> TEMPLATES = Map.of(
            "ba", CreateAgentRequest.builder()
                    .name("Business Analyst Agent")
                    .description("Analyzes requirements, writes user stories, "
                            + "and produces structured specification documents")
                    .agentType(AgentType.ADK)
                    .role("ba")
                    .model("ali-copilot")
                    .provider("alibaba")
                    .adkProvider("langchain")
                    .build(),
            "dev", CreateAgentRequest.builder()
                    .name("Developer Agent")
                    .description("Implements features, refactors code, "
                            + "and reviews diffs against project conventions")
                    .agentType(AgentType.ADK)
                    .role("dev")
                    .model("ali-copilot")
                    .provider("alibaba")
                    .adkProvider("langchain")
                    .build(),
            "qa", CreateAgentRequest.builder()
                    .name("QA Agent")
                    .description("Authors tests, validates flows, "
                            + "and screens evidence packs before release")
                    .agentType(AgentType.ADK)
                    .role("qa")
                    .model("ali-copilot")
                    .provider("alibaba")
                    .adkProvider("langchain")
                    .build()
    );

    private final AgentService agentService;

    public AgentTemplateService(AgentService agentService) {
        this.agentService = agentService;
    }

    public List<AgentTemplateDTO> listTemplates() {
        return TEMPLATES.entrySet().stream()
                .map(entry -> AgentTemplateDTO.builder()
                        .id(entry.getKey())
                        .label(entry.getValue().getName())
                        .agentType(entry.getValue().getAgentType())
                        .role(entry.getValue().getRole())
                        .model(entry.getValue().getModel())
                        .provider(entry.getValue().getProvider())
                        .adkProvider(entry.getValue().getAdkProvider())
                        .description(entry.getValue().getDescription())
                        .build())
                .toList();
    }

    public AgentResponse createFromTemplate(String templateName) {
        CreateAgentRequest template = TEMPLATES.get(templateName.toLowerCase());
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName
                    + ". Available: " + TEMPLATES.keySet());
        }
        log.info("Creating agent from template: {}", templateName);
        return agentService.createAgent(template);
    }
}
