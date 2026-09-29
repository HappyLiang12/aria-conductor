package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.execution.runtime.host.HostExecutionBackend;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Adopt-or-reap of run-owned runtimes after a process restart. A restart
 * reconstructs records, and a reconstructed ownership record authorizes
 * nothing: every decision here is driven by <em>run-store evidence</em> (the
 * persisted {@link RunExecutionBinding} row) cross-checked against this
 * process's own registry.
 *
 * <ul>
 *   <li><b>ADOPTED</b> -- this process already holds a live run-owned runtime
 *       whose handle identity equals the identity the run store recorded; the
 *       runtime is kept, never relaunched and never killed.</li>
 *   <li><b>REAPED</b> -- the run store verifies a run-owned runtime
 *       (environment id, ownership identity and mode all recorded) that this
 *       process does not hold. Destroying it is dispatched to the backend of its
 *       mode, and the decision's reason states what that dispatch actually ended:
 *       a Host backend that holds no run-owned placement for the run (a record
 *       reconstructed after a restart) destroys and signals nothing -- its
 *       destroy never signals a runtime it did not bind -- and the reason says
 *       exactly that instead of claiming a destruction. Its workspace lease is
 *       <em>not</em> released either: a leak-free release requires a verified
 *       {@link StopProof}, and a reconstructed record is not one. A Direct lease
 *       therefore stays held until an explicit, verified finalization.</li>
 *   <li><b>HELD</b> -- no run-store evidence, no verifiable ownership identity
 *       or a mode this process has no registered backend for. Nothing is
 *       adopted, reaped or released.</li>
 * </ul>
 *
 * <p>The coordinator never signals a process name or a bare PID and never mints
 * a stop proof; the only action it can take is {@link ExecutionBackend#destroy}
 * on a run whose ownership the run store verifies.
 */
@Slf4j
public final class RuntimeRecoveryCoordinator {

    public enum Decision { ADOPTED, REAPED, HELD }

    /** One recovery decision with the evidence-based reason it was taken. */
    public record RecoveryDecision(Decision decision, String reason) {
        public RecoveryDecision {
            Objects.requireNonNull(decision, "Decision is required");
            Objects.requireNonNull(reason, "Reason is required");
        }
    }

    private final ExecutionBackendRegistry backends;
    private final RunRuntimeRegistry runtimes;

    public RuntimeRecoveryCoordinator(ExecutionBackendRegistry backends, RunRuntimeRegistry runtimes) {
        this.backends = Objects.requireNonNull(backends, "Backend registry is required");
        this.runtimes = Objects.requireNonNull(runtimes, "Run runtime registry is required");
    }

    /**
     * Reconciles one reconstructed run-owned runtime against the run store.
     *
     * @param evidence the persisted binding row, or {@code null} when the
     *                 record has no run-store backing (then nothing is done)
     */
    public RecoveryDecision reconcile(RunExecutionBinding evidence) {
        if (evidence == null || evidence.getRunId() == null) {
            return new RecoveryDecision(Decision.HELD,
                    "No run-store evidence for a reconstructed record; nothing is adopted or reaped");
        }
        UUID runId = evidence.getRunId();
        if (isBlank(evidence.getRuntimeEnvironmentId()) || isBlank(evidence.getRuntimeOwnershipIdentity())) {
            return new RecoveryDecision(Decision.HELD,
                    "Run store records no verifiable ownership identity for run " + runId
                            + "; nothing is adopted or reaped and the workspace lease stays held");
        }
        ExecutionMode mode = evidence.getExecutionMode();
        if (mode == null) {
            return new RecoveryDecision(Decision.HELD,
                    "Run store records no execution mode for run " + runId
                            + "; nothing is adopted or reaped and the workspace lease stays held");
        }
        if (!backends.modes().contains(mode)) {
            // Fail closed exactly like the null case: an unregistered mode must hold the
            // record, never abort the sweep on a backend lookup (nor invent a placement).
            return new RecoveryDecision(Decision.HELD,
                    "Run store records execution mode " + mode + " for run " + runId
                            + ", but this process has no registered backend for that mode;"
                            + " nothing is adopted or reaped and the workspace lease stays held");
        }

        RunRuntimeRegistry.RunRuntime live = runtimes.find(runId).orElse(null);
        if (live != null && !live.writersVerifiedStopped() && live.handle() != null
                && evidence.getRuntimeOwnershipIdentity().equals(live.handle().ownershipIdentity())) {
            log.info("Adopting the live run-owned runtime of run {} (mode {})", runId, mode);
            return new RecoveryDecision(Decision.ADOPTED, "Adopted the live run-owned runtime of run " + runId
                    + " (ownership identity " + evidence.getRuntimeOwnershipIdentity()
                    + " verified against the run store)");
        }

        RuntimeHandle reconstructed = new RuntimeHandle(runId, mode, evidence.getRuntimeEnvironmentId(),
                evidence.getRuntimeOwnershipIdentity(), endpoint(evidence.getRuntimeEndpoint()));
        ExecutionBackend backend = backends.require(mode);
        String outcome;
        if (backend instanceof HostExecutionBackend host) {
            // A Host destroy never signals a runtime it did not bind (T9's fail-closed
            // property) and removes only what this process prepared, so its own report is
            // the only truthful source for what the dispatch actually ended.
            outcome = host.destroyAndReport(reconstructed)
                    ? "Destroyed the run-owned runtime of run " + runId
                    : "Issued the HOST destroy for run " + runId + "; this process held no run-owned runtime"
                            + " for it, so nothing was destroyed or signalled (a restart-reconstructed record"
                            + " is never signalled)";
        } else {
            // Other placements act on their recorded environment through their own control
            // plane; the dispatch is what this coordinator performs and records.
            backend.destroy(reconstructed);
            outcome = "Destroyed the run-owned runtime of run " + runId;
        }
        String lease = evidence.getWorkspaceKind() == WorkspaceKind.DIRECT
                ? "; its Direct workspace lease stays held (recovery holds no verified stop proof)"
                : "; the workspace lease stays held (recovery holds no verified stop proof)";
        log.info("Recovery reap for run {} (mode {}): {}{}", runId, mode, outcome, lease);
        return new RecoveryDecision(Decision.REAPED, outcome + lease);
    }

    private static URI endpoint(String recorded) {
        return isBlank(recorded) ? null : URI.create(recorded);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
