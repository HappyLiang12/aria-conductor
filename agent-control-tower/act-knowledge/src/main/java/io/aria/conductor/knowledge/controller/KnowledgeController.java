package io.aria.conductor.knowledge.controller;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.common.exception.ActionableFailure;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.knowledge.dto.*;
import io.aria.conductor.knowledge.service.KnowledgeService;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final WorkflowTemplateService workflowTemplateService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               WorkflowTemplateService workflowTemplateService) {
        this.knowledgeService = knowledgeService;
        this.workflowTemplateService = workflowTemplateService;
    }

    /** Template instantiation parameters: {@code {parameters: {issueRef: "#1"}}}. */
    public record InstantiateWorkflowRequest(Map<String, String> parameters) {}

    @PostMapping("/{id}/instantiate-workflow")
    public ResponseEntity<WorkflowResponse> instantiateWorkflow(
            @PathVariable UUID id,
            @RequestBody(required = false) InstantiateWorkflowRequest request) {
        Map<String, String> params = request != null && request.parameters() != null
                ? request.parameters() : Map.of();
        return ResponseEntity.ok(workflowTemplateService.instantiateTemplate(id, params));
    }


    @PostMapping
    public ResponseEntity<KnowledgeItemResponse> submitKnowledge(@Valid @RequestBody CreateKnowledgeRequest request) {
        KnowledgeItemResponse response = knowledgeService.submitKnowledge(request, request.getYamlContent());
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

    /**
     * Validation failures from template instantiation (e.g. agent role not resolvable,
     * item not a WORKFLOW, template not APPROVED) are client input errors -> 400,
     * not server errors.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return rejection(e, "Invalid workflow request.");
    }

    /** State errors from template instantiation (e.g. template/version state mismatch). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return rejection(e, "The workflow template is not in a valid state.");
    }

    /**
     * This module-local handler must not bypass the actionable contract: a
     * failure that names a repair surface keeps its code/surface in the body.
     */
    private static ResponseEntity<Map<String, String>> rejection(RuntimeException e, String fallback) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", messageOf(e, fallback));
        if (e instanceof ActionableFailure actionable) {
            body.put("code", actionable.code());
            body.put("surface", actionable.surface().name());
        }
        return ResponseEntity.badRequest().body(body);
    }

    /** Map.of rejects null values, so a message-less exception must not turn a 400 into a 500. */
    private static String messageOf(RuntimeException e, String fallback) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
