package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalRepository approvalRepository;
    private final ApprovalGate approvalGate;

    public ApprovalController(ApprovalRepository approvalRepository, ApprovalGate approvalGate) {
        this.approvalRepository = approvalRepository;
        this.approvalGate = approvalGate;
    }

    @GetMapping
    public ResponseEntity<List<Approval>> listPending() {
        List<Approval> pending = approvalRepository.findByStatus(ApprovalStatus.PENDING);
        return ResponseEntity.ok(pending);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Approval> getApproval(@PathVariable UUID id) {
        return approvalRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable UUID id,
            @RequestBody DecideApprovalRequest request) {
        log.info("Approval decision: id={}, approved={}", id, request.approved());

        try {
            approvalGate.decideApproval(id, request.approved(), request.reason());
            return ResponseEntity.ok(Map.of(
                    "approvalId", id,
                    "approved", request.approved(),
                    "status", "processed"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    public record DecideApprovalRequest(boolean approved, String reason) {}
}