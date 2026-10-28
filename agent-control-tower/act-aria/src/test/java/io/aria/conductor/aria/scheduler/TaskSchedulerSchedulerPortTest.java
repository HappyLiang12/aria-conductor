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

    /**
     * Second granularity, so the scheduler really fires inside the test window instead of at the next
     * minute boundary. A ONE_SHOT is scheduled at the next cron match
     * ({@code TaskSchedulerSchedulerPort.parseDuration}), and {@code normalizeCron} pads a 5-field
     * expression to {@code "0 * * * * *} - which is why the previous fixtures spent 0-60 s of real
     * time waiting for one callback. The 6-field form is passed through unpadded.
     */
    private static final String EVERY_SECOND = "* * * * * *";

    /**
     * Ceiling for a callback that is scheduled to land within one second. Not a tolerance for a slow
     * machine: it is the delay budget the first fire has to stay inside while the assertion still
     * means "it fired".
     */
    private static final long FIRST_FIRE_BUDGET_SECONDS = 5L;

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
        job.setScheduleExpression(EVERY_SECOND);
        job.setScheduleType("ONE_SHOT");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> firedId = new AtomicReference<>();
        job.setOnFireCallback(id -> {
            firedId.set(id);
            latch.countDown();
        });

        port.schedule(job);

        assertTrue(latch.await(FIRST_FIRE_BUDGET_SECONDS, TimeUnit.SECONDS),
                "onFire callback was not invoked within " + FIRST_FIRE_BUDGET_SECONDS + " seconds");
        assertEquals("test-job", firedId.get());
    }

    @Test
    void recoverActiveJobs_schedulesRecoveredJobs_withCallback() throws Exception {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId("rec1");
        entity.setScheduleExpression(EVERY_SECOND);
        entity.setScheduleType("ONE_SHOT");
        entity.setStatus("ACTIVE");
        when(jobRepository.findByStatus("ACTIVE")).thenReturn(List.of(entity));

        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);
        port.recoverActiveJobs();

        verify(scheduledJobService,
                timeout(TimeUnit.SECONDS.toMillis(FIRST_FIRE_BUDGET_SECONDS)).atLeastOnce()).onFire("rec1");
    }

    /**
     * The 5-field form is what callers hand the product, and the padding in {@code normalizeCron} is
     * the only reason Spring's {@code CronExpression} does not reject it. Asserting acceptance costs
     * no wall clock, unlike waiting a minute for the tick it produces, so the contract stays covered
     * while the firing tests above use second granularity.
     */
    @Test
    void schedule_acceptsFiveFieldCron_byPaddingToSecondGranularity() {
        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);

        ScheduledJob job = new ScheduledJob();
        job.setId("five-field");
        job.setScheduleExpression("* * * * *");
        job.setScheduleType("ONE_SHOT");

        assertDoesNotThrow(() -> assertEquals("five-field", port.schedule(job)),
                "a 5-field expression must be padded, not passed to CronExpression unpadded");
        port.cancel("five-field");
    }

    /**
     * The recurring branch schedules through {@code CronTrigger}, which the ONE_SHOT tests never reach.
     * Same callback contract, different scheduling call, so it is exercised on its own.
     */
    @Test
    void schedule_recurringJob_firesOnFireCallback_eachCronMatch() throws Exception {
        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);

        ScheduledJob job = new ScheduledJob();
        job.setId("recurring");
        job.setScheduleExpression(EVERY_SECOND);
        job.setScheduleType("RECURRING");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> firedId = new AtomicReference<>();
        job.setOnFireCallback(id -> {
            firedId.set(id);
            latch.countDown();
        });

        port.schedule(job);

        try {
            assertTrue(latch.await(FIRST_FIRE_BUDGET_SECONDS, TimeUnit.SECONDS),
                    "a CronTrigger job must invoke onFire within "
                            + FIRST_FIRE_BUDGET_SECONDS + " seconds");
            assertEquals("recurring", firedId.get());
        } finally {
            port.cancel("recurring");
        }
    }

    /**
     * Negative control for the two firing tests: the latch counts down only when the port really calls
     * the callback, so cancelling before the first match proves that nothing fires by itself. Without
     * it, a callback invoked synchronously by {@code schedule} - or by the test - would satisfy
     * {@link #schedule_invokesOnFireCallback_whenJobFires} vacuously.
     */
    @Test
    void cancel_beforeFirstMatch_preventsTheFireCallback() throws Exception {
        TaskSchedulerSchedulerPort port = new TaskSchedulerSchedulerPort(jobRepository, scheduledJobService);

        ScheduledJob job = new ScheduledJob();
        job.setId("cancelled");
        job.setScheduleExpression(EVERY_SECOND);
        job.setScheduleType("RECURRING");

        CountDownLatch latch = new CountDownLatch(1);
        job.setOnFireCallback(id -> latch.countDown());

        port.schedule(job);
        port.cancel("cancelled");

        assertFalse(latch.await(1_500, TimeUnit.MILLISECONDS),
                "a cancelled job must not reach the fire callback");
    }
}
