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
import java.util.List;
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
    static final String TYPE_AR_RENDER = "ar_render";

    /** The wire version every phone frame (and the hello/ready handshake) carries. */
    static final int VERSION = 1;

    /** The summary a run line carries when a turn finished cleanly. */
    static final String SUMMARY_FINISHED = "finished";

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

    /**
     * The lazy live run's progress for the phone:
     * {@code {"type":"run_event","v":1,"runId":…,"state":…,"summary":…}}, where
     * {@code state} is {@code started|progress|finished|failed}. The summary is a short,
     * stable sentence the phone shows as the run's line — {@code tool: get_time},
     * {@code finished}, {@code failed: <stable reason>} — never a payload or an exception's
     * text; a caller with nothing to say gets the state itself rather than a blank row.
     */
    static Map<String, Object> runEvent(UUID runId, String state, String summary) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", TYPE_RUN_EVENT);
        event.put("v", VERSION);
        if (runId != null) {
            event.put("runId", runId.toString());
        }
        event.put("state", state);
        event.put("summary", summary == null || summary.isBlank() ? state : summary);
        return event;
    }

    /** The run line's summary for a tool-using turn: {@code tool: <name>}. */
    static String toolSummary(String toolName) {
        return "tool: " + (toolName == null ? "" : toolName);
    }

    /** The run line's summary for a run that stopped: {@code failed: <stable reason>}. */
    static String failureSummary(String reason) {
        return "failed: " + reason;
    }

    /**
     * The phone's tool chip: {@code {"type":"tool_status","v":1,"name":…,"state":…}}, where
     * {@code state} is {@code running} before execution, {@code approval} parked and
     * {@code done} once the result returns. The bridge's function-call id is not part of
     * this frame — the chip is about the tool, not the call.
     */
    static Map<String, Object> toolStatus(String toolName, String state) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", TYPE_TOOL_STATUS);
        event.put("v", VERSION);
        event.put("name", toolName == null ? "" : toolName);
        event.put("state", state);
        return event;
    }

    /** The ask the phone renders; it always carries the live run the finalize route settles. */
    static Map<String, Object> approvalRequired(UUID runId, UUID approvalId, String toolName, String riskTier) {
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("type", TYPE_APPROVAL_REQUIRED);
        ask.put("v", VERSION);
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

    /**
     * The backup locator's render (spec D1/D6), in exactly the shape the bridge emits for
     * {@code highlight_target}: {@code {"type":"ar_render","v":1,"renderId":…,"render":{…}}}
     * with {@code ttlMs} 8000 and {@code coord} {@code normalized}. Display-only, so it
     * carries no {@code runId}. The bbox is normalized {@code [x,y,w,h]} in {@code [0,1]}.
     */
    static Map<String, Object> arRender(String renderId, LiveLocator.Render render) {
        double[] box = render.bbox();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("label", render.label());
        target.put("bbox", List.of(box[0], box[1], box[2], box[3]));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("primitive", "highlight");
        body.put("target", target);
        body.put("text", render.text());
        body.put("ttlMs", RENDER_TTL_MS);
        body.put("coord", COORD_NORMALIZED);
        body.put("safetyAlert", null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", TYPE_AR_RENDER);
        event.put("v", VERSION);
        event.put("renderId", renderId);
        event.put("render", body);
        return event;
    }

    /** The phone's default overlay life for a render (spec D1). */
    static final long RENDER_TTL_MS = 8000L;
    /** The only coordinate space the phone accepts (spec D1). */
    static final String COORD_NORMALIZED = "normalized";
}
