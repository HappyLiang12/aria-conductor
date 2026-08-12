package io.aria.conductor.knowledge.controller;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.KnowledgeStatsResponse;
import io.aria.conductor.knowledge.dto.KnowledgeVersionResponse;
import io.aria.conductor.knowledge.dto.PromoteKnowledgeRequest;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.dto.UpdateKnowledgeRequest;
import io.aria.conductor.knowledge.service.KnowledgeService;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeControllerTest extends WebMvcTestBase {

    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final WorkflowTemplateService workflowTemplateService = mock(WorkflowTemplateService.class);
    private final MockMvc mvc = mockMvcFor(new KnowledgeController(knowledgeService, workflowTemplateService));

    private static KnowledgeItemResponse.KnowledgeItemResponseBuilder itemResponse(UUID id) {
        return KnowledgeItemResponse.builder()
                .id(id)
                .name("release-checklist")
                .type(KnowledgeType.SKILL)
                .description("How to cut a release")
                .currentVersion("v0.1.0")
                .status(KnowledgeStatus.PENDING)
                .sensitivity(Sensitivity.INTERNAL)
                .filePath("knowledge/skill/release-checklist/v0.1.0.md")
                .createdAt(Instant.parse("2026-07-01T10:00:00Z"));
    }

    private static KnowledgeVersionResponse.KnowledgeVersionResponseBuilder versionResponse(UUID versionId) {
        return KnowledgeVersionResponse.builder()
                .id(versionId)
                .version("v0.1.0")
                .status(VersionStatus.PENDING)
                .content("step 1: tag the build")
                .createdAt(Instant.parse("2026-07-01T10:00:00Z"));
    }

    // -----------------------------------------------------------------
    // POST /api/v1/knowledge (submit)
    // -----------------------------------------------------------------

    @Test
    void submitKnowledge_returns201AndMapsRequestFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.submitKnowledge(any(CreateKnowledgeRequest.class)))
                .thenReturn(itemResponse(id).build());

        mvc.perform(post("/api/v1/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "release-checklist",
                                "type", "SKILL",
                                "description", "How to cut a release",
                                "content", "step 1: tag the build",
                                "sensitivity", "CONFIDENTIAL"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("release-checklist"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<CreateKnowledgeRequest> captor = ArgumentCaptor.forClass(CreateKnowledgeRequest.class);
        verify(knowledgeService).submitKnowledge(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("release-checklist");
        assertThat(captor.getValue().getType()).isEqualTo(KnowledgeType.SKILL);
        assertThat(captor.getValue().getContent()).isEqualTo("step 1: tag the build");
        assertThat(captor.getValue().getSensitivity()).isEqualTo(Sensitivity.CONFIDENTIAL);
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of("missing name", Map.of("type", "SKILL", "content", "c"), "name"),
                Arguments.of("blank name", Map.of("name", "  ", "type", "SKILL", "content", "c"), "name"),
                Arguments.of("missing type", Map.of("name", "n", "content", "c"), "type"),
                Arguments.of("missing content", Map.of("name", "n", "type", "SKILL"), "content"),
                Arguments.of("blank content", Map.of("name", "n", "type", "SKILL", "content", ""), "content"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateBodies")
    void submitKnowledge_invalidBody_returns400WithFieldInMessage(
            String label, Map<String, Object> body, String expectedField) throws Exception {
        mvc.perform(post("/api/v1/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString(expectedField)));
        verify(knowledgeService, never()).submitKnowledge(any());
    }

    @Test
    void submitKnowledge_malformedTypeEnum_returns400() throws Exception {
        mvc.perform(post("/api/v1/knowledge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "n", "type", "NOT_A_TYPE", "content", "c"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(knowledgeService, never()).submitKnowledge(any());
    }

    // -----------------------------------------------------------------
    // GET /api/v1/knowledge (list)
    // -----------------------------------------------------------------

    @Test
    void listKnowledge_noFilters_passesNullsAndReturnsBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.listKnowledge(null, null))
                .thenReturn(List.of(itemResponse(id).build()));

        mvc.perform(get("/api/v1/knowledge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].type").value("SKILL"));

        verify(knowledgeService).listKnowledge(isNull(), isNull());
    }

    @Test
    void listKnowledge_typeAndStatusParams_bindToEnums() throws Exception {
        when(knowledgeService.listKnowledge(KnowledgeType.PROMPT, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(itemResponse(UUID.randomUUID())
                        .type(KnowledgeType.PROMPT)
                        .status(KnowledgeStatus.APPROVED)
                        .build()));

        mvc.perform(get("/api/v1/knowledge")
                        .param("type", "PROMPT")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PROMPT"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));

        verify(knowledgeService).listKnowledge(KnowledgeType.PROMPT, KnowledgeStatus.APPROVED);
    }

    @Test
    void listKnowledge_emptyResult_returnsEmptyArray() throws Exception {
        when(knowledgeService.listKnowledge(null, null)).thenReturn(List.of());

        mvc.perform(get("/api/v1/knowledge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -----------------------------------------------------------------
    // GET /api/v1/knowledge/{id}
    // -----------------------------------------------------------------

    @Test
    void getKnowledge_returns200WithItemBody() throws Exception {
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(knowledgeService.getKnowledge(id)).thenReturn(
                itemResponse(id).latestVersion(versionResponse(versionId).build()).build());

        mvc.perform(get("/api/v1/knowledge/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("release-checklist"))
                .andExpect(jsonPath("$.currentVersion").value("v0.1.0"))
                .andExpect(jsonPath("$.latestVersion.id").value(versionId.toString()))
                .andExpect(jsonPath("$.latestVersion.status").value("PENDING"));
    }

    @Test
    void getKnowledge_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getKnowledge(id))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(get("/api/v1/knowledge/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message", containsString(id.toString())));
    }

    // -----------------------------------------------------------------
    // PUT /api/v1/knowledge/{id}
    // -----------------------------------------------------------------

    @Test
    void updateKnowledge_returns200AndMapsIdAndBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.updateKnowledge(eq(id), any(UpdateKnowledgeRequest.class)))
                .thenReturn(itemResponse(id).currentVersion("v0.2.0").build());

        mvc.perform(put("/api/v1/knowledge/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "description", "updated desc",
                                "content", "step 2: sign the build",
                                "sensitivity", "RESTRICTED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.currentVersion").value("v0.2.0"));

        ArgumentCaptor<UpdateKnowledgeRequest> captor = ArgumentCaptor.forClass(UpdateKnowledgeRequest.class);
        verify(knowledgeService).updateKnowledge(eq(id), captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("updated desc");
        assertThat(captor.getValue().getContent()).isEqualTo("step 2: sign the build");
        assertThat(captor.getValue().getSensitivity()).isEqualTo(Sensitivity.RESTRICTED);
    }

    @Test
    void updateKnowledge_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.updateKnowledge(eq(id), any(UpdateKnowledgeRequest.class)))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(put("/api/v1/knowledge/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "d"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // -----------------------------------------------------------------
    // POST /api/v1/knowledge/{id}/review
    // -----------------------------------------------------------------

    @Test
    void reviewKnowledge_approved_transitionReflectedInBody() throws Exception {
        UUID id = UUID.randomUUID();
        Instant approvedAt = Instant.parse("2026-07-02T09:00:00Z");
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class)))
                .thenReturn(itemResponse(id)
                        .status(KnowledgeStatus.APPROVED)
                        .latestVersion(versionResponse(UUID.randomUUID())
                                .status(VersionStatus.APPROVED)
                                .approvedAt(approvedAt)
                                .build())
                        .build());

        mvc.perform(post("/api/v1/knowledge/" + id + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVED", "reason", "looks good"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.latestVersion.status").value("APPROVED"))
                .andExpect(jsonPath("$.latestVersion.approvedAt").exists());

        ArgumentCaptor<ReviewDecisionRequest> captor = ArgumentCaptor.forClass(ReviewDecisionRequest.class);
        verify(knowledgeService).reviewKnowledge(eq(id), captor.capture());
        assertThat(captor.getValue().getDecision())
                .isEqualTo(ReviewDecisionRequest.ReviewDecision.APPROVED);
        assertThat(captor.getValue().getReason()).isEqualTo("looks good");
    }

    @Test
    void reviewKnowledge_rejected_transitionReflectedInBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class)))
                .thenReturn(itemResponse(id)
                        .status(KnowledgeStatus.REJECTED)
                        .latestVersion(versionResponse(UUID.randomUUID())
                                .status(VersionStatus.REJECTED)
                                .build())
                        .build());

        mvc.perform(post("/api/v1/knowledge/" + id + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "REJECTED", "reason", "too vague"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.latestVersion.status").value("REJECTED"));

        ArgumentCaptor<ReviewDecisionRequest> captor = ArgumentCaptor.forClass(ReviewDecisionRequest.class);
        verify(knowledgeService).reviewKnowledge(eq(id), captor.capture());
        assertThat(captor.getValue().getDecision())
                .isEqualTo(ReviewDecisionRequest.ReviewDecision.REJECTED);
        assertThat(captor.getValue().getReason()).isEqualTo("too vague");
    }

    static Stream<Arguments> invalidReviewBodies() {
        return Stream.of(
                Arguments.of("empty body", "{}"),
                Arguments.of("explicit null decision", "{\"decision\":null}"),
                Arguments.of("unknown decision enum", "{\"decision\":\"MAYBE\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReviewBodies")
    void reviewKnowledge_invalidBody_returns400(String label, String body) throws Exception {
        mvc.perform(post("/api/v1/knowledge/" + UUID.randomUUID() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(knowledgeService, never()).reviewKnowledge(any(), any());
    }

    @Test
    void reviewKnowledge_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class)))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(post("/api/v1/knowledge/" + id + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVED"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void reviewKnowledge_nonPendingItem_returns409Conflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class)))
                .thenThrow(new InvalidStateTransitionException("KnowledgeItem", "APPROVED", "APPROVED"));

        mvc.perform(post("/api/v1/knowledge/" + id + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "APPROVED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // -----------------------------------------------------------------
    // POST /api/v1/knowledge/{id}/retire
    // -----------------------------------------------------------------

    @Test
    void retireKnowledge_returns200WithRetiredStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.retireKnowledge(id)).thenReturn(itemResponse(id)
                .status(KnowledgeStatus.RETIRED)
                .retiredAt(Instant.parse("2026-07-03T08:00:00Z"))
                .build());

        mvc.perform(post("/api/v1/knowledge/" + id + "/retire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.retiredAt").exists());
    }

    @Test
    void retireKnowledge_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.retireKnowledge(id))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(post("/api/v1/knowledge/" + id + "/retire"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // -----------------------------------------------------------------
    // GET /api/v1/knowledge/{id}/yaml
    // -----------------------------------------------------------------

    @Test
    void getYamlContent_returnsYamlBodyWithYamlContentType() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getYamlContent(id, null)).thenReturn("steps:\n  - name: build");

        mvc.perform(get("/api/v1/knowledge/" + id + "/yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/yaml"))
                .andExpect(content().string("steps:\n  - name: build"));
    }

    @Test
    void getYamlContent_nullYaml_returns204AndPassesNullVersion() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getYamlContent(id, null)).thenReturn(null);

        mvc.perform(get("/api/v1/knowledge/" + id + "/yaml"))
                .andExpect(status().isNoContent());

        // Controller must always resolve the current version (null version arg).
        verify(knowledgeService).getYamlContent(eq(id), isNull());
    }

    @Test
    void getYamlContent_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getYamlContent(id, null))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(get("/api/v1/knowledge/" + id + "/yaml"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // -----------------------------------------------------------------
    // GET /api/v1/knowledge/{id}/versions and /{version}
    // -----------------------------------------------------------------

    @Test
    void getVersions_returnsVersionListNewestFirst() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getVersions(id)).thenReturn(List.of(
                versionResponse(UUID.randomUUID()).version("v0.2.0").build(),
                versionResponse(UUID.randomUUID()).version("v0.1.0").build()));

        mvc.perform(get("/api/v1/knowledge/" + id + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value("v0.2.0"))
                .andExpect(jsonPath("$[1].version").value("v0.1.0"));
    }

    @Test
    void getVersions_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getVersions(id))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(get("/api/v1/knowledge/" + id + "/versions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getVersionContent_returnsBodyAndPassesVersionPathVariable() throws Exception {
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(knowledgeService.getVersionContent(id, "v0.2.0")).thenReturn(
                versionResponse(versionId).version("v0.2.0").content("updated content").build());

        mvc.perform(get("/api/v1/knowledge/" + id + "/versions/v0.2.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId.toString()))
                .andExpect(jsonPath("$.version").value("v0.2.0"))
                .andExpect(jsonPath("$.content").value("updated content"));

        verify(knowledgeService).getVersionContent(id, "v0.2.0");
    }

    @Test
    void getVersionContent_unknownVersion_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.getVersionContent(id, "v9.9.9"))
                .thenThrow(new ResourceNotFoundException("KnowledgeVersion", id + "/v9.9.9"));

        mvc.perform(get("/api/v1/knowledge/" + id + "/versions/v9.9.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message", containsString("v9.9.9")));
    }

    // -----------------------------------------------------------------
    // POST /api/v1/knowledge/{id}/promote
    // -----------------------------------------------------------------

    @Test
    void promoteKnowledge_returns201AndMapsTargetTypeAndName() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID promotedId = UUID.randomUUID();
        when(knowledgeService.promoteKnowledgeItem(eq(sourceId), any(PromoteKnowledgeRequest.class)))
                .thenReturn(itemResponse(promotedId)
                        .name("release-workflow")
                        .type(KnowledgeType.WORKFLOW)
                        .currentVersion("v1.0.0")
                        .build());

        mvc.perform(post("/api/v1/knowledge/" + sourceId + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetType", "WORKFLOW", "targetName", "release-workflow"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(promotedId.toString()))
                .andExpect(jsonPath("$.type").value("WORKFLOW"))
                .andExpect(jsonPath("$.currentVersion").value("v1.0.0"));

        ArgumentCaptor<PromoteKnowledgeRequest> captor = ArgumentCaptor.forClass(PromoteKnowledgeRequest.class);
        verify(knowledgeService).promoteKnowledgeItem(eq(sourceId), captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo(KnowledgeType.WORKFLOW);
        assertThat(captor.getValue().getTargetName()).isEqualTo("release-workflow");
    }

    static Stream<Arguments> invalidPromoteBodies() {
        return Stream.of(
                Arguments.of("empty body", "{}"),
                Arguments.of("explicit null targetType", "{\"targetType\":null}"),
                Arguments.of("unknown targetType enum", "{\"targetType\":\"NOT_A_TYPE\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPromoteBodies")
    void promoteKnowledge_invalidBody_returns400(String label, String body) throws Exception {
        mvc.perform(post("/api/v1/knowledge/" + UUID.randomUUID() + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(knowledgeService, never()).promoteKnowledgeItem(any(), any());
    }

    @Test
    void promoteKnowledge_unknownId_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(knowledgeService.promoteKnowledgeItem(eq(id), any(PromoteKnowledgeRequest.class)))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        mvc.perform(post("/api/v1/knowledge/" + id + "/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("targetType", "TOOL"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // -----------------------------------------------------------------
    // GET /api/v1/knowledge/stats
    // -----------------------------------------------------------------

    @Test
    void instantiateWorkflow_delegatesWithParameters() throws Exception {
        UUID id = UUID.randomUUID();
        WorkflowResponse response = WorkflowResponse.builder()
                .id(UUID.randomUUID())
                .name("development-workflow-instance")
                .status(WorkflowChain.Status.RUNNING)
                .build();
        when(workflowTemplateService.instantiateTemplate(eq(id), anyMap()))
                .thenReturn(response);

        mvc.perform(post("/api/v1/knowledge/" + id + "/instantiate-workflow")
                        .contentType("application/json")
                        .content("{\"parameters\":{\"issueRef\":\"#1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.getId().toString()))
                .andExpect(jsonPath("$.name").value("development-workflow-instance"));

        verify(workflowTemplateService).instantiateTemplate(eq(id), argThat(p -> "#1".equals(p.get("issueRef"))));
    }

    @Test
    void getStats_returnsAggregatedCounts() throws Exception {
        when(knowledgeService.getStats()).thenReturn(KnowledgeStatsResponse.builder()
                .totalItems(5)
                .countByType(Map.of("SKILL", 3L, "WORKFLOW", 2L))
                .countByStatus(Map.of("PENDING", 1L, "APPROVED", 4L))
                .build());

        mvc.perform(get("/api/v1/knowledge/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.countByType.SKILL").value(3))
                .andExpect(jsonPath("$.countByType.WORKFLOW").value(2))
                .andExpect(jsonPath("$.countByStatus.APPROVED").value(4));
    }

    @Test
    void getStats_emptyStore_returnsZeroTotals() throws Exception {
        when(knowledgeService.getStats()).thenReturn(KnowledgeStatsResponse.builder()
                .totalItems(0)
                .countByType(Map.of())
                .countByStatus(Map.of())
                .build());

        mvc.perform(get("/api/v1/knowledge/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.countByType").isEmpty());
    }
}
