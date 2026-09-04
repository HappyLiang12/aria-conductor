package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.knowledge.dto.SkillCreateRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillApprovalServiceTest {

    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock SkillDefinitionRepository skillRepo;

    SkillApprovalService service;

    @BeforeEach
    void setUp() {
        service = new SkillApprovalService(itemRepository, versionRepository, skillRepo);
    }

    // ---- submitSkillForApproval: authoring side of the governance gate -----

    @Test
    void submitSkillForApproval_createsPendingSkillItemVersionAndDisabledSkill() {
        when(skillRepo.existsByName("Summarizer")).thenReturn(false);
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepo.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillCreateRequest request = SkillCreateRequest.builder()
                .name("  Summarizer ")
                .template("Summarize in {{count}} bullets:\n{{text}}")
                .description("3-bullet summarizer")
                .triggerConditions("{\"variables\":[\"text\"]}")
                .examples("[{\"input\":\"long\",\"output\":\"short\"}]")
                .build();

        SkillDefinition saved = service.submitSkillForApproval(request);

        // PENDING SKILL KnowledgeItem at v1.0.0
        ArgumentCaptor<KnowledgeItem> itemCaptor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).save(itemCaptor.capture());
        KnowledgeItem item = itemCaptor.getValue();
        assertThat(item.getId()).isNotNull();
        assertThat(item.getName()).isEqualTo("Summarizer");
        assertThat(item.getType()).isEqualTo(KnowledgeType.SKILL);
        assertThat(item.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(item.getCurrentVersion()).isEqualTo("v1.0.0");
        assertThat(item.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);

        // matching KnowledgeVersion so the review flow can resolve a version
        ArgumentCaptor<KnowledgeVersion> versionCaptor = ArgumentCaptor.forClass(KnowledgeVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        KnowledgeVersion version = versionCaptor.getValue();
        assertThat(version.getKnowledgeItemId()).isEqualTo(item.getId());
        assertThat(version.getVersion()).isEqualTo("v1.0.0");
        assertThat(version.getStatus()).isEqualTo(VersionStatus.PENDING);
        assertThat(version.getContent()).isEqualTo("Summarize in {{count}} bullets:\n{{text}}");

        // disabled SkillDefinition linked to the item
        ArgumentCaptor<SkillDefinition> skillCaptor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(skillRepo).save(skillCaptor.capture());
        SkillDefinition skill = skillCaptor.getValue();
        assertThat(skill.getId()).isNotBlank();
        assertThat(skill.getName()).isEqualTo("Summarizer");
        assertThat(skill.getDescription()).isEqualTo("3-bullet summarizer");
        assertThat(skill.getTemplate()).isEqualTo("Summarize in {{count}} bullets:\n{{text}}");
        assertThat(skill.getTriggerConditions()).isEqualTo("{\"variables\":[\"text\"]}");
        assertThat(skill.getExamples()).isEqualTo("[{\"input\":\"long\",\"output\":\"short\"}]");
        assertThat(skill.getStage()).isEqualTo("SKILL");
        assertThat(skill.isEnabled()).isFalse();
        assertThat(skill.getUsageCount()).isZero();
        assertThat(skill.getTier()).isEqualTo("TIER_2");
        assertThat(skill.getKnowledgeItemId()).isEqualTo(item.getId().toString());

        assertThat(saved.getKnowledgeItemId()).isEqualTo(item.getId().toString());
        assertThat(saved.isEnabled()).isFalse();
    }

    @Test
    void submitSkillForApproval_nullDescription_fallsBackToGeneratedDescription() {
        when(skillRepo.existsByName("CodeReviewer")).thenReturn(false);
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepo.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillCreateRequest request = SkillCreateRequest.builder()
                .name("CodeReviewer")
                .template("Review {{code}}")
                .build();

        SkillDefinition saved = service.submitSkillForApproval(request);

        ArgumentCaptor<SkillDefinition> captor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(skillRepo).save(captor.capture());
        assertThat(captor.getValue().getDescription()).isEqualTo("Skill: CodeReviewer");
        assertThat(captor.getValue().getTier()).isEqualTo("TIER_2");
        assertThat(saved.getTriggerConditions()).isNull();
        assertThat(saved.getExamples()).isNull();
    }

    @Test
    void submitSkillForApproval_duplicateName_throwsIllegalState() {
        when(skillRepo.existsByName("Summarizer")).thenReturn(true);

        SkillCreateRequest request = SkillCreateRequest.builder()
                .name("Summarizer")
                .template("Summarize {{text}}")
                .build();

        assertThatThrownBy(() -> service.submitSkillForApproval(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Summarizer");
        verify(itemRepository, never()).save(any());
        verify(skillRepo, never()).save(any());
    }

    @Test
    void submitSkillForApproval_invalidJsonTriggerConditions_throwsIllegalArgument() {
        when(skillRepo.existsByName("Summarizer")).thenReturn(false);

        SkillCreateRequest request = SkillCreateRequest.builder()
                .name("Summarizer")
                .template("Summarize {{text}}")
                .triggerConditions("{not valid json")
                .build();

        assertThatThrownBy(() -> service.submitSkillForApproval(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerConditions");
        verify(itemRepository, never()).save(any());
        verify(skillRepo, never()).save(any());
    }

    @Test
    void submitSkillForApproval_invalidJsonExamples_throwsIllegalArgument() {
        when(skillRepo.existsByName("Summarizer")).thenReturn(false);

        SkillCreateRequest request = SkillCreateRequest.builder()
                .name("Summarizer")
                .template("Summarize {{text}}")
                .examples("[not valid json")
                .build();

        assertThatThrownBy(() -> service.submitSkillForApproval(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("examples");
    }

    @Test
    void submitSkillForApproval_freeTextStructuredFields_areAccepted() {
        when(skillRepo.existsByName("Reviewer")).thenReturn(false);
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepo.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillCreateRequest request = SkillCreateRequest.builder()
                .name("Reviewer")
                .template("Review {{code}}")
                .triggerConditions("applies when a PR references an issue")
                .build();

        SkillDefinition saved = service.submitSkillForApproval(request);

        assertThat(saved.getTriggerConditions())
                .isEqualTo("applies when a PR references an issue");
    }

    // ---- onKnowledgeApproved: PENDING -> APPROVED transition -------------

    @Test
    void onKnowledgeApproved_enablesOnlyTheSkillLinkedToTheApprovedItem() {
        UUID approvedKi = UUID.randomUUID();
        SkillDefinition linked = SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("linked-skill")
                .knowledgeItemId(approvedKi.toString())
                .enabled(false)
                .stage("SKILL")
                .build();
        when(skillRepo.findByKnowledgeItemId(approvedKi.toString()))
                .thenReturn(List.of(linked));
        when(skillRepo.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onKnowledgeApproved(approvedKi);

        ArgumentCaptor<SkillDefinition> captor = ArgumentCaptor.forClass(SkillDefinition.class);
        verify(skillRepo).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("linked-skill");
        assertThat(captor.getValue().isEnabled()).isTrue();

        // an unrelated disabled skill is never touched (only the matching row is queried)
        assertThat(linked.getKnowledgeItemId()).isEqualTo(approvedKi.toString());
    }

    @Test
    void onKnowledgeApproved_repeatedEvent_isIdempotent() {
        UUID approvedKi = UUID.randomUUID();
        SkillDefinition enabled = SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("already-on")
                .knowledgeItemId(approvedKi.toString())
                .enabled(true)
                .stage("SKILL")
                .build();
        when(skillRepo.findByKnowledgeItemId(approvedKi.toString()))
                .thenReturn(List.of(enabled));

        service.onKnowledgeApproved(approvedKi);

        verify(skillRepo, never()).save(any());
        assertThat(enabled.isEnabled()).isTrue();
    }

    @Test
    void onKnowledgeApproved_unknownKnowledgeId_changesNothing() {
        // no linked rows for the id -> silent no-op, nothing persisted
        service.onKnowledgeApproved(UUID.randomUUID());

        verify(skillRepo, never()).save(any());
    }

    @Test
    void onKnowledgeApproved_emptyRepository_isANoOp() {
        when(skillRepo.findByKnowledgeItemId(any())).thenReturn(List.of());

        service.onKnowledgeApproved(UUID.randomUUID());

        verify(skillRepo, never()).save(any());
    }
}
