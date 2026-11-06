package io.aria.conductor.knowledge.selfimprove;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionEvaluatorTest {

    @Mock SkillDefinitionRepository skillRepository;
    @Mock KnowledgeItemRepository knowledgeRepository;
    @Mock PromptCallRepository promptCallRepository;

    SimilarityEngine similarityEngine;
    PromotionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        similarityEngine = new SimilarityEngine();
        evaluator = new PromotionEvaluator(similarityEngine, skillRepository,
                knowledgeRepository, promptCallRepository);
        lenient().when(knowledgeRepository.findByStatus(KnowledgeStatus.APPROVED))
                .thenReturn(List.of());
        lenient().when(skillRepository.findByKnowledgeItemId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
    }

    // ---- Stage 1 -> 2 -------------------------------------------------

    @Test
    void stage2_threeSimilarCallsAcrossAgentsAndSessions_promotes() {
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID();
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID();
        // Balanced 2-of-4 split keeps the dominance share at 50% (≤ 60%).
        List<PromptCall> calls = List.of(
                call(a1, r1, "openai", "gpt-4", "success"),
                call(a2, r2, "openai", "gpt-4", "success"),
                call(a1, r2, "openai", "gpt-4", "success"),
                call(a2, r1, "openai", "gpt-4", "success")
        );
        var d = evaluator.evaluateForStage2(calls);
        assertThat(d.approved()).as("decision=%s", d).isTrue();
        assertThat(d.stage()).isEqualTo("REUSABLE_PROMPT");
    }

    @Test
    void stage2_underThreeCalls_rejects() {
        var d = evaluator.evaluateForStage2(List.of(
                call(UUID.randomUUID(), UUID.randomUUID(), "openai", "gpt-4", "success"),
                call(UUID.randomUUID(), UUID.randomUUID(), "openai", "gpt-4", "success")));
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).contains("at least 3");
    }

    @Test
    void stage2_singleAgentDominance_rejects() {
        UUID solo = UUID.randomUUID();
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID();
        // 4-of-5 calls from the same agent → dominance > 60%.
        List<PromptCall> calls = List.of(
                call(solo, r1, "openai", "gpt-4", "success"),
                call(solo, r1, "openai", "gpt-4", "success"),
                call(solo, r2, "openai", "gpt-4", "success"),
                call(solo, r2, "openai", "gpt-4", "success"),
                call(UUID.randomUUID(), r2, "openai", "gpt-4", "success")
        );
        var d = evaluator.evaluateForStage2(calls);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).contains("Anti-gaming");
    }

    @Test
    void stage2_singleSession_rejectedByDiversityCheck() {
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID();
        UUID onlyRun = UUID.randomUUID();
        List<PromptCall> calls = List.of(
                call(a1, onlyRun, "openai", "gpt-4", "success"),
                call(a2, onlyRun, "openai", "gpt-4", "success"),
                call(a1, onlyRun, "openai", "gpt-4", "success")
        );
        var d = evaluator.evaluateForStage2(calls);
        assertThat(d.approved()).isFalse();
    }

    // ---- Stage 2 -> 3 -------------------------------------------------

    @Test
    void stage3_fivePlusUses_onApprovedItem_promotes() {
        KnowledgeItem item = approvedPrompt();
        when(skillRepository.findByKnowledgeItemId(item.getId().toString()))
                .thenReturn(List.of(skillWithUsage(7)));
        var d = evaluator.evaluateForStage3(item);
        assertThat(d.approved()).as("decision=%s", d).isTrue();
        assertThat(d.stage()).isEqualTo("SKILL");
    }

    @Test
    void stage3_underFiveUses_rejects() {
        KnowledgeItem item = approvedPrompt();
        when(skillRepository.findByKnowledgeItemId(item.getId().toString()))
                .thenReturn(List.of(skillWithUsage(2)));
        var d = evaluator.evaluateForStage3(item);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).contains("uses");
    }

    @Test
    void stage3_dedupCosineAbove95_blocks() {
        KnowledgeItem item = approvedPrompt();
        item.setName("invoice generator");
        item.setDescription("Generates invoices for customers");
        KnowledgeItem twin = approvedPrompt();
        twin.setName("invoice generator");
        twin.setDescription("Generates invoices for customers");
        when(skillRepository.findByKnowledgeItemId(item.getId().toString()))
                .thenReturn(List.of(skillWithUsage(7)));
        when(knowledgeRepository.findByStatus(KnowledgeStatus.APPROVED))
                .thenReturn(new ArrayList<>(List.of(twin)));
        var d = evaluator.evaluateForStage3(item);
        assertThat(d.approved()).isFalse();
        assertThat(d.reason()).contains("Duplicate");
    }

    // ---- Anti-gaming rate limit --------------------------------------

    @Test
    void antiGaming_rateLimit_blocksSecondPromotionWithinHour() {
        evaluator.recordAutoPromotion("alice");
        var ctx = new PromotionEvaluator.PromotionContext(
                java.util.Set.of("a1", "a2"),
                java.util.Set.of("s1", "s2"),
                "alice",
                null);
        assertThat(evaluator.passesAntiGaming(ctx)).isFalse();
    }

    @Test
    void antiGaming_oldPromotion_allowsNewPromotion() {
        var ctx = new PromotionEvaluator.PromotionContext(
                java.util.Set.of("a1", "a2"),
                java.util.Set.of("s1", "s2"),
                "bob",
                Instant.now().minusSeconds(7_200) /* 2h ago */);
        assertThat(evaluator.passesAntiGaming(ctx)).isTrue();
    }

    @Test
    void antiGaming_singleAgent_failsDiversity() {
        var ctx = new PromotionEvaluator.PromotionContext(
                java.util.Set.of("only-one"),
                java.util.Set.of("s1", "s2"),
                null, null);
        assertThat(evaluator.passesAntiGaming(ctx)).isFalse();
    }

    // ---- Stage 5 ------------------------------------------------------

    @Test
    void stage5_threeApprovedScripts_promotes() {
        var d = evaluator.evaluateForStage5(List.of(
                approvedScript(), approvedScript(), approvedScript()));
        assertThat(d.approved()).isTrue();
    }

    @Test
    void stage5_twoScripts_rejects() {
        var d = evaluator.evaluateForStage5(List.of(approvedScript(), approvedScript()));
        assertThat(d.approved()).isFalse();
    }

    // ---- helpers ------------------------------------------------------

    private static PromptCall call(UUID agent, UUID run, String prov, String model, String outcome) {
        return PromptCall.builder()
                .agentId(agent)
                .runId(run)
                .provider(prov)
                .model(model)
                .outcome(outcome)
                .toolsUsed("")
                .createdAt(Instant.now())
                .build();
    }

    private static KnowledgeItem approvedPrompt() {
        return KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("p")
                .type(KnowledgeType.PROMPT)
                .description("d")
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
    }

    private static KnowledgeItem approvedScript() {
        KnowledgeItem k = approvedPrompt();
        k.setType(KnowledgeType.SCRIPT);
        return k;
    }

    private static SkillDefinition skillWithUsage(int uses) {
        return SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("s")
                .stage("SKILL")
                .usageCount(uses)
                .build();
    }
}
