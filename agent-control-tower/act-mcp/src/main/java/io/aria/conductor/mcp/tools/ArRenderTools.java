package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.engine.ArRenderSignals;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.McpActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AR primitives for the paired glasses display. The tool changes no platform
 * state: it validates the instruction, emits one {@code ar_render} frame on the
 * invoking run's live stream, and returns the acknowledgment. The frame is the
 * device delivery path because the opencode core's tool calls never reach the
 * run's SSE stream; the ack reports whether a live stream received it.
 */
@Component
@RequiredArgsConstructor
public class ArRenderTools implements McpTool {

    private static final Set<String> PRIMITIVES = Set.of(
            "track", "arrow_to", "highlight", "label", "indicator", "step", "timer", "clear");
    private static final Set<String> TARGET_REQUIRED = Set.of(
            "track", "arrow_to", "highlight", "label");

    private final McpProperties mcpProperties;
    private final ArRenderSignals signals;

    /** The locatable target of a render instruction; all fields are optional, one must resolve. */
    public record ArTarget(Integer trackId, String objectId, String label, List<Double> bbox) {
        boolean locatable() {
            return trackId != null
                    || (objectId != null && !objectId.isBlank())
                    || (label != null && !label.isBlank())
                    || (bbox != null && bbox.size() == 4);
        }
    }

    @Tool(name = "ar_render",
            description = "Render one AR primitive on the paired glasses display. A successful call emits one ar_render frame on the run's live stream. primitive is one of track, arrow_to, highlight, label, indicator, step, timer, clear. target carries trackId/objectId/label/bbox; prefer the observation's trackId and never invent one. directionDeg is clockwise from the screen centre when anchor=screen, relative to magnetic north when anchor=heading. Confirm the ack before describing the overlay in prose. It renders on the device and changes no platform state.")
    public String arRender(
            @ToolParam(description = "track, arrow_to, highlight, label, indicator, step, timer or clear") String primitive,
            @ToolParam(description = "Target object; at least one of trackId, objectId, label or bbox", required = false) ArTarget target,
            @ToolParam(description = "Short display text", required = false) String text,
            @ToolParam(description = "Clockwise direction in degrees", required = false) Double directionDeg,
            @ToolParam(description = "Distance in metres", required = false) Double distanceM,
            @ToolParam(description = "Duration in seconds", required = false) Double durationS,
            @ToolParam(description = "screen or heading", required = false) String anchor,
            @ToolParam(description = "Confidence 0..1", required = false) Double confidence,
            ToolContext toolContext) {
        try {
            String render = primitive == null ? "" : primitive.trim();
            if (!PRIMITIVES.contains(render)) {
                return ToolResponses.error("VALIDATION",
                        "Unknown primitive '" + primitive + "'. Valid: track, arrow_to, highlight,"
                                + " label, indicator, step, timer, clear",
                        null, mcpProperties.isDebug());
            }
            if (TARGET_REQUIRED.contains(render) && (target == null || !target.locatable())) {
                return ToolResponses.error("VALIDATION",
                        "primitive '" + render + "' requires target.trackId, objectId, label or bbox",
                        null, mcpProperties.isDebug());
            }
            if ("indicator".equals(render) && directionDeg == null) {
                return ToolResponses.error("VALIDATION",
                        "primitive 'indicator' requires directionDeg", null, mcpProperties.isDebug());
            }
            if ("timer".equals(render) && (durationS == null || durationS <= 0)) {
                return ToolResponses.error("VALIDATION",
                        "primitive 'timer' requires durationS greater than 0",
                        null, mcpProperties.isDebug());
            }
            String resolvedAnchor = anchor == null || anchor.isBlank() ? "screen" : anchor.trim();
            if (!"screen".equals(resolvedAnchor) && !"heading".equals(resolvedAnchor)) {
                return ToolResponses.error("VALIDATION",
                        "anchor must be screen or heading", null, mcpProperties.isDebug());
            }
            UUID runId = McpActorContext.find(toolContext)
                    .map(ActorPrincipal::runId)
                    .orElse(null);
            boolean delivered = runId != null && signals.emit(runId, renderMap(render, target, text,
                    directionDeg, distanceM, durationS, anchor, confidence));
            return ToolResponses.ok(Map.of("ack", true, "primitive", render, "delivered", delivered));
        } catch (Exception e) {
            return ToolResponses.error("AR_RENDER_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    /** The device payload: exactly the call's args, with absent optional members omitted. */
    private static Map<String, Object> renderMap(String primitive, ArTarget target, String text,
            Double directionDeg, Double distanceM, Double durationS, String anchor,
            Double confidence) {
        Map<String, Object> render = new LinkedHashMap<>();
        render.put("primitive", primitive);
        if (target != null) {
            Map<String, Object> targetMap = new LinkedHashMap<>();
            if (target.trackId() != null) {
                targetMap.put("trackId", target.trackId());
            }
            if (target.objectId() != null && !target.objectId().isBlank()) {
                targetMap.put("objectId", target.objectId());
            }
            if (target.label() != null && !target.label().isBlank()) {
                targetMap.put("label", target.label());
            }
            if (target.bbox() != null && target.bbox().size() == 4) {
                targetMap.put("bbox", target.bbox());
            }
            if (!targetMap.isEmpty()) {
                render.put("target", targetMap);
            }
        }
        if (text != null && !text.isBlank()) {
            render.put("text", text);
        }
        if (directionDeg != null) {
            render.put("directionDeg", directionDeg);
        }
        if (distanceM != null) {
            render.put("distanceM", distanceM);
        }
        if (durationS != null) {
            render.put("durationS", durationS);
        }
        if (anchor != null && !anchor.isBlank()) {
            render.put("anchor", anchor.trim());
        }
        if (confidence != null) {
            render.put("confidence", confidence);
        }
        return render;
    }
}
