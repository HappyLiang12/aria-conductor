package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code get_time} answers the clock question a voice turn asks, so its value must be a
 * real ISO-8601 UTC instant from the injected clock rather than a formatted guess. The
 * catalog mirror of this tool is pinned by {@code ToolCatalogParityTest}.
 */
class TimeToolsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T09:15:00Z");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void getTimeReturnsTheInjectedClockAsAnIsoUtcInstant() throws Exception {
        TimeTools tools = new TimeTools(Clock.fixed(NOW, ZoneOffset.UTC));

        JsonNode body = json.readTree(tools.getTime());

        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("data").path("now").asText()).isEqualTo("2026-11-23T09:15:00Z");
        assertThat(Instant.parse(body.path("data").path("now").asText())).isEqualTo(NOW);
    }

    @Test
    void theProductionConstructorUsesTheWallClock() throws Exception {
        Instant before = Instant.now();

        JsonNode body = json.readTree(new TimeTools().getTime());
        Instant reported = Instant.parse(body.path("data").path("now").asText());

        assertThat(reported).isAfterOrEqualTo(before.minusSeconds(1));
    }
}
