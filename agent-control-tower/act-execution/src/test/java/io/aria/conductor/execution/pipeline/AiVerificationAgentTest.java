package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiVerificationAgentTest {

    @Mock private LlmClient llmClient;
    @Mock private ObjectProvider<LlmClient> llmClientProvider;

    private AiVerificationAgent agent;

    @BeforeEach
    void setUp() {
        lenient().when(llmClientProvider.getIfAvailable()).thenReturn(llmClient);
        agent = new AiVerificationAgent(llmClientProvider);
    }

    @Test
    void verify_lowRiskRead_skipsLlmAndReturnsPass() {
        Action read = new Action("list_items", ActionType.READ, "{}", "tc-1");
        ActionClassification low = ActionClassification.lowRisk("READ");

        AiVerificationResult result = agent.verify(read, low, ctx());

        assertThat(result.isPass()).isTrue();
        assertThat(result.reasoning()).contains("low risk");
        verify(llmClient, never()).complete(any());
    }

    @Test
    void verify_highRisk_callsLlmAndReturnsParsedPass() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("PASS\nTool call looks safe.", 12, 8, "stop", List.of()));
        Action drop = new Action("drop_table", ActionType.HIGH_RISK, "{\"t\":\"x\"}", "tc-2");

        AiVerificationResult result = agent.verify(drop, ActionClassification.highRisk("HIGH_RISK"), ctx());

        assertThat(result.outcome()).isEqualTo(AiVerificationResult.VerificationOutcome.PASS);
        verify(llmClient, times(1)).complete(any(LlmRequest.class));
    }

    @Test
    void verify_highRisk_llmFails_returnsParsedFail() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("FAIL\nDrops production data.", 12, 8, "stop", List.of()));

        AiVerificationResult result = agent.verify(
                new Action("drop_users", ActionType.HIGH_RISK, "{}", "tc-3"),
                ActionClassification.highRisk("HIGH_RISK"),
                ctx());

        assertThat(result.isFail()).isTrue();
        assertThat(result.reasoning()).contains("Drops production data");
    }

    @Test
    void verify_executeAction_returnsWarn() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("WARN\nAmbiguous shell command.", 5, 5, "stop", List.of()));

        AiVerificationResult result = agent.verify(
                new Action("run_shell", ActionType.EXECUTE, "ls /", "tc-4"),
                ActionClassification.mediumRisk("EXECUTE"),
                ctx());

        assertThat(result.outcome()).isEqualTo(AiVerificationResult.VerificationOutcome.WARN);
    }

    @Test
    void verify_llmThrows_returnsPassAsFireAndForget() {
        when(llmClient.complete(any(LlmRequest.class)))
                .thenThrow(new RuntimeException("network down"));

        AiVerificationResult result = agent.verify(
                new Action("delete_thing", ActionType.HIGH_RISK, "{}", "tc-5"),
                ActionClassification.highRisk("HIGH_RISK"),
                ctx());

        assertThat(result.isPass()).isTrue();
        assertThat(result.reasoning()).contains("LLM error");
    }

    @Test
    void verify_llmUnavailable_returnsPass() {
        when(llmClientProvider.getIfAvailable()).thenReturn(null);

        AiVerificationResult result = agent.verify(
                new Action("delete_thing", ActionType.HIGH_RISK, "{}", "tc-6"),
                ActionClassification.highRisk("HIGH_RISK"),
                ctx());

        assertThat(result.isPass()).isTrue();
        assertThat(result.reasoning()).contains("LLM unavailable");
    }

    @Test
    void verify_sameTurn_cachedAndCallsLlmOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        when(llmClient.complete(any(LlmRequest.class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            return new LlmResponse("PASS\nok", 1, 1, "stop", List.of());
        });

        RunContext context = ctx();
        Action sameAction = new Action("delete_x", ActionType.HIGH_RISK, "{}", "tc-cache");
        ActionClassification high = ActionClassification.highRisk("HIGH_RISK");

        agent.verify(sameAction, high, context);
        agent.verify(sameAction, high, context);
        agent.verify(sameAction, high, context);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void parse_blankResponse_defaultsToPass() {
        assertThat(agent.parse(null).isPass()).isTrue();
        assertThat(agent.parse("").isPass()).isTrue();
        assertThat(agent.parse("   ").isPass()).isTrue();
    }

    @Test
    void parse_unknownFirstLine_defaultsToPass() {
        AiVerificationResult result = agent.parse("MAYBE?\nnot sure");
        assertThat(result.isPass()).isTrue();
        assertThat(result.reasoning()).contains("Unparsed");
    }

    private RunContext ctx() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Agent agent = Agent.builder().id(agentId).name("test").build();
        AgentSession session = AgentSession.builder().runId(runId).agentId(agentId).build();
        return new RunContext(runId, agentId, agent, session);
    }
}
