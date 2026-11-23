package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The two writers of a live turn (spec §"Event translation"): phone frames (run_event,
 * tool_status, approval_required) and bridge lines (tool_result, tower). Every frame of
 * one socket goes through {@link LiveSession#lock()} so a phone write from the bridge
 * reader thread and one from an approval listener never interleave a frame.
 *
 * <p>A write is best-effort by design: a closed socket, a socket-less session (the unit
 * and integration harnesses) or a bridge that already exited drops the frame instead of
 * failing the caller — the turn's state is the run row, not a delivered byte. A failure
 * is logged as itself, never as the payload it carried.
 */
@Component
public class LiveFrames {

    static final String TYPE_RUN_EVENT = "run_event";
    static final String TYPE_TOOL_STATUS = "tool_status";
    static final String TYPE_APPROVAL_REQUIRED = "approval_required";
    static final String TYPE_TOOL_RESULT = "tool_result";
    static final String TYPE_TOWER = "tower";

    private static final Logger log = LoggerFactory.getLogger(LiveFrames.class);

    private final ObjectMapper json;

    @Autowired
    public LiveFrames(ObjectMapper json) {
        this.json = json;
    }

    /** One JSON frame to the phone; a closed socket (or none) drops it. */
    public void toPhone(LiveSession live, Map<String, Object> payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.debug("aria live phone frame not serializable");
            return;
        }
        toPhoneRaw(live, body);
    }

    /** One already-serialized frame to the phone (a bridge {@code control} event). */
    public void toPhoneRaw(LiveSession live, String payload) {
        if (live == null || live.ws() == null || payload == null || live.closed()) {
            return;
        }
        synchronized (live.lock()) {
            try {
                live.ws().sendMessage(new TextMessage(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("aria live send failed");
            }
        }
    }

    /** One JSON line down this socket's bridge; no bridge means nothing to answer. */
    public void toBridge(LiveSession live, Map<String, Object> payload) {
        LiveBridge bridge = live == null ? null : live.bridge();
        if (bridge == null) {
            return;
        }
        try {
            bridge.send(json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.debug("aria live bridge frame not serializable");
        }
    }

    /** The lazy live run's progress for the phone: {@code started|progress|finished|failed}. */
    static Map<String, Object> runEvent(UUID runId, String state) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", TYPE_RUN_EVENT);
        if (runId != null) {
            event.put("runId", runId.toString());
        }
        event.put("state", state);
        return event;
    }

    /** The phone's tool chip: {@code running} before execution, {@code approval} parked, {@code done}. */
    static Map<String, Object> toolStatus(String callId, String toolName, String status) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", TYPE_TOOL_STATUS);
        event.put("id", callId == null ? "" : callId);
        event.put("toolName", toolName == null ? "" : toolName);
        event.put("status", status);
        return event;
    }

    /** The ask the phone renders; it always carries the live run the finalize route settles. */
    static Map<String, Object> approvalRequired(UUID runId, UUID approvalId, String toolName, String riskTier) {
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("type", TYPE_APPROVAL_REQUIRED);
        ask.put("runId", runId.toString());
        ask.put("approvalId", approvalId.toString());
        ask.put("toolName", toolName);
        ask.put("riskTier", riskTier);
        return ask;
    }

    /** The bridge's function-call answer, keyed by the {@code tool_call.id} it asked with. */
    static Map<String, Object> toolResult(String callId, boolean ok, String output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", TYPE_TOOL_RESULT);
        result.put("id", callId == null ? "" : callId);
        result.put("ok", ok);
        result.put("output", output == null ? "" : output);
        return result;
    }

    /** A TOWER injection: the bridge turns it into a model turn of its own. */
    static Map<String, Object> tower(String text) {
        Map<String, Object> injection = new LinkedHashMap<>();
        injection.put("type", TYPE_TOWER);
        injection.put("text", text);
        return injection;
    }
}
