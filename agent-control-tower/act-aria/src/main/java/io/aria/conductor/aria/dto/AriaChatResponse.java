package io.aria.conductor.aria.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AriaChatResponse {

    /**
     * Run UUID — uniquely identifies this execution.
     */
    private String runId;

    /**
     * Persistent conversation identifier echoed from the request.
     */
    private String conversationId;

    private String message;
    private String intent;
    private List<AriaAction> actionsTaken;
    private Instant timestamp;

    /**
     * Observed run status at the moment this reply was produced (F2). Present so
     * a client can tell a completed turn from a still-running one instead of
     * parsing the message. Additive: clients that ignore it keep working.
     */
    private String status;

    /** Stable failure identity for FAILED/ABORTED runs (null otherwise). */
    private String errorCode;

    /** Human-readable failure reason for FAILED/ABORTED runs (null otherwise). */
    private String errorMessage;
}
