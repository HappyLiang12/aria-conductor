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
 * by hand for the same reason.
 *
 * <p>The run id lives on the {@link LiveSession} until the turn ends; the caller that
 * settles the turn clears it ({@code session.runId(null)}), so the next turn's first
 * tool call gets a fresh row.
 */
@Slf4j
@Service
public class LiveRunService {

    /**
     * A live tool turn never loops — it has no model of its own — so the honest cap is
     * one iteration rather than the engine's 50-iteration default.
     */
    static final int LIVE_MAX_ITERATIONS = 1;

    private static final Set<RunStatus> TERMINAL_STATUSES =
            EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

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
     * The run of the current tool turn: the one the session already owns, or a newly
     * inserted {@code RUNNING} row on the turn's first tool call. Idempotent per turn;
     * the double-checked lock keeps two near-simultaneous calls from inserting two rows.
     */
    @Transactional
    public UUID findOrCreateForToolTurn(LiveSession session, String toolName) {
        UUID existing = session.runId();
        if (existing != null) {
            return existing;
        }
        synchronized (session) {
            existing = session.runId();
            if (existing != null) {
                return existing;
            }
            Run run = Run.builder()
                    .agentId(AriaConstants.ARIA_AGENT_ID)
                    .conversationId(session.conversationId())
                    .promptSeed("[live] tool: " + toolName)
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
     * Writes the turn's terminal state (or the parked {@code WAITING_INPUT} state Task 4
     * uses while an approval is pending). A run that is already terminal is left alone:
     * a late bridge event must not resurrect a finished run, and an unknown id is logged
     * and ignored rather than thrown at the socket.
     *
     * <p>No completion event is published here — the live event router (Task 4) owns the
     * bridge-facing {@code run_event} and the settled-turn cleanup.
     */
    @Transactional
    public void settle(UUID runId, RunStatus status, String reason) {
        if (runId == null || status == null) {
            return;
        }
        Run run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("live run settle ignored: unknown run {}", runId);
            return;
        }
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            log.debug("live run settle ignored: run {} already {}", runId, run.getStatus());
            return;
        }
        run.setStatus(status);
        if (TERMINAL_STATUSES.contains(status)) {
            run.setCompletedAt(Instant.now(clock));
        }
        if (status == RunStatus.FAILED && reason != null && !reason.isBlank()) {
            run.setErrorMessage(reason);
        }
        runRepository.save(run);
        log.debug("live run settled: id={} status={}", runId, status);
    }
}
