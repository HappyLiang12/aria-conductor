package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code get_time} answers the clock question a voice turn asks: the local zoned time is
 * what the model must speak, and the UTC instant is the same moment. The catalog mirror
 * of this tool is pinned by {@code ToolCatalogParityTest}.
 */
class TimeToolsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T09:15:00Z");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void getTimeReturnsTheInjectedZoneAndTheSameUtcInstant() throws Exception {
        TimeTools tools = new TimeTools(Clock.fixed(NOW, ZoneId.of("Asia/Hong_Kong")));

        JsonNode body = json.readTree(tools.getTime());

        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("data").path("now").asText())
                .isEqualTo("2026-11-23T17:15:00+08:00");
        assertThat(body.path("data").path("zone").asText()).isEqualTo("Asia/Hong_Kong");
        assertThat(Instant.parse(body.path("data").path("utc").asText())).isEqualTo(NOW);
    }

    @Test
    void aUtcClockStillReportsTheLocalZoneAsUtc() throws Exception {
        TimeTools tools = new TimeTools(Clock.fixed(NOW, ZoneOffset.UTC));

        JsonNode body = json.readTree(tools.getTime());

        assertThat(body.path("data").path("now").asText()).isEqualTo("2026-11-23T09:15:00Z");
        assertThat(body.path("data").path("zone").asText()).isEqualTo("Z");
    }

    @Test
    void theProductionConstructorUsesTheWallClock() throws Exception {
        Instant before = Instant.now();

        JsonNode body = json.readTree(new TimeTools().getTime());
        Instant reported = Instant.parse(body.path("data").path("utc").asText());

        assertThat(reported).isAfterOrEqualTo(before.minusSeconds(1));
    }
}
