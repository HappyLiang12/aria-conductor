package io.aria.conductor.knowledge.listener;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.service.SkillApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillApprovalListenerTest {

    @Mock SkillApprovalService skillApprovalService;
    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock SkillDefinitionRepository skillRepo;

    @Test
    void onKnowledgeApproved_forwardsSkillEventKnowledgeIdToTheService() {
        SkillApprovalListener listener = new SkillApprovalListener(skillApprovalService);
        UUID knowledgeId = UUID.randomUUID();

        listener.onKnowledgeApproved(new KnowledgeApprovedEvent(this, knowledgeId, "Summarizer", "SKILL"));

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(skillApprovalService).onKnowledgeApproved(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(knowledgeId);
    }

    @Test
    void onKnowledgeApproved_nonSkillEvent_isNotForwarded() {
        SkillApprovalListener listener = new SkillApprovalListener(skillApprovalService);

        listener.onKnowledgeApproved(new KnowledgeApprovedEvent(this, UUID.randomUUID(), "a-prompt", "PROMPT"));

        verify(skillApprovalService, never()).onKnowledgeApproved(any());
    }

    @Test
    void onKnowledgeApproved_endToEnd_enablesTheLinkedSkillInTheRepository() {
        // real service wired to mocked repos: SKILL event -> linked skill flips to enabled
        SkillApprovalListener listener = new SkillApprovalListener(
                new SkillApprovalService(itemRepository, versionRepository, skillRepo));
        UUID knowledgeId = UUID.randomUUID();
        SkillDefinition gated = SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("gated-skill")
                .knowledgeItemId(knowledgeId.toString())
                .enabled(false)
                .stage("SKILL")
                .build();
        when(skillRepo.findByKnowledgeItemId(knowledgeId.toString())).thenReturn(List.of(gated));
        when(skillRepo.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        listener.onKnowledgeApproved(new KnowledgeApprovedEvent(this, knowledgeId, "gated-skill", "SKILL"));

        ArgumentCaptor<SkillDefinition> skillCaptor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(skillRepo).save(skillCaptor.capture());
        SkillDefinition saved = skillCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("gated-skill");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getKnowledgeItemId()).isEqualTo(knowledgeId.toString());
    }

    @Test
    void onKnowledgeApproved_endToEnd_unrelatedKnowledgeId_leavesSkillsDisabled() {
        SkillApprovalListener listener = new SkillApprovalListener(
                new SkillApprovalService(itemRepository, versionRepository, skillRepo));
        SkillDefinition gated = SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("still-gated")
                .knowledgeItemId(UUID.randomUUID().toString())
                .enabled(false)
                .stage("SKILL")
                .build();
        // The repository has no rows linked to the unrelated approved item -> no-op.
        listener.onKnowledgeApproved(
                new KnowledgeApprovedEvent(this, UUID.randomUUID(), "other-item", "SKILL"));

        verify(skillRepo, never()).save(any());
        assertThat(gated.isEnabled()).isFalse();
    }
}
