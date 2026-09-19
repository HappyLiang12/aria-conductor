package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the operator's answer to a HITL ask (spec 4.4): free-text answer,
 * optionally marking the ask APPROVED/DENIED with a decision timestamp.
 *
 * <p>Intentionally lighter than {@link ApprovalGate#decideApproval}: no gate
 * future unblocking and no workflow-advancing {@code ApprovalDecidedEvent} —
 * answering a QUESTION ask must not resume the paused run or advance an SDD chain.
 *
 * <p>Guards: only PENDING asks are answerable (a decided ask is immutable), and
 * the approved/denied flag is restricted to QUESTION asks — gate approvals
 * (APPROVAL / REVIEW_REQUEST asks) must go through
 * {@link ApprovalGate#decideApproval} ({@code /decide}) so they get
 * the run-resume/workflow side effects. Free-text answer-only updates remain
 * allowed for any PENDING ask.
 */
@Service
@RequiredArgsConstructor
public class ApprovalAnswerService {

    private final ApprovalRepository approvalRepository;

    @Transactional
    public Approval answer(UUID id, String answer, Boolean approved, String reason) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("Approval already decided: " + approval.getStatus());
        }
        // R20.6: free-text answers never resolve an ACP permission ask; that record
        // mirrors a live ACP request and must be decided through /decide.
        if (approval.getSource() == ApprovalSource.ACP_PERMISSION) {
            throw new IllegalArgumentException(
                    "ACP permission asks are decided via /decide (ACP permission coordinator), not /answer");
        }
        if (approved != null && approval.getAskType() != Approval.AskType.QUESTION) {
            throw new IllegalArgumentException(
                    "Only QUESTION asks are answerable here; gate approvals must use /decide");
        }
        approval.setAnswer(answer);
        if (approved != null) {
            approval.setStatus(approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED);
            approval.setDecidedAt(Instant.now());
            if (reason != null) approval.setReason(reason);
        }
        return approvalRepository.save(approval);
    }
}
