package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aria.conductor.common.security.ActorPrincipal;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform MCP tool result envelopes. Tools return JSON strings so every client
 * (opencode model, external agent) reads one shape: {"ok":bool, "data"|error fields}.
 * An error envelope is exactly {"ok":false, "errorType", "message"} on every
 * transport: the client answer never carries a stack trace or exception class
 * (information disclosure in an operator-facing tool response). debug=true
 * (aria.mcp.debug) records the cause server-side in the log instead.
 *
 * <p>It also owns the one operator-authority refusal every OPERATOR_ONLY tool speaks
 * (spec C4/F9). The platform, not the prompt, is the authority boundary, and the
 * refusal has to be actionable: it names the action that was refused and the surface
 * where the operator can perform it. A bare "forbidden" leaves the caller with no
 * next step and invites the assistant to claim the tool does not exist.
 */
@Slf4j
public final class ToolResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ToolResponses() {
    }

    public static String ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        return write(body);
    }

    /**
     * The uniform error envelope: exactly {@code ok=false}, {@code errorType} and
     * {@code message}. The cause is never serialized to the client; when
     * {@code debug} is on it is logged server-side instead.
     */
    public static String error(String errorType, String message, Throwable cause, boolean debug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorType", errorType);
        body.put("message", message);
        if (debug && cause != null) {
            log.debug("MCP tool error [{}]: {}", errorType, message, cause);
        }
        return write(body);
    }

    /**
     * One refusal shape for every operator-only tool: stable code, named action, named
     * surface. {@code errorType} stays {@code FORBIDDEN} for every one of them so existing
     * clients keep working.
     *
     * @param action  the refused action in gerund form, e.g. {@code retiring an agent}
     * @param surface where the operator can actually perform it, e.g.
     *                {@code Retire it on the Crew page.}
     */
    public static String operatorOnly(String action, String surface) {
        return "Operator authority required: " + action + " is an operator decision. " + surface;
    }

    /**
     * Applies the operator authority boundary before any service call: only the
     * transport-resolved OPERATOR passes, and an absent actor fails closed (there is no
     * credential to honour).
     *
     * @throws SecurityException carrying {@link #operatorOnly} for a worker or an absent actor
     */
    static void requireOperator(ActorPrincipal actor, String action, String surface) {
        if (actor == null || actor.role() != ActorPrincipal.Role.OPERATOR) {
            throw new SecurityException(operatorOnly(action, surface));
        }
    }

    private static String write(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Tool result serialization failed: {}", e.getMessage());
            return "{\"ok\":false,\"errorType\":\"SERIALIZATION\",\"message\":\"tool result could not be serialized\"}";
        }
    }
}
