package io.aria.conductor.knowledge.controller;

import io.aria.conductor.knowledge.dto.SkillCreateRequest;
import io.aria.conductor.knowledge.dto.SkillResponse;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import io.aria.conductor.knowledge.service.SkillApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillDefinitionRepository skillRepo;
    private final SkillApprovalService skillApprovalService;

    @GetMapping
    public ResponseEntity<List<SkillResponse>> listSkills(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Boolean enabled) {
        List<SkillDefinition> skills;
        if (stage != null) {
            if (Boolean.TRUE.equals(enabled)) {
                skills = skillRepo.findByStageAndEnabledTrue(stage);
            } else if (Boolean.FALSE.equals(enabled)) {
                skills = skillRepo.findByStageAndEnabledFalse(stage);
            } else {
                skills = skillRepo.findByStage(stage);
            }
        } else if (Boolean.TRUE.equals(enabled)) {
            skills = skillRepo.findByEnabledTrue();
        } else if (Boolean.FALSE.equals(enabled)) {
            skills = skillRepo.findByEnabledFalse();
        } else {
            skills = skillRepo.findAll();
        }
        return ResponseEntity.ok(skills.stream().map(this::toResponse).toList());
    }

    @GetMapping("/list")
    public ResponseEntity<List<SkillResponse>> listSkillsAlias(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Boolean enabled) {
        return listSkills(stage, enabled);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillResponse> getSkill(@PathVariable String id) {
        return skillRepo.findById(id)
                .map(skill -> ResponseEntity.ok(toResponse(skill)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Author a new skill: creates a PENDING SKILL KnowledgeItem + disabled SkillDefinition. */
    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody SkillCreateRequest request) {
        SkillDefinition skill = skillApprovalService.submitSkillForApproval(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(skill));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<SkillResponse> toggleSkill(@PathVariable String id) {
        return skillRepo.findById(id).map(skill -> {
            skill.setEnabled(!skill.isEnabled());
            return ResponseEntity.ok(toResponse(skillRepo.save(skill)));
        }).orElse(ResponseEntity.notFound().build());
    }

    private SkillResponse toResponse(SkillDefinition skill) {
        return SkillResponse.builder()
                .id(skill.getId())
                .name(skill.getName())
                .description(skill.getDescription())
                .template(skill.getTemplate())
                .triggerConditions(skill.getTriggerConditions())
                .examples(skill.getExamples())
                .stage(skill.getStage())
                .tier(skill.getTier())
                .enabled(skill.isEnabled())
                .usageCount(skill.getUsageCount())
                .knowledgeItemId(skill.getKnowledgeItemId())
                .createdAt(skill.getCreatedAt())
                .updatedAt(skill.getUpdatedAt())
                .build();
    }
}
