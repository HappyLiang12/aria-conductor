package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.RunStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bridge's {@code tool_call} turned into a governed call on the turn's live run (spec
 * X7, L4): one run row per tool-using turn, started before the tool runs, and the outcome
 * reported both ways — {@code tool_result} answers the bridge's function call, while
 * {@code tool_status}/{@code run_event} keep the phone's chips truthful.
 *
 * <p>The turn's run is created on the first call, so a pure chat turn owns no row. A call
 * the executor refuses is still answered: the bridge must always get its
 * {@code tool_result}, or the model waits on a function call that can never complete.
 * Failures carry the executor's stable sentence, never an exception's text or stack.
 * A gated write is not answered here: the handler creates the one approval row on the
 * turn's run and parks it, and the operator's decision answers the call later.
 *
 * <p>Execution is off the stdout reader thread: that thread carries every other line of the
 * socket (audio, control), so it hands the call to a small bounded pool and returns. Each
 * call is answered by the worker that ran it, so a call's result can never overtake its
 * own execution; different calls may interleave. A queue that is full refuses the call —
 * the reader is never made to wait for a worker, and the refused call is still answered.
 */
@Slf4j
@Component
public class LiveToolCallHandler implements LiveToolCallSink {

    /** Answered when the line cannot be read at all: stable, no parser text. */
    static final String UNREADABLE = "Tool call unreadable";
    /** Answered when the call blew up before an outcome existed: stable, no stack. */
    static final String FAILED = "Tool call failed";
    /** Answered for a call that names no tool. */
    static final String UNKNOWN_TOOL = "Unknown live tool";
    /** Answered when the execution queue is full: refused, never left open. */
    static final String BUSY = "Tool execution is busy";

    /** Two workers: one turn rarely issues two calls, and the pool is shared by sockets. */
    static final int WORKERS = 2;
    /** Calls beyond this are refused instead of queued without limit. */
    static final int QUEUE_CAPACITY = 8;

    private final LiveRunService runs;
    private final LiveToolExecutor tools;
    private final LiveFrames frames;
    private final ObjectMapper json;
    private final LiveEventRouter router;
    private final LiveApprovalService approvals;
    private final Executor executor;

    @Autowired
    public LiveToolCallHandler(LiveRunService runs, LiveToolExecutor tools, LiveFrames frames,
                               ObjectMapper json, LiveEventRouter router,
                               LiveApprovalService approvals) {
        this(runs, tools, frames, json, router, approvals, boundedPool());
    }

    LiveToolCallHandler(LiveRunService runs, LiveToolExecutor tools, LiveFrames frames,
                        ObjectMapper json, LiveEventRouter router, LiveApprovalService approvals,
                        Executor executor) {
        this.runs = runs;
        this.tools = tools;
        this.frames = frames;
        this.json = json;
        this.router = router;
        this.approvals = approvals;
        this.executor = executor;
    }

