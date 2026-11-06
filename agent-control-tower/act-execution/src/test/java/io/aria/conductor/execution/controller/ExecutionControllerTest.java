package io.aria.conductor.execution.controller;

import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.SessionStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.engine.SessionStateManager;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static io.aria.conductor.test.TestDataBuilder.anAgentSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionControllerTest extends WebMvcTestBase {

    private final AgentLoopEngine loopEngine = mock(AgentLoopEngine.class);
    private final SessionStateManager sessionStateManager = mock(SessionStateManager.class);
    private final MockMvc mvc = mockMvcFor(new ExecutionController(loopEngine, sessionStateManager));

    @Test
    void startRun_returns202AndDelegatesToEngine() throws Exception {
        UUID runId = UUID.randomUUID();

        mvc.perform(post("/api/v1/execution/start/" + runId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("STARTED"));

        ArgumentCaptor<UUID> runIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(loopEngine).startRun(runIdCaptor.capture());
        assertThat(runIdCaptor.getValue()).isEqualTo(runId);
    }

    @Test
    void startRun_unknownRun_returns404FromAdvice() throws Exception {
        UUID runId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Run", runId)).when(loopEngine).startRun(runId);

        mvc.perform(post("/api/v1/execution/start/" + runId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(runId.toString())));
    }

    @Test
    void getStatus_activeSession_returnsSessionTelemetry() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentSession session = anAgentSession().withRunId(runId).withAgentId(agentId)
                .withStatus(SessionStatus.ACTIVE).withTurnCount(7)
                .withTotalInputTokens(1200).withTotalOutputTokens(340).build();
        when(sessionStateManager.getSession(runId)).thenReturn(session);

        mvc.perform(get("/api/v1/execution/status/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.turnCount").value(7))
                .andExpect(jsonPath("$.totalInputTokens").value(1200))
                .andExpect(jsonPath("$.totalOutputTokens").value(340));
    }

    @Test
    void getStatus_unknownRun_returnsNotFoundMarkerBody() throws Exception {
        UUID runId = UUID.randomUUID();
        when(sessionStateManager.getSession(runId)).thenReturn(null);

        mvc.perform(get("/api/v1/execution/status/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void pauseRun_returns200AndDelegatesToEngine() throws Exception {
        UUID runId = UUID.randomUUID();

        mvc.perform(post("/api/v1/execution/pause/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("PAUSING"));
        verify(loopEngine).pauseRun(runId);
    }

    @Test
    void pauseRun_inactiveRun_returns409FromAdvice() throws Exception {
        UUID runId = UUID.randomUUID();
        doThrow(new IllegalStateException("Run is not active: " + runId))
                .when(loopEngine).pauseRun(runId);

        mvc.perform(post("/api/v1/execution/pause/" + runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Run is not active: " + runId));
    }

    @Test
    void resumeRun_returns200AndDelegatesToEngine() throws Exception {
        UUID runId = UUID.randomUUID();

        mvc.perform(post("/api/v1/execution/resume/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("RESUMING"));
        verify(loopEngine).resumeRun(runId);
    }

    @Test
    void resumeRun_invalidState_returns409FromAdvice() throws Exception {
        UUID runId = UUID.randomUUID();
        doThrow(new IllegalStateException("Run is not paused: " + runId))
                .when(loopEngine).resumeRun(runId);

        mvc.perform(post("/api/v1/execution/resume/" + runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    /**
     * Malformed run ids never reach the engine. The shared advice has no dedicated
     * type-mismatch handler, so the catch-all maps this to 500 — pinned here so a
     * future (intentional) remap to 400 shows up as a conscious test change.
     */
    @ParameterizedTest
    @ValueSource(strings = {"start", "pause", "resume"})
    void lifecycleEndpoints_malformedRunId_neverReachEngine(String action) throws Exception {
        mvc.perform(post("/api/v1/execution/" + action + "/not-a-uuid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
        verifyNoInteractions(loopEngine);
    }
}
