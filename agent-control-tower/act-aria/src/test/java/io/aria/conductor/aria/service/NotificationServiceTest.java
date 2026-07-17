package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.NotificationCountDto;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.persistence.AriaNotificationEntity;
import io.aria.conductor.aria.persistence.AriaNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock AriaNotificationRepository notificationRepository;
    @Mock SimpMessagingTemplate messagingTemplate;
    @InjectMocks NotificationService notificationService;

    @Test
    void create_persistsAndBroadcasts() {
        when(notificationRepository.save(any(AriaNotificationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NotificationDto result = notificationService.create(
                "run.completed", "Run done", "Details", "RUN", "run-123");

        assertThat(result.type()).isEqualTo("run.completed");
        assertThat(result.title()).isEqualTo("Run done");
        assertThat(result.resourceType()).isEqualTo("RUN");
        assertThat(result.resourceId()).isEqualTo("run-123");
        assertThat(result.isRead()).isFalse();
        verify(notificationRepository).save(any(AriaNotificationEntity.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/events"), any(Object.class));
    }

    @Test
    void getUnreadCount_returnsCount() {
        when(notificationRepository.countUnread()).thenReturn(5L);
        NotificationCountDto result = notificationService.getUnreadCount();
        assertThat(result.unreadCount()).isEqualTo(5L);
    }

    @Test
    void markAllRead_delegatesToRepository() {
        when(notificationRepository.markAllRead()).thenReturn(3);
        notificationService.markAllRead();
        verify(notificationRepository).markAllRead();
    }

    @Test
    void list_returnsPagedResults() {
        AriaNotificationEntity entity = new AriaNotificationEntity();
        entity.setId("n1"); entity.setType("run.completed"); entity.setTitle("T");
        Page<AriaNotificationEntity> page = new PageImpl<>(List.of(entity));
        when(notificationRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20)))
                .thenReturn(page);

        Page<NotificationDto> result = notificationService.list(0, 20);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo("n1");
    }
}
