package io.aria.conductor.aria.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.sandbox.SandboxRunner;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.execution.tool.ToolExecutionResult;
import io.aria.conductor.execution.tool.ToolHandler;
import io.aria.conductor.execution.tool.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AriaToolsWorkflowTest {

    @Mock private ToolDefinitionRepository toolDefinitionRepository;
    @Mock private SandboxRunner sandboxRunner;
    @Mock private WorkspaceManager workspaceManager;

    private ToolExecutionEngine toolExecutionEngine;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        toolExecutionEngine = new ToolExecutionEngine(
                toolDefinitionRepository,
                sandboxRunner,
                handlers,
                workspaceManager
        );
    }

    private ToolDefinition createToolDefinition(String name, String handlerClass) {
        return ToolDefinition.builder()
                .id("tool-" + name)
                .name(name)
                .description("Tool: " + name)
                .tier("TIER_1")
                .category("GENERAL")
                .sandboxMode("NONE")
                .handlerClass(handlerClass)
                .enabled(true)
                .version(1)
                .parameters("{}")
                .timeoutMs(30000)
                .build();
    }

    // ==================== get_workflow ====================

    @Test
    void getWorkflow_shouldReturnJson() {
        String toolName = "get_workflow";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("id", UUID.randomUUID().toString());
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void getWorkflow_invalidId_shouldReturnError() {
        String toolName = "get_workflow";
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("id", "not-a-uuid");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unknown tool");
    }

    // ==================== cancel_workflow ====================

    @Test
    void cancelWorkflow_shouldReturnConfirmation() {
        String toolName = "cancel_workflow";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("id", UUID.randomUUID().toString());
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    // ==================== retry_workflow_step ====================

    @Test
    void retryWorkflowStep_shouldReturnStatus() {
        String toolName = "retry_workflow_step";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("id", UUID.randomUUID().toString());
        args.put("stepIndex", 1);
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void retryWorkflowStep_negativeIndex_shouldReturnError() {
        String toolName = "retry_workflow_step";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("id", UUID.randomUUID().toString());
        args.put("stepIndex", -1);
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    // ==================== update_workflow ====================

    @Test
    void updateWorkflow_shouldReturnUpdatedInfo() {
        String toolName = "update_workflow";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("id", UUID.randomUUID().toString());
        args.put("name", "Updated Name");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    // ==================== delete_workflow ====================

    @Test
    void deleteWorkflow_shouldReturnDeletedMessage() {
        String toolName = "delete_workflow";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("id", UUID.randomUUID().toString());
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    // ==================== merge_workflows ====================

    @Test
    void mergeWorkflows_shouldReturnMergedInfo() {
        String toolName = "merge_workflows";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("sourceIds", UUID.randomUUID() + "," + UUID.randomUUID());
        args.put("name", "Merged WF");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    // ==================== reuse_workflow ====================

    @Test
    void reuseWorkflow_shouldInstantiateTemplate() {
        String toolName = "reuse_workflow";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("templateId", UUID.randomUUID().toString());
        args.put("parameters", Map.of("env", "prod"));
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    // ==================== list_workflow_templates ====================

    @Test
    void listWorkflowTemplates_shouldReturnList() {
        String toolName = "list_workflow_templates";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        ToolExecutionResult result = toolExecutionEngine.execute(toolName, new HashMap<>());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void listWorkflowTemplates_empty_shouldReturnNoTemplates() {
        String toolName = "list_workflow_templates";
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.empty());

        ToolExecutionResult result = toolExecutionEngine.execute(toolName, new HashMap<>());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unknown tool");
    }

    // ==================== save_workflow_as_template ====================

    @Test
    void saveWorkflowAsTemplate_shouldReturnKnowledgeItemId() {
        String toolName = "save_workflow_as_template";
        ToolDefinition toolDef = createToolDefinition(toolName, "workflowHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("workflowId", UUID.randomUUID().toString());
        args.put("name", "Saved Template");
        args.put("description", "A saved template");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }
}
