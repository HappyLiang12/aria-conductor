package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.UsageSnapshot;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One live session with a run-bound Qoder bridge (task 11): prompts, the
 * bridge's cooperative control vocabulary and native permission replies,
 * translated into the shared {@link CoreEvent}/{@link CoreResult} contract.
 *
 * <p>Translation rules, all taken from the committed bridge contract rather
 * than invented:
 * <ul>
 *   <li>The session identity is the native ACP session id the bridge serves
 *       ({@code GET /session}), not a locally minted one.</li>
 *   <li>The final output is the ordered concatenation of
 *       {@code agent_message_chunk} text; thought chunks are forwarded as
 *       progress but are not the answer.</li>
 *   <li>Usage is what the core reported. A reported counter passes through
 *       exactly -- including a reported zero -- while an unreported counter
 *       stays {@code null}; {@code credits} is never reported natively. The
 *       observed model comes from the core's own quota member and is never the
 *       requested pin.</li>
 *   <li>Events are forwarded with the bridge's own sequence and payload, so the
 *       governance layer sees exactly what the runtime emitted.</li>
 *   <li>Control acknowledgements are the bridge's truthful ones. Native pause
 *       does not exist (the bridge refuses with {@code E_PAUSE_UNSUPPORTED}) and
 *       the session reports an unchanged, unverified state instead of falling
 *       back to anything: process-level suspension is the backend's separately
 *       verified strategy.</li>
 *   <li>A permission reply maps the operator's chosen <em>native</em> option id
 *       onto the bridge's choice vocabulary through the options the core
 *       actually offered; an unoffered option, an allow-always option and a
 *       reply correlated to another run or session are refused.</li>
 * </ul>
 *
 * <p>The session opens no container and no user workspace: it talks to the
 * endpoint its launch produced.
 */
