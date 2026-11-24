package io.aria.conductor.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The clock. A live voice turn asks "what time is it" far more often than it asks about
 * a run, and the model has no clock of its own, so the platform answers instead of the
 * assistant guessing a time from training data.
 *
 * <p>Read-only and stateless: {@code get_time} is a reviewed WORKER_READ tool in the one
 * catalog; it returns the local zoned time (the operator's clock) alongside the UTC instant.
 */
@Component
public class TimeTools implements McpTool {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final Clock clock;

    /** Production wiring: the wall clock is the only unmanaged dependency. */
    @Autowired
    public TimeTools() {
        this(Clock.systemDefaultZone());
    }

    TimeTools(Clock clock) {
        this.clock = clock;
    }

    @Tool(name = "get_time",
            description = "Return the current date and time. `now` is the local wall-clock time to speak (ISO-8601 with offset), `zone` its zone id, `utc` the same instant. Read-only and stateless; always answer time questions from `now`, never guess.")
    public String getTime() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("now", now.format(LOCAL));
        body.put("zone", now.getZone().getId());
        body.put("utc", now.toInstant().toString());
        return ToolResponses.ok(body);
    }
}
