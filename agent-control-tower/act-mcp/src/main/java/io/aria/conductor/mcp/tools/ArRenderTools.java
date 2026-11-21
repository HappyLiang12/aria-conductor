package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AR primitives for the paired glasses display. The tool changes no platform
 * state: it validates the instruction and returns the acknowledgment the tool
 * call already streams to the device.
 */
@Component
@RequiredArgsConstructor
public class ArRenderTools implements McpTool {

    private static final Set<String> PRIMITIVES = Set.of(
            "track", "arrow_to", "highlight", "label", "indicator", "step", "timer", "clear");
    private static final Set<String> TARGET_REQUIRED = Set.of(
            "track", "arrow_to", "highlight", "label");

    private final McpProperties mcpProperties;

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
            description = "Render one AR primitive on the paired glasses display. primitive is one of track, arrow_to, highlight, label, indicator, step, timer, clear. target carries trackId/objectId/label/bbox; prefer the observation's trackId and never invent one. directionDeg is clockwise from the screen centre when anchor=screen, relative to magnetic north when anchor=heading. Confirm the ack before describing the overlay in prose. It renders on the device and changes no platform state.")
    public String arRender(
            @ToolParam(description = "track, arrow_to, highlight, label, indicator, step, timer or clear") String primitive,
            @ToolParam(description = "Target object; at least one of trackId, objectId, label or bbox", required = false) ArTarget target,
            @ToolParam(description = "Short display text", required = false) String text,
            @ToolParam(description = "Clockwise direction in degrees", required = false) Double directionDeg,
            @ToolParam(description = "Distance in metres", required = false) Double distanceM,
            @ToolParam(description = "Duration in seconds", required = false) Double durationS,
            @ToolParam(description = "screen or heading", required = false) String anchor,
            @ToolParam(description = "Confidence 0..1", required = false) Double confidence) {
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
            return ToolResponses.ok(Map.of("ack", true, "primitive", render));
        } catch (Exception e) {
            return ToolResponses.error("AR_RENDER_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
