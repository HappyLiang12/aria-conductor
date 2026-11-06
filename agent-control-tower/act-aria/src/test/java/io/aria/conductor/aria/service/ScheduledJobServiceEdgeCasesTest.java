package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.ScheduledJobDto;
import io.aria.conductor.aria.persistence.ScheduledJobEntity;
import io.aria.conductor.aria.persistence.ScheduledJobRepository;
import io.aria.conductor.common.port.SchedulerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Not-found paths, list filter branches and the PAUSED-update reschedule guard
 * of {@link ScheduledJobService}; ScheduledJobServiceTest covers the
 * create/pause/resume/delete/onFire/update happy paths.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledJobServiceEdgeCasesTest {

    @Mock private ScheduledJobRepository jobRepository;
    @Mock private SchedulerPort schedulerPort;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private ScheduledJobService service;

    private ScheduledJobEntity entity(String id, String status) {
        ScheduledJobEntity entity = new ScheduledJobEntity();
        entity.setId(id);
        entity.setScheduleType("CRON");
        entity.setCategory("REMINDER");
        entity.setTitle("Standup");
        entity.setScheduleExpression("0 0 9 * * ?");
        entity.setNotificationTitle("Standup time");
        entity.setNotificationBody("Join the call");
        entity.setStatus(status);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private ScheduledJobDto dtoWithOnly(String title) {
        return new ScheduledJobDto(null, null, null, title,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void pause_unknownIdThrows() {
        when(jobRepository.findById("j-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause("j-404"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Job not found: j-404");
        verify(schedulerPort, never()).pause(any());
    }

    @Test
    void resume_unknownIdThrows() {
        when(jobRepository.findById("j-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume("j-404"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Job not found: j-404");
        verify(schedulerPort, never()).resume(any());
    }

    @Test
    void update_unknownIdThrows() {
        when(jobRepository.findById("j-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("j-404", dtoWithOnly("x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Job not found: j-404");
        verify(schedulerPort, never()).cancel(any());
    }

    @Test
    void onFire_unknownIdThrowsAndNothingIsNotified() {
        when(jobRepository.findById("j-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onFire("j-404"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Job not found: j-404");
        verify(notificationService, never())
                .create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onFire_stampsLastFiredAtAndNotifiesWithLowercaseCategory() {
        ScheduledJobEntity job = entity("j-1", "ACTIVE");
        when(jobRepository.findById("j-1")).thenReturn(Optional.of(job));

        service.onFire("j-1");

        ArgumentCaptor<ScheduledJobEntity> captor =
                ArgumentCaptor.forClass(ScheduledJobEntity.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getLastFiredAt()).isNotNull();
        // category is lowercased and the job id doubles as resourceId + jobId
        verify(notificationService).create(
                "reminder", "Standup time", "Join the call", "JOB", "j-1", "j-1");
    }

    @Test
    void update_pausedJobIsCancelledButNotRescheduled() {
        ScheduledJobEntity job = entity("j-1", "PAUSED");
        when(jobRepository.findById("j-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ScheduledJobEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.update("j-1", dtoWithOnly("New title"));

        verify(schedulerPort).cancel("j-1");
        verify(schedulerPort, never()).schedule(any());
    }

    @Test
    void update_nullFieldsPreserveExistingValues() {
        ScheduledJobEntity job = entity("j-1", "ACTIVE");
        when(jobRepository.findById("j-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ScheduledJobEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ScheduledJobDto result = service.update("j-1", dtoWithOnly("New title"));

        assertThat(result.title()).isEqualTo("New title");
        // untouched fields keep their persisted values
        assertThat(result.category()).isEqualTo("REMINDER");
        assertThat(result.scheduleExpression()).isEqualTo("0 0 9 * * ?");
        assertThat(result.notificationTitle()).isEqualTo("Standup time");
    }

    @Test
    void list_withCategoryAndStatusUsesCombinedQuery() {
        when(jobRepository.findByCategoryAndStatus("REMINDER", "ACTIVE"))
                .thenReturn(List.of(entity("j-1", "ACTIVE")));

        List<ScheduledJobDto> result = service.list("REMINDER", "ACTIVE");

        assertThat(result).extracting(ScheduledJobDto::id).containsExactly("j-1");
        verify(jobRepository, never()).findAll();
    }

    @Test
    void list_withCategoryOnlyUsesCategoryQuery() {
        when(jobRepository.findByCategory("REMINDER"))
                .thenReturn(List.of(entity("j-1", "ACTIVE"), entity("j-2", "PAUSED")));

        List<ScheduledJobDto> result = service.list("REMINDER", null);

        assertThat(result).hasSize(2)
                .extracting(ScheduledJobDto::status)
                .containsExactly("ACTIVE", "PAUSED");
    }

    @Test
    void list_withStatusOnlyUsesStatusQuery() {
        when(jobRepository.findByStatus("PAUSED"))
                .thenReturn(List.of(entity("j-2", "PAUSED")));

        List<ScheduledJobDto> result = service.list(null, "PAUSED");

        assertThat(result).extracting(ScheduledJobDto::id).containsExactly("j-2");
    }

    @Test
    void list_withoutFiltersReturnsAll() {
        when(jobRepository.findAll())
                .thenReturn(List.of(entity("j-1", "ACTIVE")));

        List<ScheduledJobDto> result = service.list(null, null);

        assertThat(result).extracting(ScheduledJobDto::title).containsExactly("Standup");
    }
}
