package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.common.model.AgentSkill;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.dto.SkillCreateRequest;
import io.aria.conductor.knowledge.listener.SkillApprovalListener;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Governed skill lifecycle over real H2 (no LLM, no prompt calls): author a skill
 * (AC1), show it is not assignable before approval (AC2), approve its linked SKILL
 * item which flips exactly that skill to enabled (AC3), then resolve the agent's
 * SkillContext(s) carrying the authored template (AC4). Mirrors the tool-governance
 * slice style; components are composed manually on the slice's repositories.
 */
class SkillLifecycleIntegrationTest extends DataJpaTestBase {

    @Autowired KnowledgeItemRepository itemRepository;
    @Autowired KnowledgeVersionRepository versionRepository;
    @Autowired SkillDefinitionRepository skillRepo;
    @Autowired AgentSkillRepository agentSkillRepository;

    @TempDir
    Path tmpRoot;

    private KnowledgeService knowledgeService;
    private SkillApprovalService skillApprovalService;
    private SkillApprovalListener approvalListener;
    private SkillContextProviderImpl skillProvider;
    private RecordingPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        knowledgeService = new KnowledgeService(itemRepository, versionRepository,
                new KnowledgeFileService(tmpRoot.toString()), publisher);
        skillApprovalService = new SkillApprovalService(itemRepository, versionRepository, skillRepo);
        approvalListener = new SkillApprovalListener(skillApprovalService);
        skillProvider = new SkillContextProviderImpl(agentSkillRepository, skillRepo);
    }

    @Test
    void authorApproveAssign_resolvesSkillContextEndToEnd() {
        String template = "Summarize the following in exactly {{count}} bullet points:\n{{text}}";
        SkillDefinition authored = authorSkill("Summarizer", template, "3-bullet summarizer");

        // AC1: full artifact pair — PENDING SKILL item + version + disabled linked definition
        assertThat(authored.isEnabled()).isFalse();
        assertThat(authored.getStage()).isEqualTo("SKILL");
        assertThat(authored.getTemplate()).isEqualTo(template);
        assertThat(authored.getUsageCount()).isZero();
        assertThat(authored.getTier()).isEqualTo("TIER_2");

        KnowledgeItem item = itemRepository.findById(UUID.fromString(authored.getKnowledgeItemId()))
                .orElseThrow();
        assertThat(item.getType()).isEqualTo(KnowledgeType.SKILL);
        assertThat(item.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(item.getCurrentVersion()).isEqualTo("v1.0.0");
        assertThat(versionRepository.findByKnowledgeItemIdAndVersion(item.getId(), "v1.0.0"))
                .hasValueSatisfying(v -> {
                    assertThat(v.getContent()).isEqualTo(template);
                    assertThat(v.getStatus()).isEqualTo(VersionStatus.PENDING);
                });
        flushAndClear();

        // AC2: not assignable before approval (AgentService gate resolves nothing)
        String agentId = "agent-ac4";
        assertThat(skillProvider.getEnabledSkillsByIds(List.of(authored.getId()))).isEmpty();

        // AC3: approving the linked SKILL item flips exactly that skill to enabled
        assertThat(knowledgeService.reviewKnowledge(item.getId(),
                decision(ReviewDecisionRequest.ReviewDecision.APPROVED)).getStatus())
                .isEqualTo(KnowledgeStatus.APPROVED);
        dispatchApprovals();
        flushAndClear();

        SkillDefinition afterApproval = skillRepo.findById(authored.getId()).orElseThrow();
        assertThat(afterApproval.isEnabled()).isTrue();

        // duplicate/re-listened event is idempotent: no error, stays enabled
        approvalListener.onKnowledgeApproved(
                new KnowledgeApprovedEvent(this, item.getId(), "Summarizer", "SKILL"));
        flushAndClear();
        assertThat(skillRepo.findById(authored.getId()).orElseThrow().isEnabled()).isTrue();

        // AC4: enabled skill is visible and assignable, and resolves a SkillContext with the template
        assertThat(skillRepo.findByEnabledTrue())
                .extracting(SkillDefinition::getId)
                .contains(authored.getId());
        agentSkillRepository.save(AgentSkill.builder()
                .id(new AgentSkillId(agentId, authored.getId())).build());
        flushAndClear();

        List<SkillContext> contexts = skillProvider.getEnabledSkillsForAgent(agentId);
        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).name()).isEqualTo("Summarizer");
        assertThat(contexts.get(0).template()).isEqualTo(template);

        // the disabled/unlinked filtering is unchanged: an arbitrary id stays out
        assertThat(skillProvider.getEnabledSkillsByIds(List.of("does-not-exist"))).isEmpty();
    }

    @Test
    void rejectedReview_leavesSkillDisabled() {
        SkillDefinition authored = authorSkill("RejectedSkill", "Draft {{x}} template", null);
        KnowledgeItem item = itemRepository.findById(UUID.fromString(authored.getKnowledgeItemId()))
                .orElseThrow();
        flushAndClear();

        knowledgeService.reviewKnowledge(item.getId(), decision(ReviewDecisionRequest.ReviewDecision.REJECTED));
        dispatchApprovals();
        flushAndClear();

        assertThat(itemRepository.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeStatus.REJECTED);
        assertThat(skillRepo.findById(authored.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void approvingNonSkillItem_withNoLinkedDefinition_isInert() {
        SkillDefinition authored = authorSkill("StillGated", "Template {{x}}", null);
        flushAndClear();

        KnowledgeItem prompt = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("a-prompt")
                .type(KnowledgeType.PROMPT)
                .description("prompt item")
                .status(KnowledgeStatus.PENDING)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        prompt = itemRepository.save(prompt);
        versionRepository.save(KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(prompt.getId())
                .version("v1.0.0")
                .status(VersionStatus.PENDING)
                .content("prompt body")
                .createdAt(Instant.now())
                .build());
        flushAndClear();

        assertThat(knowledgeService.reviewKnowledge(prompt.getId(),
                decision(ReviewDecisionRequest.ReviewDecision.APPROVED)).getStatus())
                .isEqualTo(KnowledgeStatus.APPROVED);
        dispatchApprovals();
        flushAndClear();

        assertThat(skillRepo.findById(authored.getId()).orElseThrow().isEnabled()).isFalse();
    }

    // ---- helpers ----------------------------------------------------------

    private SkillDefinition authorSkill(String name, String template, String description) {
        SkillCreateRequest request = SkillCreateRequest.builder()
                .name(name)
                .template(template)
                .description(description)
                .build();
        return skillApprovalService.submitSkillForApproval(request);
    }

    private static ReviewDecisionRequest decision(ReviewDecisionRequest.ReviewDecision decision) {
        return ReviewDecisionRequest.builder().decision(decision).reason("test").build();
    }

    private void dispatchApprovals() {
        for (Object event : publisher.events) {
            if (event instanceof KnowledgeApprovedEvent approved) {
                approvalListener.onKnowledgeApproved(approved);
            }
        }
    }

    /** Minimal synchronous publisher that records events for manual listener dispatch. */
    private static final class RecordingPublisher implements org.springframework.context.ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }
}
