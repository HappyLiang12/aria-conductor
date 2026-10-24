package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.runtime.progress.CoreProgressPump;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One live session with a run-owned OpenCode server (task 11): the native
 * message envelope, cooperative cancellation and usage normalization behind the
 * shared {@link CoreSession} port.
 *
 * <p>Translation rules, taken from the recorded native subset rather than
 * invented:
 * <ul>
 *   <li>The session identity is the native session id the server returned.</li>
 *   <li>A prompt is one message envelope: the task's system material in the
 *       native {@code system} member and the ordered text parts (the history
 *       transcript followed by the current user request). The reviewed model pin
 *       is a session-level record and never a payload member -- opencode >= 1.18
 *       refuses a string model member, so the server's configured model serves.
 *       History is context material, never a replayed native message,
 *       so an earlier tool action is not executed again.</li>
 *   <li>Usage is what the message envelope reported: a missing {@code tokens}
 *       member means unknown counters, not zero, and the observed model is the
 *       envelope's {@code modelID} -- never the requested pin.</li>
 *   <li>The native envelope of the answered message is forwarded verbatim as
 *       one {@link CoreEvent}, so the governance layer keeps the exact wire
 *       shape.</li>
 *   <li>While the prompt runs, a {@link CoreProgressPump} polls the recorded
 *       message-list route and forwards each part's new observations as
 *       {@code opencode.progress} CoreEvents (a part that grew since the last poll is
 *       a new observation of the SAME part, so an answer streaming in place keeps
 *       reaching the dashboard); the cursor is seeded from the pre-prompt message list
 *       and the pump is stopped on every completion path (success, failure,
 *       cancellation) and on {@link #close()}. The stop joins the poll worker
 *       within a bounded budget, so the run's terminal result never waits longer
 *       than that budget on progress observability, and the turn's HTTP timeout
 *       is measured after the baseline seed, not before it.</li>
 *   <li>The SUCCESS path drains that pump once ({@link CoreProgressPump#drain()})
 *       after {@code sendPrompt} returned and before stopping it. This asymmetry is
 *       the point: the reviewed core writes a part's whole content in ONE step and
 *       finalizes the assistant text part only at/after the POST returns, so without
 *       that final read a real HOST turn persisted {@code PROGRESS rows=0}. Failure,
 *       cancellation and {@link #close()} do not drain -- a turn that did not answer
 *       has nothing to recover, and a halted run must stay silent (#111).</li>
 *   <li>Cancel uses the recorded abort route. Its boolean answer is an
 *       acknowledgement, not proof of a stopped core (the committed
 *       non-cooperative fixture answers {@code true} while ignoring the abort),
 *       so the truthful acknowledgement stays unverified and the verified stop
 *       remains the backend's.</li>
 *   <li>Native pause does not exist in the reviewed subset (the recorded
 *       unsupported-mode evidence answers the pause route with 404), so the
 *       session refuses locally and reports the unchanged, unverified state --
 *       it never invents a call and never falls back by itself.</li>
 *   <li>The HTTP surface has no native permission reply channel: a decision is
 *       refused explicitly instead of being sent to an invented route.</li>
 * </ul>
 *
 * <p>The session allocates no container and no user workspace: it talks to the
 * endpoint its launch produced.
 */
@Slf4j
public final class OpenCodeCoreSession implements CoreSession, AutoCloseable {

    /** Payload serializer of the progress fragments; no time types are involved. */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    private final ExecutionSpec spec;
    private final OpenCodeHttpClient client;
    private final String sessionId;
    private final String model;
    /**
     * Session-scoped progress cursor: the tracked parts and the seq survive across
     * prompts of this native session, so a continuation neither replays the
     * previous turn (nor an earlier snapshot of one still-listed part) nor
     * restarts seq at 1.
     */
    private final CoreProgressPump.ProgressCursor progressCursor = new CoreProgressPump.ProgressCursor();
    /** Guards the closed flag and the pump assignment made by the prompt thread. */
    private final Object lifecycleLock = new Object();
    /** The turn-budget clock: the prompt timeout is what the deadline still allows. */
    private final Clock clock;
    /** Set by close(); a prompt (or its pump) never starts after it is observed. */
    private volatile boolean closed;
    /** The pump of the in-flight prompt, so close() can halt it. */
    private volatile CoreProgressPump progressPump;

    OpenCodeCoreSession(ExecutionSpec spec, OpenCodeHttpClient client, String sessionId, String model) {
        this(spec, client, sessionId, model, Clock.systemUTC());
    }

    OpenCodeCoreSession(ExecutionSpec spec, OpenCodeHttpClient client, String sessionId, String model,
            Clock clock) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.client = Objects.requireNonNull(client, "client");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.model = Objects.requireNonNull(model, "model");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    /**
     * The reviewed model pin of this session: the model the run requested. It is
     * reported alongside the observed model; the native message envelope never
     * carries it (the server serves with its configured model).
     */
    String requestedModel() {
        return model;
    }

    /**
     * Sends one message envelope and completes with its normalized result. The
     * request runs on its own virtual thread: the port is asynchronous and the
     * caller's thread must not be held for a whole core turn. The exact native
     * envelope is forwarded before the stage completes, so a consumer that saw
     * the result cannot miss the event that carried it.
     *
     * <p>The turn's HTTP timeout is the deadline as it stands right before the
     * send, i.e. after the pre-prompt progress baseline: time the baseline spent
     * is time the run no longer has, and a deadline that runs out during it is a
     * TIMEOUT refusal rather than a prompt sent with no budget left.
     */
    @Override
    public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events) {
        return prompt(task, events, spec.deadline());
    }

    @Override
    public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events, Instant deadline) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(events, "events");
        if (deadline == null) {
            return CompletableFuture.failedFuture(noDeadlineRefusal());
        }
        Duration budgetAtCall = remaining(deadline);
        if (budgetAtCall.isZero()) {
            return CompletableFuture.failedFuture(new TaskExecutionException(
                    TaskExecutionException.Cause.TIMEOUT,
                    "The run deadline of run " + spec.runId() + " elapsed before the prompt was sent"));
        }
        if (closed) {
            return CompletableFuture.failedFuture(closedRefusal());
        }
        List<String> parts = new ArrayList<>();
        if (!task.history().isEmpty()) {
            parts.add(CorePromptComposition.transcript(task.history()));
        }
        parts.add(task.userPrompt() == null ? "" : task.userPrompt());
        String systemPrompt = task.systemPrompt();

        CompletableFuture<CoreResult> future = new CompletableFuture<>();
        Thread.ofVirtual().name("opencode-prompt-" + spec.runId()).start(() -> {
            CoreProgressPump pump = new CoreProgressPump(client, sessionId, spec.runId(), spec.agentId(),
                    fragment -> events.accept(new CoreEvent("opencode.progress", spec.runId(),
                            sessionId, null, progressPayload(fragment))),
                    CoreProgressPump.DEFAULT_POLL_INTERVAL, CoreProgressPump.DEFAULT_COALESCE_WINDOW,
                    progressCursor);
            // The pump assignment/start and the closed check share one lock:
            // close() either sees a started pump and stops it, or wins first
            // and this prompt refuses before any pump starts.
            CoreResult result = null;
            RuntimeException failure = null;
            try {
                seedProgressBaseline();
                // The turn budget is measured AFTER the progress baseline: the
                // pre-prompt message-list read is a real HTTP call (up to
                // LIST_MESSAGES_TIMEOUT) and its time belongs to the turn, not to
                // the pump. The caller's pre-check above only refuses an already
                // expired deadline cheaply; this is the budget the send gets.
                Duration turnTimeout = remaining(deadline);
                if (turnTimeout.isZero()) {
                    throw new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                            "The run deadline of run " + spec.runId()
                                    + " elapsed during the pre-prompt progress baseline");
                }
                synchronized (lifecycleLock) {
                    if (closed) {
                        future.completeExceptionally(closedRefusal());
                        return;
                    }
                    progressPump = pump;
                    pump.start();
                }
                OpenCodeHttpClient.MessageResult message =
                        client.sendPrompt(sessionId, systemPrompt, parts, turnTimeout);
                // The success path only, and before the halt: opencode 1.18.15 finalizes
                // the assistant's text part at/after this POST returns, so the terminal
                // parts of the turn exist only in a read taken now. A failed or cancelled
                // turn deliberately does not drain -- it has no finished answer to recover,
                // and issue #111 requires a halted run to stay silent.
                pump.drain();
                events.accept(new CoreEvent("opencode.message", spec.runId(), sessionId, null,
                        message.rawJson()));
                result = new CoreResult(sessionId, message.finalOutput(),
                        new UsageSnapshot(message.inputTokens(), message.outputTokens(), null,
                                message.observedModel()),
                        false);
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                // Every exit path -- success, failure, cancellation, close --
                // stops the pump before the terminal result becomes visible, so
                // no progress event can follow the future's completion.
                pump.stop();
                synchronized (lifecycleLock) {
                    if (progressPump == pump) {
                        progressPump = null;
                    }
                }
            }
            if (failure != null) {
                future.completeExceptionally(failure);
            } else {
                future.complete(result);
            }
        });
        return future;
    }

    /** The {@code opencode.progress} payload the coordinator maps to a RunProgressEvent. */
    private static String progressPayload(CoreProgressPump.ProgressFragment fragment) {
        ObjectNode payload = PAYLOAD_MAPPER.createObjectNode();
        payload.put("kind", fragment.kind().name());
        payload.put("content", fragment.content());
        payload.put("toolName", fragment.toolName());
        payload.put("seq", fragment.seq());
        payload.put("iteration", fragment.iteration());
        // The part this observation is of: null when the core reported no id, which
        // the producer keeps as an explicit JSON null rather than a missing member.
        payload.put("partId", fragment.partId());
        return payload.toString();
    }

    /**
     * Seeds the session cursor from the message list as it stands before this
     * prompt is sent. The pump's own first poll races the new turn; this
     * pre-prompt snapshot is what makes "only new parts" true even for a
     * session whose previous turn was so fast that its pump never observed it.
     * Progress is observability: a baseline read that unexpectedly throws must
     * never fail the prompt (the client documents that it does not throw).
     */
    private void seedProgressBaseline() {
        try {
            OpenCodeHttpClient.MessageListResult baseline = client.listMessages(sessionId);
            if (!baseline.degraded()) {
                progressCursor.seedFrom(baseline.messages());
            }
        } catch (RuntimeException e) {
            // Keep the prompt alive; the pump's first poll remains the baseline.
            log.debug("OpenCode session {}: the pre-prompt progress baseline failed (ignored): {}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * Native pause is not part of the verified subset: no call is made, no
     * state change is reported, and no fallback is chosen by the session.
     */
    @Override
    public CompletionStage<ControlAck> pause(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, false));
    }

    @Override
    public CompletionStage<ControlAck> resume(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, false));
    }

    /**
     * The recorded abort route. Nothing more is claimed: the boolean the core
     * answers with is an acknowledgement of the request, so the state stays
     * RUNNING/unverified and a verified stop remains the owning backend's.
     */
    @Override
    public CompletionStage<ControlAck> cancel(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        try {
            client.abortSession(sessionId);
            return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, false));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Refused explicitly: this HTTP surface carries no native permission reply
     * channel, and an approval is never delivered through an invented route.
     * The run-bound decision belongs to the run policy and the permission
     * coordinator, which own the write gate.
     */
    @Override
    public CompletionStage<Void> decide(PermissionReply reply) {
        Objects.requireNonNull(reply, "reply");
        return CompletableFuture.failedFuture(new IllegalStateException(
                "The OpenCode HTTP surface exposes no native permission reply channel; request "
                        + reply.requestId() + " cannot be answered natively and is delivered by the run policy, "
                        + "never by an invented core route"));
    }

    @Override
    public void close() {
        CoreProgressPump pump;
        synchronized (lifecycleLock) {
            closed = true;
            pump = progressPump;
            progressPump = null;
        }
        if (pump != null) {
            pump.stop();
        }
        client.close();
    }

    /** The refusal of a prompt or pump that would start after {@link #close()}. */
    private IllegalStateException closedRefusal() {
        return new IllegalStateException("The OpenCode session of run " + spec.runId()
                + " is closed; no prompt or progress pump may start");
    }

    /**
     * The remaining budget of the turn's deadline; zero means no prompt may start
     * (or, after the progress baseline, that none is left for the send).
     */
    private Duration remaining(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * A run without its frozen deadline is a programming error: the design
     * freezes a 45-minute deadline for every run, so a prompt that arrived
     * without one is refused instead of silently becoming the HTTP client's
     * five-minute default.
     */
    private IllegalStateException noDeadlineRefusal() {
        return new IllegalStateException("The OpenCode session of run " + spec.runId()
                + " carries no run deadline; every run freezes a 45-minute deadline, so a prompt"
                + " without one is refused");
    }
}