    /**
     * The production pool: daemon threads (a live socket must never keep the JVM alive) and
     * a bounded queue whose overflow is refused rather than absorbed.
     */
    private static ExecutorService boundedPool() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(WORKERS, WORKERS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "aria-live-tool-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    @Override
    public void onToolCall(LiveSession session, String line) {
        try {
            executor.execute(() -> handle(session, line));
        } catch (RejectedExecutionException e) {
            log.debug("aria live tool call refused: queue full");
            Call call = read(line);
            answerFailure(session, call.id(), call.name(), BUSY);
        }
    }

    /** What the reader handed to a worker: the whole call, executed and answered there. */
    private void handle(LiveSession session, String line) {
        String callId = "";
        String toolName = "";
        UUID runId = null;
        try {
            JsonNode call = json.readTree(line);
            callId = call == null ? "" : call.path("id").asText("");
            toolName = call == null ? "" : call.path("name").asText("");
            if (toolName.isBlank()) {
                // No run and no slot: a nameless call is not a tool-using turn.
                frames.toBridge(session, LiveFrames.toolResult(callId, false, UNKNOWN_TOOL));
                frames.toPhone(session, LiveFrames.toolStatus(toolName, "done"));
                return;
            }
            JsonNode arguments = call.get("arguments");
            runId = runs.findOrCreateForToolTurn(session, toolName);
            frames.toPhone(session, LiveFrames.runEvent(runId, "started", LiveFrames.toolSummary(toolName)));
            frames.toPhone(session, LiveFrames.toolStatus(toolName, "running"));

            LiveToolExecutor.ToolOutcome outcome = tools.execute(toolName, arguments);
            if (outcome instanceof LiveToolExecutor.ToolOutcome.PendingApproval pending) {
                // The run the executor names wins when it names one; the turn's row otherwise,
                // because a gated tool has no way to know which turn asked it.
                UUID parked = pending.runId() == null ? runId : pending.runId();
                JsonNode pendingArguments = pending.arguments() == null ? arguments : pending.arguments();
                UUID approvalId = pending.approvalId();
                boolean created = approvalId == null;
                if (created) {
                    // The ask is the turn's own row: one approval per gated call, on the
                    // lazy live run the phone is already watching.
                    approvalId = approvals.createPending(parked, pending.toolName(),
                            pendingArguments, pending.riskTier());
                }
                boolean parkedOk = router.parkForApproval(session, parked, approvalId,
                        pending.toolName(), pending.riskTier(), callId, pendingArguments);
                if (created && !parkedOk) {
                    // The run could not wait (a cancel or a close won the race): the ask has
                    // no recipient, so it is released instead of waiting forever.
                    approvals.release(approvalId);
                }
            } else if (outcome instanceof LiveToolExecutor.ToolOutcome.Value value) {
                frames.toBridge(session, LiveFrames.toolResult(callId, true, value.json()));
                frames.toPhone(session, LiveFrames.toolStatus(toolName, "done"));
                frames.toPhone(session, LiveFrames.runEvent(runId, "progress", LiveFrames.toolSummary(toolName)));
            } else if (outcome instanceof LiveToolExecutor.ToolOutcome.Failure failure) {
                frames.toBridge(session, LiveFrames.toolResult(callId, false, failure.message()));
                frames.toPhone(session, LiveFrames.toolStatus(toolName, "done"));
            }
        } catch (JsonProcessingException e) {
            log.debug("aria live tool call unreadable: chars={}", line == null ? 0 : line.length());
            answerFailure(session, callId, toolName, UNREADABLE);
        } catch (RuntimeException e) {
            log.warn("live tool call failed: tool={} reason={}", toolName, e.getClass().getSimpleName());
            answerFailure(session, callId, toolName, FAILED);
            if (runId != null) {
                // The turn's row must not stay RUNNING against a call that never completed.
                runs.settle(session, runId, RunStatus.FAILED, FAILED);
                frames.toPhone(session, LiveFrames.runEvent(runId, "failed", LiveFrames.failureSummary(FAILED)));
            }
        }
    }

    @Override
    public void onTurnDone(LiveSession session) {
        router.turnDone(session);
    }

    @Override
    public void onClosed(LiveSession session) {
        router.socketClosed(session);
    }

    /** The answer to a call that never produced an outcome; the chip still names its tool. */
    private void answerFailure(LiveSession session, String callId, String toolName, String message) {
        frames.toBridge(session, LiveFrames.toolResult(callId, false, message));
        frames.toPhone(session, LiveFrames.toolStatus(toolName, "done"));
    }

    /** The id and name of a line that was refused before a worker could read it. */
    private Call read(String line) {
        try {
            JsonNode call = json.readTree(line);
            if (call == null) {
                return Call.NONE;
            }
            return new Call(call.path("id").asText(""), call.path("name").asText(""));
        } catch (JsonProcessingException | RuntimeException e) {
            return Call.NONE;
        }
    }

    private record Call(String id, String name) {
        static final Call NONE = new Call("", "");
    }
}
