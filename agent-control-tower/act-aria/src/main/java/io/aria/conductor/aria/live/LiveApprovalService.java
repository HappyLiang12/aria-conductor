package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.security.CredentialRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The approval row a gated live write waits under (spec X15). The row is the engine's
 * own: {@code TOOL_CALL}/{@code APPROVAL}, PENDING, bound to the turn's lazy live run,
 * carrying the tool name, a redacted bounded argument document and the risk tier. No
 * parallel approval table and no second decision route — the operator answers with the
 * existing {@code POST /approvals/{id}/decide}, and the router sees the same
 * {@code ApprovalDecidedEvent} every other gate is decided by.
 *
 * <p>{@code ApprovalRequestedEvent} is deliberately not published. Its listeners mirror
 * asks onto kanban cards and run SSE streams; a voice ask belongs on the phone, and the
 * live run owns no engine stream. The row alone is what the REST list and the decide
 * route need.
 *
 * <p>Privacy: only the tool name and the row's own size ever reach a log line.
 */
@Slf4j
@Service
public class LiveApprovalService {

    /** The argument document is bounded before it becomes a TEXT column. */
    static final int MAX_ARGUMENTS_CHARS = 4 * 1024;
    /** What a released ask records when its run could not take the park. */
    static final String RELEASED_REASON = "Live run could not wait for an approval";

    private final ApprovalRepository approvals;
    private final ObjectMapper json;

    public LiveApprovalService(ApprovalRepository approvals, ObjectMapper json) {
        this.approvals = approvals;
        this.json = json;
    }

    /**
     * Persists the PENDING ask for one gated call.
     *
     * @param runId     the turn's lazy live run (never null)
     * @param toolName  the gated tool
     * @param arguments the bridge's argument object, or null when it sent none
     * @param riskTier  the reviewed tier the phone renders
     * @return the approval id the decision will arrive under
     */
    public UUID createPending(UUID runId, String toolName, JsonNode arguments, String riskTier) {
        Approval approval = Approval.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .askType(Approval.AskType.APPROVAL)
                .reason("live tool call: " + toolName)
                .toolName(toolName)
                .arguments(boundedArguments(arguments))
                .riskTier(riskTier)
                .requestedAt(Instant.now())
                .build();
        approvals.save(approval);
        log.debug("live approval created: id={} run={} tool={}", approval.getId(), runId, toolName);
        return approval.getId();
    }

    /**
     * Releases a PENDING ask whose run could not take the park (a cancel or a close won
     * the race). Without this the row would wait for an operator on a run the phone was
     * already told failed.
     */
    public void release(UUID approvalId) {
        if (approvalId == null) {
            return;
        }
        approvals.findById(approvalId)
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .ifPresent(approval -> {
                    approval.setStatus(ApprovalStatus.EXPIRED);
                    approval.setReason(RELEASED_REASON);
                    approval.setDecidedAt(Instant.now());
                    approvals.save(approval);
                    log.debug("live approval released: id={}", approvalId);
                });
    }

    /** A redacted, bounded argument document; null when the bridge sent none. */
    private String boundedArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return null;
        }
        String raw;
        try {
            raw = json.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            return null;
        }
        String redacted = CredentialRedactor.redact(raw);
        if (redacted == null || redacted.length() <= MAX_ARGUMENTS_CHARS) {
            return redacted;
        }
        return redacted.substring(0, MAX_ARGUMENTS_CHARS) + " [truncated]";
    }
}
