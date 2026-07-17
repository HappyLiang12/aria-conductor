package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.NotificationCountDto;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.persistence.AriaNotificationEntity;
import io.aria.conductor.aria.persistence.AriaNotificationRepository;
import io.aria.conductor.dashboard.dto.WsBroadcastEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class NotificationService {

    private final AriaNotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(AriaNotificationRepository notificationRepository,
                               SimpMessagingTemplate messagingTemplate) {
        this.notificationRepository = notificationRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public NotificationDto create(String type, String title, String body,
                                  String resourceType, String resourceId) {
        return create(type, title, body, resourceType, resourceId, null, null);
    }

    @Transactional
    public NotificationDto create(String type, String title, String body,
                                  String resourceType, String resourceId,
                                  String jobId) {
        return create(type, title, body, resourceType, resourceId, jobId, null);
    }

    @Transactional
    public NotificationDto create(String type, String title, String body,
                                  String resourceType, String resourceId,
                                  String jobId, String userId) {
        AriaNotificationEntity entity = new AriaNotificationEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setBody(body);
        entity.setResourceType(resourceType);
        entity.setResourceId(resourceId);
        entity.setJobId(jobId);
        entity.setRead(false);
        entity.setCreatedAt(Instant.now());
        AriaNotificationEntity saved = notificationRepository.save(entity);
        NotificationDto dto = toDto(saved);
        broadcast(dto);
        return dto;
    }

    public NotificationCountDto getUnreadCount() {
        return new NotificationCountDto(notificationRepository.countUnread());
    }

    @Transactional
    public void markAllRead() {
        notificationRepository.markAllRead();
    }

    @Transactional
    public NotificationDto markRead(String id) {
        AriaNotificationEntity entity = notificationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Notification not found: " + id));
        entity.setRead(true);
        notificationRepository.save(entity);
        return toDto(entity);
    }

    public Page<NotificationDto> list(int page, int size) {
        return notificationRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(this::toDto);
    }

    private NotificationDto toDto(AriaNotificationEntity entity) {
        return new NotificationDto(
                entity.getId(),
                entity.getType(),
                entity.getTitle(),
                entity.getBody(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getJobId(),
                entity.isRead(),
                entity.getCreatedAt());
    }

    private void broadcast(NotificationDto dto) {
        try {
            WsBroadcastEvent event = new WsBroadcastEvent(
                    "aria.notification",
                    Map.of(
                            "id", dto.id(),
                            "type", dto.type(),
                            "title", dto.title(),
                            "resourceType", dto.resourceType() != null ? dto.resourceType() : "",
                            "resourceId", dto.resourceId() != null ? dto.resourceId() : ""
                    ),
                    Instant.now().toString()
            );
            messagingTemplate.convertAndSend("/topic/events", event);
        } catch (Exception e) {
            log.warn("Failed to broadcast notification {}: {}", dto.id(), e.getMessage());
        }
    }
}
