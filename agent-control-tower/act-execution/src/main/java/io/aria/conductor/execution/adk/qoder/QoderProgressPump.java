package io.aria.conductor.execution.adk.qoder;

import com.fasterxml.jackson.databind.JsonNode;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.execution.adk.TaskExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Progress pump for the Qoder provider (C0.2 bridge event stream).
 *
 * <p>Reads one bridge session's SSE stream ({@link QoderBridgeClient#openEventStream(String)})
 * on a virtual thread while {@code executeTask} blocks on the run, and:
 * <ul>
 *   <li>maps every bridge event onto a {@link RunProgressEvent} carrying the bridge's own
 *       monotonic {@code sequence} as its {@code seq} (never a locally restarted counter),
 *       so the dashboard watermark stays aligned with the bridge replay window;</li>
 *   <li>aggregates the run outcome ({@code agent_message} text, {@code usage} tokens) and
 *       the terminal signal for {@link #awaitTerminal(Duration)}.</li>
 * </ul>
 *
 * <p>Terminal semantics (frozen): only an explicit {@code completed}/{@code failed} bridge
 * event ends the run. A clean server-side EOF without one is reported as
 * {@link WaitStatus#STREAM_ENDED} — completion must be signalled, never inferred.
 *
 * <p>The sink (event publisher) is isolated: a throw from it never affects the run.
 */
@Slf4j
public class QoderProgressPump {

    /** How a terminal wait ended. */
    public enum WaitStatus {
        /** An explicit terminal bridge event ({@code completed}/{@code failed}) arrived. */
        TERMINAL,
        /** The event stream ended without any terminal event (never a task completion). */
        STREAM_ENDED,
        /** The wait budget elapsed with the stream still open. */
        TIMEOUT
    }

    /** Aggregated run outcome gathered from the bridge event stream. */
    public record TerminalOutcome(String finalOutput, int inputTokens, int outputTokens,
                                  boolean aborted, boolean failed, String failureReason,
                                  String failureCode) { }

    /** Result of {@link #awaitTerminal(Duration)}. */
    public record WaitResult(WaitStatus status, TerminalOutcome outcome) { }

    private final UUID runId;
    private final UUID agentId;
    private final Consumer<RunProgressEvent> sink;
    private final QoderBridgeClient.EventStream stream;

    private final StringBuilder output = new StringBuilder();
    /** toolCallId → toolName, so {@code tool_call_update} (which carries no name) can be attributed. */
    private final Map<String, String> toolNames = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<TerminalOutcome> terminal = new AtomicReference<>();
    private final AtomicReference<String> streamFailure = new AtomicReference<>();
    private final CountDownLatch terminalArrived = new CountDownLatch(1);
    private final CountDownLatch loopEnded = new CountDownLatch(1);
    private volatile int inputTokens;
    private volatile int outputTokens;
    private volatile Thread reader;

    public QoderProgressPump(QoderBridgeClient client, String bridgeSessionId, UUID runId, UUID agentId,
                             Consumer<RunProgressEvent> sink) {
        this.runId = runId;
        this.agentId = agentId;
        this.sink = sink;
        this.stream = client.openEventStream(bridgeSessionId);
    }

    /** Start the reader (idempotent). */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        reader = Thread.ofVirtual().name("qoder-events-" + runId).start(this::readLoop);
    }

    /**
     * Wait for an explicit terminal signal.
     *
     * @return {@link WaitStatus#TERMINAL} with the aggregated outcome, the stream ended
     *         without a terminal event, or the budget elapsed
     * @throws TaskExecutionException {@code ABORTED} when the wait is interrupted
     */
    public WaitResult awaitTerminal(Duration timeout) {
        try {
            terminalArrived.await(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                    "Interrupted while waiting for Qoder run " + runId, e);
        }
        TerminalOutcome outcome = terminal.get();
        if (outcome != null) {
            return new WaitResult(WaitStatus.TERMINAL, outcome);
        }
        if (loopEnded.getCount() == 0) {
            return new WaitResult(WaitStatus.STREAM_ENDED, null);
        }
        return new WaitResult(WaitStatus.TIMEOUT, null);
    }

    /**
     * Bounded wait for the reader loop to end (terminal event reached or stream ended) —
     * the provider's proof that a cancelled run actually stopped.
     */
    public boolean awaitStopped(Duration grace) {
        try {
            if (loopEnded.await(Math.max(0, grace.toMillis()), TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return loopEnded.getCount() == 0;
    }

    /** Close the stream and join the reader (idempotent, bounded). */
    public void stop() {
        try {
            stream.close();
        } catch (Exception e) {
            log.debug("Failed to close the Qoder event stream for run {}: {}", runId, e.getMessage());
        }
        Thread current = reader;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The reader failure detail when the stream died before a terminal event (may be null). */
    public String streamFailureMessage() {
        return streamFailure.get();
    }

    // ---- internals ----

    private void readLoop() {
        try {
            stream.read(this::handle);
        } catch (Exception e) {
            // A stream failure is not a run completion: record the detail, the provider maps
            // the "no terminal event" condition onto a typed failure.
            streamFailure.set(e.getMessage());
            log.warn("Qoder bridge event stream failed for run {}: {}", runId, e.getMessage());
        } finally {
            loopEnded.countDown();
            // Wake an in-progress awaitTerminal too: a stream that ended WITHOUT a terminal
            // event must surface as STREAM_ENDED immediately, never wait out the full budget.
            terminalArrived.countDown();
        }
    }

    private void handle(QoderBridgeClient.BridgeEvent event) {
        String type = event.type();
        JsonNode payload = event.payload();
        long seq = event.sequence();
        switch (type) {
            case "session_started" -> publish(seq, RunProgressEvent.Kind.STATUS,
                    "qoder.model=" + payload.path("model").asText(""), null);
            case "agent_message" -> {
                String text = payload.path("text").asText("");
                synchronized (output) {
                    output.append(text);
                }
                publish(seq, RunProgressEvent.Kind.THINKING, text, null);
            }
            case "tool_call" -> {
                String toolCallId = payload.path("toolCallId").asText(null);
                String toolName = payload.path("toolName").asText(null);
                if (toolCallId != null && toolName != null) {
                    toolNames.put(toolCallId, toolName);
                }
                publish(seq, RunProgressEvent.Kind.TOOL_CALL,
                        "tool_call " + (toolName == null ? "(unknown tool)" : toolName)
                                + " [" + payload.path("kind").asText("") + " "
                                + payload.path("status").asText("") + "]", toolName);
            }
            case "tool_call_update" -> {
                String toolCallId = payload.path("toolCallId").asText(null);
                String status = payload.path("status").asText("");
                String toolName = toolCallId == null ? null : toolNames.get(toolCallId);
                boolean ended = "completed".equals(status) || "failed".equals(status);
                publish(seq, ended ? RunProgressEvent.Kind.TOOL_RESULT : RunProgressEvent.Kind.TOOL_CALL,
                        "tool_call_update " + status, toolName);
            }
            case "permission_request" -> {
                String toolName = payload.path("toolName").asText(null);
                publish(seq, RunProgressEvent.Kind.STATUS,
                        "permission_request " + (toolName == null ? "(unknown tool)" : toolName)
                                + " — " + payload.path("title").asText(""), toolName);
            }
            case "mode_changed" -> publish(seq, RunProgressEvent.Kind.STATUS,
                    "mode_changed: " + payload.path("currentModeId").asText(""), null);
            case "usage" -> {
                inputTokens += payload.path("inputTokens").asInt(0);
                outputTokens += payload.path("outputTokens").asInt(0);
                publish(seq, RunProgressEvent.Kind.STATUS,
                        "qoder.usage input=" + payload.path("inputTokens").asInt(0)
                                + " output=" + payload.path("outputTokens").asInt(0)
                                + " credits=" + payload.path("credits").asText("n/a"), null);
            }
            case "completed" -> complete(payload.path("stopReason").asText(null));
            case "failed" -> fail(payload.path("reason").asText(null), payload.path("code").asText(null));
            default -> log.debug("Ignoring bridge event type '{}' for run {}", type, runId);
        }
    }

    private void complete(String stopReason) {
        boolean aborted = stopReason != null && "cancelled".equalsIgnoreCase(stopReason);
        TerminalOutcome outcome = new TerminalOutcome(currentOutput(), inputTokens, outputTokens,
                aborted, false, null, null);
        if (terminal.compareAndSet(null, outcome)) {
            terminalArrived.countDown();
        }
    }

    private void fail(String reason, String code) {
        TerminalOutcome outcome = new TerminalOutcome(currentOutput(), inputTokens, outputTokens,
                false, true, reason, code);
        if (terminal.compareAndSet(null, outcome)) {
            terminalArrived.countDown();
        }
    }

    private String currentOutput() {
        synchronized (output) {
            return output.toString();
        }
    }

    private void publish(long seq, RunProgressEvent.Kind kind, String content, String toolName) {
        try {
            sink.accept(new RunProgressEvent(this, runId, agentId, 0, kind, content, toolName, seq));
        } catch (Exception e) {
            log.warn("Qoder progress sink failed for run {} (pump continues): {}", runId, e.getMessage());
        }
    }
}
