package io.aria.conductor.execution.tool;

import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.sandbox.SandboxRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression guard for #26: even when the RunContext has no workspace dir populated,
 * the engine must provision one via the shared WorkspaceManager contract and inject
 * _workspaceDir into workspace-aware handlers (git pack, file tools).
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutionEngineTest {

    @Mock
    ToolDefinitionRepository toolRepo;

    @Mock
    SandboxRunner sandboxRunner;

    @TempDir
    Path tempDir;

    @Test
    void injectsWorkspaceDir_whenContextHasNone() {
        WorkspaceManager workspaceManager = new WorkspaceManager(tempDir.toString());
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        ToolHandler capturingHandler = args -> {
            captured.set(args);
            return "ok";
        };
        ToolExecutionEngine engine = new ToolExecutionEngine(
                toolRepo, sandboxRunner, Map.of("testHandler", capturingHandler), workspaceManager);

        ToolDefinition tool = org.mockito.Mockito.mock(ToolDefinition.class);
        when(tool.getName()).thenReturn("test_tool");
        when(tool.getHandlerClass()).thenReturn("testHandler");
        when(tool.isEnabled()).thenReturn(true);
        when(tool.getStatus()).thenReturn(null); // legacy → APPROVED-equivalent
        when(tool.getKind()).thenReturn(null);   // not MCP/AGENT
        when(tool.getSandboxMode()).thenReturn("NONE");
        when(toolRepo.findByName("test_tool")).thenReturn(Optional.of(tool));

        UUID runId = UUID.randomUUID();
        RunContext ctx = new RunContext(runId, UUID.randomUUID(), null, null, 50);
        assertThat(ctx.getWorkspaceDir()).isNull(); // precondition: not provisioned yet

        ToolExecutionResult result = engine.execute("test_tool", Map.of(), ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(captured.get()).containsKey("_workspaceDir");
        String wsDir = (String) captured.get().get("_workspaceDir");
        assertThat(wsDir).contains(runId.toString());
        assertThat(Files.isDirectory(Path.of(wsDir))).isTrue();
        assertThat(ctx.getWorkspaceDir()).isEqualTo(wsDir); // populated on the context
    }
}
