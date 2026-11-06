package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

    private static String write(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Tool result serialization failed: {}", e.getMessage());
            return "{\"ok\":false,\"errorType\":\"SERIALIZATION\",\"message\":\"tool result could not be serialized\"}";
        }
    }
}
