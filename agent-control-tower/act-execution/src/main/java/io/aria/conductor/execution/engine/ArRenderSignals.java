package io.aria.conductor.execution.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Emits one {@code ar_render} frame on a run's live SSE stream. The frame is the
 * device contract: the tool call's own transport never reaches the glasses on the
 * opencode core (it only raises progress events), so the tool pushes the render
 * directly to the run's stream instead of relying on a tool_call frame.
 *
 * <p>Best-effort by design: a run with no live stream (a sync turn, a closed
 * client) is not an error; the tool call's audit row and its ack remain the
 * durable record. The run id is the actor's own run, never a caller-supplied
 * argument.
 */
@Component
public class ArRenderSignals {

    /** The device-facing event name, stable across cores. */
    public static final String EVENT_NAME = "ar_render";

    private final RunStreamRegistry registry;

    public ArRenderSignals(RunStreamRegistry registry) {
        this.registry = registry;
    }

    /**
     * Emits {@code {runId, renderId, render}} for one render instruction.
     *
     * @return true when the frame was written to a live stream; false when no
     *         stream exists or the write failed (never an exception)
     */
    public boolean emit(UUID runId, Map<String, Object> render) {
        if (runId == null || render == null) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId.toString());
        payload.put("renderId", UUID.randomUUID().toString());
        payload.put("render", render);
        return registry.emit(runId, EVENT_NAME, payload);
    }
}
