package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.KnowledgeVersionResponse;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeToolsTest {

    @Mock KnowledgeService knowledgeService;
    McpProperties mcpProperties;
    KnowledgeTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new KnowledgeTools(knowledgeService, mcpProperties);
    }

    @Test
    void listKnowledge_delegatesWithFilters() {
        when(knowledgeService.listKnowledge(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(KnowledgeItemResponse.builder()
                        .id(UUID.randomUUID()).name("development-workflow").build()));

        String json = tools.listKnowledge("WORKFLOW", "APPROVED");

        verify(knowledgeService).listKnowledge(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED);
        assertThat(json).contains("development-workflow").contains("\"ok\":true");
    }

    @Test
    void listKnowledge_blankFiltersListAll() {
        when(knowledgeService.listKnowledge(isNull(), isNull())).thenReturn(List.of());

        String json = tools.listKnowledge(null, null);

        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void storeKnowledge_submitsPendingItem() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.submitKnowledge(any())).thenReturn(KnowledgeItemResponse.builder()
                .id(id).name("deploy-runbook").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.PENDING).build());
        ArgumentCaptor<CreateKnowledgeRequest> captor = ArgumentCaptor.forClass(CreateKnowledgeRequest.class);

        String json = tools.storeKnowledge("deploy-runbook", "steps...", "skill", "how to deploy");

        verify(knowledgeService).submitKnowledge(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("deploy-runbook");
        assertThat(captor.getValue().getType()).isEqualTo(KnowledgeType.SKILL);
        assertThat(captor.getValue().getContent()).isEqualTo("steps...");
        assertThat(captor.getValue().getDescription()).isEqualTo("how to deploy");
        assertThat(json).contains("\"ok\":true").contains(id.toString()).contains("PENDING");
    }

    @Test
    void storeKnowledge_rejectsBlankContent() {
        String json = tools.storeKnowledge("deploy-runbook", "   ", null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("content is required");
    }

    @Test
    void storeKnowledge_rejectsUnknownType() {
        String json = tools.storeKnowledge("deploy-runbook", "steps...", "WIDGET", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("SKILL, SCRIPT, PROMPT");
    }

    @Test
    void queryKnowledge_returnsOnlyMatchingApprovedItems() {
        KnowledgeItemResponse hit = KnowledgeItemResponse.builder()
                .id(UUID.randomUUID()).name("Deploy runbook")
                .status(KnowledgeStatus.APPROVED)
                .latestVersion(KnowledgeVersionResponse.builder().version("v1.0.0")
                        .content("rolling deploy steps").build())
                .build();
        KnowledgeItemResponse miss = KnowledgeItemResponse.builder()
                .id(UUID.randomUUID()).name("Billing notes").description("invoices")
                .latestVersion(KnowledgeVersionResponse.builder().content("unrelated").build())
                .build();
        when(knowledgeService.listKnowledge(null, KnowledgeStatus.APPROVED)).thenReturn(List.of(hit, miss));

        String json = tools.queryKnowledge("DEPLOY");

        verify(knowledgeService).listKnowledge(null, KnowledgeStatus.APPROVED);
        assertThat(json).contains("\"ok\":true").contains("Deploy runbook").doesNotContain("Billing notes");
    }

    @Test
    void queryKnowledge_matchesDescriptionAndVersionContent() {
        KnowledgeItemResponse item = KnowledgeItemResponse.builder()
                .id(UUID.randomUUID()).name("runbook").description("rollback procedure")
                .latestVersion(KnowledgeVersionResponse.builder().content("canary rollout").build())
                .build();
        when(knowledgeService.listKnowledge(null, KnowledgeStatus.APPROVED)).thenReturn(List.of(item));

        assertThat(tools.queryKnowledge("canary")).contains("runbook");
        assertThat(tools.queryKnowledge("rollback")).contains("runbook");
        assertThat(tools.queryKnowledge("absent")).doesNotContain("runbook");
    }

    @Test
    void reviewKnowledge_approvesItem() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(any(), any())).thenReturn(KnowledgeItemResponse.builder()
                .id(id).name("deploy-runbook").status(KnowledgeStatus.APPROVED).build());
        ArgumentCaptor<ReviewDecisionRequest> captor = ArgumentCaptor.forClass(ReviewDecisionRequest.class);

        String json = tools.reviewKnowledge(id.toString(), "approved", "looks good");

        verify(knowledgeService).reviewKnowledge(eq(id), captor.capture());
        assertThat(captor.getValue().getDecision())
                .isEqualTo(ReviewDecisionRequest.ReviewDecision.APPROVED);
        assertThat(captor.getValue().getReason()).isEqualTo("looks good");
        assertThat(json).contains("\"ok\":true").contains(id.toString()).contains("APPROVED");
    }

    @Test
    void reviewKnowledge_rejectsInvalidDecision() {
        String json = tools.reviewKnowledge(UUID.randomUUID().toString(), "MAYBE", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("APPROVED, REJECTED");
    }

    @Test
    void reviewKnowledge_rejectsMalformedId() {
        String json = tools.reviewKnowledge("not-a-uuid", "APPROVED", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
    }

    @Test
    void reviewKnowledge_mapsMissingItemToNotFound() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(any(), any()))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        String json = tools.reviewKnowledge(id.toString(), "APPROVED", null);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void reviewKnowledge_mapsInvalidTransitionToConflict() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(any(), any()))
                .thenThrow(new InvalidStateTransitionException("KnowledgeItem", "APPROVED", "APPROVED"));

        String json = tools.reviewKnowledge(id.toString(), "APPROVED", null);

        assertThat(json).contains("\"errorType\":\"CONFLICT\"");
    }

    @Test
    void retireKnowledge_retiresItem() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.retireKnowledge(id)).thenReturn(KnowledgeItemResponse.builder()
                .id(id).name("deploy-runbook").status(KnowledgeStatus.RETIRED).build());

        String json = tools.retireKnowledge(id.toString());

        assertThat(json).contains("\"ok\":true").contains(id.toString()).contains("RETIRED");
    }

    @Test
    void retireKnowledge_mapsMissingItemToNotFound() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.retireKnowledge(id))
                .thenThrow(new ResourceNotFoundException("KnowledgeItem", id));

        String json = tools.retireKnowledge(id.toString());

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void retireKnowledge_rejectsMalformedId() {
        String json = tools.retireKnowledge("not-a-uuid");

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
    }
}
