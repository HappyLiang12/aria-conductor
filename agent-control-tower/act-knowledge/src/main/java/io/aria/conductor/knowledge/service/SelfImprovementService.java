package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.*;
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
import io.aria.conductor.execution.repository.PromptCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SelfImprovementService {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovementService.class);

    /** Matches Mustache-style template variables like {{name}}. */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private final PromptCallRepository promptCallRepository;
    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeFileService fileService;

    /** Optional Stage 3-5 dependencies; null in legacy unit tests. */
    private final PromotionEvaluator promotionEvaluator;
    private final SkillDefinitionRepository skillRepository;
    private final KnowledgeLineageRepository lineageRepository;
    private final SandboxExecutor sandboxExecutor;

    public SelfImprovementService(PromptCallRepository promptCallRepository,
                                  KnowledgeItemRepository itemRepository,
                                  KnowledgeVersionRepository versionRepository,
                                  KnowledgeFileService fileService) {
        this(promptCallRepository, itemRepository, versionRepository, fileService,
                null, null, null, null);
    }

    @Autowired
    public SelfImprovementService(PromptCallRepository promptCallRepository,
                                  KnowledgeItemRepository itemRepository,
                                  KnowledgeVersionRepository versionRepository,
                                  KnowledgeFileService fileService,
                                  PromotionEvaluator promotionEvaluator,
                                  SkillDefinitionRepository skillRepository,
                                  KnowledgeLineageRepository lineageRepository,
                                  SandboxExecutor sandboxExecutor) {
        this.promptCallRepository = promptCallRepository;
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.fileService = fileService;
        this.promotionEvaluator = promotionEvaluator;
        this.skillRepository = skillRepository;
        this.lineageRepository = lineageRepository;
        this.sandboxExecutor = sandboxExecutor;
    }

    @Transactional
    public PromptCall recordPromptCall(PromptCall promptCall) {
        log.debug("Recording prompt call for agent={}, run={}", promptCall.getAgentId(), promptCall.getRunId());
        return promptCallRepository.save(promptCall);
    }

    @Transactional
    public KnowledgeItemResponse promoteToKnowledge(PromoteKnowledgeRequest request, Long sourcePromptCallId) {
        PromptCall source = promptCallRepository.findById(sourcePromptCallId)
                .orElseThrow(() -> new ResourceNotFoundException("PromptCall", sourcePromptCallId));

        String name = request.getTargetName() != null ? request.getTargetName()
                : "promoted-" + sourcePromptCallId.toString().substring(0, 8);
        String version = "v1.0.0"; // Major version on promotion
        String content = buildPromotedContent(source);

        KnowledgeItem item = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(request.getTargetType())
                .description("Promoted from prompt call " + sourcePromptCallId)
                .status(KnowledgeStatus.PENDING)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion(version)
                .createdAt(Instant.now())
                .build();

        String filePath = fileService.storeContent(item.getType(), item.getName(), version, content);
        item.setFilePath(filePath);
        item = itemRepository.save(item);

        KnowledgeVersion kv = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(item.getId())
                .version(version)
                .status(VersionStatus.PENDING)
                .content(content)
                .createdAt(Instant.now())
                .build();
        versionRepository.save(kv);

        log.info("Promoted prompt call {} to knowledge item: id={}, type={}, name={}",
                sourcePromptCallId, item.getId(), request.getTargetType(), name);

        return KnowledgeItemResponse.builder()
                .id(item.getId())
                .name(item.getName())
                .type(item.getType())
                .description(item.getDescription())
                .currentVersion(item.getCurrentVersion())
                .status(item.getStatus())
                .sensitivity(item.getSensitivity())
                .filePath(item.getFilePath())
                .createdAt(item.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public PromptCallStatsResponse getPromptCallStats(UUID agentId) {
        List<PromptCall> calls = promptCallRepository.findByAgentId(agentId);
        long totalInputTokens = calls.stream().mapToLong(PromptCall::getInputTokens).sum();
        long totalOutputTokens = calls.stream().mapToLong(PromptCall::getOutputTokens).sum();

        return PromptCallStatsResponse.builder()
                .agentId(agentId)
                .totalCalls(calls.size())
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .build();
    }

    @Transactional(readOnly = true)
    public List<PromptCall> listPromptCalls(UUID agentId, UUID runId) {
        if (agentId != null) {
            return promptCallRepository.findByAgentId(agentId);
        } else if (runId != null) {
            return promptCallRepository.findByRunId(runId);
        }
        return promptCallRepository.findAll();
    }

    /**
     * Stage 2 → 3: promote a reusable prompt into a {@link SkillDefinition}.
     * Always creates the descendant {@link KnowledgeItem} as PENDING; the
     * underlying anti-gaming gate is enforced by {@link PromotionEvaluator}.
     */
    @Transactional
    public SkillDefinition promoteToSkill(KnowledgeItem reusablePrompt) {
        requireStageDeps();
        PromotionEvaluator.PromotionDecision d = promotionEvaluator.evaluateForStage3(reusablePrompt);
        if (!d.approved()) {
            throw new InvalidStateTransitionException("KnowledgeItem",
                    String.valueOf(reusablePrompt == null ? null : reusablePrompt.getStatus()),
                    "SKILL: " + d.reason());
        }

        String content = readContent(reusablePrompt);
        Set<String> vars = extractVariables(content);

        // New KnowledgeItem (PENDING) carrying review state for the skill.
        KnowledgeItem skillItem = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(reusablePrompt.getName() + "-skill")
                .type(KnowledgeType.SKILL)
                .description("Skill promoted from prompt " + reusablePrompt.getId())
                .status(KnowledgeStatus.PENDING)
                .sensitivity(reusablePrompt.getSensitivity())
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        String filePath = fileService.storeContent(skillItem.getType(), skillItem.getName(),
                skillItem.getCurrentVersion(), content);
        skillItem.setFilePath(filePath);
        skillItem = itemRepository.save(skillItem);

        SkillDefinition skill = SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name(skillItem.getName())
                .description(skillItem.getDescription())
                .template(content)
                .triggerConditions("{\"variables\":" + jsonArray(vars) + "}")
                .examples("[]")
                .knowledgeItemId(skillItem.getId().toString())
                .stage("SKILL")
                .usageCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        skill = skillRepository.save(skill);

        recordLineage(reusablePrompt.getId().toString(),
                skillItem.getId().toString(), "PROMOTED_FROM");

        log.info("Promoted reusable prompt {} -> skill {} ({} variables)",
                reusablePrompt.getId(), skill.getId(), vars.size());
        return skill;
    }

    /**
     * Stage 3 → 4: validate that {@code skill} compiles/executes via
     * {@link SandboxExecutor} and create the descendant SCRIPT item. Tests
     * pass = sandbox exit code 0 within the timeout budget.
     */
    @Transactional
    public KnowledgeItem promoteToScript(KnowledgeItem skill) {
        requireStageDeps();
        PromotionEvaluator.PromotionDecision d = promotionEvaluator.evaluateForStage4(skill);
        if (!d.approved()) {
            throw new InvalidStateTransitionException("KnowledgeItem",
                    String.valueOf(skill == null ? null : skill.getStatus()),
                    "SCRIPT: " + d.reason());
        }

        String content = readContent(skill);
        SandboxExecutor.SandboxResult result =
                sandboxExecutor.execute(content, "python", Map.of());
        if (!result.isSuccess()) {
            throw new InvalidStateTransitionException("KnowledgeItem",
                    skill.getStatus().name(),
                    "SCRIPT: sandbox failed (exit=" + result.exitCode()
                            + ", timedOut=" + result.timedOut() + ")");
        }

        KnowledgeItem scriptItem = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(skill.getName() + "-script")
                .type(KnowledgeType.SCRIPT)
                .description("Script promoted from skill " + skill.getId())
                .status(KnowledgeStatus.PENDING)
                .sensitivity(skill.getSensitivity())
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        String filePath = fileService.storeContent(scriptItem.getType(), scriptItem.getName(),
                scriptItem.getCurrentVersion(), content);
        scriptItem.setFilePath(filePath);
        scriptItem = itemRepository.save(scriptItem);

        recordLineage(skill.getId().toString(),
                scriptItem.getId().toString(), "PROMOTED_FROM");
        log.info("Promoted skill {} -> script {}", skill.getId(), scriptItem.getId());
        return scriptItem;
    }

    Set<String> extractVariables(String template) {
        Set<String> out = new LinkedHashSet<>();
        if (template == null) return out;
        Matcher m = TEMPLATE_VAR.matcher(template);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private String readContent(KnowledgeItem item) {
        return fileService.readContent(item.getType(), item.getName(), item.getCurrentVersion())
                .orElseGet(() -> versionRepository
                        .findByKnowledgeItemIdAndVersion(item.getId(), item.getCurrentVersion())
                        .map(KnowledgeVersion::getContent)
                        .orElse(""));
    }

    private void recordLineage(String ancestorId, String descendantId, String relation) {
        if (lineageRepository == null) return;
        KnowledgeLineage edge = KnowledgeLineage.builder()
                .id(UUID.randomUUID().toString())
                .ancestorId(ancestorId)
                .descendantId(descendantId)
                .depth(1)
                .relationType(relation)
                .createdAt(Instant.now())
                .build();
        lineageRepository.save(edge);
    }

    private void requireStageDeps() {
        if (promotionEvaluator == null || skillRepository == null
                || lineageRepository == null || sandboxExecutor == null) {
            throw new IllegalStateException(
                    "Stage 3-5 promotion dependencies are not wired ("
                            + "PromotionEvaluator/SkillDefinitionRepository/"
                            + "KnowledgeLineageRepository/SandboxExecutor)");
        }
    }

    private static String jsonArray(Set<String> tokens) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String t : tokens) {
            if (!first) sb.append(',');
            sb.append('"').append(t.replace("\"", "\\\"")).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }

    private String buildPromotedContent(PromptCall source) {
        return String.format("""
                # Promoted from Prompt Call
                - Source ID: %d
                - Agent: %s
                - Run: %s
                - Provider: %s
                - Model: %s
                - Input Tokens: %d
                - Output Tokens: %d
                - Latency: %dms
                - Created: %s
                """,
                source.getId(),
                source.getAgentId(),
                source.getRunId(),
                source.getProvider(),
                source.getModel(),
                source.getInputTokens(),
                source.getOutputTokens(),
                source.getLatencyMs(),
                source.getCreatedAt());
    }
}
