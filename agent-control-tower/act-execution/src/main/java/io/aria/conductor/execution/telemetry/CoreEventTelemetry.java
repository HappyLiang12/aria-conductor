package io.aria.conductor.execution.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.runtime.CoreEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Maps the bridge's derived core events onto {@link RunTelemetryService}: the
 * event-to-telemetry half of F17/F9 that the run coordinator's event loop
 * ({@code CoreExecutionService#handleEvent}) calls.
 *
 * <p>The payloads are the bridge's own JSON (packages/qoder-acp-bridge/src/
 * tool-events.ts, usage.ts): every field is nullable, {@code input}/{@code result}
 * may be an object or a capped string ending in {@code " [truncated]"}, and a
 * token counter is reported only when the provider frame carried it.
 *
 * <p>Three rules hold for every payload:
 * <ul>
 *   <li>a malformed, non-object or non-telemetry payload is logged and skipped --
 *       observability never fails a run;</li>
 *   <li>an absent or malformed counter stays null, a carried zero stays zero
 *       (unknown usage is never invented as zero);</li>
 *   <li>only the three telemetry types are recorded here. {@code permission.request}
 *       names the same {@code toolCallId}, and the approval path owns that event;
 *       recording it here would duplicate the {@code tool.call} row of the same
 *       call.</li>
 * </ul>
 *
 * <p>Plain object on purpose: the mapping is testable without a Spring context.
 * The bean is the production wiring seam the coordinator receives.
 */
@Slf4j
@Component
public class CoreEventTelemetry {

    /** The bridge event types this adapter owns; every other type belongs to another consumer. */
    static final Set<String> HANDLED_TYPES = Set.of("tool.call", "tool.result", "usage");

    /**
     * Only protocol JSON is parsed and re-serialized here (never a domain type), so
     * the mapper needs no module registration.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunTelemetryService telemetry;

    public CoreEventTelemetry(RunTelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    /** True when the event type carries telemetry this adapter records. */
    public static boolean handles(String type) {
        return type != null && HANDLED_TYPES.contains(type);
    }

    /**
     * Records one core event.
     *
     * @param runId the run the event belongs to; the event's own run id, when set,
     *              is the same run and is preferred
     * @return true when the payload was well-formed and handed to the persistence
     *         seam; false for a skipped event (unhandled type, no run id, malformed
     *         payload, or a failed write). Never throws.
     */
    public boolean record(UUID runId, CoreEvent event) {
        if (event == null || !handles(event.type())) {
            return false;
        }
        UUID targetRunId = event.runId() != null ? event.runId() : runId;
        if (targetRunId == null) {
            log.warn("A {} core event carries no run id and cannot be recorded", event.type());
            return false;
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(event.payloadJson() == null || event.payloadJson().isBlank()
                    ? "{}" : event.payloadJson());
        } catch (JsonProcessingException e) {
            log.warn("Run {}: an unreadable {} payload is skipped: {}", targetRunId, event.type(),
                    e.getOriginalMessage());
            return false;
        }
        if (payload == null || !payload.isObject()) {
            log.warn("Run {}: a {} payload that is not a JSON object is skipped", targetRunId, event.type());
            return false;
        }
        try {
            switch (event.type()) {
                case "tool.call" -> telemetry.recordToolCall(targetRunId,
                        new RunTelemetryService.ToolCallEvent(text(payload, "toolCallId"),
                                text(payload, "toolName"), serialized(payload, "input"),
                                flag(payload, "isError")));
                case "tool.result" -> telemetry.recordToolResult(targetRunId,
                        new RunTelemetryService.ToolResultEvent(text(payload, "toolCallId"),
                                text(payload, "toolName"), serialized(payload, "result"),
                                flag(payload, "isError")));
                case "usage" -> telemetry.recordUsage(targetRunId, new RunTelemetryService.UsageEvent(
                        token(payload, "inputTokens"), token(payload, "outputTokens"),
                        token(payload, "totalTokens")));
                default -> {
                    return false; // unreachable: handles() gated the switch
                }
            }
            return true;
        } catch (RuntimeException e) {
            // Observability must never fail the run: a persistence failure is reported and the
            // event dropped, while the core keeps running.
            log.warn("Run {}: the {} event could not be recorded and is dropped: {}",
                    targetRunId, event.type(), e.getMessage(), e);
            return false;
        }
    }

    /** A textual member, or null when it is absent, null or blank. */
    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    /**
     * An {@code input}/{@code result} member as the text the audit row stores: a
     * textual value verbatim (a capped value keeps its {@code " [truncated]"}
     * suffix), any other structure as compact JSON, an absent value as null.
     */
    private static String serialized(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // A JsonNode always serializes; keep the observation rather than dropping the call.
            return node.toString();
        }
    }

    /** A boolean flag member; absent or unreadable is false (tool.call carries none). */
    private static boolean flag(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node != null && node.asBoolean(false);
    }

    /**
     * One reported token counter. Only an integral, non-negative JSON number is a
     * report: it is preserved exactly, including a genuine zero. An absent, null,
     * negative or non-integral value is null -- never a fabricated zero.
     */
    private static Long token(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        long value = node.asLong();
        return value < 0 ? null : value;
    }
}
