package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Gaps left by KnowledgeToolHandlerTest: the query_knowledge APPROVED
 * restriction with keyword wildcarding, invalid enum values on every path,
 * review/retire flows and exception mapping.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeToolHandlerEdgeCasesTest {

    @Mock private KnowledgeService knowledgeService;
    @Mock private KnowledgeItemRepository knowledgeItemRepository;

    @InjectMocks
    private KnowledgeToolHandler handler;

    @Test
    void queryKnowledge_restrictsToApprovedAndWildcardsKeyword() {
        when(knowledgeItemRepository.searchByKeyword(
                eq("%deploy%"), isNull(), eq(KnowledgeStatus.APPROVED), any(PageRequest.class)))
                .thenReturn(List.of(KnowledgeItem.builder()
                        .id(UUID.randomUUID()).name("Deploy Script")
                        .type(KnowledgeType.SKILL).status(KnowledgeStatus.APPROVED).build()));

        String result = handler.execute(Map.of(
                "toolName", "query_knowledge", "keyword", "Deploy"));

        // keyword must be lowercased and wrapped in SQL wildcards for the DB-layer search
        verify(knowledgeItemRepository).searchByKeyword(
                eq("%deploy%"), isNull(), eq(KnowledgeStatus.APPROVED), any(PageRequest.class));
        assertThat(result).contains("Deploy Script").contains("1 total");
    }

    @Test
    void searchKnowledge_blankKeywordPassesNullKeywordAndNullStatus() {
        when(knowledgeItemRepository.searchByKeyword(
                isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(List.of());

        String result = handler.execute(Map.of("toolName", "search_knowledge"));

        verify(knowledgeItemRepository).searchByKeyword(
                isNull(), isNull(), isNull(), any(PageRequest.class));
        assertThat(result).isEqualTo("No knowledge items found.");
    }

    @Test
    void searchKnowledge_noMatchesMentionsTheKeyword() {
        when(knowledgeItemRepository.searchByKeyword(
                any(), any(), any(), any(PageRequest.class))).thenReturn(List.of());

        String result = handler.execute(Map.of(
                "toolName", "search_knowledge", "keyword", "quantum"));

        assertThat(result).isEqualTo("No knowledge items found matching keyword: quantum");
    }

    @Test
    void searchKnowledge_invalidTypeReturnsErrorBeforeQuerying() {
        String result = handler.execute(Map.of(
                "toolName", "search_knowledge", "keyword", "x", "type", "BOGUS"));

        assertThat(result).startsWith("Error: Invalid type: BOGUS");
        verifyNoInteractions(knowledgeItemRepository);
    }

    @Test
    void listKnowledge_invalidStatusReturnsErrorBeforeQuerying() {
        String result = handler.execute(Map.of(
                "toolName", "list_knowledge", "status", "SOMETIMES"));

        assertThat(result).startsWith("Error: Invalid status: SOMETIMES");
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void reviewKnowledge_missingIdReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "review_knowledge", "decision", "APPROVED"));

        assertThat(result).startsWith("Error").contains("Missing required parameter: id");
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void reviewKnowledge_missingDecisionReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "review_knowledge", "id", UUID.randomUUID().toString()));

        assertThat(result).startsWith("Error").contains("Missing required parameter: decision");
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void reviewKnowledge_invalidDecisionListsValidOptions() {
        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "id", UUID.randomUUID().toString(),
                "decision", "maybe"));

        assertThat(result).startsWith("Error: Invalid decision: maybe")
                .contains("APPROVED").contains("REJECTED");
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void reviewKnowledge_rejectionPassesDecisionAndReasonToService() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class)))
                .thenReturn(KnowledgeItemResponse.builder()
                        .id(id).status(KnowledgeStatus.REJECTED).build());

        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "id", id.toString(),
                "decision", "rejected",
                "reason", "content is outdated"));

        ArgumentCaptor<ReviewDecisionRequest> captor =
                ArgumentCaptor.forClass(ReviewDecisionRequest.class);
        verify(knowledgeService).reviewKnowledge(eq(id), captor.capture());
        assertThat(captor.getValue().getDecision())
                .isEqualTo(ReviewDecisionRequest.ReviewDecision.REJECTED);
        assertThat(captor.getValue().getReason()).isEqualTo("content is outdated");
        assertThat(result).contains("Decision: REJECTED").contains("Status: REJECTED");
    }

    @Test
    void retireKnowledge_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "retire_knowledge"));

        assertThat(result).startsWith("Error").contains("id");
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void retireKnowledge_reportsResultingStatus() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.retireKnowledge(id)).thenReturn(
                KnowledgeItemResponse.builder().id(id).status(KnowledgeStatus.RETIRED).build());

        String result = handler.execute(Map.of(
                "toolName", "retire_knowledge", "id", id.toString()));

        assertThat(result).contains("retired successfully").contains("Status: RETIRED");
    }

    @Test
    void storeKnowledge_aliasBehavesLikeCreateKnowledge() {
        when(knowledgeService.submitKnowledge(any())).thenReturn(
                KnowledgeItemResponse.builder().id(UUID.randomUUID()).build());

        String result = handler.execute(Map.of(
                "toolName", "store_knowledge",
                "name", "Runbook", "content", "steps..."));

        assertThat(result).contains("Knowledge 'Runbook' created").contains("status: PENDING");
    }

    @Test
    void serviceExceptionIsMappedToErrorString() {
        UUID id = UUID.randomUUID();
        when(knowledgeService.retireKnowledge(id))
                .thenThrow(new IllegalStateException("already retired"));

        String result = handler.execute(Map.of(
                "toolName", "retire_knowledge", "id", id.toString()));

        assertThat(result).isEqualTo("Error: already retired");
    }

    @Test
    void unknownToolReturnsError() {
        String result = handler.execute(Map.of("toolName", "summon_knowledge"));

        assertThat(result).isEqualTo("Error: Unknown tool: summon_knowledge");
    }
}
