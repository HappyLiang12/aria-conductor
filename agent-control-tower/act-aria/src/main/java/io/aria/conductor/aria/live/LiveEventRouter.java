package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.port.LiveRunPark;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * The live run's other half (spec §"Tools, approvals, live run"): the events the rest of
 * the system already emits, turned into the phone's frames and the bridge's injections.
 *
 * <p>Three lifecycles meet here and all of them are owned by the run row, not by a
 * blocked thread:
 * <ul>
 *   <li>a gated tool <em>parks</em> the run as WAITING_INPUT and registers a
 *       {@link LiveRunPark}, so {@code POST /runs/{id}/finalize} answers 202 and settles
 *       ABORTED with {@code [tower] finalized} — a route that already exists for
 *       coordinated runs and needs no second one;</li>
 *   <li>an operator decision ({@link ApprovalDecidedEvent}) is resolved by approval id
 *       against the pending call the socket holds: approve re-executes that call once and
 *       injects its result, deny fails the run with a stable sentence;</li>
 *   <li>a run that ends elsewhere ({@link RunCompletedEvent}, e.g. an operator cancel from
 *       the dashboard) is forwarded once and unparked.</li>
 * </ul>
 *
 * <p>Every event is filtered by the live run before anything is forwarded: a decision or a
 * completion for any other run is not this class's business, and the run store stays the
 * authority for them. Nothing here logs a payload — ids, states and class names only.
 *
 * <p>Two rules keep the seam honest. A decision is applied only after its transaction
 * committed, so nothing written here can roll the operator's own decision back. And a park
 * is the claim on a pending call: whoever releases it (the finalize, a close, the decision)
 * owns the outcome, so a call reaches the model exactly once.
 */
@Slf4j
@Component
public class LiveEventRouter {

    /** The ABORTED reason the finalize route records for a live run. */
    public static final String FINALIZE_REASON = "Operator finalized the live run";
    /** What the model is told when the operator ends the turn instead of answering. */
    public static final String TOWER_FINALIZED = "[tower] finalized";
    /** What the model is told when the operator refuses the gated call. */
    public static final String TOWER_DENIED = "[tower] denied by operator";
    /** The run's own failure reason for a denied call; stable, no tool output in it. */
    static final String DENIED_REASON = "denied by operator";
    /** Answered to the bridge when the run could not take the park at all. */
    static final String APPROVAL_UNAVAILABLE = "approval unavailable";
    /** The ABORTED reason a parked run records when its socket is gone. */
    static final String SESSION_CLOSED_REASON = "Live session closed while waiting for an approval";
    /** Payload status of a parked call; mirrors {@link LiveRunService}'s WAITING_INPUT. */
    static final String PARK_REASON = "approval pending";
    /** Reported when a gated tool answers its own approval with another ask. */
    static final String ASKED_AGAIN = "Tool is still gated";

    private final LiveRunService runs;
    private final LiveToolExecutor tools;
    private final LiveSessionRegistry sessions;
    private final LiveFrames frames;
    private final LiveRunPark park;

    @Autowired
    public LiveEventRouter(LiveRunService runs, LiveToolExecutor tools, LiveSessionRegistry sessions,
                           LiveFrames frames, ObjectProvider<LiveRunPark> park) {
        this(runs, tools, sessions, frames, park.getIfAvailable(() -> LiveRunPark.NONE));
    }

    LiveEventRouter(LiveRunService runs, LiveToolExecutor tools, LiveSessionRegistry sessions,
                    LiveFrames frames, LiveRunPark park) {
        this.runs = runs;
        this.tools = tools;
        this.sessions = sessions;
        this.frames = frames;
        this.park = park;
    }

    // --- parking --------------------------------------------------------------------------

