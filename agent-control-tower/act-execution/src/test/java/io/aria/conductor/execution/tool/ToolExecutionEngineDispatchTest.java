package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.PackKind;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.sandbox.SandboxRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dispatch/guard tests for {@link ToolExecutionEngine} — the registration and routing gate in
 * front of every tool handler. Verifies: unknown-tool refusal, disabled-tool refusal, the
 * APPROVED-only registration pre-check, MCP/AGENT kind refusal, sandbox-mode routing (NONE →
 * handler; DOCKER/PROCESS → sandbox when available else handler fallback; unknown mode → failure),
 * and that reserved {@code _}-prefixed args are stripped before reaching the handler.
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutionEngineDispatchTest {

    @Mock private ToolDefinitionRepository toolRepo;
    @Mock private SandboxRunner sandboxRunner;
    @Mock private WorkspaceManager workspaceManager;

    private final AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    private ToolHandler capturingHandler;

    @BeforeEach
    void setUp() {
        capturingHandler = args -> {
            captured.set(args);
            return "handled";
        };
    }

    private ToolExecutionEngine engine() {
        return new ToolExecutionEngine(toolRepo, sandboxRunner,
                Map.of("myHandler", capturingHandler), workspaceManager);
    }

    /** A tool mock stubbed leniently on the full happy path; individual tests override fields. */
    private ToolDefinition happyTool(String sandboxMode) {
        ToolDefinition tool = mock(ToolDefinition.class);
        lenient().when(tool.getName()).thenReturn("my_tool");
        lenient().when(tool.isEnabled()).thenReturn(true);
        lenient().when(tool.getStatus()).thenReturn(VersionStatus.APPROVED);
        lenient().when(tool.getKind()).thenReturn(PackKind.HANDLER);
        lenient().when(tool.getSandboxMode()).thenReturn(sandboxMode);
        lenient().when(tool.getHandlerClass()).thenReturn("myHandler");
        lenient().when(toolRepo.findByName("my_tool")).thenReturn(Optional.of(tool));
        return tool;
    }

    @Test
    void execute_refusesUnknownTool() {
        when(toolRepo.findByName("ghost")).thenReturn(Optional.empty());

        ToolExecutionResult result = engine().execute("ghost", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).isEqualTo("Unknown tool: ghost");
    }

    @Test
    void execute_refusesDisabledTool() {
        ToolDefinition tool = happyTool("NONE");
        when(tool.isEnabled()).thenReturn(false);

        ToolExecutionResult result = engine().execute("my_tool", Map.of());

        assertThat(result.error()).isEqualTo("Tool is disabled: my_tool");
    }

    @Test
    void execute_refusesNonApprovedTool() {
        ToolDefinition tool = happyTool("NONE");
        when(tool.getStatus()).thenReturn(VersionStatus.PENDING);

        ToolExecutionResult result = engine().execute("my_tool", Map.of());

        assertThat(result.error()).isEqualTo("Tool not approved for execution: my_tool (status=PENDING)");
    }

    @Test
    void execute_refusesMcpKind() {
        ToolDefinition tool = happyTool("NONE");
        when(tool.getKind()).thenReturn(PackKind.MCP);

        assertThat(engine().execute("my_tool", Map.of()).error())
                .isEqualTo("MCP pack kind not yet supported (Phase 2): my_tool");
    }

    @Test
    void execute_refusesAgentKind() {
        ToolDefinition tool = happyTool("NONE");
        when(tool.getKind()).thenReturn(PackKind.AGENT);

        assertThat(engine().execute("my_tool", Map.of()).error())
                .isEqualTo("AGENT pack kind not yet supported (Phase 3): my_tool");
    }

    @Test
    void execute_dispatchesToHandler_whenSandboxModeNone() {
        happyTool("NONE");

        ToolExecutionResult result = engine().execute("my_tool", Map.of("k", "v"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("handled");
        // The engine injects the resolved tool name for dispatcher handlers.
        assertThat(captured.get()).containsEntry("toolName", "my_tool");
    }

    @Test
    void execute_stripsReservedUnderscoreArgs_beforeHandler() {
        happyTool("NONE");

        engine().execute("my_tool", Map.of("safe", "1", "_forged", "evil"));

        assertThat(captured.get()).containsEntry("safe", "1");
        assertThat(captured.get()).doesNotContainKey("_forged");
    }

    @Test
    void execute_fallsBackToHandler_whenSandboxUnavailable() {
        happyTool("DOCKER");
        when(sandboxRunner.isSandboxAvailable()).thenReturn(false);

        ToolExecutionResult result = engine().execute("my_tool", Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("handled"); // handler fallback path
    }

    @Test
    void execute_refusesUnknownSandboxMode() {
        happyTool("WORMHOLE");

        assertThat(engine().execute("my_tool", Map.of()).error())
                .isEqualTo("Unknown sandbox mode: WORMHOLE");
    }
}
