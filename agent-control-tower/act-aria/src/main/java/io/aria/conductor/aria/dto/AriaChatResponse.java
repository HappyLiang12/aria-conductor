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
}
