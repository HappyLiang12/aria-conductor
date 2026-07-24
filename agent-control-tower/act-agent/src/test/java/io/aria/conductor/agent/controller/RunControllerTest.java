package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunControllerTest extends WebMvcTestBase {

    private final RunService runService = mock(RunService.class);
    private final MockMvc mvc = mockMvcFor(new RunController(runService));

    private RunResponse run(UUID id, UUID agentId, RunStatus status) {
        return RunResponse.builder().id(id).agentId(agentId).status(status)
                .promptSeed("seed").maxIterations(5).build();
    }

    @Test
    void createRun_returns201WithBody() throws Exception {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(run(id, agentId, RunStatus.PENDING));

        mvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CreateRunRequest.builder()
                                .agentId(agentId).promptSeed("do it").maxIterations(5).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createRun_missingAgentId_returns400() throws Exception {
        mvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("promptSeed", "hello"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRun_blankPromptSeed_returns400() throws Exception {
        mvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("agentId", UUID.randomUUID().toString(), "promptSeed", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRun_retiredAgent_returns400() throws Exception {
        when(runService.createRun(any()))
                .thenThrow(new IllegalArgumentException("Cannot create run for retired agent"));

        mvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CreateRunRequest.builder()
                                .agentId(UUID.randomUUID()).promptSeed("x").build())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listRuns_noFilters_usesListAll() throws Exception {
        when(runService.listRuns()).thenReturn(List.of(
                run(UUID.randomUUID(), UUID.randomUUID(), RunStatus.RUNNING)));

        mvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RUNNING"));
        verify(runService).listRuns();
    }

    @Test
    void listRuns_byAgentOnly_usesAgentQuery() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(runService.listRunsByAgent(agentId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/runs").param("agentId", agentId.toString()))
                .andExpect(status().isOk());
        verify(runService).listRunsByAgent(agentId);
    }

    @Test
    void listRuns_byStatusOnly_usesStatusQuery() throws Exception {
        when(runService.listRunsByStatus(RunStatus.FAILED)).thenReturn(List.of());

        mvc.perform(get("/api/v1/runs").param("status", "FAILED"))
                .andExpect(status().isOk());
        verify(runService).listRunsByStatus(RunStatus.FAILED);
    }

    @Test
    void listRuns_byAgentAndStatus_usesCompoundQuery() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(runService.listRunsByAgentAndStatus(agentId, RunStatus.PAUSED)).thenReturn(List.of());

        mvc.perform(get("/api/v1/runs")
                        .param("agentId", agentId.toString())
                        .param("status", "PAUSED"))
                .andExpect(status().isOk());
        verify(runService).listRunsByAgentAndStatus(agentId, RunStatus.PAUSED);
    }

    @Test
    void getRun_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.getRun(id)).thenReturn(run(id, UUID.randomUUID(), RunStatus.COMPLETED));

        mvc.perform(get("/api/v1/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getRun_returns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.getRun(id)).thenThrow(new ResourceNotFoundException("Run", id));

        mvc.perform(get("/api/v1/runs/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void pauseRun_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.pauseRun(id)).thenReturn(run(id, UUID.randomUUID(), RunStatus.PAUSED));

        mvc.perform(post("/api/v1/runs/" + id + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void pauseRun_invalidTransition_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.pauseRun(id))
                .thenThrow(new InvalidStateTransitionException("Run", "PENDING", "PAUSED"));

        mvc.perform(post("/api/v1/runs/" + id + "/pause"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void resumeRun_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.resumeRun(id)).thenReturn(run(id, UUID.randomUUID(), RunStatus.RUNNING));

        mvc.perform(post("/api/v1/runs/" + id + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void cancelRun_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.cancelRun(id)).thenReturn(run(id, UUID.randomUUID(), RunStatus.CANCELLED));

        mvc.perform(post("/api/v1/runs/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelRun_invalidTransition_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.cancelRun(id))
                .thenThrow(new InvalidStateTransitionException("Run", "COMPLETED", "CANCELLED"));

        mvc.perform(post("/api/v1/runs/" + id + "/cancel"))
                .andExpect(status().isConflict());
    }
}
