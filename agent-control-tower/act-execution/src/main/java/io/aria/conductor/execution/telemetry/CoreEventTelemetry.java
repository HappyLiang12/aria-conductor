package io.aria.conductor.execution.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.engine.RunStreamRegistry;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.security.CredentialRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
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
 *   <li>an absent or malformed counter stays null; an all-zero carried report is the
 *       CLI's placeholder and is dropped by the telemetry service before any write,
 *       so nothing is invented as zero;</li>
 *   <li>only the three telemetry types are recorded here. {@code permission.request}
 *       names the same {@code toolCallId}, and the approval path owns that event;
 *       recording it here would duplicate the {@code tool.call} row of the same
 *       call.</li>
 * </ul>
 *
 * <p>It is also the producer of the live {@code tool_call} / {@code tool_result} signals the
 * Aria panel's activity strip renders (read by {@code act-dashboard/src/api/aria.ts}: a recorded
 * tool event is handed to the run's own stream, never before the write succeeded. A run without a
 * live stream (a sync turn, a closed panel) keeps the row and streams nothing.
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

    /**
     * The run-owned streams that carry the live tool signals, or null when this wiring serves
     * none. Optional on purpose (the same seam as {@code CoreExecutionService.attachTelemetry}):
     * the adapter stays constructible without a stream registry, which is what every existing
     * construction (unit tests, a wiring without the engine) does.
     */
    private volatile RunStreamRegistry streamRegistry;

    public CoreEventTelemetry(RunTelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    /** Attaches the run's live-stream seam. Never required: without it the signals are not streamed. */
    @Autowired(required = false)
    public void attachStreamRegistry(RunStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
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
        } catch (RuntimeException e) {
            // Observability must never fail the run: a persistence failure is reported and the
            // event dropped, while the core keeps running.
            log.warn("Run {}: the {} event could not be recorded and is dropped: {}",
                    targetRunId, event.type(), e.getMessage(), e);
            return false;
        }
        // Only a recorded event is streamed: the panel must never show a tool call or result
        // that the audit rows do not have -- nor less redacted than they are.
        streamSignal(targetRunId, event.type(), payload);
        return true;
    }

    /**
     * Hands a recorded tool event to the run's live stream as the activity strip's
     * {@code tool_call} / {@code tool_result} frame (consumed by {@code act-dashboard/src/api/aria.ts}).
     *
     * <p>The {@code args} / {@code result} values are the redacted text the audit row stores, so the
     * panel can never show a credential the row beside it masked.
     *
     * <p>No call here escapes into the core event path: a run with no live stream is the normal
     * case (a sync turn, a panel that already closed) and {@link RunStreamRegistry#emit} reports
     * it as {@code false}, which is neither an error nor logged as one; a completed emitter is
     * never resurrected, because the registry only addresses what a live turn bound.
     */
    private void streamSignal(UUID runId, String type, JsonNode payload) {
        RunStreamRegistry registry = this.streamRegistry;
        if (registry == null) {
            return;
        }
        String eventName = switch (type) {
            case "tool.call" -> "tool_call";
            case "tool.result" -> "tool_result";
            default -> null; // usage carries no live frame
        };
        if (eventName == null) {
            return;
        }
        Map<String, Object> signal = new HashMap<>();
        signal.put("runId", runId.toString());
        signal.put("name", text(payload, "toolName"));
        signal.put("toolCallId", text(payload, "toolCallId"));
        // The two values below are the ones the audit row stores, so they are redacted exactly where
        // RunTelemetryService redacts before persisting (recordToolCall/recordToolResult). CredentialRedactor
        // stays the single authority on what counts as a credential; this frame must carry the same text the
        // row carries, because the panel renders both side by side. The key names stay the frame contract.
        if ("tool.call".equals(type)) {
            signal.put("args", CredentialRedactor.redact(serialized(payload, "input")));
        } else {
            signal.put("result", CredentialRedactor.redact(serialized(payload, "result")));
            // Always present: an absent flag is "not an error", never a measured success.
            signal.put("ok", !flag(payload, "isError"));
        }
        try {
            registry.emit(runId, eventName, signal);
        } catch (RuntimeException e) {
            // The registry promises not to throw; a run must not depend on that promise.
            log.debug("Run {}: the {} signal could not be handed to the stream: {}",
                    runId, eventName, e.getMessage());
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
