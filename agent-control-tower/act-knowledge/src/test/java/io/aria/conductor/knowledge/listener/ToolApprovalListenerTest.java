package io.aria.conductor.knowledge.listener;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import io.aria.conductor.knowledge.service.ToolApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static io.aria.conductor.test.TestDataBuilder.aToolDefinition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolApprovalListenerTest {

    @Mock ToolApprovalService toolApprovalService;
    @Mock KnowledgeService knowledgeService;
    @Mock ToolDefinitionRepository toolRepo;

    @Test
    void onKnowledgeApproved_forwardsTheEventKnowledgeIdToTheService() {
        ToolApprovalListener listener = new ToolApprovalListener(toolApprovalService);
        UUID knowledgeId = UUID.randomUUID();

        listener.onKnowledgeApproved(new KnowledgeApprovedEvent(this, knowledgeId, "Tool: x", "TOOL"));

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(toolApprovalService).onKnowledgeApproved(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(knowledgeId);
    }

    @Test
    void onKnowledgeApproved_endToEnd_enablesTheLinkedToolInTheRepository() {
        // real service wired to a mocked repo: event -> tool flips to enabled
        ToolApprovalListener listener =
                new ToolApprovalListener(new ToolApprovalService(knowledgeService, toolRepo));
        UUID knowledgeId = UUID.randomUUID();
        ToolDefinition gated = aToolDefinition()
                .withName("gated-tool")
                .withKnowledgeItemId(knowledgeId.toString())
                .withEnabled(false)
                .build();
        when(toolRepo.findAll()).thenReturn(List.of(gated));
        when(toolRepo.save(any(ToolDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        listener.onKnowledgeApproved(new KnowledgeApprovedEvent(this, knowledgeId, "Tool: gated", "TOOL"));

        ArgumentCaptor<ToolDefinition> toolCaptor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(toolRepo).save(toolCaptor.capture());
        ToolDefinition saved = toolCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("gated-tool");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getKnowledgeItemId()).isEqualTo(knowledgeId.toString());
    }

    @Test
    void onKnowledgeApproved_endToEnd_unrelatedKnowledgeId_leavesToolsDisabled() {
        ToolApprovalListener listener =
                new ToolApprovalListener(new ToolApprovalService(knowledgeService, toolRepo));
        ToolDefinition gated = aToolDefinition()
                .withKnowledgeItemId(UUID.randomUUID().toString())
                .withEnabled(false)
                .build();
        when(toolRepo.findAll()).thenReturn(List.of(gated));

        listener.onKnowledgeApproved(
                new KnowledgeApprovedEvent(this, UUID.randomUUID(), "other-item", "PROMPT"));

        verify(toolRepo, never()).save(any());
        assertThat(gated.isEnabled()).isFalse();
    }
}
