package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowToolsTest {

    @Mock WorkflowTemplateService workflowTemplateService;
    @Mock WorkflowService workflowService;
    McpProperties mcpProperties;
    WorkflowTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new WorkflowTools(workflowTemplateService, workflowService, mcpProperties);
    }

    @Test
    void listWorkflowTemplates_delegatesAndWraps() {
        KnowledgeItemResponse tpl = KnowledgeItemResponse.builder()
                .id(UUID.randomUUID()).name("development-workflow").type(null).status(null).build();
        when(workflowTemplateService.findMatchingTemplates("sdd")).thenReturn(List.of(tpl));

        String json = tools.listWorkflowTemplates("sdd");

        assertThat(json).contains("development-workflow").contains("\"ok\":true");
    }

    @Test
    void instantiateWorkflowTemplate_returnsChainJson() {
        WorkflowResponse chain = WorkflowResponse.builder()
                .id(UUID.randomUUID()).name("development-workflow-instance").build();
        when(workflowTemplateService.instantiateTemplate(any(), any())).thenReturn(chain);

        String json = tools.instantiateWorkflowTemplate(UUID.randomUUID(),
                Map.of("issueRef", "#55", "repoUrl", "https://github.com/HappyLiang12/aria-conductor.git"));

        assertThat(json).contains("\"ok\":true").contains("development-workflow-instance");
    }

    @Test
    void instantiateWorkflowTemplate_mapsValidationErrorWithoutStack_whenDebugOff() {
        when(workflowTemplateService.instantiateTemplate(any(), any()))
                .thenThrow(new IllegalArgumentException("Template requires repoUrl parameter"));

        String json = tools.instantiateWorkflowTemplate(UUID.randomUUID(), Map.of());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).contains("repoUrl");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void instantiateWorkflowTemplate_debugOn_includesStack() {
        mcpProperties.setDebug(true);
        when(workflowTemplateService.instantiateTemplate(any(), any()))
                .thenThrow(new IllegalArgumentException("Template requires repoUrl parameter"));

        String json = tools.instantiateWorkflowTemplate(UUID.randomUUID(), Map.of());

        assertThat(json).contains("stackTrace").contains("IllegalArgumentException");
    }

    @Test
    void getWorkflow_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(workflowService.getWorkflow(id)).thenThrow(
                new io.aria.conductor.common.exception.ResourceNotFoundException("WorkflowChain", id));

        String json = tools.getWorkflow(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
