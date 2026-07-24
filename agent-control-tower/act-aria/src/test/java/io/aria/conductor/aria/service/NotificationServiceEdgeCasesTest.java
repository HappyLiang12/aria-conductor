package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.persistence.AriaNotificationEntity;
import io.aria.conductor.aria.persistence.AriaNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * markRead paths, broadcast failure tolerance and create-overload wiring of
 * {@link NotificationService}; NotificationServiceTest only covers the
 * create/count/markAllRead/list happy paths.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceEdgeCasesTest {

    @Mock private AriaNotificationRepository notificationRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private NotificationService service;

    private AriaNotificationEntity unreadEntity(String id) {
        AriaNotificationEntity entity = new AriaNotificationEntity();
        entity.setId(id);
        entity.setType("info");
        entity.setTitle("Deploy finished");
        entity.setBody("All green");
        entity.setResourceType("RUN");
        entity.setResourceId("run-1");
        entity.setRead(false);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    @Test
    void markRead_flipsFlagPersistsAndReturnsDto() {
        AriaNotificationEntity entity = unreadEntity("n-1");
        when(notificationRepository.findById("n-1")).thenReturn(Optional.of(entity));

        NotificationDto dto = service.markRead("n-1");

        ArgumentCaptor<AriaNotificationEntity> captor =
                ArgumentCaptor.forClass(AriaNotificationEntity.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().isRead()).isTrue();
        assertThat(dto.id()).isEqualTo("n-1");
        assertThat(dto.isRead()).isTrue();
        assertThat(dto.title()).isEqualTo("Deploy finished");
    }

    @Test
    void markRead_unknownIdThrowsWithIdInMessage() {
        when(notificationRepository.findById("n-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead("n-404"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Notification not found: n-404");
    }

    @Test
    void create_stillReturnsDtoWhenBroadcastFails() {
        when(notificationRepository.save(any(AriaNotificationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate).convertAndSend(eq("/topic/events"), any(Object.class));

        NotificationDto dto = service.create("warning", "Broker gone", "body", "RUN", "run-1");

        // broadcast failure must be swallowed; persistence result is still returned
        assertThat(dto.type()).isEqualTo("warning");
        assertThat(dto.title()).isEqualTo("Broker gone");
        verify(notificationRepository).save(any(AriaNotificationEntity.class));
    }

    @Test
    void create_sevenArgOverloadPersistsJobIdAndUserId() {
        when(notificationRepository.save(any(AriaNotificationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NotificationDto dto = service.create(
                "reminder", "Standup", "Daily standup", "JOB", "job-1", "job-1", "user-9");

        ArgumentCaptor<AriaNotificationEntity> captor =
                ArgumentCaptor.forClass(AriaNotificationEntity.class);
        verify(notificationRepository).save(captor.capture());
        AriaNotificationEntity saved = captor.getValue();
        assertThat(saved.getJobId()).isEqualTo("job-1");
        assertThat(saved.getUserId()).isEqualTo("user-9");
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.isRead()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(dto.jobId()).isEqualTo("job-1");
    }

    @Test
    void create_fiveArgOverloadDefaultsJobIdAndUserIdToNull() {
        when(notificationRepository.save(any(AriaNotificationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.create("info", "T", "B", "RUN", "run-1");

        ArgumentCaptor<AriaNotificationEntity> captor =
                ArgumentCaptor.forClass(AriaNotificationEntity.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getJobId()).isNull();
        assertThat(captor.getValue().getUserId()).isNull();
    }
}
