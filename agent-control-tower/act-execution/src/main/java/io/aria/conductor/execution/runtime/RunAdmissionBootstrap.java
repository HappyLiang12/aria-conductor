package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Objects;

/**
 * Restart safety for the admission queue: runs left PENDING by a previous
 * backend re-enter the engine (and queue) in creation order. Ordered last among
 * ApplicationReadyEvent listeners so AgentLoopEngine.recoverOrphanedRuns (which
 * fails RUNNING/INITIALIZING orphans) can never mis-classify a freshly admitted run.
 */
@Slf4j
@Component
public class RunAdmissionBootstrap {

    private final RunRepository runs;
    private final AgentLoopEngine engine;

    public RunAdmissionBootstrap(RunRepository runs, AgentLoopEngine engine) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Re-enqueues on the async executor: with a deep backlog this loop may park
     * on admission and must not hold the ApplicationReady thread. Ordering vs
     * the orphan reaper is unaffected — this task is only submitted after the
     * HIGHEST_PRECEDENCE reaper listener has already returned — and per-run FIFO
     * still comes from the admission queue's (createdAt, runId) sort.
     */
    @Async
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runs.findByStatus(RunStatus.PENDING).stream()
                .sorted(Comparator.comparing(Run::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(run -> {
                    try {
                        engine.startRun(run.getId());
                    } catch (RuntimeException e) {
                        log.warn("Could not re-start queued run {} after boot", run.getId(), e);
                    }
                });
    }
}
