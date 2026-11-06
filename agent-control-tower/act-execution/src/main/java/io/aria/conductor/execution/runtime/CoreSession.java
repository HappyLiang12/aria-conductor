package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.approval.PermissionReply;

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One live session with a running core: prompt delivery, the cooperative
 * control operations, and the answer to one native permission request. Control
 * acknowledgements are truthful -- an operation is only reported as verified
 * after the core's observed state confirms it.
 */
public interface CoreSession {

    String sessionId();

    CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events);

    /**
     * Sends one prompt bounded by the given deadline. The coordinator owns the
     * deadline of every turn (the run's frozen deadline on the first turn, a
     * fresh per-turn window after a clarification answer), so a session that
     * enforces its own timeout must enforce that same instant. The default
     * keeps sessions that only know their frozen spec deadline unchanged.
     */
    default CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events, Instant deadline) {
        return prompt(task, events);
    }

    CompletionStage<ControlAck> pause(Instant deadline);

    CompletionStage<ControlAck> resume(Instant deadline);

    CompletionStage<ControlAck> cancel(Instant deadline);

    CompletionStage<Void> decide(PermissionReply reply);
}
