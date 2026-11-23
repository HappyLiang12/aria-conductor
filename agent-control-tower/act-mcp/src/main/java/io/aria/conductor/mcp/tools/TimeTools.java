package io.aria.conductor.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The clock. A live voice turn asks "what time is it" far more often than it asks about
 * a run, and the model has no clock of its own, so the platform answers instead of the
 * assistant guessing a time from training data.
 *
 * <p>Read-only and stateless: {@code get_time} is a reviewed WORKER_READ tool in the one
 * catalog, and the ISO-8601 UTC instant is the only thing it returns.
 */
@Component
public class TimeTools implements McpTool {

    private final Clock clock;

    /** Production wiring: the wall clock is the only unmanaged dependency. */
    @Autowired
    public TimeTools() {
        this(Clock.systemUTC());
    }

    TimeTools(Clock clock) {
        this.clock = clock;
    }

    @Tool(name = "get_time",
            description = "Return the current date and time as an ISO-8601 UTC instant, e.g. 2026-11-23T09:15:00Z. Read-only and stateless; use it instead of guessing the time.")
    public String getTime() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("now", Instant.now(clock).toString());
        return ToolResponses.ok(body);
    }
}
