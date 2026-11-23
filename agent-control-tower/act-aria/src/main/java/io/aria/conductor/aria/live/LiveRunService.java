package io.aria.conductor.aria.live;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The lazy live run (spec L4): a tool-using turn owns exactly one {@code Run} row,
 * inserted on its first tool call, and a pure chat turn owns none.
 *
 * <p>The row exists so the turn is visible in the dashboard, the audit trail and the
 * finalize route like any other run — but no core ever executes it. It is therefore
 * written with {@link RunRepository#save} only: {@code RunService.createRun} publishes
 * a {@code RunStartedEvent} that dispatches the execution engine, which would launch a
 * subprocess for a prompt seed no model would ever answer. The audit event is published
 * by hand for the same reason. Once created, every later write is one of the
 * repository's conditional statements, never a read-then-save.
 *
 * <p>The run id lives on the {@link LiveSession} until the turn ends; {@link #settle}
 * clears that slot itself, so idempotency does not depend on a caller remembering to.
 * A slot that holds a row which is no longer live (settled, cancelled, or gone) is
 * never reused: the next tool call inserts a fresh row.
 */
@Slf4j
@Service
public class LiveRunService {

    /**
     * The prompt seed prefix of every live-created row. Internal plumbing, not the
     * operator's conversation: tools that summarize runs filter on it.
     */
    public static final String LIVE_SEED_PREFIX = "[live] ";

    /**
     * A live tool turn never loops — it has no model of its own — so the honest cap is
     * one iteration rather than the engine's 50-iteration default.
     */
    static final int LIVE_MAX_ITERATIONS = 1;

    private static final Set<RunStatus> TERMINAL_STATUSES =
            EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    /** The same set as the repository's {@code terminal} parameter; EnumSet order is stable. */
    private static final List<RunStatus> TERMINAL_STATUS_LIST = List.copyOf(TERMINAL_STATUSES);

    /**
     * The only committed states in which a turn's row can still take tool calls. A row
     * that left this set has finished (or was cancelled by an operator) and must not be
     * reused as the next turn's row.
     */
    private static final Set<RunStatus> LIVE_STATUSES =
            EnumSet.of(RunStatus.RUNNING, RunStatus.WAITING_INPUT);

    private final RunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** Production wiring: the wall clock is the only unmanaged dependency. */
    @Autowired
    public LiveRunService(RunRepository runRepository, ApplicationEventPublisher eventPublisher) {
        this(runRepository, eventPublisher, Clock.systemUTC());
    }

    LiveRunService(RunRepository runRepository, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * The run of the current tool turn: the one the session already owns while it is
     * still live, or a newly inserted {@code RUNNING} row on the turn's first tool call.
     * Idempotent per turn; the double-checked lock keeps two near-simultaneous calls
     * from inserting two rows.
     *
     * <p>The slot is re-validated against the committed status on every call: an
     * operator cancel (or a settle whose slot clear was skipped) leaves a terminal id
     * behind, and reusing it would attach this turn's tools to a dead run.
     */
    @Transactional
    public UUID findOrCreateForToolTurn(LiveSession session, String toolName) {
        UUID existing = liveRunId(session.runId());
        if (existing != null) {
            return existing;
        }
        synchronized (session) {
            existing = liveRunId(session.runId());
            if (existing != null) {
                return existing;
            }
            Run run = Run.builder()
                    .agentId(AriaConstants.ARIA_AGENT_ID)
                    .conversationId(session.conversationId())
                    .promptSeed(LIVE_SEED_PREFIX + "tool: " + toolName)
                    .status(RunStatus.RUNNING)
                    .maxIterations(LIVE_MAX_ITERATIONS)
                    .createdAt(Instant.now(clock))
                    .build();
            Run saved = runRepository.save(run);
            eventPublisher.publishEvent(new AuditLogEvent(this, "RUN", "Run", saved.getId().toString(),
                    "START", "Run started (live tool turn)", session.conversationId()));
            session.runId(saved.getId());
            log.debug("live run created: id={} conversation={} tool={}", saved.getId(),
                    session.conversationId(), toolName);
            return saved.getId();
        }
    }

    /**
     * Writes the turn's terminal state (or the parked {@code WAITING_INPUT} state an
     * approval uses while it waits) with the repository's single-statement conditional
     * writers, and clears the session's run slot once the run is terminal. A concurrent
     * writer — an operator cancel above all — is evaluated by the database against the
     * committed row, so this call can never resurrect a run that finished or was cancelled
     * after it read the row; when it loses, it claims nothing and reports the committed
     * state.
     *
     * <p>The slot survives a park: WAITING_INPUT is still the turn's live row (it can take
     * its tool's answer and its finalize), so it must stay reachable from the session that
     * owns it.
     *
     * <p>No completion event is published here — the live event router owns the
     * bridge-facing {@code run_event} and the settled-turn cleanup.
     *
     * @param session the socket that owns the turn; its slot is cleared when it still
     *                holds {@code runId} (may be null for a run with no live session)
     * @return the run's committed status after the attempt, or null when the id is
     *         unknown or no status was supplied
     */
    @Transactional
    public RunStatus settle(LiveSession session, UUID runId, RunStatus status, String reason) {
        if (runId == null || status == null) {
            return null;
        }
        if (status == RunStatus.WAITING_INPUT) {
            // Parked, not finished: no completion time, and only a RUNNING row may park.
            return park(runId, status);
        }
        if (!TERMINAL_STATUSES.contains(status)) {
            log.warn("live run settle ignored: {} is not a settleable live status", status);
            return committedStatus(runId);
        }
        if (session != null) {
            session.clearRunSlot(runId);
        }
        Instant now = Instant.now(clock);
        int written = runRepository.completeIfNotTerminal(runId, status, 0, 0L, now, null,
                failureReason(status, reason), null, null, now, TERMINAL_STATUS_LIST);
        return report(runId, status, written);
    }

    /** The park write: no completion time, and only a RUNNING row may take it. */
    private RunStatus park(UUID runId, RunStatus status) {
        int written = runRepository.updateStatusIfCurrentIn(runId, status, null, Instant.now(clock),
                List.of(RunStatus.RUNNING));
        return report(runId, status, written);
    }

    private RunStatus report(UUID runId, RunStatus status, int written) {
        RunStatus committed = committedStatus(runId);
        if (committed == null) {
            log.warn("live run settle ignored: unknown run {}", runId);
        } else if (written == 0) {
            log.debug("live run settle lost for {}: committed status {} stays", runId, committed);
        } else {
            log.debug("live run settled: id={} status={}", runId, committed);
        }
        return committed;
    }

    /**
     * The slot's run when it can still take this turn's calls. Read as a projection so
     * a persistence-context snapshot cannot answer with a status another transaction
     * already replaced.
     */
    private UUID liveRunId(UUID runId) {
        if (runId == null) {
            return null;
        }
        Optional<RunStatus> status = runRepository.findStateById(runId)
                .map(state -> state.getStatus());
        return status.filter(LIVE_STATUSES::contains).map(ignored -> runId).orElse(null);
    }

    private RunStatus committedStatus(UUID runId) {
        return runRepository.findStateById(runId).map(state -> state.getStatus()).orElse(null);
    }

    private static String failureReason(RunStatus status, String reason) {
        if (status != RunStatus.FAILED || reason == null || reason.isBlank()) {
            return null;
        }
        return reason;
    }
}
