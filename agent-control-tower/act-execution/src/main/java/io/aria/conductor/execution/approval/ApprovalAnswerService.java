package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
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
 */
@Service
@RequiredArgsConstructor
public class ApprovalAnswerService {

    private final ApprovalRepository approvalRepository;

    @Transactional
    public Approval answer(UUID id, String answer, Boolean approved, String reason) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));
        approval.setAnswer(answer);
        if (approved != null) {
            approval.setStatus(approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED);
            approval.setDecidedAt(Instant.now());
            if (reason != null) approval.setReason(reason);
        }
        return approvalRepository.save(approval);
    }
}
