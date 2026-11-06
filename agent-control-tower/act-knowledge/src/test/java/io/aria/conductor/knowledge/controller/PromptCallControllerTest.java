package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.knowledge.dto.PromptCallStatsResponse;
import io.aria.conductor.knowledge.service.SelfImprovementService;
import io.aria.conductor.test.TestDataBuilder;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PromptCallControllerTest extends WebMvcTestBase {

    private final SelfImprovementService selfImprovementService = mock(SelfImprovementService.class);
    private final MockMvc mvc = mockMvcFor(new PromptCallController(selfImprovementService));

    // -----------------------------------------------------------------
    // GET /api/v1/prompt-calls
    // -----------------------------------------------------------------

    @Test
    void listPromptCalls_noFilters_passesNullsAndReturnsBody() throws Exception {
        PromptCall call = TestDataBuilder.aPromptCall()
                .withId(7L)
                .withProvider("openai")
                .withModel("gpt-4o-mini")
                .withOutcome("success")
                .build();
        when(selfImprovementService.listPromptCalls(null, null)).thenReturn(List.of(call));

        mvc.perform(get("/api/v1/prompt-calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$[0].outcome").value("success"));

        verify(selfImprovementService).listPromptCalls(isNull(), isNull());
    }

    @Test
    void listPromptCalls_agentIdParam_bindsUuidAndFilters() throws Exception {
        UUID agentId = UUID.randomUUID();
        PromptCall call = TestDataBuilder.aPromptCall()
                .withAgentId(agentId)
                .withInputTokens(120)
                .withOutputTokens(80)
                .build();
        when(selfImprovementService.listPromptCalls(agentId, null)).thenReturn(List.of(call));

        mvc.perform(get("/api/v1/prompt-calls").param("agentId", agentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$[0].inputTokens").value(120))
                .andExpect(jsonPath("$[0].outputTokens").value(80));

        verify(selfImprovementService).listPromptCalls(eq(agentId), isNull());
    }

    @Test
    void listPromptCalls_runIdParam_bindsUuidAndFilters() throws Exception {
        UUID runId = UUID.randomUUID();
        PromptCall call = TestDataBuilder.aPromptCall()
                .withRunId(runId)
                .withLatencyMs(432)
                .build();
        when(selfImprovementService.listPromptCalls(null, runId)).thenReturn(List.of(call));

        mvc.perform(get("/api/v1/prompt-calls").param("runId", runId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(runId.toString()))
                .andExpect(jsonPath("$[0].latencyMs").value(432));

        verify(selfImprovementService).listPromptCalls(isNull(), eq(runId));
    }

    @Test
    void listPromptCalls_noData_returnsEmptyArray() throws Exception {
        when(selfImprovementService.listPromptCalls(null, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/prompt-calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -----------------------------------------------------------------
    // GET /api/v1/prompt-calls/stats
    // -----------------------------------------------------------------

    @Test
    void getStats_returnsAggregationFields() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(selfImprovementService.getPromptCallStats(agentId)).thenReturn(
                PromptCallStatsResponse.builder()
                        .agentId(agentId)
                        .totalCalls(3)
                        .totalInputTokens(450)
                        .totalOutputTokens(210)
                        .build());

        mvc.perform(get("/api/v1/prompt-calls/stats").param("agentId", agentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.totalCalls").value(3))
                .andExpect(jsonPath("$.totalInputTokens").value(450))
                .andExpect(jsonPath("$.totalOutputTokens").value(210));

        verify(selfImprovementService).getPromptCallStats(agentId);
    }

    @Test
    void getStats_agentWithNoCalls_returnsZeroedAggregates() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(selfImprovementService.getPromptCallStats(agentId)).thenReturn(
                PromptCallStatsResponse.builder()
                        .agentId(agentId)
                        .totalCalls(0)
                        .totalInputTokens(0)
                        .totalOutputTokens(0)
                        .build());

        mvc.perform(get("/api/v1/prompt-calls/stats").param("agentId", agentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.totalCalls").value(0))
                .andExpect(jsonPath("$.totalInputTokens").value(0))
                .andExpect(jsonPath("$.totalOutputTokens").value(0));
    }
}
