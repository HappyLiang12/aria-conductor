package io.aria.conductor.dashboard.listener;

import io.aria.conductor.common.repository.RunProgressEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Retention for run_progress_events (spec Section 2): deletes fragments older
 * than aria.progress.retention-days (default 7). Runs daily at 03:30 server time.
 */
@Slf4j
@Component
public class RunProgressRetentionTask {

    private final RunProgressEventRepository progressRepository;
    private final int retentionDays;

    public RunProgressRetentionTask(RunProgressEventRepository progressRepository,
                                    @Value("${aria.progress.retention-days:7}") int retentionDays) {
        this.progressRepository = progressRepository;
        this.retentionDays = retentionDays;
    }

    // @Modifying bulk DELETE requires an active transaction (Spring Data custom query methods don't open one)
    @Transactional
    @Scheduled(cron = "${aria.progress.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        int removed = progressRepository.deleteByCreatedAtBefore(Instant.now().minusSeconds(86400L * retentionDays));
        if (removed > 0) {
            log.info("Run progress retention: removed {} fragments older than {} days", removed, retentionDays);
        }
    }
}
