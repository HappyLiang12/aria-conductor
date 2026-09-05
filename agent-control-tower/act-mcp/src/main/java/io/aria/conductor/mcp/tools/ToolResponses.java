package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform MCP tool result envelopes. Tools return JSON strings so every client
 * (opencode model, external agent) reads one shape: {"ok":bool, "data"|error fields}.
 * debug=true (aria.mcp.debug) adds the full stack trace for external-agent debugging.
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

    public static String error(String errorType, String message, Throwable cause, boolean debug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("errorType", errorType);
        body.put("message", message);
        if (debug && cause != null) {
            body.put("stackTrace", stackTraceOf(cause));
        }
        return write(body);
    }

    private static String stackTraceOf(Throwable cause) {
        StringWriter sw = new StringWriter();
        cause.printStackTrace(new PrintWriter(sw));
        return sw.toString();
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
