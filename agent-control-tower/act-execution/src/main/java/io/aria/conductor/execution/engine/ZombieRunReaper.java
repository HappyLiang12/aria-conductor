package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZombieRunReaper {

    private final RunRepository runRepository;
    private final AgentLoopEngine agentLoopEngine;

    @Value("${run.reaper.timeout-minutes:120}")
    private long timeoutMinutes;

    @Scheduled(fixedRate = 60_000) // every 60 seconds
    public void reapZombieRuns() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<Run> running = runRepository.findByStatus(RunStatus.RUNNING);

        for (Run run : running) {
            Instant lastActivity = run.getUpdatedAt() != null ? run.getUpdatedAt() : run.getCreatedAt();
            if (lastActivity.isBefore(cutoff) && !agentLoopEngine.hasActiveContext(run.getId())) {
                log.warn("Reaping zombie run: id={}, status={}, lastActivity={}, threshold={}min",
                        run.getId(), run.getStatus(), lastActivity, timeoutMinutes);
                run.setStatus(RunStatus.FAILED);
                run.setErrorMessage("Zombie run reaped: no active execution context, last activity " + lastActivity);
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            }
        }
    }
}
