package io.aria.conductor.common.port;

import java.util.UUID;

/**
 * Port for waking a run parked in WAITING_INPUT (2026-10-05 spec §5). Lives in
 * act-common so the run store (act-agent) can expose the finalize route without
 * depending on the execution module: the parked run's thread is woken with the
 * finalize signal and finalizes itself, exactly as the answer path wakes it
 * with the operator's answer.
 */
public interface RunInputPort {

    /**
     * Wakes the parked run with the finalize signal.
     *
     * @return false when the run is not parked in this process — the caller
     *         answers 409 instead of pretending something was scheduled
     */
    boolean requestFinalize(UUID runId);
}
