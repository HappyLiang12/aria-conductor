package io.aria.conductor.execution.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.approval.NativePermission;
import io.aria.conductor.execution.approval.PermissionChoice;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.approval.PermissionOption;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.approval.PermissionTarget;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.security.ActorTokenService;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The run coordinator: one run-owned execution attempt end to end --
 * resolve its immutable binding, prepare the workspace and environment, launch,
 * open one core session, prompt with the full accepted history, stream events
 * and permissions, and finalize with stop -&gt; capture -&gt; destroy -&gt; release.
 *
 * <p>Truthful control: a control state is persisted only after the matching
 * verified acknowledgement ({@link ControlAck#verified()}), a refused or
 * unverified request leaves the run in its previous state with a pending control
 * request that carries the exact failure reason, and a cancellation is persisted
 * only after the backend returned a matching {@code StopProof(runId, true)}. A
 * prompt-completion event is never a stop proof: only {@link RunFinalizer}
 * establishes the verified stop, and only it writes terminal artifact state.
 *
 * <p>Manual pause and permission waiting are separate reasons. A verified manual
 * pause holds permission delivery ({@link PermissionCoordinator#manualPause});
 * a decision received while paused is recorded but delivered only when a resume
 * re-validates it ({@link PermissionCoordinator#deliverPending}) and the reply is
 * handed to the owning session. Resume reuses the same run-owned handle and
 * session and never replays a write.
 *
 * <p>The coordinator is constructed by the runtime cutover wiring (Task 18); it
 * takes no Spring annotation, so tests and callers use one explicit constructor.
 */
@Slf4j
public class CoreExecutionService implements RuntimeActivity {

    /**
     * The run-deadline enforcement of every live run; daemon so it never blocks
     * shutdown. It is the default scheduler of the last constructor.
     */
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "run-deadline");
        thread.setDaemon(true);
        return thread;
    });

    /** Bounded retries of a refused stop before a launch abort is left unresolved. */
    private static final int MAX_STOP_ATTEMPTS = 2;

    /** Terminal runtime-state names recorded with the run, beyond the frozen {@link ControlState} set. */
    static final String STATE_COMPLETED = "COMPLETED";
    static final String STATE_FAILED = "FAILED";
    static final String STATE_CANCELLED = "CANCELLED";

    private final ExecutionBackendRegistry backends;
    private final WorkspaceService workspaces;
    private final RunFinalizer finalizer;
    private final RunRuntimeRegistry runtimes;
    private final PermissionCoordinator permissions;
    private final RuntimeCredentialService credentials;
    private final ActorTokenService actorTokens;
    private final RunExecutionBindingRepository bindings;
    private final Clock clock;
    private final Duration cleanupWindow;
    private final ScheduledExecutorService deadlines;
    private final ObjectMapper mapper = new ObjectMapper();

    public CoreExecutionService(ExecutionBackendRegistry backends, WorkspaceService workspaces,
            RunFinalizer finalizer, RunRuntimeRegistry runtimes,
            PermissionCoordinator permissions, RuntimeCredentialService credentials,
            ActorTokenService actorTokens, RunExecutionBindingRepository bindings) {
        this(backends, workspaces, finalizer, runtimes, permissions, credentials,
                actorTokens, bindings, Clock.systemUTC(), Duration.ofMinutes(5));
    }

    /**
     * @param clock         the clock the run's control timestamps and cleanup
     *                      windows are measured against
     * @param cleanupWindow the caller's own cleanup window for stop requests
     *                      (control requests are never bounded by the run
     *                      deadline, and the run deadline never clamps them)
     */
    public CoreExecutionService(ExecutionBackendRegistry backends, WorkspaceService workspaces,
            RunFinalizer finalizer, RunRuntimeRegistry runtimes,
            PermissionCoordinator permissions, RuntimeCredentialService credentials,
            ActorTokenService actorTokens, RunExecutionBindingRepository bindings,
            Clock clock, Duration cleanupWindow) {
        this(backends, workspaces, finalizer, runtimes, permissions, credentials, actorTokens, bindings,
                clock, cleanupWindow, DEADLINES);
    }

    /**
     * @param deadlines the scheduler each run's deadline task is armed on; the
     *                  shared daemon {@link #DEADLINES} executor is the
     *                  production default, and callers may inject their own
     */
    public CoreExecutionService(ExecutionBackendRegistry backends, WorkspaceService workspaces,
            RunFinalizer finalizer, RunRuntimeRegistry runtimes,
            PermissionCoordinator permissions, RuntimeCredentialService credentials,
            ActorTokenService actorTokens, RunExecutionBindingRepository bindings,
            Clock clock, Duration cleanupWindow, ScheduledExecutorService deadlines) {
        this.backends = Objects.requireNonNull(backends, "Backend registry is required");
        this.workspaces = Objects.requireNonNull(workspaces, "Workspace service is required");
        this.finalizer = Objects.requireNonNull(finalizer, "Run finalizer is required");
        this.runtimes = Objects.requireNonNull(runtimes, "Run runtime registry is required");
        this.permissions = Objects.requireNonNull(permissions, "Permission coordinator is required");
        this.credentials = Objects.requireNonNull(credentials, "Credential service is required");
        this.actorTokens = Objects.requireNonNull(actorTokens, "Actor token service is required");
        this.bindings = Objects.requireNonNull(bindings, "Binding repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.cleanupWindow = Objects.requireNonNull(cleanupWindow, "Cleanup window is required");
        this.deadlines = Objects.requireNonNull(deadlines, "Deadline scheduler is required");
    }

    // ------------------------------------------------------------------ execute

    /**
     * Executes one run-owned attempt of the frozen spec and finalizes it.
     *
     * @return the core's terminal result; {@code cancelled} is true exactly when
     *         a verified stop had already ended the run's writers
     * @throws IllegalStateException when the binding is missing/mismatched, the
     *         launch fails, the prompt fails or the stop cannot be verified
     */
    public CoreResult execute(ExecutionSpec spec, CoreAdapter adapter, CoreTask task) {
        Objects.requireNonNull(spec, "Execution spec is required");
        Objects.requireNonNull(adapter, "Core adapter is required");
        Objects.requireNonNull(task, "Core task is required");

        RunRuntimeRegistry.RunRuntime runtime = openRuntime(spec, adapter);
        ScheduledFuture<?> deadlineTask = scheduleDeadline(runtime);
        CoreResult result = null;
        String failure = null;
        try {
            result = runtime.session().prompt(task, event -> handleEvent(runtime, event))
                    .toCompletableFuture().join();
        } catch (CompletionException e) {
            failure = messageOf(e.getCause());
        } catch (RuntimeException e) {
            failure = messageOf(e);
        } finally {
            deadlineTask.cancel(false);
        }

        try {
            finalizeRuntime(runtime);
        } catch (RuntimeException finalizationFailure) {
            actorTokens.revokeRun(spec.runId()); // a terminal attempt carries no live worker authority
            recordPendingControl(runtime, ControlState.STOPPED, "Finalization for run " + spec.runId()
                    + " failed: " + messageOf(finalizationFailure));
            if (failure != null) {
                finalizationFailure.addSuppressed(new IllegalStateException(failure));
            }
            throw finalizationFailure;
        }

        String state = runtime.writersVerifiedStopped() && STATE_CANCELLED.equals(runtime.controlState())
                ? STATE_CANCELLED
                : failure != null ? STATE_FAILED : STATE_COMPLETED;
        runtime.recordVerifiedStop(state); // only a completed finalization verifies the stop
        persistTerminal(runtime, state, result);
        actorTokens.revokeRun(spec.runId());
        if (failure != null) {
            throw new IllegalStateException("Run " + spec.runId() + " failed: " + failure);
        }
        boolean cancelled = STATE_CANCELLED.equals(state);
        return new CoreResult(result.sessionId(), result.finalOutput(), result.usage(), cancelled);
    }

    /**
     * Resolves the frozen binding, acquires the workspace lease, prepares the
     * environment, launches the runtime, opens one core session and records the
     * observed runtime identity plus the chosen control strategy with the run.
     * A failure at any step before the session exists leaves nothing running: a
     * verified stop destroys and releases, an unverifiable one leaves the
     * environment and the lease held instead of destroying after a refused stop.
     */
    RunRuntimeRegistry.RunRuntime openRuntime(ExecutionSpec spec, CoreAdapter adapter) {
        RunExecutionBinding binding = bindings.findById(spec.runId())
                .orElseThrow(() -> new IllegalStateException("Run " + spec.runId()
                        + " has no frozen execution binding; refusing to launch without an immutable binding"));
        requireFrozenMatch(binding, spec, adapter);
        if (spec.deadline() == null) {
            throw new IllegalStateException("Run " + spec.runId() + " carries no frozen deadline;"
                    + " every run freezes a deadline before launch, so an unbounded attempt is refused");
        }
        ExecutionBackend backend = backends.require(spec.mode());
        CoreCapabilities capabilities = adapter.capabilities(spec.mode());
        WorkspaceLease lease = workspaces.acquire(spec);
        requireFrozenLeaseMatch(binding, spec, lease);
        RunRuntimeRegistry.RunRuntime runtime = runtimes.register(spec, backend, adapter, lease);
        runtime.attach(binding, capabilities);
        try {
            PreparedEnvironment environment = backend.prepare(spec, lease);
            // A run without a credential reference launches with an empty bundle
            // (the OpenCode core carries no platform credential); a reference
            // that is present but not configured still fails loudly in the
            // credential service, so a launch never silently drops its secret.
            SecretBundle secret = spec.credentialRef() == null || spec.credentialRef().isBlank()
                    ? new SecretBundle(null, java.util.Map.of())
                    : credentials.resolve(spec.credentialRef());
            LaunchProfile profile = adapter.launchProfile(spec, environment, secret);
            RuntimeHandle handle = backend.launch(environment, profile);
            CoreSession session = adapter.open(handle, spec, secret);
            runtime.launch(handle, session);
        } catch (RuntimeException e) {
            abortLaunch(runtime, lease);
            throw e;
        }
        binding.setRuntimeEnvironmentId(runtime.handle().environmentId());
        binding.setRuntimeOwnershipIdentity(runtime.handle().ownershipIdentity());
        binding.setRuntimeEndpoint(runtime.handle().endpoint() == null
                ? null : runtime.handle().endpoint().toString());
        persistState(runtime, ControlState.RUNNING.name());
        return runtime;
    }

    private void requireFrozenMatch(RunExecutionBinding binding, ExecutionSpec spec, CoreAdapter adapter) {
        if (!Objects.equals(binding.getAgentId(), spec.agentId())
                || !Objects.equals(binding.getCoreId(), spec.coreId())
                || binding.getExecutionMode() != spec.mode()) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen for "
                    + binding.getAgentId() + "/" + binding.getCoreId() + "/" + binding.getExecutionMode()
                    + " but the attempt requests " + spec.agentId() + "/" + spec.coreId() + "/" + spec.mode()
                    + "; the immutable binding is never re-resolved from mutable settings");
        }
        if (binding.getCoreId() != null && !binding.getCoreId().equals(adapter.coreId())) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen for core "
                    + binding.getCoreId() + " but the attempt carries adapter " + adapter.coreId());
        }
        if (binding.getCredentialRef() != null && !binding.getCredentialRef().equals(spec.credentialRef())) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen with credential reference "
                    + binding.getCredentialRef() + " but the attempt requests " + spec.credentialRef());
        }
        if (binding.getConfigurationRevision() != null
                && !binding.getConfigurationRevision().equals(spec.configurationRevision())) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen at configuration revision "
                    + binding.getConfigurationRevision() + " but the attempt requests "
                    + spec.configurationRevision());
        }
        if (binding.getDeadline() != null && !binding.getDeadline().equals(spec.deadline())) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen with deadline "
                    + binding.getDeadline() + " but the attempt carries " + spec.deadline());
        }
        requireFrozenWorkspace(binding, spec);
        requireFrozenSettings(binding, spec);
    }

    /**
     * The binding's workspace references must be the ones the attempt's frozen
     * selection resolves to: the recorded kind is the kind the frozen
     * mode/settings produce (a Sandbox run consumes a snapshot; otherwise Direct,
     * Worktree or the scratch fallback), and the recorded workspace root is the
     * requested workspace path. The workspace lease id has no spec counterpart by
     * construction (the attempt's lease does not exist until it is acquired), so
     * it is compared once the attempt acquired its own lease
     * ({@link #requireFrozenLeaseMatch}). A recorded reference that does not agree
     * is refused in the same shape as the other mismatches instead of silently
     * re-resolving the workspace from mutable settings.
     */
    private void requireFrozenWorkspace(RunExecutionBinding binding, ExecutionSpec spec) {
        if (binding.getWorkspaceKind() != null && binding.getWorkspaceKind() != expectedWorkspaceKind(spec)) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen with workspace kind "
                    + binding.getWorkspaceKind() + " but the attempt requests " + expectedWorkspaceKind(spec)
                    + "; the immutable binding is never re-resolved from mutable settings");
        }
        String requestedRoot = spec.settings() == null ? null : spec.settings().workspacePath();
        if (binding.getWorkspaceRoot() != null && !binding.getWorkspaceRoot().equals(requestedRoot)) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen with workspace root "
                    + binding.getWorkspaceRoot() + " but the attempt requests " + requestedRoot
                    + "; the immutable binding is never re-resolved from mutable settings");
        }
    }

    /** The workspace kind the workspace admission resolves from the frozen mode and settings. */
    private static WorkspaceKind expectedWorkspaceKind(ExecutionSpec spec) {
        if (spec.mode() == ExecutionMode.SANDBOX) {
            return WorkspaceKind.SANDBOX_SNAPSHOT;
        }
        WorkspaceMode workspaceMode = spec.settings() == null ? null : spec.settings().workspaceMode();
        if (workspaceMode == null) {
            return WorkspaceKind.SCRATCH; // the admission's fallback for a silent selection
        }
        return switch (workspaceMode) {
            case DIRECT -> WorkspaceKind.DIRECT;
            case WORKTREE -> WorkspaceKind.WORKTREE;
        };
    }

    /**
     * The recorded settings snapshot must be exactly the frozen
     * {@link AgentExecutionSettings} the attempt carries: the binding row's own
     * record is what the run executes against, never the agent's mutable
     * settings re-read at launch. A snapshot that is present but unreadable is a
     * corrupt frozen row and is refused, not skipped.
     */
    private void requireFrozenSettings(RunExecutionBinding binding, ExecutionSpec spec) {
        String frozen = binding.getSettingsJson();
        if (frozen == null || frozen.isBlank()) {
            return; // no snapshot recorded: nothing to compare against
        }
        AgentExecutionSettings snapshot;
        try {
            snapshot = mapper.readValue(frozen, AgentExecutionSettings.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Run " + spec.runId() + " carries an unreadable frozen settings"
                    + " snapshot (the row must record the serialized AgentExecutionSettings it was frozen with);"
                    + " the immutable binding is never re-resolved from mutable settings", e);
        }
        if (!snapshot.equals(spec.settings())) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen with settings " + snapshot
                    + " but the attempt requests " + spec.settings()
                    + "; the immutable binding is never re-resolved from mutable settings");
        }
    }

    /**
     * A run admitted with a recorded workspace lease must execute in that lease:
     * the attempt's own acquired lease is compared against the frozen lease id
     * here (the id exists only after the acquisition), before anything else is
     * prepared or launched. On a mismatch the attempt's own lease stays held --
     * the same fail-closed state an unresolved/refused run leaves (no release
     * without a verified stop proof) -- rather than releasing a lease no stop
     * proof ever covered.
     */
    private void requireFrozenLeaseMatch(RunExecutionBinding binding, ExecutionSpec spec, WorkspaceLease lease) {
        if (binding.getWorkspaceLeaseId() != null && !binding.getWorkspaceLeaseId().equals(lease.leaseId())) {
            throw new IllegalStateException("Run " + spec.runId() + " was frozen with workspace lease "
                    + binding.getWorkspaceLeaseId() + " but the attempt acquired " + lease.leaseId()
                    + "; the immutable binding is never re-resolved from mutable settings");
        }
    }

    /**
     * A launch failed after the runtime may already exist. The writers are
     * stopped and verified first; only then are the environment destroyed and
     * the lease released. A refused stop is retried inside the cleanup window
     * and never followed by a destroy.
     *
     * <p>The window is hoisted once at the entry of this method -- exactly as
     * {@link RunFinalizer}'s own retry bound is -- and every stop attempt is
     * bounded by that one deadline, so no retry is granted a fresh window.
     * Package-private: the abort path with a live handle has no public caller
     * today (a session-open failure aborts before the handle is attached), and
     * this is the seam its retry bound is pinned through, mirroring
     * {@link #openRuntime} for the pre-prompt half.
     */
    void abortLaunch(RunRuntimeRegistry.RunRuntime runtime, WorkspaceLease lease) {
        RuntimeHandle handle = runtime.handle();
        if (handle == null) {
            runtimes.remove(runtime.spec().runId());
            return;
        }
        Instant cleanupDeadline = cleanupDeadline();
        StopProof proof = null;
        for (int attempt = 0; attempt < MAX_STOP_ATTEMPTS; attempt++) {
            if (attempt > 0 && !clock.instant().isBefore(cleanupDeadline)) {
                break;
            }
            try {
                proof = runtime.backend().stopWriters(handle, cleanupDeadline);
            } catch (RuntimeException e) {
                log.error("Run {}: the stop after a failed launch threw: {}",
                        runtime.spec().runId(), e.getMessage());
                proof = null;
            }
            if (proof != null && proof.runId().equals(runtime.spec().runId()) && proof.allWritersStopped()) {
                break;
            }
        }
        if (proof == null || !proof.runId().equals(runtime.spec().runId()) || !proof.allWritersStopped()) {
            recordPendingControl(runtime, ControlState.STOPPED, "Launch abort for run "
                    + runtime.spec().runId() + ": the writers were not verified stopped, so the environment"
                    + " and the workspace lease are left in place (destroy after a refused stop is terminal)");
            return;
        }
        try {
            runtime.backend().destroy(handle);
        } catch (RuntimeException e) {
            log.error("Run {}: destroying the aborted launch failed: {}", runtime.spec().runId(), e.getMessage());
        }
        try {
            workspaces.release(lease, proof);
        } catch (RuntimeException e) {
            log.error("Run {}: releasing the aborted launch's lease failed: {}", runtime.spec().runId(), e.getMessage());
        }
        runtime.recordVerifiedStop(STATE_CANCELLED);
        runtimes.remove(runtime.spec().runId());
    }

    /** Stops, captures, destroys and releases through the one finalization owner. */
    private void finalizeRuntime(RunRuntimeRegistry.RunRuntime runtime) {
        ArtifactBundle bundle = finalizer.finish(runtime.backend(), runtime.handle(), runtime.lease(),
                cleanupDeadline());
        // The one terminal path: only a completed finalization records the run as
        // finalized (stop -> capture -> destroy -> release all returned).
        runtime.recordFinalized();
        log.info("Run {} finalized: artifacts at {} (complete={})", runtime.spec().runId(),
                bundle.directory(), bundle.complete());
    }

    private Instant cleanupDeadline() {
        return clock.instant().plus(cleanupWindow);
    }

    // ------------------------------------------------------------------ control

    /**
     * Verifies a manual pause. The pause is persisted and permission delivery is
     * held only for a verified PAUSED acknowledgement; anything else leaves the
     * run RUNNING with a pending control request and the exact failure reason.
     */
    public CompletionStage<ControlAck> pause(UUID runId) {
        RunRuntimeRegistry.RunRuntime runtime = requireRuntime(runId, "pause");
        return runtime.session().pause(cleanupDeadline()).thenApply(ack -> {
            if (verified(ack, ControlState.PAUSED)) {
                permissions.manualPause(runId);
                persistState(runtime, ControlState.PAUSED.name());
                runtime.recordVerifiedControl(ControlState.PAUSED.name());
            } else {
                recordPendingControl(runtime, ControlState.PAUSED, refusal("Pause", runId, ack));
            }
            return ack;
        });
    }

    /**
     * Verifies a resume on the same run-owned handle/session, releases the
     * manual hold and delivers only decisions a fresh validity check still
     * accepts; nothing is replayed to the core otherwise.
     */
    public CompletionStage<ControlAck> resume(UUID runId) {
        RunRuntimeRegistry.RunRuntime runtime = requireRuntime(runId, "resume");
        return runtime.session().resume(cleanupDeadline()).thenApply(ack -> {
            if (verified(ack, ControlState.RUNNING)) {
                permissions.manualResume(runId);
                deliverHeldDecisions(runtime);
                persistState(runtime, ControlState.RUNNING.name());
                runtime.recordVerifiedControl(ControlState.RUNNING.name());
            } else {
                recordPendingControl(runtime, ControlState.RUNNING, refusal("Resume", runId, ack));
            }
            return ack;
        });
    }

    /**
     * Cancels the run's prompt and writers. The cancellation is persisted and
     * worker authority is revoked only after the backend returned a matching
     * verified {@code StopProof}; a refused stop is recorded as the pending
     * control request and nothing else changes.
     */
    public CompletionStage<ControlAck> cancel(UUID runId) {
        RunRuntimeRegistry.RunRuntime runtime = requireRuntime(runId, "cancel");
        try {
            runtime.session().cancel(cleanupDeadline()).toCompletableFuture().join();
        } catch (RuntimeException e) {
            log.warn("Run {}: the cooperative session cancel was refused ({}); the stop below is authoritative",
                    runId, messageOf(e));
        }
        StopProof proof;
        try {
            proof = runtime.backend().stopWriters(runtime.handle(), cleanupDeadline());
        } catch (RuntimeException e) {
            recordPendingControl(runtime, ControlState.STOPPED, "Stop refused for run " + runId
                    + ": the backend refused the stop (" + messageOf(e) + ")");
            return CompletableFuture.completedFuture(refusedCancelAck(runtime));
        }
        if (proof == null || !proof.runId().equals(runId) || !proof.allWritersStopped()) {
            recordPendingControl(runtime, ControlState.STOPPED, stopRefusal(runId, proof));
            return CompletableFuture.completedFuture(refusedCancelAck(runtime));
        }
        runtime.recordVerifiedStop(STATE_CANCELLED);
        persistState(runtime, STATE_CANCELLED);
        actorTokens.revokeRun(runId);
        return CompletableFuture.completedFuture(new ControlAck(ControlState.STOPPED, true));
    }

    private RunRuntimeRegistry.RunRuntime requireRuntime(UUID runId, String action) {
        Objects.requireNonNull(runId, "Run id is required");
        RunRuntimeRegistry.RunRuntime runtime = runtimes.find(runId).orElse(null);
        if (runtime == null || runtime.session() == null || runtime.handle() == null) {
            throw new IllegalStateException("Run " + runId
                    + " has no run-owned runtime in this process; there is nothing to " + action);
        }
        return runtime;
    }

    private static boolean verified(ControlAck ack, ControlState expected) {
        return ack != null && ack.verified() && ack.state() == expected;
    }

    /**
     * The acknowledgment of a refused cancel reports the run's frozen verified
     * state, never a hardcoded RUNNING: a paused run stays PAUSED, a run whose
     * writers were already verified stopped reports STOPPED, and everything else
     * is still RUNNING. {@code verified=false} is the whole point -- the cancel
     * request itself was refused and nothing was persisted.
     */
    private static ControlAck refusedCancelAck(RunRuntimeRegistry.RunRuntime runtime) {
        if (runtime.writersVerifiedStopped()) {
            return new ControlAck(ControlState.STOPPED, false);
        }
        ControlState frozen = ControlState.PAUSED.name().equals(runtime.controlState())
                ? ControlState.PAUSED : ControlState.RUNNING;
        return new ControlAck(frozen, false);
    }

    private static String refusal(String action, UUID runId, ControlAck ack) {
        return action + " refused for run " + runId + ": the core reported "
                + (ack == null ? "no acknowledgement" : ack.state())
                + " (verified=" + (ack != null && ack.verified()) + ")";
    }

    private static String stopRefusal(UUID runId, StopProof proof) {
        if (proof == null) {
            return "Stop refused for run " + runId + ": the backend returned no stop proof";
        }
        if (!proof.runId().equals(runId)) {
            return "Stop refused for run " + runId + ": the backend returned a stop proof for run "
                    + proof.runId() + ", not for this run";
        }
        return "Stop refused for run " + runId + ": the backend reported allWritersStopped=false";
    }

    private void recordPendingControl(RunRuntimeRegistry.RunRuntime runtime, ControlState requested,
            String reason) {
        runtime.recordPendingControl(new RunRuntimeRegistry.ControlRequest(requested, clock.instant(), reason));
        log.warn("Run {}: {}", runtime.spec().runId(), reason);
    }

    /** Registers and records a native permission ask; an ask without a decidable option is never registered. */
    private void registerAsk(RunRuntimeRegistry.RunRuntime runtime, CoreEvent event) {
        try {
            JsonNode payload = mapper.readTree(event.payloadJson() == null ? "{}" : event.payloadJson());
            String requestId = payload.path("requestId").asText("");
            if (requestId.isBlank()) {
                log.warn("Run {}: a permission.request without a request id cannot be correlated; ignored",
                        runtime.spec().runId());
                return;
            }
            List<PermissionOption> options = new ArrayList<>();
            for (JsonNode option : payload.path("options")) {
                String optionId = option.path("optionId").asText("");
                PermissionChoice choice = switch (option.path("kind").asText("")) {
                    case "allow_once" -> PermissionChoice.ALLOW_ONCE;
                    case "reject_once" -> PermissionChoice.DENY;
                    default -> null;
                };
                if (choice != null && !optionId.isBlank()) {
                    options.add(new PermissionOption(optionId, choice));
                }
            }
            if (options.isEmpty()) {
                log.warn("Run {}: native permission request {} offers no decidable option; it is not"
                        + " registered, because no choice may be invented for it",
                        runtime.spec().runId(), requestId);
                return;
            }
            NativePermission ask = new NativePermission(runtime.spec().runId(), event.sessionId(), requestId,
                    payload.path("toolName").asText("unknown"), PermissionTarget.NATIVE_TOOL,
                    event.payloadJson(), List.copyOf(options), runtime.spec().deadline());
            UUID approvalId = permissions.register(ask);
            runtime.rememberPermissionAsk(approvalId);
        } catch (RuntimeException e) {
            log.error("Run {}: registering the native permission ask failed: {}",
                    runtime.spec().runId(), e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Run {}: an unparseable permission.request payload cannot be correlated: {}",
                    runtime.spec().runId(), e.getMessage());
        }
    }

    private void deliverHeldDecisions(RunRuntimeRegistry.RunRuntime runtime) {
        for (UUID approvalId : runtime.permissionAsks()) {
            try {
                Optional<PermissionReply> reply = permissions.deliverPending(approvalId);
                if (reply.isPresent()) {
                    runtime.session().decide(reply.get());
                    runtime.forgetPermissionAsk(approvalId);
                }
            } catch (RuntimeException e) {
                log.warn("Run {}: delivering the held decision {} failed: {}",
                        runtime.spec().runId(), approvalId, e.getMessage());
            }
        }
    }

    private void handleEvent(RunRuntimeRegistry.RunRuntime runtime, CoreEvent event) {
        runtimes.forward(runtime.spec().runId(), event);
        if ("permission.request".equals(event.type())) {
            registerAsk(runtime, event);
        }
    }

    /** Subscribes an observer to every core event of the run. */
    public void observe(UUID runId, Consumer<CoreEvent> observer) {
        runtimes.observe(runId, observer);
    }

    /** The issued-but-unverified control request of a run, if any. */
    public Optional<RunRuntimeRegistry.ControlRequest> pendingControl(UUID runId) {
        return runtimes.find(runId).flatMap(RunRuntimeRegistry.RunRuntime::pendingControl);
    }

    // ------------------------------------------------------------------ runtime activity

    @Override
    public boolean writersStopped(UUID runId) {
        return runtimes.writersStopped(runId);
    }

    @Override
    public Set<UUID> activeRuns(UUID agentId) {
        return runtimes.activeRuns(agentId);
    }

    // ------------------------------------------------------------------ deadline

    private ScheduledFuture<?> scheduleDeadline(RunRuntimeRegistry.RunRuntime runtime) {
        UUID runId = runtime.spec().runId();
        long delayMillis = Math.max(0,
                Duration.between(clock.instant(), runtime.spec().deadline()).toMillis());
        return deadlines.schedule(() -> {
            try {
                if (runtimes.find(runId).isPresent() && !runtimes.writersStopped(runId)) {
                    log.warn("Run {} reached its frozen deadline {}; cancelling the in-flight prompt instead"
                            + " of leaving it in flight", runId, runtime.spec().deadline());
                    cancel(runId);
                }
            } catch (RuntimeException e) {
                log.error("Run {}: enforcing the run deadline failed: {}", runId, e.getMessage());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ persistence

    private void persistState(RunRuntimeRegistry.RunRuntime runtime, String state) {
        persist(runtime, state, null);
    }

    private void persistTerminal(RunRuntimeRegistry.RunRuntime runtime, String state, CoreResult result) {
        persist(runtime, state, result);
    }

    /**
     * Persists the observed runtime state together with the chosen control
     * strategy (a reported value passes through exactly; unknown usage stays
     * NULL). The immutable binding row is the run's own record, never the
     * agent's mutable settings.
     */
    private void persist(RunRuntimeRegistry.RunRuntime runtime, String state, CoreResult result) {
        RunExecutionBinding binding = runtime.binding();
        if (binding == null) {
            return;
        }
        if (result != null && result.usage() != null) {
            UsageSnapshot usage = result.usage();
            binding.setUsageInputTokens(usage.inputTokens());
            binding.setUsageOutputTokens(usage.outputTokens());
            binding.setUsageCredits(usage.credits());
            binding.setObservedModel(usage.observedModel());
        }
        String strategy = runtime.capabilities() == null
                ? ControlStrategy.UNVERIFIED.name() : runtime.capabilities().pauseStrategy().name();
        binding.setRuntimeState(state + "/" + strategy);
        bindings.save(binding);
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
    }
}
