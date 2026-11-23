package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
 *
 * <p>Executed on the bridge reader thread; the run writes are the repository's conditional
 * statements and the frames take the socket's own lock, so no answer parks this thread.
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

    private final LiveRunService runs;
    private final LiveToolExecutor tools;
    private final LiveFrames frames;
    private final ObjectMapper json;
    private final LiveEventRouter router;

    @Autowired
    public LiveToolCallHandler(LiveRunService runs, LiveToolExecutor tools, LiveFrames frames,
                               ObjectMapper json, LiveEventRouter router) {
        this.runs = runs;
        this.tools = tools;
        this.frames = frames;
        this.json = json;
        this.router = router;
    }

    @Override
    public void onToolCall(LiveSession session, String line) {
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
                frames.toPhone(session, LiveFrames.toolStatus(callId, toolName, "done"));
                return;
            }
            JsonNode arguments = call.get("arguments");
            runId = runs.findOrCreateForToolTurn(session, toolName);
            frames.toPhone(session, LiveFrames.runEvent(runId, "started"));
            frames.toPhone(session, LiveFrames.toolStatus(callId, toolName, "running"));

            LiveToolExecutor.ToolOutcome outcome = tools.execute(toolName, arguments);
            if (outcome instanceof LiveToolExecutor.ToolOutcome.PendingApproval pending) {
                // The run the executor names wins when it names one; the turn's row otherwise,
                // because a gated tool has no way to know which turn asked it.
                UUID parked = pending.runId() == null ? runId : pending.runId();
                router.parkForApproval(session, parked, pending.approvalId(), toolName,
                        pending.riskTier(), callId, arguments);
            } else if (outcome instanceof LiveToolExecutor.ToolOutcome.Value value) {
                frames.toBridge(session, LiveFrames.toolResult(callId, true, value.json()));
                frames.toPhone(session, LiveFrames.toolStatus(callId, toolName, "done"));
                frames.toPhone(session, LiveFrames.runEvent(runId, "progress"));
            } else if (outcome instanceof LiveToolExecutor.ToolOutcome.Failure failure) {
                frames.toBridge(session, LiveFrames.toolResult(callId, false, failure.message()));
                frames.toPhone(session, LiveFrames.toolStatus(callId, toolName, "done"));
            }
        } catch (JsonProcessingException e) {
            log.debug("aria live tool call unreadable: chars={}", line == null ? 0 : line.length());
            answerFailure(session, callId, UNREADABLE);
        } catch (RuntimeException e) {
            log.warn("live tool call failed: tool={} reason={}", toolName, e.getClass().getSimpleName());
            answerFailure(session, callId, FAILED);
            if (runId != null) {
                // The turn's row must not stay RUNNING against a call that never completed.
                runs.settle(session, runId, RunStatus.FAILED, FAILED);
                frames.toPhone(session, LiveFrames.runEvent(runId, "failed"));
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

    private void answerFailure(LiveSession session, String callId, String message) {
        frames.toBridge(session, LiveFrames.toolResult(callId, false, message));
        frames.toPhone(session, LiveFrames.toolStatus(callId, "", "done"));
    }
}