public final class QoderCoreSession implements CoreSession, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExecutionSpec spec;
    private final QoderBridgeClient client;
    private final String model;
    private final String sessionId;

    /** Options the core offered, per native permission request id. */
    private final Map<Integer, List<OfferedOption>> offeredOptions = new ConcurrentHashMap<>();

    private final Object promptLock = new Object();
    private volatile boolean closed;
    private volatile boolean promptInFlight;
    private volatile int currentPromptId = -1;
    private volatile CompletableFuture<CoreResult> pending;
    private volatile Consumer<CoreEvent> consumer;
    private volatile StringBuilder output;
    private volatile QoderBridgeClient.EventStream stream;
    private volatile Thread reader;

    /** One offered native permission option, as the core offered it. */
    private record OfferedOption(String optionId, String kind) {
    }

    QoderCoreSession(ExecutionSpec spec, QoderBridgeClient client, String model) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        QoderBridgeClient.SessionView view = client.sessionBoundTo(spec.runId());
        String nativeSessionId = view.sessionId();
        if (nativeSessionId == null || nativeSessionId.isBlank()) {
            throw new IllegalStateException("The Qoder bridge for run " + spec.runId()
                    + " reports no native session id; no session can be supervised without one");
        }
        this.sessionId = nativeSessionId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    /** The reviewed model pin the launch asked the core for. */
    String requestedModel() {
        return model;
    }

    @Override
    public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(events, "events");
        if (spec.deadline() == null) {
            return CompletableFuture.failedFuture(noDeadlineRefusal());
        }
        CompletableFuture<CoreResult> promptFuture;
        synchronized (promptLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The Qoder session of run " + spec.runId() + " is closed"));
            }
            if (promptInFlight) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "A prompt is already in flight for run " + spec.runId()
                                + "; the bridge accepts one prompt at a time"));
            }
            promptInFlight = true;
            currentPromptId = -1;
            promptFuture = new CompletableFuture<>();
            pending = promptFuture;
            consumer = events;
            output = new StringBuilder();
        }
        String text = CorePromptComposition.joined(task.systemPrompt(), task.history(), task.userPrompt());
        try {
            // The reader start belongs to the guarded block: an unreachable
            // bridge must fail this prompt as a stage -- and must clear the
            // in-flight state it just claimed -- instead of escaping
            // synchronously and leaving the session latched.
            startReader();
            QoderBridgeClient.PromptAck ack = client.prompt(text);
            currentPromptId = ack.promptId();
        } catch (RuntimeException e) {
            clearPromptIfCurrent(promptFuture);
            return CompletableFuture.failedFuture(translate(e));
        }
        scheduleDeadline(promptFuture);
        // The captured stage is returned, never the field: a concurrent clear
        // (a close, or an event handled on the reader thread) may null the field
        // between the guarded block and here, and a null return would surface as
        // an NPE in the caller instead of a prompt outcome.
        return promptFuture;
    }

    @Override
    public CompletionStage<ControlAck> pause(Instant deadline) {
        return control("pause", deadline);
    }

    @Override
    public CompletionStage<ControlAck> resume(Instant deadline) {
        return control("resume", deadline);
    }

    @Override
    public CompletionStage<ControlAck> cancel(Instant deadline) {
        return control("cancel", deadline);
    }

    /**
     * One control operation. The bridge's answer is passed through truthfully;
     * the native-unavailable refusal ({@code E_PAUSE_UNSUPPORTED}) becomes an
     * unverified acknowledgement of the unchanged state, because the session
     * must never claim a pause the core did not reach -- and must never fall
     * back to another technique on its own. The caller's window bounds the
     * request; an already elapsed window is answered without a call, because no
     * state may be claimed from a window that is already over.
     */
    private CompletionStage<ControlAck> control(String action, Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis <= 0) {
            return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, false));
        }
        try {
            QoderBridgeClient.ControlBody ack = client.control(action, Duration.ofMillis(millis));
            return CompletableFuture.completedFuture(new ControlAck(
                    ControlState.valueOf(ack.state()), ack.verified()));
        } catch (QoderBridgeClient.BridgeFailure e) {
            if ("E_PAUSE_UNSUPPORTED".equals(e.code())) {
                return CompletableFuture.completedFuture(new ControlAck(ControlState.RUNNING, false));
            }
            return CompletableFuture.failedFuture(translate(e));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(translate(e));
        }
    }

    @Override
    public CompletionStage<Void> decide(PermissionReply reply) {
        Objects.requireNonNull(reply, "reply");
        try {
            return CompletableFuture.completedFuture(deliver(reply));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(translate(e));
        }
    }

    private Void deliver(PermissionReply reply) {
        if (!spec.runId().equals(reply.runId())) {
            throw new IllegalArgumentException("Permission reply belongs to run " + reply.runId()
                    + ", not the session's run " + spec.runId());
        }
        if (!sessionId.equals(reply.sessionId())) {
            throw new IllegalArgumentException("Permission reply belongs to session " + reply.sessionId()
                    + ", not this session " + sessionId);
        }
        int requestId;
        try {
            requestId = Integer.parseInt(reply.requestId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Permission reply carries a non-numeric request id: "
                    + reply.requestId(), e);
        }
        List<OfferedOption> options = offeredOptions.get(requestId);
        if (options == null) {
            throw new IllegalStateException("No native permission request " + requestId
                    + " was observed by this session; nothing may be answered for it");
        }
        OfferedOption selected = options.stream()
                .filter(option -> option.optionId().equals(reply.optionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Option " + reply.optionId()
                        + " was not offered for native permission request " + requestId
                        + "; offered: " + options.stream().map(OfferedOption::optionId).toList()));
        String choice = choice(selected);
        client.permissionResponse(requestId, choice);
        return null;
    }

    /**
     * The bridge's choice vocabulary. An allow-once reply stays an allow-once
     * reply: an allow-always option is never selected as its substitute.
     */
    private static String choice(OfferedOption option) {
        return switch (option.kind()) {
            case "allow_once" -> "ALLOW_ONCE";
            case "reject_once" -> "DENY";
            case "allow_always" -> throw new IllegalStateException("Option " + option.optionId()
                    + " is an allow_always option; an allow-once decision never grants a session-wide escalation");
            default -> throw new IllegalStateException("Option " + option.optionId()
                    + " carries the unsupported kind " + option.kind());
        };
    }

    @Override
    public void close() {
        synchronized (promptLock) {
            closed = true;
        }
        QoderBridgeClient.EventStream open = stream;
        if (open != null) {
            open.close();
        }
        CompletableFuture<CoreResult> future;
        synchronized (promptLock) {
            future = pending;
            clearPromptLocked();
        }
        if (future != null) {
            future.completeExceptionally(new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                    "The Qoder session of run " + spec.runId() + " was closed while a prompt was in flight"));
        }
        client.close();
    }

    // ------------------------------------------------------------ translation

    private void startReader() {
        if (reader != null) {
            return;
        }
        synchronized (promptLock) {
            if (reader != null) {
                return;
            }
            stream = client.events(0);
            Thread thread = new Thread(this::readLoop, "qoder-bridge-events-" + spec.runId());
            thread.setDaemon(true);
            reader = thread;
            thread.start();
        }
    }

    private void readLoop() {
        QoderBridgeClient.EventStream open = stream;
        for (;;) {
            Optional<QoderBridgeClient.Frame> next;
            try {
                next = open.next();
            } catch (RuntimeException e) {
                if (closed) {
                    return; // the stream was closed locally; close() fails the pending prompt
                }
                failPending("The Qoder bridge event stream failed: " + e.getMessage(), e);
                return;
            }
            if (next.isEmpty()) {
                if (closed) {
                    return;
                }
                failPending("The Qoder bridge event stream ended before the prompt settled", null);
                return;
            }
            dispatch(next.get());
        }
    }

    private void dispatch(QoderBridgeClient.Frame frame) {
        JsonNode payload = parse(frame.payloadJson());
        String type = frame.type() == null ? "" : frame.type();
        if ("permission.request".equals(type)) {
            recordOfferedOptions(payload);
        }
        // Forward first: a consumer that observes the terminal event must never
        // race the completion of the stage that carried it.
        Consumer<CoreEvent> target = promptInFlight ? consumer : null;
        if (target != null) {
            target.accept(new CoreEvent(type, spec.runId(), sessionId, requestIdOf(type, payload),
                    frame.payloadJson()));
        }
        if (promptInFlight && "session.update".equals(type)) {
            accumulate(payload);
        }
        if (promptInFlight && isTerminal(type) && isCurrentPrompt(payload)) {
            if ("prompt.result".equals(type)) {
                completeResult(payload);
            } else {
                failPending("Qoder bridge refused the prompt (" + payload.path("code").asText(null) + "): "
                        + payload.path("message").asText(null), null);
            }
        }
    }

    private void recordOfferedOptions(JsonNode payload) {
        if (!payload.path("requestId").canConvertToInt()) {
            return;
        }
        List<OfferedOption> options = new ArrayList<>();
        for (JsonNode option : payload.path("options")) {
            options.add(new OfferedOption(option.path("optionId").asText(null), option.path("kind").asText(null)));
        }
        offeredOptions.put(payload.path("requestId").asInt(), List.copyOf(options));
    }

    private void accumulate(JsonNode payload) {
        JsonNode update = payload.path("update");
        if (!"agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
            return;
        }
        StringBuilder text = output;
        if (text == null || !"text".equals(update.path("content").path("type").asText())) {
            return;
        }
        text.append(update.path("content").path("text").asText());
    }

    private boolean isCurrentPrompt(JsonNode payload) {
        if (currentPromptId < 0) {
            return true;
        }
        return payload.path("promptId").asInt(-1) == currentPromptId;
    }

    private static boolean isTerminal(String type) {
        return "prompt.result".equals(type) || "prompt.error".equals(type);
    }

    private static String requestIdOf(String type, JsonNode payload) {
        if (type.startsWith("permission.") && payload.path("requestId").isNumber()) {
            return payload.path("requestId").asText();
        }
        if (type.startsWith("prompt.") && payload.path("promptId").isNumber()) {
            return payload.path("promptId").asText();
        }
        return null;
    }

    /**
     * The terminal result. A reported counter is preserved exactly (a reported
     * zero is a zero); an unreported one stays null, and the observed model is
     * the core's own report -- never the requested pin.
     */
    private void completeResult(JsonNode payload) {
        CompletableFuture<CoreResult> future;
        StringBuilder text;
        synchronized (promptLock) {
            future = pending;
            text = output;
            clearPromptLocked();
        }
        if (future == null) {
            return;
        }
        JsonNode usage = payload.path("usage");
        Long inputTokens = usage.has("inputTokens") ? usage.path("inputTokens").asLong() : null;
        Long outputTokens = usage.has("outputTokens") ? usage.path("outputTokens").asLong() : null;
        String observedModel = payload.path("observedModel").isTextual()
                ? payload.path("observedModel").asText()
                : null;
        boolean cancelled = "cancelled".equals(payload.path("stopReason").asText(null));
        future.complete(new CoreResult(sessionId, text == null ? "" : text.toString(),
                new UsageSnapshot(inputTokens, outputTokens, null, observedModel), cancelled));
    }

    private void failPending(String message, Throwable cause) {
        CompletableFuture<CoreResult> future;
        synchronized (promptLock) {
            future = pending;
            clearPromptLocked();
        }
        if (future != null) {
            future.completeExceptionally(cause == null
                    ? new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR, message)
                    : new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR, message, cause));
        }
    }

    private void clearPrompt() {
        synchronized (promptLock) {
            clearPromptLocked();
        }
    }

    private void clearPromptLocked() {
        promptInFlight = false;
        currentPromptId = -1;
        pending = null;
        consumer = null;
        output = null;
    }

    private void scheduleDeadline(CompletableFuture<CoreResult> future) {
        Instant deadline = spec.deadline();
        if (deadline == null) {
            // Unreachable: prompt() refuses a null deadline up front. Kept as a
            // hard failure so the silent "scheduled without any deadline" path
            // cannot come back through a future caller.
            throw noDeadlineRefusal();
        }
        long millis = Duration.between(Instant.now(), deadline).toMillis();
        if (millis <= 0) {
            completeTimedOut(future, deadline);
            return;
        }
        CompletableFuture.delayedExecutor(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> completeTimedOut(future, deadline));
    }

    /**
     * A run without its frozen deadline is a programming error: the design
     * freezes a 45-minute deadline for every run, so a prompt that arrived
     * without one is refused instead of being allowed to run unbounded.
     */
    private IllegalStateException noDeadlineRefusal() {
        return new IllegalStateException("The Qoder session of run " + spec.runId()
                + " carries no run deadline; every run freezes a 45-minute deadline, so a prompt"
                + " without one is refused");
    }

    private void completeTimedOut(CompletableFuture<CoreResult> future, Instant deadline) {
        if (future.isDone()) {
            return;
        }
        if (future.completeExceptionally(new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                "The prompt of run " + spec.runId() + " exceeded the run deadline " + deadline))) {
            clearPromptIfCurrent(future);
        }
    }

    private void clearPromptIfCurrent(CompletableFuture<CoreResult> future) {
        synchronized (promptLock) {
            if (pending == future) {
                clearPromptLocked();
            }
        }
    }

    private static JsonNode parse(String payloadJson) {
        try {
            return JSON.readTree(payloadJson);
        } catch (IOException e) {
            throw new IllegalStateException("The Qoder bridge emitted an unparseable event payload: "
                    + payloadJson, e);
        }
    }

    private static Throwable translate(RuntimeException failure) {
        if (failure instanceof TaskExecutionException) {
            return failure;
        }
        if (failure instanceof QoderBridgeClient.BridgeFailure bridge) {
            return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                    "Qoder bridge refused the request (" + bridge.code() + "): " + bridge.getMessage(),
                    bridge);
        }
        return failure;
    }
}
