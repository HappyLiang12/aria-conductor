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

    CompletionStage<ControlAck> pause(Instant deadline);

    CompletionStage<ControlAck> resume(Instant deadline);

    CompletionStage<ControlAck> cancel(Instant deadline);

    CompletionStage<Void> decide(PermissionReply reply);
}
