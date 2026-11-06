package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.PromoteKnowledgeRequest;
import io.aria.conductor.knowledge.dto.PromptCallStatsResponse;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.selfimprove.KnowledgeLineage;
import io.aria.conductor.knowledge.selfimprove.KnowledgeLineageRepository;
import io.aria.conductor.knowledge.selfimprove.PromotionEvaluator;
import io.aria.conductor.knowledge.selfimprove.SandboxExecutor;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static io.aria.conductor.test.TestDataBuilder.aKnowledgeItem;
import static io.aria.conductor.test.TestDataBuilder.aPromptCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SelfImprovementServiceTest {

    @Mock PromptCallRepository promptCallRepository;
    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock KnowledgeFileService fileService;
    @Mock PromotionEvaluator promotionEvaluator;
    @Mock SkillDefinitionRepository skillRepository;
    @Mock KnowledgeLineageRepository lineageRepository;
    @Mock SandboxExecutor sandboxExecutor;

    SelfImprovementService service;

    @BeforeEach
    void setUp() {
        service = new SelfImprovementService(promptCallRepository, itemRepository,
                versionRepository, fileService, promotionEvaluator, skillRepository,
                lineageRepository, sandboxExecutor);
    }

    // ---- recordPromptCall ----------------------------------------------

    @Test
    void recordPromptCall_persistsAndReturnsSavedEntity() {
        PromptCall call = aPromptCall().withProvider("anthropic").withModel("claude-3").build();
        when(promptCallRepository.save(call)).thenAnswer(inv -> inv.getArgument(0));

        PromptCall out = service.recordPromptCall(call);

        assertThat(out.getProvider()).isEqualTo("anthropic");
        assertThat(out.getModel()).isEqualTo("claude-3");
        ArgumentCaptor<PromptCall> captor = ArgumentCaptor.forClass(PromptCall.class);
        verify(promptCallRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(call);
    }

    // ---- promoteToKnowledge --------------------------------------------

    @Test
    void promoteToKnowledge_createsPendingItemAndVersionFromSourceCall() {
        long sourceId = 1234567890L;
        PromptCall source = aPromptCall()
                .withId(sourceId)
                .withProvider("openai")
                .withModel("gpt-4o")
                .withInputTokens(11)
                .withOutputTokens(22)
                .withLatencyMs(333)
                .build();
        when(promptCallRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(fileService.storeContent(eq(KnowledgeType.PROMPT), eq("my-prompt"), eq("v1.0.0"), anyString()))
                .thenReturn("prompt/my-prompt/v1.0.0.md");
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PromoteKnowledgeRequest request = PromoteKnowledgeRequest.builder()
                .targetType(KnowledgeType.PROMPT)
                .targetName("my-prompt")
                .build();

        KnowledgeItemResponse response = service.promoteToKnowledge(request, sourceId);

        ArgumentCaptor<KnowledgeItem> itemCaptor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).save(itemCaptor.capture());
        KnowledgeItem saved = itemCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("my-prompt");
        assertThat(saved.getType()).isEqualTo(KnowledgeType.PROMPT);
        assertThat(saved.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(saved.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        assertThat(saved.getCurrentVersion()).isEqualTo("v1.0.0");
        assertThat(saved.getFilePath()).isEqualTo("prompt/my-prompt/v1.0.0.md");
        assertThat(saved.getDescription()).contains(String.valueOf(sourceId));

        ArgumentCaptor<KnowledgeVersion> versionCaptor = ArgumentCaptor.forClass(KnowledgeVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        KnowledgeVersion kv = versionCaptor.getValue();
        assertThat(kv.getKnowledgeItemId()).isEqualTo(saved.getId());
        assertThat(kv.getVersion()).isEqualTo("v1.0.0");
        assertThat(kv.getStatus()).isEqualTo(VersionStatus.PENDING);
        assertThat(kv.getContent())
                .contains("Source ID: " + sourceId)
                .contains("openai")
                .contains("gpt-4o")
                .contains("Input Tokens: 11")
                .contains("Output Tokens: 22")
                .contains("Latency: 333ms");

        assertThat(response.getId()).isEqualTo(saved.getId());
        assertThat(response.getName()).isEqualTo("my-prompt");
        assertThat(response.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(response.getFilePath()).isEqualTo("prompt/my-prompt/v1.0.0.md");
    }

    @Test
    void promoteToKnowledge_withoutTargetName_derivesNameFromSourceId() {
        long sourceId = 9876543210L;
        when(promptCallRepository.findById(sourceId))
                .thenReturn(Optional.of(aPromptCall().withId(sourceId).build()));
        when(fileService.storeContent(any(), anyString(), anyString(), anyString())).thenReturn("p");
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PromoteKnowledgeRequest request = PromoteKnowledgeRequest.builder()
                .targetType(KnowledgeType.PROMPT)
                .build();

        KnowledgeItemResponse response = service.promoteToKnowledge(request, sourceId);

        // "promoted-" + first 8 chars of the source id
        assertThat(response.getName()).isEqualTo("promoted-98765432");
    }

    @Test
    void promoteToKnowledge_unknownSourceCall_throwsAndWritesNothing() {
        when(promptCallRepository.findById(404L)).thenReturn(Optional.empty());

        PromoteKnowledgeRequest request = PromoteKnowledgeRequest.builder()
                .targetType(KnowledgeType.PROMPT)
                .build();

        assertThatThrownBy(() -> service.promoteToKnowledge(request, 404L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(itemRepository, versionRepository, fileService);
    }

    // ---- getPromptCallStats / listPromptCalls ---------------------------

    @Test
    void getPromptCallStats_sumsTokensAcrossCalls() {
        UUID agentId = UUID.randomUUID();
        when(promptCallRepository.findByAgentId(agentId)).thenReturn(List.of(
                aPromptCall().withAgentId(agentId).withInputTokens(100).withOutputTokens(50).build(),
                aPromptCall().withAgentId(agentId).withInputTokens(7).withOutputTokens(3).build()));

        PromptCallStatsResponse stats = service.getPromptCallStats(agentId);

        assertThat(stats.getAgentId()).isEqualTo(agentId);
        assertThat(stats.getTotalCalls()).isEqualTo(2);
        assertThat(stats.getTotalInputTokens()).isEqualTo(107);
        assertThat(stats.getTotalOutputTokens()).isEqualTo(53);
    }

    @Test
    void getPromptCallStats_noCalls_returnsZeroTotals() {
        UUID agentId = UUID.randomUUID();
        when(promptCallRepository.findByAgentId(agentId)).thenReturn(List.of());

        PromptCallStatsResponse stats = service.getPromptCallStats(agentId);

        assertThat(stats.getTotalCalls()).isZero();
        assertThat(stats.getTotalInputTokens()).isZero();
        assertThat(stats.getTotalOutputTokens()).isZero();
    }

    @Test
    void listPromptCalls_agentIdWins_thenRunId_thenFindAll() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PromptCall byAgent = aPromptCall().withAgentId(agentId).build();
        PromptCall byRun = aPromptCall().withRunId(runId).build();
        PromptCall other = aPromptCall().build();
        when(promptCallRepository.findByAgentId(agentId)).thenReturn(List.of(byAgent));
        when(promptCallRepository.findByRunId(runId)).thenReturn(List.of(byRun));
        when(promptCallRepository.findAll()).thenReturn(List.of(other));

        assertThat(service.listPromptCalls(agentId, runId)).containsExactly(byAgent);
        assertThat(service.listPromptCalls(null, runId)).containsExactly(byRun);
        assertThat(service.listPromptCalls(null, null)).containsExactly(other);
    }

    // ---- promoteToSkill (Stage 2 -> 3) ----------------------------------

    @Test
    void promoteToSkill_approved_createsPendingSkillItemSkillDefinitionAndLineage() {
        KnowledgeItem prompt = approvedItem(KnowledgeType.PROMPT, "greeting-prompt");
        String content = "Say {{greeting}} to {{name}}, {{greeting}} again";
        when(promotionEvaluator.evaluateForStage3(prompt)).thenReturn(
                PromotionEvaluator.PromotionDecision.approve("SKILL", "ok", prompt));
        when(fileService.readContent(KnowledgeType.PROMPT, "greeting-prompt", "v1.0.0"))
                .thenReturn(Optional.of(content));
        when(fileService.storeContent(eq(KnowledgeType.SKILL), eq("greeting-prompt-skill"),
                eq("v1.0.0"), eq(content))).thenReturn("skill/greeting-prompt-skill/v1.0.0.yaml");
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepository.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillDefinition skill = service.promoteToSkill(prompt);

        ArgumentCaptor<KnowledgeItem> itemCaptor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).save(itemCaptor.capture());
        KnowledgeItem skillItem = itemCaptor.getValue();
        assertThat(skillItem.getName()).isEqualTo("greeting-prompt-skill");
        assertThat(skillItem.getType()).isEqualTo(KnowledgeType.SKILL);
        assertThat(skillItem.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(skillItem.getSensitivity()).isEqualTo(prompt.getSensitivity());
        assertThat(skillItem.getCurrentVersion()).isEqualTo("v1.0.0");
        assertThat(skillItem.getFilePath()).isEqualTo("skill/greeting-prompt-skill/v1.0.0.yaml");

        assertThat(skill.getName()).isEqualTo("greeting-prompt-skill");
        assertThat(skill.getTemplate()).isEqualTo(content);
        assertThat(skill.getStage()).isEqualTo("SKILL");
        assertThat(skill.getUsageCount()).isZero();
        assertThat(skill.getKnowledgeItemId()).isEqualTo(skillItem.getId().toString());
        // duplicate {{greeting}} deduplicated, insertion order preserved
        assertThat(skill.getTriggerConditions())
                .isEqualTo("{\"variables\":[\"greeting\",\"name\"]}");

        ArgumentCaptor<KnowledgeLineage> lineageCaptor = ArgumentCaptor.forClass(KnowledgeLineage.class);
        verify(lineageRepository).save(lineageCaptor.capture());
        KnowledgeLineage edge = lineageCaptor.getValue();
        assertThat(edge.getAncestorId()).isEqualTo(prompt.getId().toString());
        assertThat(edge.getDescendantId()).isEqualTo(skillItem.getId().toString());
        assertThat(edge.getRelationType()).isEqualTo("PROMOTED_FROM");
        assertThat(edge.getDepth()).isEqualTo(1);
    }

    @Test
    void promoteToSkill_missingFile_fallsBackToVersionRepositoryContent() {
        KnowledgeItem prompt = approvedItem(KnowledgeType.PROMPT, "db-prompt");
        when(promotionEvaluator.evaluateForStage3(prompt)).thenReturn(
                PromotionEvaluator.PromotionDecision.approve("SKILL", "ok", prompt));
        when(fileService.readContent(KnowledgeType.PROMPT, "db-prompt", "v1.0.0"))
                .thenReturn(Optional.empty());
        when(versionRepository.findByKnowledgeItemIdAndVersion(prompt.getId(), "v1.0.0"))
                .thenReturn(Optional.of(KnowledgeVersion.builder().content("db content {{x}}").build()));
        when(fileService.storeContent(any(), anyString(), anyString(), anyString())).thenReturn("p");
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepository.save(any(SkillDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillDefinition skill = service.promoteToSkill(prompt);

        assertThat(skill.getTemplate()).isEqualTo("db content {{x}}");
        assertThat(skill.getTriggerConditions()).isEqualTo("{\"variables\":[\"x\"]}");
    }

    @Test
    void promoteToSkill_rejectedByEvaluator_throwsAndWritesNothing() {
        KnowledgeItem prompt = approvedItem(KnowledgeType.PROMPT, "unused-prompt");
        when(promotionEvaluator.evaluateForStage3(prompt)).thenReturn(
                new PromotionEvaluator.PromotionDecision(false, "SKILL", "needs 5 uses", null));

        assertThatThrownBy(() -> service.promoteToSkill(prompt))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("needs 5 uses")
                .hasMessageContaining("APPROVED");
        verify(itemRepository, never()).save(any());
        verifyNoInteractions(skillRepository, lineageRepository, fileService);
    }

    @Test
    void promoteToSkill_withoutStageDependencies_throwsIllegalState() {
        SelfImprovementService legacy = new SelfImprovementService(
                promptCallRepository, itemRepository, versionRepository, fileService);

        assertThatThrownBy(() -> legacy.promoteToSkill(approvedItem(KnowledgeType.PROMPT, "p")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not wired");
        verify(itemRepository, never()).save(any());
    }

    // ---- promoteToScript (Stage 3 -> 4) ----------------------------------

    @Test
    void promoteToScript_sandboxPasses_createsPendingScriptItemAndLineage() {
        KnowledgeItem skill = approvedItem(KnowledgeType.SKILL, "invoice-skill");
        skill.setSensitivity(Sensitivity.CONFIDENTIAL);
        String content = "print('hello')";
        when(promotionEvaluator.evaluateForStage4(skill)).thenReturn(
                PromotionEvaluator.PromotionDecision.approve("SCRIPT", "ok", skill));
        when(fileService.readContent(KnowledgeType.SKILL, "invoice-skill", "v1.0.0"))
                .thenReturn(Optional.of(content));
        when(sandboxExecutor.execute(eq(content), eq("python"), anyMap()))
                .thenReturn(new SandboxExecutor.SandboxResult(0, "hello", "", 12L, false));
        when(fileService.storeContent(eq(KnowledgeType.SCRIPT), eq("invoice-skill-script"),
                eq("v1.0.0"), eq(content))).thenReturn("script/invoice-skill-script/v1.0.0.py");
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItem scriptItem = service.promoteToScript(skill);

        assertThat(scriptItem.getName()).isEqualTo("invoice-skill-script");
        assertThat(scriptItem.getType()).isEqualTo(KnowledgeType.SCRIPT);
        assertThat(scriptItem.getStatus()).isEqualTo(KnowledgeStatus.PENDING);
        assertThat(scriptItem.getSensitivity()).isEqualTo(Sensitivity.CONFIDENTIAL);
        assertThat(scriptItem.getFilePath()).isEqualTo("script/invoice-skill-script/v1.0.0.py");

        ArgumentCaptor<KnowledgeLineage> lineageCaptor = ArgumentCaptor.forClass(KnowledgeLineage.class);
        verify(lineageRepository).save(lineageCaptor.capture());
        assertThat(lineageCaptor.getValue().getAncestorId()).isEqualTo(skill.getId().toString());
        assertThat(lineageCaptor.getValue().getDescendantId()).isEqualTo(scriptItem.getId().toString());
        assertThat(lineageCaptor.getValue().getRelationType()).isEqualTo("PROMOTED_FROM");
    }

    @Test
    void promoteToScript_sandboxFails_throwsWithExitCodeAndWritesNothing() {
        KnowledgeItem skill = approvedItem(KnowledgeType.SKILL, "broken-skill");
        when(promotionEvaluator.evaluateForStage4(skill)).thenReturn(
                PromotionEvaluator.PromotionDecision.approve("SCRIPT", "ok", skill));
        when(fileService.readContent(KnowledgeType.SKILL, "broken-skill", "v1.0.0"))
                .thenReturn(Optional.of("raise SystemExit(3)"));
        when(sandboxExecutor.execute(anyString(), eq("python"), anyMap()))
                .thenReturn(new SandboxExecutor.SandboxResult(3, "", "boom", 5L, false));

        assertThatThrownBy(() -> service.promoteToScript(skill))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("exit=3")
                .hasMessageContaining("timedOut=false");
        verify(itemRepository, never()).save(any());
        verifyNoInteractions(lineageRepository);
    }

    @Test
    void promoteToScript_rejectedByEvaluator_neverExecutesSandbox() {
        KnowledgeItem skill = approvedItem(KnowledgeType.SKILL, "young-skill");
        when(promotionEvaluator.evaluateForStage4(skill)).thenReturn(
                new PromotionEvaluator.PromotionDecision(false, "SCRIPT", "needs 10 uses", null));

        assertThatThrownBy(() -> service.promoteToScript(skill))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("needs 10 uses");
        verifyNoInteractions(sandboxExecutor, itemRepository, lineageRepository);
    }

    // ---- extractVariables decision table ----------------------------------

    @ParameterizedTest(name = "[{index}] template={0}")
    @MethodSource("variableTemplates")
    void extractVariables_parsesMustacheTokens(String template, Set<String> expected) {
        assertThat(service.extractVariables(template))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    static Stream<Arguments> variableTemplates() {
        return Stream.of(
                Arguments.of("Hello {{name}}, meet {{other}}", Set.of("name", "other")),
                Arguments.of("{{ spaced }} and {{spaced}}", Set.of("spaced")),
                Arguments.of("no variables here", Set.of()),
                Arguments.of("", Set.of()),
                Arguments.of(null, Set.of()),
                Arguments.of("{{bad name}} {{good_1}}", Set.of("good_1")));
    }

    // ---- helpers ----------------------------------------------------------

    private static KnowledgeItem approvedItem(KnowledgeType type, String name) {
        return aKnowledgeItem()
                .withType(type)
                .withName(name)
                .withStatus(KnowledgeStatus.APPROVED)
                .withCurrentVersion("v1.0.0")
                .build();
    }
}
