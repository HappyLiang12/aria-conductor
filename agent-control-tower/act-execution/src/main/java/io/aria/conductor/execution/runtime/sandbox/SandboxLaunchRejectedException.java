package io.aria.conductor.execution.runtime.sandbox;

import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.StopProof;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The image launcher refused this run's launch, and this exception carries what it said
 * instead of replacing its verdict with the readiness window's timeout. Its message is
 * the launcher's own event name and message ({@link LaunchObservation#launcherVerdict()}),
 * so the operator can act on the reason the image gave.
 *
 * <p>It also carries the one decision the launch path can prove from that verdict: when
 * the launcher refused <em>before spawning</em> ({@link LaunchObservation#neverSpawnedProven()}),
 * no writer of this run can exist, and the {@link #neverSpawnedStopProof()} says so in the
 * form the cleanup path consumes. That proof is absent for every other outcome — a
 * post-spawn fault, an unreadable stream, a launcher that said nothing — which is exactly
 * the state in which destroying unverified stays forbidden.
 *
 * <p>The environment itself is destroyed by the launch path that raised this
 * ({@link SandboxExecutionBackend#launch}), as it already is for any pre-core failure;
 * the proof exists for the workspace lease, which only the coordinator's launch-abort
 * decision can release.
 */
public final class SandboxLaunchRejectedException extends TaskExecutionException {

    private final UUID runId;
    private final LaunchObservation observation;
    private final StopProof neverSpawnedStopProof;

    SandboxLaunchRejectedException(UUID runId, LaunchObservation observation, String message) {
        super(Cause.SANDBOX_UNAVAILABLE, message);
        this.runId = Objects.requireNonNull(runId, "runId");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.neverSpawnedStopProof = observation.neverSpawnedProven()
                ? new StopProof(runId, true) : null;
    }

    /** The run whose launch the image refused. */
    public UUID runId() {
        return runId;
    }

    /** The launcher's recorded verdict behind this refusal. */
    public LaunchObservation observation() {
        return observation;
    }

    /** True only when the launcher proved it refused before spawning any child. */
    public boolean launchNeverSpawnedProven() {
        return neverSpawnedStopProof != null;
    }

    /**
     * The stop proof of a launch that provably never spawned: all writers are stopped
     * because none of them can have started. Empty whenever the evidence is anything less.
     */
    public Optional<StopProof> neverSpawnedStopProof() {
        return Optional.ofNullable(neverSpawnedStopProof);
    }
}
