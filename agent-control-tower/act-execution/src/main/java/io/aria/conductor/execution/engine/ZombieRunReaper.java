package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodically detects and reaps zombie runs — runs stuck in RUNNING state with no active
 * execution context (e.g., after a JVM restart or silent thread death).
 * <p>
 * Pattern follows {@link io.aria.conductor.execution.approval.ApprovalExpiryChecker}.
 * <p>
 * NOTE: {@link AgentLoopEngine#hasActiveContext} is JVM-local state; this reaper is only safe
 * for single-instance deployments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZombieRunReaper {

    private final RunRepository runRepository;
    private final AgentLoopEngine agentLoopEngine;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${run.reaper.timeout-minutes:120}")
    private long timeoutMinutes;

    @Scheduled(fixedRate = 60_000) // every 60 seconds
    public void reapZombieRuns() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<Run> running = runRepository.findByStatus(RunStatus.RUNNING);

        for (Run run : running) {
            Instant lastActivity = run.getUpdatedAt() != null ? run.getUpdatedAt() : run.getCreatedAt();
            if (lastActivity.isBefore(cutoff) && !agentLoopEngine.hasActiveContext(run.getId())) {
                reapZombie(run, lastActivity);
            }
        }
    }

    private void reapZombie(Run run, Instant lastActivity) {
        // Re-read and re-verify status immediately before writing to avoid a lost update:
        // an external actor (e.g. RunService.cancelRun) may have transitioned this run to a
        // terminal state between our initial query and this save.
        runRepository.findById(run.getId()).ifPresent(current -> {
            if (current.getStatus() != RunStatus.RUNNING) {
                log.info("Skipping zombie reap for run {}: status changed to {}", current.getId(), current.getStatus());
                return;
            }
            log.warn("Reaping zombie run: id={}, status={}, lastActivity={}, threshold={}min",
                    current.getId(), current.getStatus(), lastActivity, timeoutMinutes);
            current.setStatus(RunStatus.FAILED);
            current.setErrorMessage("Zombie run reaped: no active execution context, last activity " + lastActivity);
            current.setCompletedAt(Instant.now());
            runRepository.save(current);

            // Publish the completion event so downstream listeners (workflow chainer, kanban,
            // notifications, audit, WS broadcast) reconcile the reaped run instead of leaving
            // chains/boards stuck in RUNNING.
            try {
                eventPublisher.publishEvent(new RunCompletedEvent(
                        this, current.getId(), current.getAgentId(), RunStatus.FAILED, null));
            } catch (Exception e) {
                log.warn("Failed to publish RunCompletedEvent for reaped run {}: {}", current.getId(), e.getMessage());
            }
        });
    }
}