    /**
     * Parks the turn's run on a gated call: WAITING_INPUT, the pending call on the socket,
     * the park, and the two frames the phone renders the ask from.
     *
     * <p>The park is registered last: a finalize that arrives before the row is committed
     * WAITING_INPUT is answered 409 rather than settling a run that never waited. A run
     * that could not take the park (an operator cancel committed first) is reported as the
     * failure it is instead of being offered to an operator who no longer owns it.
     */
    public void parkForApproval(LiveSession session, UUID runId, UUID approvalId, String toolName,
                                String riskTier, String callId, JsonNode arguments) {
        RunStatus parked = runs.settle(session, runId, RunStatus.WAITING_INPUT, PARK_REASON);
        if (parked != RunStatus.WAITING_INPUT) {
            // The call is answered before anything else: Qwen is waiting on this function
            // call, and a park that was refused must not become a hung turn.
            frames.toBridge(session, LiveFrames.toolResult(callId, false, APPROVAL_UNAVAILABLE));
            frames.toPhone(session, LiveFrames.toolStatus(callId, toolName, "done"));
            frames.toPhone(session, LiveFrames.runEvent(runId, state(parked)));
            log.debug("live run could not park: id={} state={}", runId, state(parked));
            return;
        }
        LivePendingToolCall call = new LivePendingToolCall(runId, approvalId, toolName, riskTier,
                callId, arguments);
        session.pendingCall(call);
        park.park(runId, () -> onFinalize(session, call));
        frames.toPhone(session, LiveFrames.toolStatus(callId, toolName, "approval"));
        frames.toPhone(session, LiveFrames.approvalRequired(runId, approvalId, toolName, riskTier));
        log.debug("live run parked on approval: id={}", runId);
    }

    /**
     * What {@code POST /runs/{id}/finalize} runs on a live park. The coordinator removed
     * the park before calling this, so the same request can never run it twice.
     */
    private void onFinalize(LiveSession session, LivePendingToolCall call) {
        session.takePendingCall(call.approvalId());
        runs.settle(session, call.runId(), RunStatus.ABORTED, FINALIZE_REASON);
        frames.toPhone(session, LiveFrames.runEvent(call.runId(), state(RunStatus.ABORTED)));
        frames.toBridge(session, LiveFrames.tower(TOWER_FINALIZED));
        log.debug("live run finalized by the operator: id={}", call.runId());
    }

    // --- events ---------------------------------------------------------------------------

