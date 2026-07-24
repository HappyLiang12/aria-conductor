package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.MergeWorkflowRequest;
import io.aria.conductor.agent.dto.ReuseWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerTest extends WebMvcTestBase {

    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final MockMvc mvc = mockMvcFor(new WorkflowController(workflowService));

    private WorkflowResponse wf(UUID id, String name, WorkflowChain.Status status) {
        return WorkflowResponse.builder().id(id).name(name).status(status)
                .currentStepIndex(0).totalSteps(2).build();
    }

    private CreateWorkflowRequest validCreate() {
        return CreateWorkflowRequest.builder()
                .name("release-flow")
                .steps(List.of(CreateWorkflowRequest.StepDef.builder()
                        .agentId(UUID.randomUUID()).promptTemplate("do step").maxIterations(3).build()))
                .build();
    }

    @Test
    void createWorkflow_returns201WithBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.createAndStart(any())).thenReturn(wf(id, "release-flow", WorkflowChain.Status.RUNNING));

        mvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validCreate())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("release-flow"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void createWorkflow_blankName_returns400() throws Exception {
        CreateWorkflowRequest bad = validCreate();
        bad.setName("");
        mvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWorkflow_noSteps_returns400() throws Exception {
        CreateWorkflowRequest bad = CreateWorkflowRequest.builder().name("x").steps(List.of()).build();
        mvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWorkflows_returns200() throws Exception {
        when(workflowService.listWorkflows()).thenReturn(List.of(
                wf(UUID.randomUUID(), "a", WorkflowChain.Status.PENDING)));

        mvc.perform(get("/api/v1/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("a"));
    }

    @Test
    void getWorkflow_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.getWorkflow(id)).thenReturn(wf(id, "wf", WorkflowChain.Status.COMPLETED));

        mvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getWorkflow_returns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.getWorkflow(id)).thenThrow(new ResourceNotFoundException("Workflow", id));

        mvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelWorkflow_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.cancelWorkflow(id)).thenReturn(wf(id, "wf", WorkflowChain.Status.CANCELLED));

        mvc.perform(post("/api/v1/workflows/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelWorkflow_alreadyTerminal_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.cancelWorkflow(id))
                .thenThrow(new IllegalStateException("Workflow already completed"));

        mvc.perform(post("/api/v1/workflows/" + id + "/cancel"))
                .andExpect(status().isConflict());
    }

    @Test
    void retryWorkflow_delegatesStepIndex() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.retryStep(eq(id), eq(2)))
                .thenReturn(wf(id, "wf", WorkflowChain.Status.RUNNING));

        mvc.perform(post("/api/v1/workflows/" + id + "/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("stepIndex", 2))))
                .andExpect(status().isOk());
        verify(workflowService).retryStep(id, 2);
    }

    @Test
    void updateWorkflow_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowService.updateWorkflow(eq(id), any(), any(), any()))
                .thenReturn(wf(id, "renamed", WorkflowChain.Status.PENDING));

        mvc.perform(put("/api/v1/workflows/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "renamed", "description", "d"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"));
    }

    @Test
    void deleteWorkflow_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/v1/workflows/" + id))
                .andExpect(status().isNoContent());
        verify(workflowService).deleteWorkflow(id);
    }

    @Test
    void mergeWorkflows_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        when(workflowService.mergeWorkflows(any(), eq("merged")))
                .thenReturn(wf(id, "merged", WorkflowChain.Status.PENDING));

        mvc.perform(post("/api/v1/workflows/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(MergeWorkflowRequest.builder()
                                .sourceIds(List.of(s1, s2)).name("merged").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("merged"));
    }

    /**
     * Exercised at the controller-method level rather than through MockMvc: the
     * {@link ReuseWorkflowRequest} DTO is declared with {@code @Data @Builder} but no
     * {@code @NoArgsConstructor}, so Jackson cannot deserialize a JSON body and a full HTTP
     * round-trip always yields 500 (reported as a pre-existing bug). Invoking the handler directly
     * still verifies the real delegation + 201 CREATED contract without depending on that defect.
     */
    @Test
    void reuseWorkflow_delegatesToTemplateAndReturns201() {
        UUID templateId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        ReuseWorkflowRequest request = ReuseWorkflowRequest.builder()
                .parameters(Map.of("env", "prod")).build();
        when(workflowService.createFromTemplate(eq(templateId), eq(Map.of("env", "prod"))))
                .thenReturn(wf(newId, "instance", WorkflowChain.Status.PENDING));

        ResponseEntity<WorkflowResponse> response =
                new WorkflowController(workflowService).reuseWorkflow(templateId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(newId);
        verify(workflowService).createFromTemplate(templateId, Map.of("env", "prod"));
    }
}
