package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByResourceTypeAndResourceId(String resourceType, String resourceId);
    List<AuditEvent> findByEventType(String eventType);
    List<AuditEvent> findTop20ByOrderByCreatedAtDesc();
    void deleteByConversationId(String conversationId);
}
