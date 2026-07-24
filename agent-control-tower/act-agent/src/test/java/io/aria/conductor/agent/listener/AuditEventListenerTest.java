package io.aria.conductor.agent.listener;

import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.common.event.AgentCreatedEvent;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.AuditEvent;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Verifies that each domain event is translated into a persisted {@link AuditEvent} carrying the
 * correct eventType / resourceType / action taxonomy and a resourceId derived from the event.
 * The AuditEvent is captured so field-level assertions can kill mutants that swap the constant
 * strings or drop the id/detail wiring.
 */
@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock
    private AuditEventRepository repository;

    @InjectMocks
    private AuditEventListener listener;

    private AuditEvent captureSaved() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void onAgentCreated_persistsCreateAuditWithAgentTaxonomy() {
        UUID agentId = UUID.randomUUID();
        listener.onAgentCreated(new AgentCreatedEvent(this, agentId, "triage-bot", "ADK"));

        AuditEvent saved = captureSaved();
        assertThat(saved.getEventType()).isEqualTo("AGENT_CREATED");
        assertThat(saved.getResourceType()).isEqualTo("Agent");
        assertThat(saved.getAction()).isEqualTo("CREATE");
        assertThat(saved.getResourceId()).isEqualTo(agentId.toString());
        assertThat(saved.getDetails()).contains("triage-bot").contains("ADK");
    }

    @Test
    void onRunStarted_persistsStartAuditWithRunTaxonomy() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        listener.onRunStarted(new RunStartedEvent(this, runId, agentId));

        AuditEvent saved = captureSaved();
        assertThat(saved.getEventType()).isEqualTo("RUN_STARTED");
        assertThat(saved.getResourceType()).isEqualTo("Run");
        assertThat(saved.getAction()).isEqualTo("START");
        assertThat(saved.getResourceId()).isEqualTo(runId.toString());
        assertThat(saved.getDetails()).contains(agentId.toString());
    }

    @Test
    void onRunCompleted_persistsCompleteAuditWithStatusDetail() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        listener.onRunCompleted(new RunCompletedEvent(this, runId, agentId, RunStatus.COMPLETED));

        AuditEvent saved = captureSaved();
        assertThat(saved.getEventType()).isEqualTo("RUN_COMPLETED");
        assertThat(saved.getResourceType()).isEqualTo("Run");
        assertThat(saved.getAction()).isEqualTo("COMPLETE");
        assertThat(saved.getResourceId()).isEqualTo(runId.toString());
        assertThat(saved.getDetails()).contains("COMPLETED");
    }

    @Test
    void onRunCompleted_carriesFailedStatusThrough() {
        listener.onRunCompleted(new RunCompletedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
                RunStatus.FAILED));

        assertThat(captureSaved().getDetails()).contains("FAILED");
    }

    @Test
    void onAuditLog_passesThroughAllFieldsIncludingConversationId() {
        AuditLogEvent event = new AuditLogEvent(this, "TOOL_CALL", "Tool", "read_file",
                "INVOKE", "invoked read_file", "conv-123");
        listener.onAuditLog(event);

        AuditEvent saved = captureSaved();
        assertThat(saved.getEventType()).isEqualTo("TOOL_CALL");
        assertThat(saved.getResourceType()).isEqualTo("Tool");
        assertThat(saved.getResourceId()).isEqualTo("read_file");
        assertThat(saved.getAction()).isEqualTo("INVOKE");
        assertThat(saved.getDetails()).isEqualTo("invoked read_file");
        assertThat(saved.getConversationId()).isEqualTo("conv-123");
    }
}
