package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Workflow template tools (Phase 2 core loop). Thin wrappers over the same
 * services the REST controllers call — REST/dashboard and MCP stay at parity.
 * Error types mirror GlobalExceptionHandler's REST status mapping (409 CONFLICT
 * for InvalidStateTransitionException/IllegalStateException).
 */
@Component
@RequiredArgsConstructor
public class WorkflowTools implements McpTool {

    private final WorkflowTemplateService workflowTemplateService;
    private final WorkflowService workflowService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_workflow_templates",
            description = "List APPROVED workflow templates. Optional intent keywords filter by name/description; blank lists all.")
    public String listWorkflowTemplates(
            @ToolParam(description = "Intent keywords, or blank for all", required = false) String userIntent) {
        try {
            return ToolResponses.ok(workflowTemplateService.findMatchingTemplates(userIntent));
        } catch (Exception e) {
            return ToolResponses.error("TEMPLATE_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "instantiate_workflow_template",
            description = "Instantiate an APPROVED workflow template into a runnable chain. Returns the chain JSON including id. Use list_workflow_templates first to obtain templateId.")
    public String instantiateWorkflowTemplate(
            @ToolParam(description = "KnowledgeItem id of the APPROVED WORKFLOW template") UUID templateId,
            @ToolParam(description = "Template parameters, e.g. issueRef and repoUrl", required = false)
            Map<String, String> parameters) {
        try {
            WorkflowResponse chain = workflowTemplateService.instantiateTemplate(
                    templateId, parameters == null ? Map.of() : parameters);
            return ToolResponses.ok(chain);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            // Parity with GlobalExceptionHandler: both map to 409 CONFLICT over REST.
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("INSTANTIATION_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_workflow",
            description = "Get a workflow chain by id: status (PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED), steps with runIds and output previews.")
    public String getWorkflow(@ToolParam(description = "Chain id") UUID chainId) {
        try {
            WorkflowResponse chain = workflowService.getWorkflow(chainId);
            return ToolResponses.ok(chain);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("WORKFLOW_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
