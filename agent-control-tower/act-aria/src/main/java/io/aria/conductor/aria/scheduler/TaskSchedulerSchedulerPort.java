package io.aria.conductor.aria.scheduler;

import io.aria.conductor.aria.persistence.ScheduledJobEntity;
import io.aria.conductor.aria.persistence.ScheduledJobRepository;
import io.aria.conductor.aria.service.ScheduledJobService;
import io.aria.conductor.common.model.ScheduledJob;
import io.aria.conductor.common.port.SchedulerPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

@Slf4j
@Component
public class TaskSchedulerSchedulerPort implements SchedulerPort {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final ScheduledJobRepository jobRepository;
    private final ScheduledJobService scheduledJobService;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public TaskSchedulerSchedulerPort(ScheduledJobRepository jobRepository,
                                       @Lazy ScheduledJobService scheduledJobService) {
        this.jobRepository = jobRepository;
        this.scheduledJobService = scheduledJobService;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(4);
        this.taskScheduler.setThreadNamePrefix("scheduled-job-");
        this.taskScheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverActiveJobs() {
        try {
            List<ScheduledJobEntity> activeJobs = jobRepository.findByStatus("ACTIVE");
            for (ScheduledJobEntity entity : activeJobs) {
                try {
                    schedule(toScheduledJob(entity));
                } catch (Exception e) {
                    log.warn("Failed to recover job [{}]: {}", entity.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query active jobs for recovery: {}", e.getMessage());
        }
    }

    @Override
    public String schedule(ScheduledJob job) {
        cancel(job.getId());
        ScheduledFuture<?> future;
        if ("ONE_SHOT".equals(job.getScheduleType())) {
            Duration delay = parseDuration(job.getScheduleExpression());
            future = taskScheduler.schedule(
                    () -> fireCallback(job), Instant.now().plus(delay));
        } else {
            future = taskScheduler.schedule(
                    () -> fireCallback(job), new CronTrigger(normalizeCron(job.getScheduleExpression())));
        }
        scheduledTasks.put(job.getId(), future);
        return job.getId();
    }

    @Override
    public void cancel(String jobId) {
        ScheduledFuture<?> future = scheduledTasks.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void pause(String jobId) {
        cancel(jobId);
    }

    @Override
    public void resume(String jobId) {
        ScheduledJobEntity entity = jobRepository.findById(jobId).orElse(null);
        if (entity == null) return;
        schedule(toScheduledJob(entity));
    }

    private ScheduledJob toScheduledJob(ScheduledJobEntity entity) {
        ScheduledJob job = new ScheduledJob();
        job.setId(entity.getId());
        job.setScheduleExpression(entity.getScheduleExpression());
        job.setScheduleType(entity.getScheduleType());
        job.setOnFireCallback(id -> scheduledJobService.onFire(id));
        return job;
    }

    private String normalizeCron(String expression) {
        return expression.trim().split("\\s+").length == 5 ? "0 " + expression : expression;
    }

    private Duration parseDuration(String expression) {
        try {
            String cronExpr = normalizeCron(expression);
            CronExpression cron = CronExpression.parse(cronExpr);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = cron.next(now);
            if (next == null)
                throw new IllegalArgumentException("Cron expression has no future matches: " + expression);
            return Duration.between(now, next);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: \"" + expression + "\"", e);
        }
    }

    private void fireCallback(ScheduledJob job) {
        Consumer<String> callback = job.getOnFireCallback();
        if (callback != null) {
            callback.accept(job.getId());
        }
    }
}
