package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.knowledge.dto.*;
import io.aria.conductor.knowledge.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping
    public ResponseEntity<KnowledgeItemResponse> submitKnowledge(@Valid @RequestBody CreateKnowledgeRequest request) {
        KnowledgeItemResponse response = knowledgeService.submitKnowledge(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeItemResponse>> listKnowledge(
            @RequestParam(required = false) KnowledgeType type,
            @RequestParam(required = false) KnowledgeStatus status) {
        List<KnowledgeItemResponse> response = knowledgeService.listKnowledge(type, status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeItemResponse> getKnowledge(@PathVariable UUID id) {
        KnowledgeItemResponse response = knowledgeService.getKnowledge(id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeItemResponse> updateKnowledge(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateKnowledgeRequest request) {
        KnowledgeItemResponse response = knowledgeService.updateKnowledge(id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<KnowledgeItemResponse> reviewKnowledge(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        KnowledgeItemResponse response = knowledgeService.reviewKnowledge(id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<KnowledgeItemResponse> retireKnowledge(@PathVariable UUID id) {
        KnowledgeItemResponse response = knowledgeService.retireKnowledge(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/yaml")
    public ResponseEntity<String> getYamlContent(@PathVariable UUID id) {
        String yaml = knowledgeService.getYamlContent(id, null);
        if (yaml == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml"))
                .body(yaml);
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<KnowledgeVersionResponse>> getVersions(@PathVariable UUID id) {
        List<KnowledgeVersionResponse> response = knowledgeService.getVersions(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/versions/{version}")
    public ResponseEntity<KnowledgeVersionResponse> getVersionContent(
            @PathVariable UUID id,
            @PathVariable String version) {
        KnowledgeVersionResponse response = knowledgeService.getVersionContent(id, version);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<KnowledgeItemResponse> promoteKnowledge(
            @PathVariable UUID id,
            @Valid @RequestBody PromoteKnowledgeRequest request) {
        KnowledgeItemResponse response = knowledgeService.promoteKnowledgeItem(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/stats")
    public ResponseEntity<KnowledgeStatsResponse> getStats() {
        KnowledgeStatsResponse response = knowledgeService.getStats();
        return ResponseEntity.ok(response);
    }
}
