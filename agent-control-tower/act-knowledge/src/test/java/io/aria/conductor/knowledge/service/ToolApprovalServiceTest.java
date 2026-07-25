package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static io.aria.conductor.test.TestDataBuilder.aToolDefinition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolApprovalServiceTest {

    @Mock KnowledgeService knowledgeService;
    @Mock ToolDefinitionRepository toolRepo;

    ToolApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ToolApprovalService(knowledgeService, toolRepo);
    }

    // ---- submitToolForApproval: PENDING side of the governance gate -----

    @Test
    void submitToolForApproval_disablesToolAndLinksPendingKnowledgeItem() {
        UUID kiId = UUID.randomUUID();
        when(knowledgeService.submitKnowledge(any(CreateKnowledgeRequest.class)))
                .thenReturn(pendingKnowledgeResponse(kiId));
        when(toolRepo.save(any(ToolDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ToolDefinition tool = aToolDefinition()
                .withId("caller-supplied-id")
                .withName("http-fetch")
                .withDescription("Fetches a URL")
                .withEnabled(true) // caller tries to sneak in an enabled tool
                .build();

        ToolDefinition saved = service.submitToolForApproval(tool, "openapi: 3.0.0");

        // knowledge item request carries the governance metadata
        ArgumentCaptor<CreateKnowledgeRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateKnowledgeRequest.class);
        verify(knowledgeService).submitKnowledge(requestCaptor.capture());
        CreateKnowledgeRequest kiRequest = requestCaptor.getValue();
        assertThat(kiRequest.getName()).isEqualTo("Tool: http-fetch");
        assertThat(kiRequest.getType()).isEqualTo(KnowledgeType.TOOL);
        assertThat(kiRequest.getDescription()).isEqualTo("Fetches a URL");
        assertThat(kiRequest.getContent()).isEqualTo("openapi: 3.0.0");
        assertThat(kiRequest.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);

        // tool is force-disabled until the KI is APPROVED, with a fresh id
        ArgumentCaptor<ToolDefinition> toolCaptor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(toolRepo).save(toolCaptor.capture());
        ToolDefinition persisted = toolCaptor.getValue();
        assertThat(persisted.isEnabled()).isFalse();
        assertThat(persisted.getKnowledgeItemId()).isEqualTo(kiId.toString());
        assertThat(persisted.getId()).isNotEqualTo("caller-supplied-id");
        assertThatCode(() -> UUID.fromString(persisted.getId())).doesNotThrowAnyException();

        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getKnowledgeItemId()).isEqualTo(kiId.toString());
    }

    @Test
    void submitToolForApproval_nullContent_fallsBackToToolDescription() {
        UUID kiId = UUID.randomUUID();
        when(knowledgeService.submitKnowledge(any(CreateKnowledgeRequest.class)))
                .thenReturn(pendingKnowledgeResponse(kiId));
        when(toolRepo.save(any(ToolDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ToolDefinition tool = aToolDefinition().withDescription("desc-as-content").build();

        service.submitToolForApproval(tool, null);

        ArgumentCaptor<CreateKnowledgeRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateKnowledgeRequest.class);
        verify(knowledgeService).submitKnowledge(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getContent()).isEqualTo("desc-as-content");
    }

    // ---- onKnowledgeApproved: PENDING -> APPROVED transition -------------

    @Test
    void onKnowledgeApproved_enablesOnlyTheToolLinkedToTheApprovedItem() {
        UUID approvedKi = UUID.randomUUID();
        ToolDefinition linked = aToolDefinition()
                .withName("linked-tool")
                .withKnowledgeItemId(approvedKi.toString())
                .withEnabled(false)
                .build();
        ToolDefinition unrelated = aToolDefinition()
                .withName("unrelated-tool")
                .withKnowledgeItemId(UUID.randomUUID().toString())
                .withEnabled(false)
                .build();
        ToolDefinition unlinked = aToolDefinition()
                .withName("never-submitted")
                .withKnowledgeItemId(null)
                .withEnabled(false)
                .build();
        when(toolRepo.findAll()).thenReturn(List.of(unrelated, unlinked, linked));
        when(toolRepo.save(any(ToolDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onKnowledgeApproved(approvedKi);

        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(toolRepo).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("linked-tool");
        assertThat(captor.getValue().isEnabled()).isTrue();
        // the other tools remain gated
        assertThat(unrelated.isEnabled()).isFalse();
        assertThat(unlinked.isEnabled()).isFalse();
    }

    @Test
    void onKnowledgeApproved_unknownKnowledgeId_changesNothing() {
        ToolDefinition pending = aToolDefinition()
                .withKnowledgeItemId(UUID.randomUUID().toString())
                .withEnabled(false)
                .build();
        when(toolRepo.findAll()).thenReturn(List.of(pending));

        service.onKnowledgeApproved(UUID.randomUUID());

        verify(toolRepo, never()).save(any());
        assertThat(pending.isEnabled()).isFalse();
    }

    @Test
    void onKnowledgeApproved_emptyRepository_isANoOp() {
        when(toolRepo.findAll()).thenReturn(List.of());

        service.onKnowledgeApproved(UUID.randomUUID());

        verify(toolRepo, never()).save(any());
    }

    // ---- helpers ----------------------------------------------------------

    private static KnowledgeItemResponse pendingKnowledgeResponse(UUID id) {
        return KnowledgeItemResponse.builder()
                .id(id)
                .name("Tool: something")
                .type(KnowledgeType.TOOL)
                .status(KnowledgeStatus.PENDING)
                .build();
    }
}
