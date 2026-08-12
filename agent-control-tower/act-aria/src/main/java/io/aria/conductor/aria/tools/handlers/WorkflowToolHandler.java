package io.aria.conductor.aria.tools.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.execution.tool.ToolHandler;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * Aria orchestration handler for executable workflow chains (#37).
 *
 * <p>Lets Aria turn a BA→Dev→QA plan into a real, running {@code WorkflowChain} instead of only
 * documenting it as knowledge. {@code create_workflow} accepts either a structured {@code steps}
 * array or a {@code yaml} definition (dual-format), resolves each step's agent by id, name, or
 * role, and delegates to {@link WorkflowService#createAndStart} which starts step 0 asynchronously.
 */
@Slf4j
@Component("workflowHandler")
public class WorkflowToolHandler implements ToolHandler {

    private final WorkflowService workflowService;
    private final WorkflowTemplateService workflowTemplateService;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public WorkflowToolHandler(WorkflowService workflowService,
                               WorkflowTemplateService workflowTemplateService,
                               AgentRepository agentRepository,
                               ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.workflowTemplateService = workflowTemplateService;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "create_workflow" -> createWorkflow(arguments);
                case "get_workflow" -> getWorkflow(arguments);
                case "list_workflows" -> listWorkflows();
                case "cancel_workflow" -> cancelWorkflow(arguments);
                case "retry_workflow_step" -> retryWorkflowStep(arguments);
                case "instantiate_template" -> instantiateTemplate(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("WorkflowToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    /** Start the governed SDD loop from an APPROVED WORKFLOW template (e.g. development-workflow). */
    private String instantiateTemplate(Map<String, Object> args) throws Exception {
        String templateId = Objects.toString(args.get("templateId"), "");
        if (templateId.isBlank()) return error("Missing required parameter: templateId");
        UUID id;
        try {
            id = UUID.fromString(templateId);
        } catch (IllegalArgumentException e) {
            return error("templateId must be a UUID");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) args.get("parameters");
        if (params == null) params = Map.of();
        WorkflowResponse resp = workflowTemplateService.instantiateTemplate(id, params);
        return "Workflow template instantiated and started (chain id: " + resp.getId()
                + ", name: " + resp.getName() + ", status: " + resp.getStatus()
                + "). The loop pauses for human spec approval (SPEC_REVIEW) before Dev runs.";
    }

    private String createWorkflow(Map<String, Object> args) throws Exception {
        String name = Objects.toString(args.get("name"), "");
        if (name.isEmpty()) return error("Missing required parameter: name");
        String description = Objects.toString(args.get("description"), "");

        List<Map<String, Object>> rawSteps = extractSteps(args);
        if (rawSteps == null || rawSteps.isEmpty()) {
            return error("Missing required parameter: steps (provide a non-empty 'steps' array "
                    + "of {agent, promptTemplate} objects, or a 'yaml' workflow definition)");
        }

        List<CreateWorkflowRequest.StepDef> steps = new ArrayList<>();
        for (int i = 0; i < rawSteps.size(); i++) {
            Map<String, Object> s = rawSteps.get(i);
            String agentRef = firstNonBlank(
                    Objects.toString(s.get("agentId"), ""),
                    Objects.toString(s.get("agentName"), ""),
                    Objects.toString(s.get("agent"), ""),
                    Objects.toString(s.get("role"), ""));
            if (agentRef.isBlank()) {
                return error("Step " + (i + 1) + " is missing an agent (agentId, agentName, or role)");
            }
            UUID agentId = resolveAgent(agentRef);
            if (agentId == null) {
                return error("Step " + (i + 1) + ": agent not found: " + agentRef);
            }
            String prompt = firstNonBlank(
                    Objects.toString(s.get("promptTemplate"), ""),
                    Objects.toString(s.get("prompt"), ""));
            if (prompt.isBlank()) {
                return error("Step " + (i + 1) + " is missing a promptTemplate");
            }
            steps.add(CreateWorkflowRequest.StepDef.builder()
                    .agentId(agentId)
                    .promptTemplate(prompt)
                    .maxIterations(parseInt(s.get("maxIterations"), 3))
                    .build());
        }

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name(name)
                .description(description)
                .steps(steps)
                .build();
        WorkflowResponse resp = workflowService.createAndStart(request);
        return "Workflow '" + name + "' created and started (id: " + resp.getId()
                + ", steps: " + steps.size() + ", status: " + resp.getStatus() + ")";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSteps(Map<String, Object> args) throws Exception {
        Object stepsObj = args.get("steps");
        if (stepsObj instanceof List<?> list) {
            return toMapList(list);
        }
        if (stepsObj instanceof String s && !s.isBlank()) {
            // steps passed as a JSON array string
            return objectMapper.readValue(s, List.class);
        }
        String yaml = Objects.toString(args.get("yaml"), "");
        if (!yaml.isBlank()) {
            Object parsed = new Yaml().load(yaml);
            if (parsed instanceof Map<?, ?> root && root.get("steps") instanceof List<?> list) {
                return toMapList(list);
            }
            if (parsed instanceof List<?> list) {
                return toMapList(list);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** Resolve a step agent reference by UUID, then name, then role (first non-retired). */
    private UUID resolveAgent(String idOrName) {
        UUID byIdOrName = AgentToolHandler.resolveAgentId(agentRepository, idOrName);
        if (byIdOrName != null) return byIdOrName;
        return agentRepository.findByRole(idOrName).stream()
                .filter(a -> a.getHealthStatus() != HealthStatus.RETIRED)
                .map(Agent::getId)
                .findFirst().orElse(null);
    }

    private String getWorkflow(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        WorkflowResponse w = workflowService.getWorkflow(UUID.fromString(id));
        StringBuilder sb = new StringBuilder("Workflow: ").append(w.getName()).append("\n");
        sb.append("  ID: ").append(w.getId()).append("\n");
        sb.append("  Status: ").append(w.getStatus()).append("\n");
        sb.append("  Step: ").append(w.getCurrentStepIndex() + 1).append("/").append(w.getTotalSteps()).append("\n");
        if (w.getSteps() != null) {
            for (WorkflowResponse.StepInfo st : w.getSteps()) {
                sb.append("  - Step ").append(st.getIndex() + 1)
                        .append(" | agent: ").append(st.getAgentId())
                        .append(" | status: ").append(st.getStatus())
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String listWorkflows() {
        List<WorkflowResponse> workflows = workflowService.listWorkflows();
        if (workflows.isEmpty()) return "No workflows found.";
        StringBuilder sb = new StringBuilder("Workflows (" + workflows.size() + " total):\n");
        for (WorkflowResponse w : workflows) {
            sb.append("  - ").append(w.getName())
                    .append(" | ID: ").append(w.getId())
                    .append(" | Status: ").append(w.getStatus())
                    .append(" | Step: ").append(w.getCurrentStepIndex() + 1).append("/").append(w.getTotalSteps())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String cancelWorkflow(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        WorkflowResponse w = workflowService.cancelWorkflow(UUID.fromString(id));
        return "Workflow " + id + " cancelled. Status: " + w.getStatus();
    }

    private String retryWorkflowStep(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        int stepIndex = parseInt(args.get("stepIndex"), -1);
        if (stepIndex < 0) return error("Missing or invalid required parameter: stepIndex");
        WorkflowResponse w = workflowService.retryStep(UUID.fromString(id), stepIndex);
        return "Workflow " + id + " step " + stepIndex + " retried. Status: " + w.getStatus();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static int parseInt(Object v, int def) {
        if (v == null) return def;
        try {
            return (v instanceof Number n) ? n.intValue() : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