    /**
     * The operator's answer to a gated call the socket holds. A decision for any other
     * approval — another run's, another deployment's, an ask already answered — is
     * ignored: this class only forwards, it never adjudicates.
     *
     * <p>Bound to the commit ({@code ApprovalGate} publishes inside the decision
     * transaction): executing the tool and writing the run there would let a failure here
     * roll the operator's decision back after the bridge already saw a result. A
     * publication with no transaction around it (a test, a future caller) is still served,
     * because the decision is the only signal this route has.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        if (event.getApprovalId() == null) {
            return;
        }
        for (LiveSession session : List.copyOf(sessions.all())) {
            LivePendingToolCall call = session.takePendingCall(event.getApprovalId());
            if (call == null) {
                continue;
            }
            // The park is the claim on this call: a finalize that released it already settled
            // the run and injected its own tower, so the decision must neither execute nor
            // inject a second one.
            if (!park.unpark(call.runId())) {
                log.debug("live approval arrived after its park was released: id={}", call.runId());
                return;
            }
            if (event.getDecision() == ApprovalStatus.APPROVED) {
                approve(session, call);
            } else {
                deny(session, call);
            }
            return;
        }
    }

    /** The run finished somewhere else (an operator cancel above all): say so once. */
    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        if (event.getRunId() == null) {
            return;
        }
        for (LiveSession session : List.copyOf(sessions.all())) {
            UUID owned = session.runId();
            if (!event.getRunId().equals(owned)) {
                continue;
            }
            park.unpark(owned);
            session.takePendingCalls().forEach(call -> park.unpark(call.runId()));
            session.clearRunSlot(owned);
            frames.toPhone(session, LiveFrames.runEvent(owned, state(event.getStatus())));
            log.debug("live run completed elsewhere: id={} state={}", owned, state(event.getStatus()));
            return;
        }
    }

    // --- turn end and socket close --------------------------------------------------------

    /**
     * The bridge's turn end. The run is finalized as COMPLETED only when nothing is parked:
     * a turn waiting on the operator is still the turn, and its end is the decision or the
     * finalize, never this signal. A chat-only turn called no tool and owns no row.
     */
    public void turnDone(LiveSession session) {
        UUID runId = session.runId();
        if (runId == null) {
            return;
        }
        if (park.parked(runId)) {
            return;
        }
        RunStatus settled = runs.settle(session, runId, RunStatus.COMPLETED, null);
        session.takePendingCalls().forEach(call -> park.unpark(call.runId()));
        frames.toPhone(session, LiveFrames.runEvent(runId, state(settled)));
        log.debug("live turn finished: id={} state={}", runId, state(settled));
    }

    /**
     * The socket is gone. Nothing may stay parked for a socket no one can answer on, so
     * every park and pending call is released and the parked run is settled ABORTED: a
     * released park alone would leave a WAITING_INPUT row that no route can reach again
     * (finalize answers 409, the approval has no recipient). The phone frame is
     * best-effort — the socket that would render it is usually already closed.
     *
     * <p>Idempotent: the release returns false the second time, so a close reported twice
     * settles once.
     */
    public void socketClosed(LiveSession session) {
        if (session == null) {
            return;
        }
        UUID parkedRun = session.runId();
        boolean released = parkedRun != null && park.unpark(parkedRun);
        for (LivePendingToolCall call : session.takePendingCalls()) {
            if (park.unpark(call.runId())) {
                released = true;
                parkedRun = call.runId();
            }
        }
        if (!released) {
            return;
        }
        RunStatus settled = runs.settle(session, parkedRun, RunStatus.ABORTED, SESSION_CLOSED_REASON);
        frames.toPhone(session, LiveFrames.runEvent(parkedRun, state(settled)));
        log.debug("live run aborted on socket close: id={} state={}", parkedRun, state(settled));
    }

    // --- outcomes -------------------------------------------------------------------------

    /** The approved call runs once; its value answers the bridge and is injected as TOWER. */
    private void approve(LiveSession session, LivePendingToolCall call) {
        LiveToolExecutor.ToolOutcome outcome = tools.execute(call.toolName(), call.arguments());
        boolean ok = outcome instanceof LiveToolExecutor.ToolOutcome.Value;
        String output = switch (outcome) {
            case LiveToolExecutor.ToolOutcome.Value value -> value.json();
            case LiveToolExecutor.ToolOutcome.Failure failure -> failure.message();
            // A gated tool answered with another ask cannot be parked again from here: the
            // operator already decided this call, so it is reported instead of re-parked.
            case LiveToolExecutor.ToolOutcome.PendingApproval ignored -> ASKED_AGAIN;
        };
        frames.toBridge(session, LiveFrames.toolResult(call.callId(), ok, output));
        frames.toPhone(session, LiveFrames.toolStatus(call.callId(), call.toolName(), "done"));
        if (ok) {
            frames.toBridge(session, LiveFrames.tower("[tower] " + output));
        }
        RunStatus settled = runs.settle(session, call.runId(), ok ? RunStatus.COMPLETED : RunStatus.FAILED,
                ok ? null : output);
        frames.toPhone(session, LiveFrames.runEvent(call.runId(), state(settled)));
        log.debug("live approval executed: id={} ok={}", call.runId(), ok);
    }

    /**
     * The refused call never runs; the bridge is answered first (every function call gets
     * a result) and the model is then told, and the run fails with a stable reason.
     */
    private void deny(LiveSession session, LivePendingToolCall call) {
        frames.toBridge(session, LiveFrames.toolResult(call.callId(), false, DENIED_REASON));
        frames.toBridge(session, LiveFrames.tower(TOWER_DENIED));
        frames.toPhone(session, LiveFrames.toolStatus(call.callId(), call.toolName(), "done"));
        RunStatus settled = runs.settle(session, call.runId(), RunStatus.FAILED, DENIED_REASON);
        frames.toPhone(session, LiveFrames.runEvent(call.runId(), state(settled)));
        log.debug("live approval denied: id={}", call.runId());
    }

    /**
     * The phone's run state for a committed status: only COMPLETED is a finish, and an
     * unknown run (null) or any way of stopping is reported as the failure it is.
     */
    private static String state(RunStatus status) {
        return status == RunStatus.COMPLETED ? "finished" : "failed";
    }
}
