package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.port.RunRuntimeControlPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The run store's runtime-control port ({@link RunRuntimeControlPort}), served
 * by the run coordinator (Task 19, fix round 7; cancel added for #111 fix
 * round 1): a coordinated run's pause, resume and cancel are the coordinator's
 * verified control operations on the run-owned core, and the run store may
 * persist PAUSED/RUNNING/CANCELLED only after this port returned -- the same
 * truthfulness contract the engine's own pause/resume/cancel paths carry (a
 * refusal surfaces the exact pending-control reason and claims no state).
 *
 * <p>{@link #owns} is the coordinator's ownership answer for one run
 * ({@link CoreExecutionService#ownsRun(UUID, UUID)}): the run's runtime record is
 * live in this process's registry. It deliberately does NOT require an opened
 * session -- a run whose launch is still in flight is owned and its cancel stops
 * the core that launch started (#111 fix round 2). Pause and resume do require
 * the session and refuse without it; the port never persists anything itself.
 */
@Slf4j
public class CoordinatedRunRuntimeControl implements RunRuntimeControlPort {

    /** The bounded verification window, identical to the engine's control window. */
    static final long CONTROL_TIMEOUT_SECONDS = 30;

    private final ObjectProvider<CoreExecutionService> coordinator;

    public CoordinatedRunRuntimeControl(ObjectProvider<CoreExecutionService> coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public boolean owns(UUID agentId, UUID runId) {
        if (agentId == null || runId == null) {
            return false;
        }
        CoreExecutionService control = coordinator.getIfAvailable();
        return control != null && control.ownsRun(agentId, runId);
    }

    @Override
    public void pause(UUID runId) {
        CoreExecutionService control = requireCoordinator(runId, "pause");
        ControlAck ack = await(control.pause(runId), runId, "pause");
        if (ack.state() != ControlState.PAUSED || !ack.verified()) {
            throw new IllegalStateException("Run " + runId + " was not paused: " + refusalReason(control, runId,
                    "the run-owned core did not verify the pause"));
        }
    }

    @Override
    public void cancel(UUID runId) {
        CoreExecutionService control = requireCoordinator(runId, "cancel");
        ControlAck ack = await(control.cancel(runId), runId, "cancel");
        if (ack.state() != ControlState.STOPPED || !ack.verified()) {
            throw new IllegalStateException("Run " + runId + " was not stopped: " + refusalReason(control, runId,
                    "the run-owned core did not verify the stop"));
        }
    }

    @Override
    public void resume(UUID runId) {
        CoreExecutionService control = requireCoordinator(runId, "resume");
        ControlAck ack = await(control.resume(runId), runId, "resume");
        if (ack.state() != ControlState.RUNNING || !ack.verified()) {
            throw new IllegalStateException("Run " + runId + " was not resumed: " + refusalReason(control, runId,
                    "the run-owned core did not verify the resume"));
        }
    }

    private CoreExecutionService requireCoordinator(UUID runId, String action) {
        CoreExecutionService control = coordinator.getIfAvailable();
        if (control == null) {
            throw new IllegalStateException("Run " + runId + " has no deployed run coordinator to verify the "
                    + action);
        }
        return control;
    }

    private static String refusalReason(CoreExecutionService control, UUID runId, String fallback) {
        return control.pendingControl(runId)
                .map(RunRuntimeRegistry.ControlRequest::failureReason)
                .orElse(fallback);
    }

    private static ControlAck await(CompletionStage<ControlAck> stage, UUID runId, String action) {
        try {
            return stage.toCompletableFuture().get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Run " + runId + " " + action + " was interrupted", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Run " + runId + " " + action
                    + " was not acknowledged within " + CONTROL_TIMEOUT_SECONDS + "s; no state was claimed for it", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Run " + runId + " " + action + " failed: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e.getCause());
        }
    }
}
