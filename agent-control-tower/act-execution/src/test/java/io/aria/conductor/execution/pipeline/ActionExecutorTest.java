package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.execution.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionExecutorTest {

    @Mock
    AdkProviderRegistry adkProviderRegistry;

    @Mock
    ToolExecutionEngine toolExecutionEngine;

    @Test
    void execute_shouldReturnSuccess_whenToolSucceeds() {
        var executor = new ActionExecutor(adkProviderRegistry, toolExecutionEngine);
        Action action = new Action("search", ActionType.READ, "{\"query\":\"test\"}", "call-1");
        RunContext runContext = buildContext();
        when(toolExecutionEngine.execute(eq("search"), anyMap()))
                .thenReturn(ToolExecutionResult.success("found 3 results"));

        ActionResult result = executor.execute(action, runContext);

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        assertThat(result.output()).isEqualTo("found 3 results");
    }

    @Test
    void execute_shouldReturnFailed_whenToolFails() {
        var executor = new ActionExecutor(adkProviderRegistry, toolExecutionEngine);
        Action action = new Action("delete", ActionType.WRITE, "{}", "call-2");
        RunContext runContext = buildContext();
        when(toolExecutionEngine.execute(eq("delete"), anyMap()))
                .thenReturn(ToolExecutionResult.failed("permission denied"));

        ActionResult result = executor.execute(action, runContext);

        assertThat(result.status()).isEqualTo(ActionResult.Status.FAILED);
        assertThat(result.error()).isEqualTo("permission denied");
    }

    @Test
    void execute_shouldHandleNullOutput() {
        var executor = new ActionExecutor(adkProviderRegistry, toolExecutionEngine);
        Action action = new Action("search", ActionType.READ, "{}", "call-3");
        RunContext runContext = buildContext();
        when(toolExecutionEngine.execute(eq("search"), anyMap()))
                .thenReturn(ToolExecutionResult.success(null));

        ActionResult result = executor.execute(action, runContext);

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        assertThat(result.output()).isEmpty();
    }

    @Test
    void execute_shouldHandleEmptyArguments() {
        var executor = new ActionExecutor(adkProviderRegistry, toolExecutionEngine);
        Action action = new Action("list", ActionType.READ, null, "call-4");
        RunContext runContext = buildContext();
        when(toolExecutionEngine.execute(eq("list"), eq(Map.of())))
                .thenReturn(ToolExecutionResult.success("ok"));

        ActionResult result = executor.execute(action, runContext);

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
    }

    @Test
    void execute_shouldFailOnMalformedJsonArguments() {
        var executor = new ActionExecutor(adkProviderRegistry, toolExecutionEngine);
        Action action = new Action("search", ActionType.READ, "{invalid json}", "call-5");
        RunContext runContext = buildContext();

        ActionResult result = executor.execute(action, runContext);

        assertThat(result.status()).isEqualTo(ActionResult.Status.FAILED);
        assertThat(result.error()).contains("Invalid JSON arguments");
    }

    private RunContext buildContext() {
        Agent agent = Agent.builder()
                .id(UUID.randomUUID())
                .name("test-agent")
                .provider("test")
                .model("test-model")
                .build();
        return new RunContext(UUID.randomUUID(), agent.getId(), agent, null, 10);
    }
}
