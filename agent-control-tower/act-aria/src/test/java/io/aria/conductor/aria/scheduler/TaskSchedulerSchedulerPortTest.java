package io.aria.conductor.aria.scheduler;

import io.aria.conductor.aria.persistence.ScheduledJobEntity;
import io.aria.conductor.aria.persistence.ScheduledJobRepository;
import io.aria.conductor.aria.service.ScheduledJobService;
import io.aria.conductor.common.model.ScheduledJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskSchedulerSchedulerPortTest {

    @Mock
    private ScheduledJobRepository jobRepository;
    @Mock
    private ScheduledJobService scheduledJobService;

    @Test
    void recoverActiveJobs_shouldNotThrow_whenTableDoesNotExist() {
        when(jobRepository.findByStatus("ACTIVE"))
                .thenThrow(new DataAccessException("Table SCHEDULED_JOBS not found") {});

        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);
        assertDoesNotThrow(port::recoverActiveJobs);
    }

    @Test
    void schedule_invokesOnFireCallback_whenJobFires() throws Exception {
        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);

        ScheduledJob job = new ScheduledJob();
        job.setId("test-job");
        job.setScheduleExpression("* * * * *");
        job.setScheduleType("ONE_SHOT");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> firedId = new AtomicReference<>();
        job.setOnFireCallback(id -> {
            firedId.set(id);
            latch.countDown();
        });

        port.schedule(job);

        assertTrue(latch.await(65, TimeUnit.SECONDS),
                "onFire callback was not invoked within 65 seconds");
        assertEquals("test-job", firedId.get());
    }

    @Test
    void recoverActiveJobs_schedulesRecoveredJobs_withCallback() throws Exception {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId("rec1");
        entity.setScheduleExpression("* * * * *");
        entity.setScheduleType("ONE_SHOT");
        entity.setStatus("ACTIVE");
        when(jobRepository.findByStatus("ACTIVE")).thenReturn(List.of(entity));

        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);
        port.recoverActiveJobs();

        verify(scheduledJobService, timeout(65000).atLeastOnce()).onFire("rec1");
    }
}
