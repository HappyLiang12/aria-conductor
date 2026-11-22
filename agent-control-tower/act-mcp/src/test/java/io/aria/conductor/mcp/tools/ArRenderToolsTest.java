package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.engine.ArRenderSignals;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.security.CredentialRedactor;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArRenderToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RUN = UUID.randomUUID();

    private final ArRenderSignals signals = mock(ArRenderSignals.class);
    private final ArRenderTools tools = new ArRenderTools(new McpProperties(), signals);

    @Test
    void trackWithATrackIdIsAcknowledged() throws Exception {
        String result = tools.arRender("track",
                new ArRenderTools.ArTarget(7, null, null, null),
                "TV remote", null, null, null, null, null, noActor());

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
                primitive, target, text, directionDeg, null, durationS, null, null, noActor()));

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

    /** The device frame is the call's args, addressed to the worker's own run. */
    @Test
    void aValidRenderReachesTheActorsRun() throws Exception {
        when(signals.emit(eq(RUN), anyMap())).thenReturn(true);

        JsonNode node = JSON.readTree(tools.arRender("track",
                new ArRenderTools.ArTarget(7, null, null, null), "TV remote",
                null, null, null, null, null, actorContext(RUN)));

        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("data").path("delivered").asBoolean()).isTrue();
        ArgumentCaptor<Map<String, Object>> captor = payloadCaptor();
        verify(signals).emit(eq(RUN), captor.capture());
        Map<String, Object> render = captor.getValue();
        assertThat(render).containsEntry("primitive", "track").containsEntry("text", "TV remote");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) render.get("target");
        assertThat(target).containsEntry("trackId", 7);
    }

    @Test
    void anAnonymousCallIsAcknowledgedWithoutDelivery() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("clear", null,
                null, null, null, null, null, null, noActor()));

        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("data").path("delivered").asBoolean()).isFalse();
        verifyNoInteractions(signals);
    }

    @Test
    void aValidationFailureEmitsNothing() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("explode", null,
                null, null, null, null, null, null, actorContext(RUN)));

        assertThat(node.path("ok").asBoolean()).isFalse();
        verifyNoInteractions(signals);
    }

    @Test
    void anUnknownPrimitiveIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("explode",
                new ArRenderTools.ArTarget(7, null, null, null),
                null, null, null, null, null, null, noActor()));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("errorType").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void trackWithoutALocatableTargetIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("track", null,
                null, null, null, null, null, null, noActor()));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("requires target");
    }

    @Test
    void indicatorRequiresADirection() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("indicator", null,
                null, null, null, null, null, null, noActor()));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("directionDeg");
    }

    @Test
    void timerRequiresAPositiveDuration() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("timer", null,
                null, null, null, 0.0, null, null, noActor()));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("durationS");
    }

    @Test
    void anUnknownAnchorIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("step", null,
                "Step 2", null, null, null, "world", null, noActor()));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("anchor");
    }

    @Test
    void clearNeedsNoTarget() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("clear", null,
                null, null, null, null, null, null, noActor()));

        assertThat(node.path("ok").asBoolean()).isTrue();
    }

    @Test
    void redactedRenderArgumentsRemainParseableJson() throws Exception {
        String args = "{\"primitive\":\"track\",\"target\":{\"trackId\":7},\"text\":\"TV remote\"}";

        JsonNode node = JSON.readTree(CredentialRedactor.redact(args));

        assertThat(node.path("primitive").asText()).isEqualTo("track");
        assertThat(node.path("target").path("trackId").asInt()).isEqualTo(7);
    }

    private static ToolContext noActor() {
        return new ToolContext(Map.of());
    }

    private static ToolContext actorContext(UUID runId) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(
                ActorPrincipal.worker(runId, Instant.now().plusSeconds(60))));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
