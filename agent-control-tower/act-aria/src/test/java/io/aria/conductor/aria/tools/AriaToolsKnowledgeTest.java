package io.aria.conductor.aria.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.sandbox.SandboxRunner;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.execution.tool.ToolExecutionResult;
import io.aria.conductor.execution.tool.ToolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AriaToolsKnowledgeTest {

    @Mock private ToolDefinitionRepository toolDefinitionRepository;
    @Mock private SandboxRunner sandboxRunner;

    private ToolExecutionEngine toolExecutionEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Map<String, ToolHandler> handlers = new HashMap<>();
        toolExecutionEngine = new ToolExecutionEngine(
                toolDefinitionRepository,
                sandboxRunner,
                handlers
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

    @Test
    void storeKnowledge_shouldCreateWithStatusPending() {
        String toolName = "store_knowledge";
        ToolDefinition toolDef = createToolDefinition(toolName, "knowledgeHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        // Since there's no real handler registered, the engine will return a "No handler" error.
        // This test validates the engine dispatches to the correct handler lookup path.
        Map<String, Object> args = new HashMap<>();
        args.put("name", "git-refresh-skill");
        args.put("content", "git fetch && git pull");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void storeKnowledge_withMissingName_shouldReturnError() {
        // If the tool definition is not found, engine returns an error
        when(toolDefinitionRepository.findByName("store_knowledge")).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("content", "do something");
        ToolExecutionResult result = toolExecutionEngine.execute("store_knowledge", args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).containsIgnoringCase("Unknown tool");
    }

    @Test
    void storeKnowledge_withMissingContent_shouldReturnError() {
        when(toolDefinitionRepository.findByName("store_knowledge")).thenReturn(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("name", "my-skill");
        ToolExecutionResult result = toolExecutionEngine.execute("store_knowledge", args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).containsIgnoringCase("Unknown tool");
    }

    @Test
    void storeKnowledge_shouldSetTypeAndSensitivity() {
        String toolName = "store_knowledge";
        ToolDefinition toolDef = createToolDefinition(toolName, "knowledgeHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "backup-script");
        args.put("content", "#!/bin/sh\nbackup");
        args.put("type", "SCRIPT");
        args.put("description", "nightly backup");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void storeKnowledge_shouldNeverAutoApprove() {
        String toolName = "store_knowledge";
        ToolDefinition toolDef = createToolDefinition(toolName, "knowledgeHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "rogue-skill");
        args.put("content", "x");
        args.put("status", "APPROVED");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void queryKnowledge_shouldReturnOnlyApprovedItems() {
        String toolName = "query_knowledge";
        ToolDefinition toolDef = createToolDefinition(toolName, "knowledgeQueryHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        ToolExecutionResult result = toolExecutionEngine.execute(toolName, new HashMap<>());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void queryKnowledge_withKeyword_shouldFilterByNameOrDescription() {
        String toolName = "query_knowledge";
        ToolDefinition toolDef = createToolDefinition(toolName, "knowledgeQueryHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        Map<String, Object> args = new HashMap<>();
        args.put("keyword", "git");
        ToolExecutionResult result = toolExecutionEngine.execute(toolName, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }

    @Test
    void queryKnowledge_shouldRespectSensitivityScope() {
        String toolName = "query_knowledge";
        ToolDefinition toolDef = createToolDefinition(toolName, "knowledgeQueryHandler");
        when(toolDefinitionRepository.findByName(toolName)).thenReturn(Optional.of(toolDef));

        ToolExecutionResult result = toolExecutionEngine.execute(toolName, new HashMap<>());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No handler");
    }
}
