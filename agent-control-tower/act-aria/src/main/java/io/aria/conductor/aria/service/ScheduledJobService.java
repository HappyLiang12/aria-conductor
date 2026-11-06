package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.persistence.ScheduledJobEntity;
import io.aria.conductor.aria.persistence.ScheduledJobRepository;
import io.aria.conductor.common.model.ScheduledJob;
import io.aria.conductor.common.port.SchedulerPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduledJobService {

    private final ScheduledJobRepository jobRepository;
    private final SchedulerPort schedulerPort;
    private final NotificationService notificationService;

    public ScheduledJobService(ScheduledJobRepository jobRepository,
                               SchedulerPort schedulerPort,
                               NotificationService notificationService) {
        this.jobRepository = jobRepository;
        this.schedulerPort = schedulerPort;
        this.notificationService = notificationService;
    }

    @Transactional
    public ScheduledJobDto create(ScheduledJobDto input) {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setScheduleType(input.scheduleType());
        entity.setCategory(input.category());
        entity.setTitle(input.title());
        entity.setScheduleExpression(input.scheduleExpression());
        entity.setNotificationTitle(input.notificationTitle());
        entity.setNotificationBody(input.notificationBody());
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        ScheduledJobEntity saved = jobRepository.save(entity);

        schedulerPort.schedule(toScheduledJob(saved));
        return toDto(saved);
    }

    @Transactional
    public ScheduledJobDto pause(String id) {
        ScheduledJobEntity entity = jobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        entity.setStatus("PAUSED");
        entity.setUpdatedAt(Instant.now());
        ScheduledJobEntity saved = jobRepository.save(entity);
        schedulerPort.pause(id);
        return toDto(saved);
    }

    @Transactional
    public ScheduledJobDto resume(String id) {
        ScheduledJobEntity entity = jobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        entity.setStatus("ACTIVE");
        entity.setUpdatedAt(Instant.now());
        ScheduledJobEntity saved = jobRepository.save(entity);
        schedulerPort.resume(id);
        return toDto(saved);
    }

    @Transactional
    public void delete(String id) {
        schedulerPort.cancel(id);
        jobRepository.deleteById(id);
    }

    @Transactional
    public ScheduledJobDto update(String id, ScheduledJobDto input) {
        ScheduledJobEntity entity = jobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        if (input.scheduleType() != null) entity.setScheduleType(input.scheduleType());
        if (input.category() != null) entity.setCategory(input.category());
        if (input.title() != null) entity.setTitle(input.title());
        if (input.scheduleExpression() != null) entity.setScheduleExpression(input.scheduleExpression());
        if (input.notificationTitle() != null) entity.setNotificationTitle(input.notificationTitle());
        if (input.notificationBody() != null) entity.setNotificationBody(input.notificationBody());
        entity.setUpdatedAt(Instant.now());
        ScheduledJobEntity saved = jobRepository.save(entity);

        schedulerPort.cancel(id);
        if ("ACTIVE".equals(saved.getStatus())) {
            schedulerPort.schedule(toScheduledJob(saved));
        }
        return toDto(saved);
    }

    @Transactional
    public void onFire(String id) {
        ScheduledJobEntity entity = jobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        entity.setLastFiredAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        jobRepository.save(entity);

        notificationService.create(entity.getCategory().toLowerCase(),
                entity.getNotificationTitle(),
                entity.getNotificationBody(),
                "JOB", id, id);
    }

    public List<ScheduledJobDto> list(String category, String status) {
        if (category != null && status != null) {
            return jobRepository.findByCategoryAndStatus(category, status).stream()
                    .map(this::toDto).toList();
        } else if (category != null) {
            return jobRepository.findByCategory(category).stream()
                    .map(this::toDto).toList();
        } else if (status != null) {
            return jobRepository.findByStatus(status).stream()
                    .map(this::toDto).toList();
        }
        return jobRepository.findAll().stream().map(this::toDto).toList();
    }

    private ScheduledJob toScheduledJob(ScheduledJobEntity entity) {
        ScheduledJob job = new ScheduledJob();
        job.setId(entity.getId());
        job.setUserId(entity.getUserId());
        job.setScheduleType(entity.getScheduleType());
        job.setCategory(entity.getCategory());
        job.setTitle(entity.getTitle());
        job.setScheduleExpression(entity.getScheduleExpression());
        job.setNextFireAt(entity.getNextFireAt());
        job.setLastFiredAt(entity.getLastFiredAt());
        job.setStatus(entity.getStatus());
        job.setNotificationTitle(entity.getNotificationTitle());
        job.setNotificationBody(entity.getNotificationBody());
        job.setCreatedAt(entity.getCreatedAt());
        job.setUpdatedAt(entity.getUpdatedAt());
        job.setOnFireCallback(id -> onFire(id));
        return job;
    }

    private ScheduledJobDto toDto(ScheduledJobEntity entity) {
        return new ScheduledJobDto(
                entity.getId(),
                entity.getScheduleType(),
                entity.getCategory(),
                entity.getTitle(),
                entity.getScheduleExpression(),
                entity.getNotificationTitle(),
                entity.getNextFireAt(),
                entity.getLastFiredAt(),
                entity.getNotificationBody(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
