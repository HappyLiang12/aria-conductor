package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeToolHandlerTest {

    @Mock
    private KnowledgeService knowledgeService;

    @Mock
    private KnowledgeItemRepository knowledgeItemRepository;

    @InjectMocks
    private KnowledgeToolHandler handler;

    @Test
    void createKnowledgeShouldCallService() {
        UUID id = UUID.randomUUID();
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(id)
                .name("test-knowledge")
                .status(KnowledgeStatus.PENDING)
                .build();
        when(knowledgeService.submitKnowledge(any())).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "create_knowledge",
                "name", "test-knowledge",
                "content", "test content",
                "type", "SKILL"
        ));

        assertTrue(result.contains("test-knowledge"));
        assertTrue(result.contains(id.toString()));
        verify(knowledgeService).submitKnowledge(any());
    }

    @Test
    void createKnowledgeMissingNameShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "create_knowledge",
                "content", "test content",
                "type", "SKILL"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void createKnowledgeMissingContentShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "create_knowledge",
                "name", "test",
                "type", "SKILL"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void createKnowledgeInvalidTypeShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "create_knowledge",
                "name", "test",
                "content", "content",
                "type", "INVALID_TYPE"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void searchKnowledgeShouldReturnResults() {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = KnowledgeItem.builder()
                .id(id)
                .name("test-knowledge")
                .type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.APPROVED)
                .description("test description")
                .sensitivity(Sensitivity.INTERNAL)
                .createdAt(Instant.now())
                .currentVersion("v1")
                .filePath("/path")
                .escalationCount(0)
                .build();
        when(knowledgeItemRepository.searchByKeyword(any(), any(), any(), any())).thenReturn(List.of(item));

        String result = handler.execute(Map.of(
                "toolName", "search_knowledge",
                "keyword", "test"
        ));

        assertTrue(result.contains("test-knowledge"));
        verify(knowledgeItemRepository).searchByKeyword(any(), any(), any(), any());
    }

    @Test
    void searchKnowledgeEmptyKeywordShouldReturnAllApproved() {
        when(knowledgeItemRepository.searchByKeyword(any(), any(), any(), any())).thenReturn(List.of());

        String result = handler.execute(Map.of("toolName", "search_knowledge"));

        assertFalse(result.startsWith("Error"));
    }

    @Test
    void searchKnowledgeShouldFindPendingItems() {
        UUID id = UUID.randomUUID();
        KnowledgeItem pending = KnowledgeItem.builder()
                .id(id).name("pending-skill").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.PENDING).description("fresh")
                .sensitivity(Sensitivity.INTERNAL).createdAt(Instant.now())
                .currentVersion("v0.1.0").escalationCount(0).build();
        when(knowledgeItemRepository.searchByKeyword(any(), any(), any(), any())).thenReturn(List.of(pending));

        String result = handler.execute(Map.of("toolName", "search_knowledge", "keyword", "pending"));

        assertTrue(result.contains("pending-skill"));
        assertTrue(result.contains("PENDING"));
    }

    @Test
    void listKnowledgeShouldReturnItems() {
        UUID id = UUID.randomUUID();
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(id)
                .name("item1")
                .type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.APPROVED)
                .build();
        when(knowledgeService.listKnowledge(null, null)).thenReturn(List.of(response));

        String result = handler.execute(Map.of("toolName", "list_knowledge"));

        assertTrue(result.contains("item1"));
        verify(knowledgeService).listKnowledge(null, null);
    }

    @Test
    void listKnowledgeWithTypeFilterShouldFilter() {
        when(knowledgeService.listKnowledge(KnowledgeType.SCRIPT, null)).thenReturn(List.of());

        String result = handler.execute(Map.of(
                "toolName", "list_knowledge",
                "type", "SCRIPT"
        ));

        assertTrue(result.contains("No knowledge items found"));
        verify(knowledgeService).listKnowledge(KnowledgeType.SCRIPT, null);
    }

    @Test
    void reviewKnowledgeShouldApprove() {
        UUID id = UUID.randomUUID();
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(id)
                .name("k1")
                .status(KnowledgeStatus.APPROVED)
                .build();
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class))).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "id", id.toString(),
                "decision", "APPROVED",
                "reason", "looks good"
        ));

        ArgumentCaptor<ReviewDecisionRequest> captor = ArgumentCaptor.forClass(ReviewDecisionRequest.class);
        verify(knowledgeService).reviewKnowledge(eq(id), captor.capture());
        assertEquals(ReviewDecisionRequest.ReviewDecision.APPROVED, captor.getValue().getDecision());

        assertTrue(result.contains("APPROVED"));
    }

    @Test
    void reviewKnowledgeMissingIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "decision", "APPROVED"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void retireKnowledgeShouldSucceed() {
        UUID id = UUID.randomUUID();
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(id)
                .name("old")
                .status(KnowledgeStatus.RETIRED)
                .build();
        when(knowledgeService.retireKnowledge(id)).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "retire_knowledge",
                "id", id.toString()
        ));

        assertTrue(result.contains("retired successfully"));
        verify(knowledgeService).retireKnowledge(id);
    }

    @Test
    void retireKnowledgeMissingIdShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "retire_knowledge"));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void reviewKnowledgeByNameShouldResolveAndApprove() {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = KnowledgeItem.builder()
                .id(id).name("project-architecture-basics").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.PENDING).build();
        when(knowledgeItemRepository.findByName("project-architecture-basics")).thenReturn(List.of(item));
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(id).name("project-architecture-basics").status(KnowledgeStatus.APPROVED).build();
        when(knowledgeService.reviewKnowledge(eq(id), any(ReviewDecisionRequest.class))).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "id", "project-architecture-basics",
                "decision", "APPROVED"
        ));

        verify(knowledgeItemRepository).findByName("project-architecture-basics");
        verify(knowledgeService).reviewKnowledge(eq(id), any(ReviewDecisionRequest.class));
        assertTrue(result.contains("APPROVED"));
        assertFalse(result.contains("Invalid UUID"));
    }

    @Test
    void reviewKnowledgeByUnknownNameShouldReturnNotFound() {
        when(knowledgeItemRepository.findByName("does-not-exist")).thenReturn(List.of());

        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "id", "does-not-exist",
                "decision", "APPROVED"
        ));

        assertEquals("Error: Knowledge not found: does-not-exist", result);
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void reviewKnowledgeByAmbiguousNameShouldListCandidates() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        KnowledgeItem a = KnowledgeItem.builder()
                .id(first).name("shared-name").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.PENDING).build();
        KnowledgeItem b = KnowledgeItem.builder()
                .id(second).name("shared-name").type(KnowledgeType.PROMPT)
                .status(KnowledgeStatus.APPROVED).build();
        when(knowledgeItemRepository.findByName("shared-name")).thenReturn(List.of(a, b));

        String result = handler.execute(Map.of(
                "toolName", "review_knowledge",
                "id", "shared-name",
                "decision", "APPROVED"
        ));

        assertTrue(result.startsWith("Error: Multiple knowledge items found with name 'shared-name'"));
        assertTrue(result.contains(first.toString()));
        assertTrue(result.contains(second.toString()));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void retireKnowledgeByNameShouldResolveAndRetire() {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = KnowledgeItem.builder()
                .id(id).name("old-runbook").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.APPROVED).build();
        when(knowledgeItemRepository.findByName("old-runbook")).thenReturn(List.of(item));
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(id).status(KnowledgeStatus.RETIRED).build();
        when(knowledgeService.retireKnowledge(id)).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "retire_knowledge",
                "id", "old-runbook"
        ));

        verify(knowledgeService).retireKnowledge(id);
        assertTrue(result.contains("retired successfully"));
        assertTrue(result.contains("Status: RETIRED"));
    }

    @Test
    void findKnowledgeShouldReturnItemIdentity() {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = KnowledgeItem.builder()
                .id(id).name("project-architecture-basics").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.PENDING).build();
        when(knowledgeItemRepository.findByName("project-architecture-basics")).thenReturn(List.of(item));

        String result = handler.execute(Map.of(
                "toolName", "find_knowledge",
                "name", "project-architecture-basics"
        ));

        assertTrue(result.contains(id.toString()));
        assertTrue(result.contains("PENDING"));
        assertTrue(result.contains("SKILL"));
    }

    @Test
    void findKnowledgeMissingNameShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "find_knowledge"));

        assertEquals("Error: Missing required parameter: name", result);
        verifyNoInteractions(knowledgeItemRepository);
    }

    @Test
    void findKnowledgeNotFoundShouldReturnError() {
        when(knowledgeItemRepository.findByName("ghost")).thenReturn(List.of());

        String result = handler.execute(Map.of(
                "toolName", "find_knowledge",
                "name", "ghost"
        ));

        assertEquals("Error: Knowledge not found: ghost", result);
    }

    @Test
    void findKnowledgeMultipleMatchesShouldListAll() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        KnowledgeItem a = KnowledgeItem.builder()
                .id(first).name("runbook").type(KnowledgeType.SKILL)
                .status(KnowledgeStatus.APPROVED).build();
        KnowledgeItem b = KnowledgeItem.builder()
                .id(second).name("runbook").type(KnowledgeType.PROMPT)
                .status(KnowledgeStatus.PENDING).build();
        when(knowledgeItemRepository.findByName("runbook")).thenReturn(List.of(a, b));

        String result = handler.execute(Map.of(
                "toolName", "find_knowledge",
                "name", "runbook"
        ));

        assertTrue(result.contains(first.toString()));
        assertTrue(result.contains(second.toString()));
        assertTrue(result.lines().count() == 2);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
