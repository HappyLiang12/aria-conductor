package io.aria.conductor.knowledge.selfimprove;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.service.KnowledgeFileService;
import io.aria.conductor.knowledge.service.SelfImprovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end wiring test: prompt calls → Stage 2 evaluation → reusable
 * prompt → Stage 3 promotion to {@link SkillDefinition}. Components are
 * composed manually (no Spring) to keep the test fast and independent
 * from the application context.
 */
@ExtendWith(MockitoExtension.class)
class SkillPromotionIntegrationTest {

    @Mock PromptCallRepository promptCallRepository;
    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock SkillDefinitionRepository skillRepository;
    @Mock KnowledgeLineageRepository lineageRepository;

    KnowledgeFileService fileService;
    SimilarityEngine similarityEngine;
    PromotionEvaluator evaluator;
    SandboxExecutor sandbox;
    SelfImprovementService selfImprovement;

    Path tmpRoot;

    @BeforeEach
    void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("act-skill-it-");
        fileService = new KnowledgeFileService(tmpRoot.toString());
        similarityEngine = new SimilarityEngine();
        evaluator = new PromotionEvaluator(similarityEngine, skillRepository,
                itemRepository, promptCallRepository);
        sandbox = new SandboxExecutor();
        selfImprovement = new SelfImprovementService(promptCallRepository, itemRepository,
                versionRepository, fileService, evaluator, skillRepository,
                lineageRepository, sandbox);

        lenient().when(itemRepository.save(any(KnowledgeItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(skillRepository.save(any(SkillDefinition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineageRepository.save(any(KnowledgeLineage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(itemRepository.findByStatus(KnowledgeStatus.APPROVED))
                .thenReturn(List.of());
    }

    @Test
    void fullFlow_promptCallsCluster_promotedToReusable_thenPromotedToSkill() {
        // -- given: a Stage-2-eligible cluster of prompt calls
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID();
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID();
        List<PromptCall> cluster = List.of(
                call(a1, r1), call(a2, r2), call(a1, r2), call(a2, r1));

        var stage2 = evaluator.evaluateForStage2(cluster);
        assertThat(stage2.approved()).as("stage2=%s", stage2).isTrue();

        // -- when: a reusable prompt is approved with usage and we promote
        KnowledgeItem reusable = approvedPrompt("summarise-{{topic}}",
                "Summarise meeting notes about {{topic}} for {{audience}}");
        // Stage 3 expects ≥5 uses → wire skill repository to report 6 uses
        when(skillRepository.findByKnowledgeItemId(reusable.getId().toString()))
                .thenReturn(List.of(skillUsage(6)));

        SkillDefinition created = selfImprovement.promoteToSkill(reusable);

        // -- then: SkillDefinition exists with template and variable list
        assertThat(created).isNotNull();
        assertThat(created.getTemplate()).contains("{{topic}}").contains("{{audience}}");
        assertThat(created.getTriggerConditions()).contains("topic").contains("audience");
        assertThat(created.getStage()).isEqualTo("SKILL");
        assertThat(created.getKnowledgeItemId()).isNotBlank();

        // The descendant KnowledgeItem is PENDING (auto-promotion never
        // bypasses human review).
        ArgumentCaptor<KnowledgeItem> itemCaptor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).save(itemCaptor.capture());
        KnowledgeItem savedItem = itemCaptor.getValue();
        assertThat(savedItem.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(savedItem.getType()).isEqualTo(KnowledgeType.SKILL);

        // Lineage edge captured: ancestor=reusable.id, descendant=savedItem.id.
        ArgumentCaptor<KnowledgeLineage> linCap = ArgumentCaptor.forClass(KnowledgeLineage.class);
        verify(lineageRepository).save(linCap.capture());
        KnowledgeLineage edge = linCap.getValue();
        assertThat(edge.getAncestorId()).isEqualTo(reusable.getId().toString());
        assertThat(edge.getDescendantId()).isEqualTo(savedItem.getId().toString());
        assertThat(edge.getRelationType()).isEqualTo("PROMOTED_FROM");
    }

    // ---- helpers ------------------------------------------------------

    private PromptCall call(UUID agent, UUID run) {
        return PromptCall.builder()
                .agentId(agent)
                .runId(run)
                .provider("openai")
                .model("gpt-4")
                .outcome("success")
                .toolsUsed("")
                .createdAt(Instant.now())
                .build();
    }

    private KnowledgeItem approvedPrompt(String name, String content) {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = KnowledgeItem.builder()
                .id(id)
                .name(name)
                .type(KnowledgeType.PROMPT)
                .description("seed prompt")
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        // Seed file content so the service can read it back.
        fileService.storeContent(item.getType(), item.getName(),
                item.getCurrentVersion(), content);
        return item;
    }

    private SkillDefinition skillUsage(int uses) {
        return SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("any")
                .stage("SKILL")
                .usageCount(uses)
                .build();
    }
}
