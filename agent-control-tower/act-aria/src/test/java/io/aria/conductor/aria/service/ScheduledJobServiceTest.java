package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.persistence.ScheduledJobEntity;
import io.aria.conductor.aria.persistence.ScheduledJobRepository;
import io.aria.conductor.common.model.ScheduledJob;
import io.aria.conductor.common.port.SchedulerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledJobServiceTest {

    @Mock ScheduledJobRepository jobRepository;
    @Mock SchedulerPort schedulerPort;
    @Mock NotificationService notificationService;
    @InjectMocks ScheduledJobService scheduledJobService;

    @Test
    void create_persistsAndSchedules() {
        ScheduledJobDto input = new ScheduledJobDto(null, "RECURRING", "REMINDER",
                "Test Reminder", "3600", "Reminder!", null, null, null, null, null, null);

        when(jobRepository.save(any(ScheduledJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(schedulerPort.schedule(any())).thenReturn("sched-key");

        ScheduledJobDto result = scheduledJobService.create(input);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.title()).isEqualTo("Test Reminder");
        verify(schedulerPort).schedule(any());
    }

    @Test
    void pause_changesStatusToPaused() {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId("j1"); entity.setStatus("ACTIVE");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(entity));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledJobDto result = scheduledJobService.pause("j1");
        assertThat(result.status()).isEqualTo("PAUSED");
        verify(schedulerPort).pause("j1");
    }

    @Test
    void resume_changesStatusToActive() {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId("j1"); entity.setStatus("PAUSED");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(entity));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledJobDto result = scheduledJobService.resume("j1");
        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(schedulerPort).resume("j1");
    }

    @Test
    void delete_cancelsAndRemoves() {
        scheduledJobService.delete("j1");
        verify(schedulerPort).cancel("j1");
        verify(jobRepository).deleteById("j1");
    }

    @Test
    void onFire_createsNotification() {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId("j1"); entity.setCategory("REMINDER");
        entity.setNotificationTitle("Time's up!");
        entity.setNotificationBody("Your reminder fired.");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(entity));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduledJobService.onFire("j1");
        verify(notificationService).create(
                eq("reminder"), eq("Time's up!"), eq("Your reminder fired."),
                eq("JOB"), eq("j1"), eq("j1"));
    }

    @Test
    void create_setsOnFireCallbackOnScheduledJob() {
        ScheduledJobDto input = new ScheduledJobDto(null, "RECURRING", "REMINDER",
                "Test", "3600", "Title", null, null, null, null, null, null);

        when(jobRepository.save(any(ScheduledJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(schedulerPort.schedule(any())).thenReturn("sched-key");

        scheduledJobService.create(input);

        ArgumentCaptor<ScheduledJob> captor = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(schedulerPort).schedule(captor.capture());
        assertThat(captor.getValue().getOnFireCallback()).isNotNull();
    }

    @Test
    void update_modifiesFieldsAndReschedules() {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId("j1"); entity.setTitle("Old Title");
        entity.setScheduleExpression("3600"); entity.setScheduleType("RECURRING");
        entity.setCategory("REMINDER"); entity.setStatus("ACTIVE");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(entity));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schedulerPort.schedule(any())).thenReturn("sched-key");

        ScheduledJobDto input = new ScheduledJobDto(null, "ONE_SHOT", "MONITOR",
                "Updated Title", "7200", "Updated!", null, null, null, null, null, null);
        ScheduledJobDto result = scheduledJobService.update("j1", input);

        assertThat(result.title()).isEqualTo("Updated Title");
        assertThat(result.scheduleType()).isEqualTo("ONE_SHOT");
        assertThat(result.category()).isEqualTo("MONITOR");
        assertThat(result.scheduleExpression()).isEqualTo("7200");
        verify(schedulerPort).cancel("j1");
        verify(schedulerPort).schedule(any());
    }
}
