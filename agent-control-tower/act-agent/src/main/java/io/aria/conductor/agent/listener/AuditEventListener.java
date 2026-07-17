package io.aria.conductor.agent.listener;

import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.common.event.AgentCreatedEvent;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuditEventListener {

    private final AuditEventRepository auditEventRepository;

    public AuditEventListener(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @EventListener
    public void onAgentCreated(AgentCreatedEvent event) {
        log.info("Audit: Agent created - id={}, name={}, type={}", event.getAgentId(), event.getName(), event.getType());

        AuditEvent audit = AuditEvent.builder()
                .eventType("AGENT_CREATED")
                .resourceType("Agent")
                .resourceId(event.getAgentId().toString())
                .action("CREATE")
                .details(String.format("Agent '%s' of type %s created", event.getName(), event.getType()))
                .build();

        auditEventRepository.save(audit);
    }

    @EventListener
    public void onRunStarted(RunStartedEvent event) {
        log.info("Audit: Run started - runId={}, agentId={}", event.getRunId(), event.getAgentId());

        AuditEvent audit = AuditEvent.builder()
                .eventType("RUN_STARTED")
                .resourceType("Run")
                .resourceId(event.getRunId().toString())
                .action("START")
                .details(String.format("Run started for agent %s", event.getAgentId()))
                .build();

        auditEventRepository.save(audit);
    }

    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        log.info("Audit: Run completed - runId={}, status={}", event.getRunId(), event.getStatus());

        AuditEvent audit = AuditEvent.builder()
                .eventType("RUN_COMPLETED")
                .resourceType("Run")
                .resourceId(event.getRunId().toString())
                .action("COMPLETE")
                .details(String.format("Run completed with status: %s", event.getStatus()))
                .build();

        auditEventRepository.save(audit);
    }

    @EventListener
    public void onAuditLog(AuditLogEvent event) {
        AuditEvent audit = AuditEvent.builder()
                .eventType(event.getEventType())
                .resourceType(event.getResourceType())
                .resourceId(event.getResourceId())
                .action(event.getAction())
                .details(event.getDetails())
                .conversationId(event.getConversationId())
                .build();

        auditEventRepository.save(audit);
    }
}
