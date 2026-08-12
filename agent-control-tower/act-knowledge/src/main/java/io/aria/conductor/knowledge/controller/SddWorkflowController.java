package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.knowledge.sdd.SpecReviewCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
public class SddWorkflowController {

    private final SpecReviewCoordinator coordinator;

    public SddWorkflowController(SpecReviewCoordinator coordinator) { this.coordinator = coordinator; }

    @PostMapping("/{id}/resubmit-approval")
    public ResponseEntity<Approval> resubmitApproval(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(coordinator.resubmitApproval(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}