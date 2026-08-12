package io.aria.conductor.dashboard.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.AgentCreatedEvent;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.common.event.KnowledgeRetiredEvent;
import io.aria.conductor.common.event.KnowledgeSubmittedEvent;
import io.aria.conductor.common.event.ReportAmendedEvent;
import io.aria.conductor.common.event.ReportGeneratedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.dashboard.dto.WsBroadcastEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies that {@link EventBroadcastListener} maps each domain event to a {@link WsBroadcastEvent}
 * with the correct type and payload, published to the {@code /topic/events} STOMP destination via a
 * mocked {@link SimpMessagingTemplate}. Also covers the truncation, null-defaulting and conditional
 * payload branches, plus the swallow-on-failure behaviour of {@code broadcast}.
 */
class EventBroadcastListenerTest {

    private SimpMessagingTemplate messagingTemplate;
    private EventBroadcastListener listener;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        listener = new EventBroadcastListener(messagingTemplate, new ObjectMapper());
    }

    private WsBroadcastEvent captureBroadcast() {
        ArgumentCaptor<WsBroadcastEvent> captor = ArgumentCaptor.forClass(WsBroadcastEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/events"), captor.capture());
        WsBroadcastEvent event = captor.getValue();
        assertThat(event.timestamp()).isNotBlank();
        return event;
    }

    @Test
    void onAgentCreated_broadcastsAgentCreated() {
        UUID agentId = UUID.randomUUID();
        listener.onAgentCreated(new AgentCreatedEvent(this, agentId, "Scout", "WORKER"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("agent.created");
        assertThat(event.data()).containsEntry("agentId", agentId.toString())
                .containsEntry("name", "Scout")
                .containsEntry("type", "WORKER");
    }

    @Test
    void onRunStarted_broadcastsRunStarted() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        listener.onRunStarted(new RunStartedEvent(this, runId, agentId));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("run.started");
        assertThat(event.data()).containsEntry("runId", runId.toString())
                .containsEntry("agentId", agentId.toString());
    }

    @Test
    void onRunCompleted_includesStatusAndFinalOutput() {
        UUID runId = UUID.randomUUID();
        listener.onRunCompleted(new RunCompletedEvent(this, runId, UUID.randomUUID(),
                RunStatus.COMPLETED, "done"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("run.completed");
        assertThat(event.data()).containsEntry("status", "COMPLETED")
                .containsEntry("finalOutput", "done");
    }

    @Test
    void onRunCompleted_truncatesLongFinalOutput() {
        String longOutput = "x".repeat(600);
        listener.onRunCompleted(new RunCompletedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
                RunStatus.COMPLETED, longOutput));

        WsBroadcastEvent event = captureBroadcast();
        String finalOutput = (String) event.data().get("finalOutput");
        assertThat(finalOutput).hasSize(503).endsWith("...");
    }

    @Test
    void onRunCompleted_omitsFinalOutputWhenNull() {
        listener.onRunCompleted(new RunCompletedEvent(this, UUID.randomUUID(), UUID.randomUUID(),
                RunStatus.FAILED));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.data()).doesNotContainKey("finalOutput");
        assertThat(event.data()).containsEntry("status", "FAILED");
    }

    @Test
    void onApprovalRequested_broadcastsIds() {
        UUID approvalId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID toolCallId = UUID.randomUUID();
        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, toolCallId));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("approval.requested");
        assertThat(event.data()).containsEntry("approvalId", approvalId.toString())
                .containsEntry("runId", runId.toString())
                .containsEntry("toolCallId", toolCallId.toString())
                .containsEntry("approvalType", "TOOL_CALL");
    }

    @Test
    void onApprovalRequested_nullToolCallId_specReview_noNpe() {
        UUID approvalId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, null, "SPEC_REVIEW"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("approval.requested");
        assertThat(event.data()).containsEntry("toolCallId", null)
                .containsEntry("approvalType", "SPEC_REVIEW");
    }

    @Test
    void onApprovalDecided_broadcastsDecisionName() {
        UUID approvalId = UUID.randomUUID();
        listener.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("approval.decided");
        assertThat(event.data()).containsEntry("approvalId", approvalId.toString())
                .containsEntry("decision", "APPROVED");
    }

    @Test
    void onKnowledgeSubmitted_broadcastsItem() {
        UUID itemId = UUID.randomUUID();
        listener.onKnowledgeSubmitted(new KnowledgeSubmittedEvent(this, itemId, "SKILL", "Recon"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("knowledge.submitted");
        assertThat(event.data()).containsEntry("itemId", itemId.toString())
                .containsEntry("type", "SKILL")
                .containsEntry("name", "Recon");
    }

    @Test
    void onAuditLog_typeIsPrefixedWithEventType() {
        listener.onAuditLog(new AuditLogEvent(this, "agent.updated", "Agent", "a-1",
                "UPDATE", "changed model", "conv-9"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("audit.agent.updated");
        assertThat(event.data()).containsEntry("eventType", "agent.updated")
                .containsEntry("resourceType", "Agent")
                .containsEntry("resourceId", "a-1")
                .containsEntry("action", "UPDATE")
                .containsEntry("details", "changed model")
                .containsEntry("conversationId", "conv-9");
    }

    @Test
    void onAuditLog_nullDetailsAndConversation_defaultToEmptyStrings() {
        listener.onAuditLog(new AuditLogEvent(this, "run.deleted", "Run", "r-1",
                "DELETE", null, null));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.data()).containsEntry("details", "")
                .containsEntry("conversationId", "");
    }

    @Test
    void onReportGenerated_broadcastsMetadata() {
        listener.onReportGenerated(new ReportGeneratedEvent(this, "rpt-1", "Weekly", "alice"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("report.generated");
        assertThat(event.data()).containsEntry("reportId", "rpt-1")
                .containsEntry("title", "Weekly")
                .containsEntry("owner", "alice");
    }

    @Test
    void onReportGenerated_nullOwnerDefaultsToEmpty() {
        listener.onReportGenerated(new ReportGeneratedEvent(this, "rpt-2", "Ad-hoc", null));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.data()).containsEntry("owner", "");
    }

    @Test
    void onReportAmended_broadcastsInstruction() {
        listener.onReportAmended(new ReportAmendedEvent(this, "rpt-1", "add section"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("report.amended");
        assertThat(event.data()).containsEntry("reportId", "rpt-1")
                .containsEntry("instruction", "add section");
    }

    @Test
    void onKnowledgeApproved_broadcastsItem() {
        UUID id = UUID.randomUUID();
        listener.onKnowledgeApproved(new KnowledgeApprovedEvent(this, id, "Playbook", "PROCEDURE"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("knowledge.approved");
        assertThat(event.data()).containsEntry("knowledgeId", id.toString())
                .containsEntry("name", "Playbook")
                .containsEntry("type", "PROCEDURE");
    }

    @Test
    void onKnowledgeRetired_broadcastsItem() {
        UUID id = UUID.randomUUID();
        listener.onKnowledgeRetired(new KnowledgeRetiredEvent(this, id, "Old Skill"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("knowledge.retired");
        assertThat(event.data()).containsEntry("knowledgeId", id.toString())
                .containsEntry("name", "Old Skill");
    }

    @Test
    void onKanbanItemCreated_broadcastsItem() {
        listener.onKanbanItemCreated(new KanbanItemCreatedEvent(this, "k-1", "Fix bug", "HIGH"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("kanban.created");
        assertThat(event.data()).containsEntry("itemId", "k-1")
                .containsEntry("title", "Fix bug")
                .containsEntry("priority", "HIGH");
    }

    @Test
    void onKanbanItemTransitioned_broadcastsTransition() {
        listener.onKanbanItemTransitioned(new KanbanItemTransitionedEvent(this, "k-1", "TODO", "DOING"));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("kanban.transitioned");
        assertThat(event.data()).containsEntry("itemId", "k-1")
                .containsEntry("fromStatus", "TODO")
                .containsEntry("toStatus", "DOING");
    }

    @Test
    void onWorkflowAdvanced_includesNextStepWhenNonNegative() {
        UUID id = UUID.randomUUID();
        listener.onWorkflowAdvanced(new WorkflowAdvancedEvent(this, id, "Pipeline", 1, 2,
                WorkflowChain.Status.RUNNING));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("workflow.advanced");
        assertThat(event.data()).containsEntry("workflowId", id.toString())
                .containsEntry("workflowName", "Pipeline")
                .containsEntry("completedStep", 1)
                .containsEntry("chainStatus", "RUNNING")
                .containsEntry("nextStep", 2);
    }

    @Test
    void onWorkflowAdvanced_omitsNextStepWhenNegative() {
        listener.onWorkflowAdvanced(new WorkflowAdvancedEvent(this, UUID.randomUUID(), "Pipeline", 3, -1,
                WorkflowChain.Status.COMPLETED));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.data()).doesNotContainKey("nextStep");
        assertThat(event.data()).containsEntry("chainStatus", "COMPLETED");
    }

    @Test
    void onRunIteration_minimalEvent_containsCoreFieldsOnly() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        listener.onRunIteration(new RunIterationEvent(this, runId, agentId, 1, 10));

        WsBroadcastEvent event = captureBroadcast();
        assertThat(event.type()).isEqualTo("run.iteration");
        assertThat(event.data()).containsEntry("runId", runId.toString())
                .containsEntry("agentId", agentId.toString())
                .containsEntry("iteration", 1)
                .containsEntry("maxIterations", 10);
        assertThat(event.data()).doesNotContainKeys("thinking", "toolCalls", "skills");
    }

    @Test
    void onRunIteration_truncatesThinkingAndIncludesToolCallsAndSkills() {
        String longThinking = "t".repeat(600);
        RunIterationEvent.ToolCallDetail detail =
                new RunIterationEvent.ToolCallDetail("web_search", "a".repeat(600), "r".repeat(600));
        RunIterationEvent iterationEvent = new RunIterationEvent(this, UUID.randomUUID(), UUID.randomUUID(),
                2, 5, longThinking, List.of(detail), List.of("research"));

        listener.onRunIteration(iterationEvent);

        WsBroadcastEvent event = captureBroadcast();
        assertThat((String) event.data().get("thinking")).hasSize(503).endsWith("...");
        assertThat(event.data()).containsEntry("skills", List.of("research"));

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> toolCalls =
                (List<java.util.Map<String, Object>>) event.data().get("toolCalls");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0)).containsEntry("name", "web_search");
        assertThat((String) toolCalls.get(0).get("arguments")).hasSize(503).endsWith("...");
        assertThat((String) toolCalls.get(0).get("result")).hasSize(503).endsWith("...");
    }

    @Test
    void broadcast_swallowsMessagingFailure() {
        doThrow(new RuntimeException("broker down"))
                .when(messagingTemplate).convertAndSend(eq("/topic/events"), (Object) org.mockito.ArgumentMatchers.any());

        assertThatCode(() -> listener.onRunStarted(
                new RunStartedEvent(this, UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}
