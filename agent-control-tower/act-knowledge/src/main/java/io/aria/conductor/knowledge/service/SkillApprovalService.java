package io.aria.conductor.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Governed skill authoring + approval path (PENDING -> APPROVED) that mirrors tool
 * governance. {@link #submitSkillForApproval} creates a PENDING {@code type=SKILL}
 * {@link KnowledgeItem} plus its {@link KnowledgeVersion}, and a disabled
 * {@link SkillDefinition} linked via {@code knowledgeItemId}. Approving that item is
 * the single gate that makes the linked skill eligible for agent assignment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillApprovalService {

    /** Initial major version for an authored skill (matches the promotion flow). */
    static final String SKILL_INITIAL_VERSION = "v1.0.0";

    private static final String DEFAULT_TIER = "TIER_2";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final SkillDefinitionRepository skillRepository;

    @Transactional
    public SkillDefinition submitSkillForApproval(SkillCreateRequest request) {
        String name = request.getName().trim();
        String template = request.getTemplate().trim();
        if (skillRepository.existsByName(name)) {
            throw new IllegalStateException("A skill named '" + name + "' already exists");
        }
        validateJsonIfStructured("triggerConditions", request.getTriggerConditions());
        validateJsonIfStructured("examples", request.getExamples());

        String description = (request.getDescription() == null || request.getDescription().isBlank())
                ? "Skill: " + name
                : request.getDescription();
        Sensitivity sensitivity = request.getSensitivity() != null
                ? request.getSensitivity() : Sensitivity.INTERNAL;

        // PENDING SKILL KnowledgeItem + its v1.0.0 KnowledgeVersion (review flow resolves a version).
        KnowledgeItem item = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(KnowledgeType.SKILL)
                .description(description)
                .status(KnowledgeStatus.PENDING)
                .sensitivity(sensitivity)
                .currentVersion(SKILL_INITIAL_VERSION)
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        item = itemRepository.save(item);

        KnowledgeVersion version = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(item.getId())
                .version(SKILL_INITIAL_VERSION)
                .status(VersionStatus.PENDING)
                .content(template)
                .createdAt(Instant.now())
                .build();
        versionRepository.save(version);

        // Disabled SkillDefinition gated on the item above being approved.
        SkillDefinition skill = SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .description(description)
                .template(template)
                .triggerConditions(request.getTriggerConditions())
                .examples(request.getExamples())
                .sourcePromptIds(null)
                .knowledgeItemId(item.getId().toString())
                .usageCount(0)
                .stage("SKILL")
                .enabled(false)
                .tier(request.getTier() != null && !request.getTier().isBlank()
                        ? request.getTier() : DEFAULT_TIER)
                .build();
        skill = skillRepository.save(skill);

        log.info("Skill {} submitted for approval, linked KI: {}", skill.getId(), item.getId());
        return skill;
    }

    @Transactional
    public void onKnowledgeApproved(UUID knowledgeItemId) {
        List<SkillDefinition> linked = skillRepository.findByKnowledgeItemId(knowledgeItemId.toString());
        for (SkillDefinition skill : linked) {
            if (!skill.isEnabled()) {
                skill.setEnabled(true);
                skillRepository.save(skill);
                log.info("Skill {} enabled after linked knowledge item {} approved",
                        skill.getId(), knowledgeItemId);
            }
        }
    }

    /**
     * Accepts free text, but when the caller supplies a structured payload (starts with
     * {@code {}} or {@code []}) it must be valid JSON — mirrors the governed review contract.
     */
    private static void validateJsonIfStructured(String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                OBJECT_MAPPER.readTree(trimmed);
            } catch (Exception e) {
                throw new IllegalArgumentException(field + " must be valid JSON");
            }
        }
    }
}
