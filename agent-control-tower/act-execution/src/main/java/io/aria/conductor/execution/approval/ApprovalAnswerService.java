package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the operator's answer to a HITL ask (spec 4.4): free-text answer,
 * optionally marking the ask APPROVED/DENIED with a decision timestamp.
 *
 * <p>Two regimes share this route. A CLARIFICATION ask (2026-10-05 spec §5)
 * holds its run parked in {@code WAITING_INPUT}: answering it IS the decision —
 * the ask settles APPROVED with the answer as its reason, the answer is
 * recorded as the operator's {@code user} turn on the session trajectory, and
 * {@link RunInputCoordinator#submitAnswer} wakes the parked run to re-prompt.
 * Denial is not an answer: a waiting run is ended via the finalize route. The
 * wake (and the answer race it closes) sits inside this {@code @Transactional}
 * settlement: a {@code submitAnswer} refusal — the run is not parked in this
 * process, or a concurrent answer consumed the pending entry first — throws
 * before any commit, so the ask save and the trajectory row roll back together
 * and the controller surfaces 409.
 *
 * <p>Every other ask keeps the intentionally lighter legacy path — no gate
 * future unblocking and no workflow-advancing side effects — so answering a
 * legacy QUESTION ask still never resumes a paused run or advances an SDD
 * chain. Guards: only PENDING asks are answerable (a decided ask is immutable),
 * the approved/denied flag is restricted to QUESTION asks — gate approvals
 * (APPROVAL / REVIEW_REQUEST asks) must go through
 * {@link ApprovalGate#decideApproval} ({@code /decide}) so they get
 * the run-resume/workflow side effects — and ACP permission asks are refused
 * outright (R20.6: that record mirrors a live ACP request and is decided
 * through the permission coordinator).
 */
@Service
@RequiredArgsConstructor
public class ApprovalAnswerService {

    private final ApprovalRepository approvalRepository;
    private final RunInputCoordinator inputs;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final ApplicationEventPublisher events;

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
        // Plan B task 7: a CLARIFICATION ask parks its run in WAITING_INPUT — the
        // operator's answer IS the decision, and it must wake the run. This branch
        // returns its own settlement; the legacy path below stays untouched.
        if (approval.getSource() == ApprovalSource.CLARIFICATION) {
            if (Boolean.FALSE.equals(approved)) {
                throw new IllegalArgumentException(
                        "Deny a waiting run via POST /runs/{id}/finalize, not /answer");
            }
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException(
                        "An answer is required to continue a run waiting for input");
            }
            approval.setAnswer(answer);
            approval.setStatus(ApprovalStatus.APPROVED);
            approval.setReason(answer);
            approval.setDecidedAt(Instant.now());
            Approval settled = approvalRepository.save(approval);
            int nextTurn = trajectoryRepository.findMaxTurnNumberByRunId(approval.getRunId()) + 1;
            trajectoryRepository.save(SessionTrajectory.builder()
                    .runId(approval.getRunId())
                    .turnNumber(nextTurn)
                    .role("user")
                    .content(answer)
                    .build());
            // Throws when the run is not parked here (finalize raced ahead, the
            // entry was already consumed, or a restart dropped the park) — the
            // whole settlement above rolls back with it.
            inputs.submitAnswer(approval.getRunId(), answer);
            // Announce the decision exactly like ApprovalGate.decideApproval: the
            // dashboard broadcast and the notification listeners see the ask
            // settle. The native card-settle listener no-ops for CLARIFICATION
            // (it is ACP_PERMISSION-only by design); the run's own card carries
            // on with the woken run.
            events.publishEvent(new ApprovalDecidedEvent(this, approval.getId(), approval.getStatus()));
            return settled;
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
