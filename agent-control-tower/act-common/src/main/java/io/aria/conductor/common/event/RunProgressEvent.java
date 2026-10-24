package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Streamed progress fragment published by the OpenCode progress pump. Persisted to
 * run_progress_events and broadcast over WS.
 *
 * <p>A row is an <strong>observation of a part</strong>, and the part is its
 * identity: {@link #getPartId()} is the stable native part id the pump observed,
 * while {@link #getSeq()} is the monotonic order of the observations. One part that
 * grows (an answer being written, a tool whose output arrives on completion)
 * therefore produces several rows with the SAME {@code partId} and strictly greater
 * {@code seq}; consumers merge on {@code partId} instead of appending.
 * {@code partId} is null for a row whose core reported no part id (and for every row
 * persisted before the {@code part_id} column existed), which is exactly the case
 * where {@code seq} stays the only identity — see the drawer's documented fallback.
 */
@Getter
public class RunProgressEvent extends ApplicationEvent {

    /**
     * Origin of the progress fragment.
     *
     * <p>{@link #THINKING} is what the model works out; {@link #ANSWER} is what it
     * says back (issue #103: both used to be THINKING, so the Live Activity Stream
     * showed the reply under the think affordance). Every kind here is assistant
     * activity — the operator's own prompt is never a progress fragment.
     */
    public enum Kind { THINKING, ANSWER, TOOL_CALL, TOOL_RESULT, STATUS, ERROR }

    private final UUID runId;
    private final UUID agentId;
    private final int iteration;
    private final Kind kind;
    private final String content;
    private final String toolName;
    /** Monotonic order of observations within the run: the replay order and the dedupe fallback. */
    private final long seq;
    /** Stable identity of the observed part; null when the core reported none. */
    private final String partId;

    public RunProgressEvent(Object source, UUID runId, UUID agentId, int iteration,
                            Kind kind, String content, String toolName, long seq, String partId) {
        super(source);
        this.runId = runId;
        this.agentId = agentId;
        this.iteration = iteration;
        this.kind = kind;
        this.content = content != null ? content : "";
        this.toolName = toolName;
        this.seq = seq;
        this.partId = partId;
    }
}
