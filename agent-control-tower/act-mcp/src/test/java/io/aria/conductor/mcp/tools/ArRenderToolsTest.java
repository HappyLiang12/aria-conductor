package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.security.CredentialRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ArRenderToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ArRenderTools tools = new ArRenderTools(new McpProperties());

    @Test
    void trackWithATrackIdIsAcknowledged() throws Exception {
        String result = tools.arRender("track",
                new ArRenderTools.ArTarget(7, null, null, null),
                "TV remote", null, null, null, null, null);

        JsonNode node = JSON.readTree(result);
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("data").path("primitive").asText()).isEqualTo("track");
    }

    /**
     * Every primitive in the tool's contract accepts a minimal well-formed call and
     * echoes itself back. The arguments differ per primitive: the four locatable
     * primitives need a target, indicator needs a direction, timer a positive
     * duration, and step/clear need nothing beyond their own fields.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("happyPathPrimitives")
    void everyPrimitiveIsAcknowledgedWithItsOwnName(String primitive, ArRenderTools.ArTarget target,
                                                    String text, Double directionDeg, Double durationS)
            throws Exception {
        JsonNode node = JSON.readTree(tools.arRender(
                primitive, target, text, directionDeg, null, durationS, null, null));

        assertThat(node.path("ok").asBoolean())
                .as("primitive %s: %s", primitive, node.toString())
                .isTrue();
        assertThat(node.path("data").path("primitive").asText()).isEqualTo(primitive);
    }

    private static Stream<Arguments> happyPathPrimitives() {
        return Stream.of(
                Arguments.of("track", new ArRenderTools.ArTarget(7, null, null, null),
                        "TV remote", null, null),
                Arguments.of("arrow_to", new ArRenderTools.ArTarget(null, null, "cup", null),
                        "this way", null, null),
                Arguments.of("highlight", new ArRenderTools.ArTarget(null, null, "cup", null),
                        null, null, null),
                Arguments.of("label", new ArRenderTools.ArTarget(null, null, "cup", null),
                        "cup", null, null),
                Arguments.of("indicator", null, null, 45.0, null),
                Arguments.of("step", null, "Step 2", null, null),
                Arguments.of("timer", null, null, null, 180.0),
                Arguments.of("clear", null, null, null, null));
    }

    @Test
    void anUnknownPrimitiveIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("explode",
                new ArRenderTools.ArTarget(7, null, null, null),
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("errorType").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void trackWithoutALocatableTargetIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("track", null,
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("requires target");
    }

    @Test
    void indicatorRequiresADirection() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("indicator", null,
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("directionDeg");
    }

    @Test
    void timerRequiresAPositiveDuration() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("timer", null,
                null, null, null, 0.0, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("durationS");
    }

    @Test
    void anUnknownAnchorIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("step", null,
                "Step 2", null, null, null, "world", null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("anchor");
    }

    @Test
    void clearNeedsNoTarget() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("clear", null,
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isTrue();
    }

    @Test
    void redactedRenderArgumentsRemainParseableJson() throws Exception {
        String args = "{\"primitive\":\"track\",\"target\":{\"trackId\":7},\"text\":\"TV remote\"}";

        JsonNode node = JSON.readTree(CredentialRedactor.redact(args));

        assertThat(node.path("primitive").asText()).isEqualTo("track");
        assertThat(node.path("target").path("trackId").asInt()).isEqualTo(7);
    }
}
