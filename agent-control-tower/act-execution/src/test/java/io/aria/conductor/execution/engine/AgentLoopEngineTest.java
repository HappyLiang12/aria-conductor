package io.aria.conductor.execution.engine;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.llm.LlmToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineTest {

    // ── escapeJson round-trip via parseTrajectoryToolCalls ──────────────

    @Test
    void shouldRoundTripToolCallsWithNormalArgs() {
        String toolCallsJson = AgentLoopEngine.buildToolCallsJson(List.of(
                new LlmToolCall("call_1", "search", "{\"query\":\"cats\"}")
        ));
        List<LlmToolCall> parsed = AgentLoopEngine.parseTrajectoryToolCalls(toolCallsJson);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).id()).isEqualTo("call_1");
        assertThat(parsed.get(0).name()).isEqualTo("search");
        assertThat(parsed.get(0).arguments()).isEqualTo("{\"query\":\"cats\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "line1\nline2",
            "tab\there",
            "return\rhere",
            "back\\slash",
            "quote\"here",
            "混合\n\t\r\\\"控制字符"
    })
    void shouldRoundTripToolCallsWithControlCharacters(String argsContent) {
        String jsonArgs = "{\"text\":\"" + argsContent + "\"}";
        String toolCallsJson = AgentLoopEngine.buildToolCallsJson(List.of(
                new LlmToolCall("call_x", "test_tool", jsonArgs)
        ));
        List<LlmToolCall> parsed = AgentLoopEngine.parseTrajectoryToolCalls(toolCallsJson);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).arguments()).isEqualTo(jsonArgs);
    }

    // ── parseTrajectoryToolCalls edge cases ──────────────────────────────

    @Test
    void shouldReturnEmptyOnNullInput() {
        assertThat(AgentLoopEngine.parseTrajectoryToolCalls(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnBlankInput() {
        assertThat(AgentLoopEngine.parseTrajectoryToolCalls("   ")).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnNonArrayJson() {
        assertThat(AgentLoopEngine.parseTrajectoryToolCalls("{\"not\":\"array\"}")).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnInvalidJson() {
        assertThat(AgentLoopEngine.parseTrajectoryToolCalls("not json at all")).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnEmptyArray() {
        List<LlmToolCall> result = AgentLoopEngine.parseTrajectoryToolCalls("[]");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleMultipleToolCalls() {
        String toolCallsJson = AgentLoopEngine.buildToolCallsJson(List.of(
                new LlmToolCall("id1", "tool_a", "{}"),
                new LlmToolCall("id2", "tool_b", "{\"x\":1}")
        ));
        List<LlmToolCall> parsed = AgentLoopEngine.parseTrajectoryToolCalls(toolCallsJson);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).id()).isEqualTo("id1");
        assertThat(parsed.get(1).id()).isEqualTo("id2");
    }

    // ── parseMaxIterationsFromConfig: run-level value is a hard cap ─────

    private static Agent agentWithConfig(String config) {
        Agent agent = new Agent();
        agent.setConfig(config);
        return agent;
    }

    @Test
    void runLevelMaxIterationsShouldCapAgentConfig() {
        // P0 regression: user sets maxIterations=1 but agent config says 15 -> must be 1
        Agent agent = agentWithConfig("{\"maxToolCallRounds\":15}");
        assertThat(AgentLoopEngine.parseMaxIterationsFromConfig(agent, 1)).isEqualTo(1);
    }

    @Test
    void runLevelMaxIterationsShouldCapLargerAgentConfig() {
        Agent agent = agentWithConfig("{\"maxToolCallRounds\":200}");
        assertThat(AgentLoopEngine.parseMaxIterationsFromConfig(agent, 50)).isEqualTo(50);
    }

    @Test
    void zeroRunMaxIterationsShouldUseAgentConfig() {
        // 0 means "not set by caller" -> fall back to agent config value
        Agent agent = agentWithConfig("{\"maxToolCallRounds\":15}");
        assertThat(AgentLoopEngine.parseMaxIterationsFromConfig(agent, 0)).isEqualTo(15);
    }

    @Test
    void zeroRunMaxIterationsAndNoConfigShouldUseGlobalDefault() {
        Agent agent = agentWithConfig(null);
        assertThat(AgentLoopEngine.parseMaxIterationsFromConfig(agent, 0)).isEqualTo(50);
    }

    @Test
    void agentConfigUsedWhenLargerThanRunLevel() {
        // run-level cap only lowers, never raises, the agent config value
        Agent agent = agentWithConfig("{\"maxToolCallRounds\":5}");
        assertThat(AgentLoopEngine.parseMaxIterationsFromConfig(agent, 100)).isEqualTo(5);
    }

    @Test
    void invalidConfigShouldFallBackToRunLevel() {
        Agent agent = agentWithConfig("not json");
        assertThat(AgentLoopEngine.parseMaxIterationsFromConfig(agent, 7)).isEqualTo(7);
    }

    // ── toolResultContent: human-denial feedback (#32) ──────────────────

    @Test
    void deniedResultShouldProduceExplicitNonRetryableMessage() {
        String content = AgentLoopEngine.toolResultContent(
                io.aria.conductor.execution.pipeline.ActionResult.denied("reviewer blocked the push"));
        assertThat(content).startsWith("DENIED BY HUMAN REVIEWER:");
        assertThat(content).contains("reviewer blocked the push");
        assertThat(content).contains("Do not retry");
    }

    @Test
    void successResultShouldReturnOutput() {
        String content = AgentLoopEngine.toolResultContent(
                io.aria.conductor.execution.pipeline.ActionResult.success("done"));
        assertThat(content).isEqualTo("done");
    }

    @Test
    void failedResultShouldUseErrorPrefix() {
        String content = AgentLoopEngine.toolResultContent(
                io.aria.conductor.execution.pipeline.ActionResult.failed("boom"));
        assertThat(content).isEqualTo("ERROR: boom");
    }
}
