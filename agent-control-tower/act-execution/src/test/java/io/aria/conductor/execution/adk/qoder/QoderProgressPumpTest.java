package io.aria.conductor.execution.adk.qoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.RunProgressEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * Pins the pump's raw-permission sink (R11): {@code permission_request} frames are handed to
 * the optional {@code Consumer<PermissionAsk>} with the bridge sequence and the raw payload,
 * alongside the mapped {@link RunProgressEvent} publish; a {@code null} sink is silent; a
 * throwing sink never affects the run. The sink is the only path by which the raw ask reaches
 * the host's ACP permission coordinator — the mapped progress events cannot carry it.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class QoderProgressPumpTest {

    private static final String SESSION = "bridge-session-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PERMISSION_JSON = "{\"requestId\":\"req-1\",\"toolCallId\":\"call_1\","
            + "\"toolName\":\"mcp__aria__write_file\",\"rawInput\":\"{\\\"path\\\":\\\"/workspace/x\\\"}\","
            + "\"rawInputTruncated\":false,\"options\":[{\"optionId\":\"a\",\"kind\":\"allow_once\"}]}";

    @Mock QoderBridgeClient client;
    @Mock QoderBridgeClient.EventStream eventStream;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final List<RunProgressEvent> progress = new CopyOnWriteArrayList<>();
    private final List<QoderProgressPump.PermissionAsk> rawAsks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(client.openEventStream(anyString())).thenReturn(eventStream);
    }

    // ---- helpers -------------------------------------------------------------

    private static QoderBridgeClient.BridgeEvent event(long sequence, String type, String json) {
        try {
            JsonNode payload = MAPPER.readTree(json);
            return new QoderBridgeClient.BridgeEvent(sequence, type, payload);
        } catch (Exception e) {
            throw new IllegalStateException("Bad test event JSON", e);
        }
    }

    /** The mocked stream plays the events and then ends (server-side end of stream). */
    private void streamPlays(QoderBridgeClient.BridgeEvent... events) {
        doAnswer(invocation -> {
            Consumer<QoderBridgeClient.BridgeEvent> consumer = invocation.getArgument(0);
            for (QoderBridgeClient.BridgeEvent e : events) {
                consumer.accept(e);
            }
            return null;
        }).when(eventStream).read(any());
    }

    private QoderProgressPump pump(Consumer<QoderProgressPump.PermissionAsk> rawSink) {
        return new QoderProgressPump(client, SESSION, runId, agentId, progress::add, rawSink);
    }

    private QoderProgressPump.WaitResult runToEnd(Consumer<QoderProgressPump.PermissionAsk> rawSink) {
        QoderProgressPump pump = pump(rawSink);
        pump.start();
        QoderProgressPump.WaitResult wait = pump.awaitTerminal(Duration.ofSeconds(10));
        pump.stop();
        return wait;
    }

    // ---- the raw sink --------------------------------------------------------

    @Test
    void permissionFrames_reachTheRawSink_withTheBridgeSequenceAndPayload_alongsideTheMappedPublish() {
        streamPlays(
                event(1, "session_started", "{\"model\":\"efficient\"}"),
                event(2, "agent_message", "{\"text\":\"working\"}"),
                event(3, "permission_request", PERMISSION_JSON),
                event(4, "completed", "{\"stopReason\":\"end_turn\"}"));

        QoderProgressPump.WaitResult wait = runToEnd(rawAsks::add);

        assertThat(wait.status()).isEqualTo(QoderProgressPump.WaitStatus.TERMINAL);
        assertThat(rawAsks).hasSize(1);
        assertThat(rawAsks.get(0).sequence()).isEqualTo(3);
        assertThat(rawAsks.get(0).payload().path("requestId").asText()).isEqualTo("req-1");
        // The existing mapped publish is untouched: the raw sink is additive.
        assertThat(progress).anyMatch(event -> event.getKind() == RunProgressEvent.Kind.STATUS
                && event.getContent().startsWith("permission_request")
                && event.getSeq() == 3);
    }

    @Test
    void onlyPermissionFrames_reachTheRawSink() {
        streamPlays(
                event(1, "session_started", "{\"model\":\"efficient\"}"),
                event(2, "tool_call", "{\"toolCallId\":\"call_1\",\"toolName\":\"Write\"}"),
                event(3, "permission_request", PERMISSION_JSON),
                event(4, "agent_message", "{\"text\":\"done\"}"),
                event(5, "completed", "{\"stopReason\":\"end_turn\"}"));

        runToEnd(rawAsks::add);

        // Only the permission frame is an ask; tool calls and messages are not.
        assertThat(rawAsks).hasSize(1);
        assertThat(rawAsks.get(0).sequence()).isEqualTo(3);
        assertThat(rawAsks.get(0).payload().path("requestId").asText()).isEqualTo("req-1");
    }

    @Test
    void aNullRawSink_isSilent_andTheRunIsUnaffected() {
        streamPlays(
                event(1, "permission_request", PERMISSION_JSON),
                event(2, "agent_message", "{\"text\":\"ok\"}"),
                event(3, "completed", "{\"stopReason\":\"end_turn\"}"));

        QoderProgressPump.WaitResult wait = runToEnd(null);

        assertThat(wait.status()).isEqualTo(QoderProgressPump.WaitStatus.TERMINAL);
        assertThat(wait.outcome().finalOutput()).isEqualTo("ok");
    }

    @Test
    void aThrowingRawSink_neverAffectsTheRun() {
        streamPlays(
                event(1, "permission_request", PERMISSION_JSON),
                event(2, "agent_message", "{\"text\":\"ok\"}"),
                event(3, "completed", "{\"stopReason\":\"end_turn\"}"));

        QoderProgressPump.WaitResult wait = runToEnd(ask -> {
            throw new IllegalStateException("coordinator boom");
        });

        assertThat(wait.status()).isEqualTo(QoderProgressPump.WaitStatus.TERMINAL);
        assertThat(wait.outcome().finalOutput()).isEqualTo("ok");
        assertThat(progress).anyMatch(event -> event.getKind() == RunProgressEvent.Kind.STATUS
                && event.getContent().startsWith("permission_request"));
        assertThat(progress).anyMatch(event -> "ok".equals(event.getContent()));
    }
}
