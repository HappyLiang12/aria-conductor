package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.exception.ActionableIllegalArgumentException;
import io.aria.conductor.common.exception.ErrorSurface;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.port.RunBindingReaderPort;
import io.aria.conductor.common.port.RunRuntimeControlPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RunService {

    private static final Map<RunStatus, Set<RunStatus>> VALID_TRANSITIONS;

    /**
     * Every status whose recorded transition allows CANCELLED, derived from
     * {@link #VALID_TRANSITIONS} so the atomic cancel's WHERE clause can never
     * drift from the state machine. ABORTED is included on purpose: a task-level
     * abort is still cancelable.
     */
    private static final List<RunStatus> CANCELABLE_STATUSES;

    /**
     * The states a run never leaves (the engine's own terminal set). Only used to choose the
     * honest refusal message: a transition refused from one of these is not an illegal move
     * in the state machine, it is an operator asking to stop/pause/resume a run that already
     * ended, so the message names the standing state and the completion time instead of an
     * arrow ({@code COMPLETED -> CANCELLED}).
     */
    private static final Set<RunStatus> TERMINAL_STATUSES =
            EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    static {
        Map<RunStatus, Set<RunStatus>> transitions = new EnumMap<>(RunStatus.class);
        transitions.put(RunStatus.PENDING, EnumSet.of(RunStatus.INITIALIZING, RunStatus.CANCELLED));
        transitions.put(RunStatus.INITIALIZING, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.CANCELLED));
        transitions.put(RunStatus.RUNNING, EnumSet.of(RunStatus.PAUSED, RunStatus.WAITING_INPUT, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED));
        transitions.put(RunStatus.PAUSED, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED));
        // A run parked on a clarification question is alive, not terminal: the
        // operator's answer resumes it, and it may still finalize from the parked state.
        transitions.put(RunStatus.WAITING_INPUT, EnumSet.of(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED));
        transitions.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));
        transitions.put(RunStatus.FAILED, EnumSet.noneOf(RunStatus.class));
        transitions.put(RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class));
        transitions.put(RunStatus.ABORTED, EnumSet.of(RunStatus.CANCELLED));
        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
        CANCELABLE_STATUSES = VALID_TRANSITIONS.entrySet().stream()
                .filter(entry -> entry.getValue().contains(RunStatus.CANCELLED))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The statuses an atomic cancel may overwrite; see {@link #CANCELABLE_STATUSES}.
     * Public because the deferred-stop worker (act-execution) guards its conditional
     * pending-stop write and its retry sweep with the same single authority: one
     * derived list, never a second copy that can drift.
     */
    public static List<RunStatus> cancelableStatuses() {
        return CANCELABLE_STATUSES;
    }

    /** The recorded state machine's answer for one transition (the single authority the writes guard). */
    static boolean allowsTransition(RunStatus from, RunStatus to) {
        return VALID_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(RunStatus.class)).contains(to);
    }

    /**
     * Every status whose recorded transition allows {@code to}, derived from
     * {@link #VALID_TRANSITIONS} so the conditional writes' WHERE clause can never drift from
     * the state machine (the same derivation {@link #CANCELABLE_STATUSES} uses for CANCELLED).
     */
    static List<RunStatus> transitionSources(RunStatus to) {
        return VALID_TRANSITIONS.entrySet().stream()
                .filter(entry -> entry.getValue().contains(to))
                .map(Map.Entry::getKey)
                .toList();
    }

    private final RunRepository runRepository;
    private final AgentService agentService;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * The short transactional writer that owns the atomic CANCELLED transition
     * and its completion event. Kept separate so the verified core stop runs
     * outside any database transaction (fix round 1, I2).
     */
    private final RunCancellationWriter cancellationWriter;
    /**
     * The run's runtime-control gate (Task 19, fix round 7), optional and
     * resolved lazily: when the runtime of a run is owned by the execution
     * coordinator, the pause/resume/cancel is verified through this port before
     * the new state is persisted, so a PAUSED acknowledgement -- or a
     * CANCELLED record -- can never be answered for a runtime that did not
     * confirm it. A run no deployment owns keeps the plain recorded transition.
     */
    private final ObjectProvider<RunRuntimeControlPort> runtimeControlProvider;
    /**
     * The run's frozen-binding reader (Fleet Runtime Governance), optional and
     * resolved as a nullable dependency: the binding repository lives in the
     * execution module, so the run store reads it through the act-common port
     * served there. A deployment that does not serve the port (or a legacy run
     * without a binding row) maps to null coreId/executionMode -- never an
     * invented runtime snapshot.
     */
    private final RunBindingReaderPort bindingReader;

    /** Direct-instantiation (test) constructor: no runtime-control port, no binding reader. */
    public RunService(RunRepository runRepository,
                      AgentService agentService,
                      ApplicationEventPublisher eventPublisher) {
        this(runRepository, agentService, eventPublisher, null, null,
                new RunCancellationWriter(runRepository, eventPublisher));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunService(RunRepository runRepository,
                      AgentService agentService,
                      ApplicationEventPublisher eventPublisher,
                      ObjectProvider<RunRuntimeControlPort> runtimeControlProvider,
                      @org.springframework.lang.Nullable RunBindingReaderPort bindingReader,
                      RunCancellationWriter cancellationWriter) {
        this.runRepository = runRepository;
        this.agentService = agentService;
        this.eventPublisher = eventPublisher;
        this.runtimeControlProvider = runtimeControlProvider;
        this.bindingReader = bindingReader;
        this.cancellationWriter = cancellationWriter;
    }

    @Transactional
    public RunResponse createRun(CreateRunRequest request) {
        Agent agent = agentService.findAgentOrThrow(request.getAgentId());

        if (agent.getHealthStatus() == HealthStatus.RETIRED) {
            throw new ActionableIllegalArgumentException(
                    "Cannot create run for a retired agent ("
                    + agent.getId() + "). Retired agents cannot take new runs;"
                    + " create a new agent in Crew and dispatch to it.",
                    "AGENT_RETIRED", ErrorSurface.CREW);
        }
        if (agent.getHealthStatus() == HealthStatus.UNHEALTHY) {
            throw new ActionableIllegalArgumentException(
                    "Cannot create run for an unhealthy agent ("
                    + agent.getId() + "). Check the agent's configuration in Crew;"
                    + " unhealthy agents are excluded from dispatch until their health is repaired.",
                    "AGENT_UNHEALTHY", ErrorSurface.CREW);
        }

        log.info("Creating run for agent: agentId={}, prompt={}", request.getAgentId(),
                request.getPromptSeed().substring(0, Math.min(50, request.getPromptSeed().length())));

        Run run = Run.builder()
                .agentId(request.getAgentId())
                .promptSeed(request.getPromptSeed())
                .maxIterations(request.getMaxIterations() > 0 ? request.getMaxIterations() : 0)
                .status(RunStatus.PENDING)
                .dispatchedByRunId(request.getDispatchedByRunId())
                .build();

        Run saved = runRepository.save(run);
        log.info("Run created: id={}", saved.getId());

        eventPublisher.publishEvent(new RunStartedEvent(this, saved.getId(), saved.getAgentId(),
                request.isSuppressAutoCard()));

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRuns() {
        return toResponses(runRepository.findAll());
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRunsByAgent(UUID agentId) {
        return toResponses(runRepository.findByAgentId(agentId));
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRunsByStatus(RunStatus status) {
        return toResponses(runRepository.findByStatus(status));
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRunsByAgentAndStatus(UUID agentId, RunStatus status) {
        return toResponses(runRepository.findByAgentIdAndStatus(agentId, status));
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(UUID id) {
        Run run = findRunOrThrow(id);
        return toResponse(run);
    }

    /**
     * Pauses a run. When the run's runtime is owned by the execution coordinator,
     * the pause is verified on the run-owned core session first (the runtime
     * suspends its writer tree) and the PAUSED state is written only after that
     * verified acknowledgement; a refused pause throws with the exact reason and
     * leaves the run RUNNING. A run no runtime owns keeps the recorded transition.
     *
     * <p>The PAUSED record is a single conditional write guarded by the committed row
     * (#111 fix round 2, M4): a run that finalized while the verified pause was in
     * flight is refused rather than resurrected by a stale entity save.
     */
    @Transactional
    public RunResponse pauseRun(UUID id) {
        Run run = findRunOrThrow(id);
        validateTransition(run, RunStatus.PAUSED, "paused");
        log.info("Pausing run: id={}", id);
        RunRuntimeControlPort control = runtimeControlProvider == null
                ? null : runtimeControlProvider.getIfAvailable();
        boolean verified = control != null && control.owns(run.getAgentId(), id);
        if (verified) {
            control.pause(id);
        }
        return toResponse(writeStateConditionally(id, RunStatus.PAUSED, null, "paused",
                verified ? "pause" : null));
    }

    @Transactional
    public RunResponse resumeRun(UUID id) {
        return resumeRun(id, null);
    }

    /**
     * Resumes a run. A run whose runtime is owned by the execution coordinator is
     * resumed on the same run-owned session first (the verified suspension is
     * matched and released) and RUNNING is written only after that verified
     * acknowledgement; a refused resume throws with the exact reason and leaves
     * the run PAUSED. Like {@link #pauseRun}, the record itself is a conditional
     * write, so a run that terminated during the verified resume is never moved
     * back to RUNNING by a stale save.
     */
    @Transactional
    public RunResponse resumeRun(UUID id, String newInstruction) {
        Run run = findRunOrThrow(id);
        validateTransition(run, RunStatus.RUNNING, "resumed");
        RunRuntimeControlPort control = runtimeControlProvider == null
                ? null : runtimeControlProvider.getIfAvailable();
        boolean verified = control != null && control.owns(run.getAgentId(), id);
        if (verified) {
            control.resume(id);
        }
        String promptSeed = newInstruction != null && !newInstruction.isBlank() ? newInstruction : null;
        if (promptSeed != null) {
            log.info("Updating run instruction on resume: id={}", id);
        }
        log.info("Resuming run: id={}", id);
        return toResponse(writeStateConditionally(id, RunStatus.RUNNING, promptSeed, "resumed",
                verified ? "resume" : null));
    }

    /**
     * The one conditional writer of a run's non-terminal operator transition: the
     * database evaluates the committed row against the statuses this transition may
     * start from ({@link #transitionSources}), so a terminal state that arrived in the
     * meantime is refused with the state that actually stands and never overwritten.
     *
     * <p>The refusal tells the truth about what was attempted: a lost write on a path
     * that never touched a runtime keeps the pre-action wording ("nothing was
     * &lt;outcome&gt;"), while a lost write after the runtime acknowledged the action
     * ({@code verifiedAction}) names the state the run reached and says the runtime was
     * acted on even though the record was left alone. The verified wording applies to
     * every committed state, not only a terminal one: the runtime action already
     * happened, so a lost write to a live state (e.g. {@code WAITING_INPUT}) must not
     * fall back to a bare state-machine arrow. Same exception type in both cases, so the
     * operator still sees a 409.
     *
     * @throws ResourceNotFoundException         when the run row does not exist
     * @throws InvalidStateTransitionException   when the committed state no longer allows it
     */
    private Run writeStateConditionally(UUID id, RunStatus to, String promptSeed, String outcome,
                                        String verifiedAction) {
        Instant now = Instant.now();
        int updated = runRepository.updateStatusIfCurrentIn(id, to, promptSeed, now, transitionSources(to));
        if (updated == 0) {
            RunStatus committed = runRepository.findStateById(id)
                    .map(RunRepository.RunStateView::getStatus)
                    .orElse(null);
            if (committed == null) {
                throw new ResourceNotFoundException("Run", id);
            }
            if (verifiedAction != null) {
                throw reachedWhileVerified(id, committed, verifiedAction);
            }
            throw refusal(id, committed, to, outcome);
        }
        return findRunOrThrow(id);
    }

    /**
     * Cancels a run. When the run's runtime is owned by the execution coordinator,
     * the stop is verified on the run-owned core FIRST -- outside any database
     * transaction, because the verified stop may block for up to 30 s and must
     * never pin a connection -- and only then is the atomic CANCELLED write
     * attempted. A refused stop throws with the exact reason and leaves the run
     * in its prior state (nothing is written and no completion event is
     * published) -- unless the runtime vanished while the committed row is still
     * non-terminal, in which case there is no live core left to stop and the
     * recorded transition is written instead. A run no runtime owns keeps the
     * plain recorded transition, with the same atomic write guarding the
     * run-finalizes-first race.
     *
     * <p>Ownership is the coordinator's registry presence, so a run whose launch is
     * still in flight (its core process exists, its session is opening) is owned
     * here: the cancel goes to the coordinator, which stops that core, and a
     * CANCELLED is never recorded over a live owned core (#111 fix round 2, C1).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RunResponse cancelRun(UUID id) {
        Run run = findRunOrThrow(id);
        validateTransition(run, RunStatus.CANCELLED, "stopped");
        log.info("Requesting verified cancel: id={}", id);
        RunRuntimeControlPort control = runtimeControlProvider == null
                ? null : runtimeControlProvider.getIfAvailable();
        boolean verified = false;
        if (control != null && control.owns(run.getAgentId(), id)) {
            try {
                control.cancel(id);
                // The runtime ACKNOWLEDGED the stop: from here on a lost record write
                // must not tell the operator that nothing was stopped.
                verified = true;
            } catch (IllegalStateException refused) {
                // A runtime that vanished between owns() and cancel() may simply mean
                // the run finalized first. Report the committed state instead of the
                // missing-runtime message; every other refusal keeps its exact reason.
                RunStatus committed = runRepository.findStateById(id)
                        .map(RunRepository.RunStateView::getStatus)
                        .orElse(null);
                if (committed != null && !cancelableStatuses().contains(committed)) {
                    throw refusal(id, committed, RunStatus.CANCELLED, "stopped");
                }
                if (control.owns(run.getAgentId(), id)) {
                    // The runtime is still owned by this process and refused the stop:
                    // a live core that did not confirm must remain an exact refusal.
                    throw refused;
                }
                // The runtime vanished between owns() and cancel() while the committed
                // row is still non-terminal: there is no live core left to stop, so
                // fall through to the recorded transition.
            }
        }
        // The conditional write is the only terminal write and publishes the
        // completion event only when it wins, so a run that finalized while the
        // stop was in flight is never overwritten and never gets a second event.
        Run cancelled;
        try {
            cancelled = cancellationWriter.cancel(id);
        } catch (InvalidStateTransitionException lost) {
            // The atomic write lost the race: the run reached a terminal state while the
            // verified stop was in flight. The writer's own refusal names the arrow; report
            // the standing state (and its completion time) instead, like every other
            // already-terminal refusal on this endpoint.
            RunStatus committed = runRepository.findStateById(id)
                    .map(RunRepository.RunStateView::getStatus)
                    .orElse(null);
            if (committed != null && TERMINAL_STATUSES.contains(committed)) {
                // A verified stop that lost the write is not "nothing was stopped": the
                // runtime was stopped and only the recorded state was refused.
                throw verified
                        ? reachedWhileVerified(id, committed, "cancel")
                        : refusal(id, committed, RunStatus.CANCELLED, "stopped");
            }
            throw lost;
        }
        log.info("Run cancelled: id={}, status={}", cancelled.getId(), cancelled.getStatus());
        return toResponse(cancelled);
    }

    @Transactional(readOnly = true)
    public Map<RunStatus, Long> countByStatus() {
        Map<RunStatus, Long> counts = new EnumMap<>(RunStatus.class);
        for (RunStatus status : RunStatus.values()) {
            counts.put(status, runRepository.countByStatus(status));
        }
        return counts;
    }

    private Run findRunOrThrow(UUID id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Run", id));
    }

    private void validateTransition(Run run, RunStatus target, String outcome) {
        Set<RunStatus> allowed = VALID_TRANSITIONS.getOrDefault(run.getStatus(), EnumSet.noneOf(RunStatus.class));
        if (!allowed.contains(target)) {
            throw refusal(run.getId(), run.getStatus(), target, outcome, run.getCompletedAt());
        }
    }

    /**
     * The one refusal of a run transition: from a terminal state it names the state the run
     * is already in (and when it got there) and that nothing was done; any other refusal keeps
     * the recorded state machine's plain answer.
     */
    private InvalidStateTransitionException refusal(UUID id, RunStatus current, RunStatus target,
                                                    String outcome) {
        return refusal(id, current, target, outcome, null);
    }

    private InvalidStateTransitionException refusal(UUID id, RunStatus current, RunStatus target,
                                                    String outcome, Instant completedAt) {
        if (!TERMINAL_STATUSES.contains(current)) {
            return new InvalidStateTransitionException("Run", current.name(), target.name());
        }
        Instant since = completedAt != null
                ? completedAt
                : runRepository.findById(id).map(Run::getCompletedAt).orElse(null);
        return InvalidStateTransitionException.alreadyTerminal(
                "Run", id.toString(), current.name(), since, outcome);
    }

    /**
     * The refusal of a lost write AFTER the runtime acknowledged the operator action: the
     * action really happened, so {@link #refusal}'s pre-action wording ("nothing was
     * stopped/paused/resumed") would be a lie. This names the state the run reached, the
     * verified action, and that only the run record was left alone. It is still an
     * {@link InvalidStateTransitionException}, so the client keeps its 409.
     */
    private InvalidStateTransitionException reachedWhileVerified(UUID id, RunStatus committed,
                                                                 String verifiedAction) {
        String done = switch (verifiedAction) {
            case "pause" -> "paused";
            case "resume" -> "resumed";
            default -> "stopped";
        };
        return new InvalidStateTransitionException(String.format(
                "Run %s reached %s while the verified %s completed; the runtime was %s"
                        + " but the run record was not changed.",
                id, committed.name(), verifiedAction, done));
    }

    private RunResponse toResponse(Run run) {
        return toResponse(run, bindingFor(run));
    }

    /** The run's frozen binding, or null when no reader is served or the run has no row. */
    private RunExecutionBinding bindingFor(Run run) {
        if (bindingReader == null || run.getId() == null) {
            return null;
        }
        return bindingReader.findByRunId(run.getId()).orElse(null);
    }

    /**
     * List mapping resolves the runs' frozen bindings in ONE batched lookup:
     * the run list is a dashboard page, and a per-run binding query would make
     * it O(runs) round trips (N+1). Absent runs simply have no entry.
     */
    private List<RunResponse> toResponses(List<Run> runs) {
        Map<UUID, RunExecutionBinding> bindings = bindingsByRunId(runs);
        return runs.stream()
                .map(run -> toResponse(run, bindings.get(run.getId())))
                .collect(Collectors.toList());
    }

    private Map<UUID, RunExecutionBinding> bindingsByRunId(List<Run> runs) {
        if (bindingReader == null || runs.isEmpty()) {
            return Map.of();
        }
        List<UUID> runIds = runs.stream().map(Run::getId).filter(Objects::nonNull).toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return bindingReader.findByRunIdIn(runIds).stream()
                .filter(binding -> binding != null && binding.getRunId() != null)
                .collect(Collectors.toMap(RunExecutionBinding::getRunId, Function.identity(),
                        (first, second) -> first));
    }

    private RunResponse toResponse(Run run, RunExecutionBinding binding) {
        return RunResponse.builder()
                .id(run.getId())
                .agentId(run.getAgentId())
                .status(run.getStatus())
                .promptSeed(run.getPromptSeed())
                .maxIterations(run.getMaxIterations())
                .totalTokensUsed(run.getTotalTokensUsed())
                .iterationCount(run.getIterationCount())
                .errorMessage(run.getErrorMessage())
                .errorCode(run.getErrorCode())
                .errorSurface(run.getErrorSurface())
                .finalOutput(run.getFinalOutput())
                .coreId(binding == null ? null : binding.getCoreId())
                .executionMode(binding == null || binding.getExecutionMode() == null
                        ? null : binding.getExecutionMode().name())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }
}
