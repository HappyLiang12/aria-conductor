package io.aria.conductor.execution.runtime.core;

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
 *       shape. The recorded evidence contains no observed streaming route for
 *       this core, so no progress channel is invented here.</li>
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
public final class OpenCodeCoreSession implements CoreSession, AutoCloseable {

    private final ExecutionSpec spec;
    private final OpenCodeHttpClient client;
    private final String sessionId;
    private final String model;

    OpenCodeCoreSession(ExecutionSpec spec, OpenCodeHttpClient client, String sessionId, String model) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.client = Objects.requireNonNull(client, "client");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.model = Objects.requireNonNull(model, "model");
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
     */
    @Override
    public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(events, "events");
        if (spec.deadline() == null) {
            return CompletableFuture.failedFuture(noDeadlineRefusal());
        }
        Duration timeout = remaining();
        if (timeout.isZero()) {
            return CompletableFuture.failedFuture(new TaskExecutionException(
                    TaskExecutionException.Cause.TIMEOUT,
                    "The run deadline of run " + spec.runId() + " elapsed before the prompt was sent"));
        }
        List<String> parts = new ArrayList<>();
        if (!task.history().isEmpty()) {
            parts.add(CorePromptComposition.transcript(task.history()));
        }
        parts.add(task.userPrompt() == null ? "" : task.userPrompt());
        String systemPrompt = task.systemPrompt();

        CompletableFuture<CoreResult> future = new CompletableFuture<>();
        Thread.ofVirtual().name("opencode-prompt-" + spec.runId()).start(() -> {
            try {
                OpenCodeHttpClient.MessageResult message =
                        client.sendPrompt(sessionId, systemPrompt, parts, timeout);
                events.accept(new CoreEvent("opencode.message", spec.runId(), sessionId, null,
                        message.rawJson()));
                future.complete(new CoreResult(sessionId, message.finalOutput(),
                        new UsageSnapshot(message.inputTokens(), message.outputTokens(), null,
                                message.observedModel()),
                        false));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
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
        client.close();
    }

    /** The remaining run budget; zero means the run may not start another prompt. */
    private Duration remaining() {
        Instant deadline = spec.deadline();
        if (deadline == null) {
            // Unreachable: prompt() refuses a null deadline up front. Kept as a
            // hard failure so the silent "HTTP client default timeout" fallback
            // cannot come back through a future caller.
            throw noDeadlineRefusal();
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
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
