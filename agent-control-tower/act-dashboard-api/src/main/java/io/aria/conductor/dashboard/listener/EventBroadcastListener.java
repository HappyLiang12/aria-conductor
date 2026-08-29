package io.aria.conductor.dashboard.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.AgentCreatedEvent;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.HousekeepingProgressEvent;
import io.aria.conductor.common.event.KanbanItemCreatedEvent;
import io.aria.conductor.common.event.KanbanItemTransitionedEvent;
import io.aria.conductor.common.event.KnowledgeApprovedEvent;
import io.aria.conductor.common.event.KnowledgeRetiredEvent;
import io.aria.conductor.common.event.KnowledgeSubmittedEvent;
import io.aria.conductor.common.event.ReportAmendedEvent;
import io.aria.conductor.common.event.ReportGeneratedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.dashboard.dto.WsBroadcastEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class EventBroadcastListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public EventBroadcastListener(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onAgentCreated(AgentCreatedEvent event) {
        broadcast("agent.created", Map.of(
                "agentId", event.getAgentId().toString(),
                "name", event.getName(),
                "type", event.getType()
        ));
    }

    @EventListener
    public void onRunStarted(RunStartedEvent event) {
        broadcast("run.started", Map.of(
                "runId", event.getRunId().toString(),
                "agentId", event.getAgentId().toString()
        ));
    }

    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("runId", event.getRunId().toString());
        data.put("agentId", event.getAgentId().toString());
        data.put("status", event.getStatus().name());
        if (event.getFinalOutput() != null) {
            // Truncate to 500 chars for WS broadcast (full result available via API)
            String output = event.getFinalOutput();
            data.put("finalOutput", output.length() > 500 ? output.substring(0, 500) + "..." : output);
        }
        broadcast("run.completed", data);
    }

    @EventListener
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("approvalId", event.getApprovalId().toString());
        data.put("runId", event.getRunId().toString());
        // SPEC_REVIEW approvals have no tool call; carry the type instead.
        data.put("toolCallId", event.getToolCallId() != null ? event.getToolCallId().toString() : null);
        data.put("approvalType", event.getApprovalType() != null ? event.getApprovalType() : "TOOL_CALL");
        broadcast("approval.requested", data);
    }

    @EventListener
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        broadcast("approval.decided", Map.of(
                "approvalId", event.getApprovalId().toString(),
                "decision", event.getDecision().name()
        ));
    }

    @EventListener
    public void onKnowledgeSubmitted(KnowledgeSubmittedEvent event) {
        broadcast("knowledge.submitted", Map.of(
                "itemId", event.getItemId().toString(),
                "type", event.getType(),
                "name", event.getName()
        ));
    }

    @EventListener
    public void onHousekeepingProgress(HousekeepingProgressEvent event) {
        broadcast("housekeeping.progress", Map.of(
                "category", event.getCategory(),
                "cleared", event.getCleared(),
                "failed", event.getFailed(),
                "seq", event.getSeq()
        ));
    }

    @EventListener
    public void onAuditLog(AuditLogEvent event) {
        broadcast("audit." + event.getEventType(), Map.of(
                "eventType", event.getEventType(),
                "resourceType", event.getResourceType(),
                "resourceId", event.getResourceId(),
                "action", event.getAction(),
                "details", event.getDetails() != null ? event.getDetails() : "",
                "conversationId", event.getConversationId() != null ? event.getConversationId() : ""
        ));
    }

    @EventListener
    public void onReportGenerated(ReportGeneratedEvent event) {
        broadcast("report.generated", Map.of(
                "reportId", event.getReportId(),
                "title", event.getTitle(),
                "owner", event.getOwner() != null ? event.getOwner() : ""
        ));
    }

    @EventListener
    public void onReportAmended(ReportAmendedEvent event) {
        broadcast("report.amended", Map.of(
                "reportId", event.getReportId(),
                "instruction", event.getInstruction()
        ));
    }

    @EventListener
    public void onKnowledgeApproved(KnowledgeApprovedEvent event) {
        broadcast("knowledge.approved", Map.of(
                "knowledgeId", event.getKnowledgeId().toString(),
                "name", event.getName(),
                "type", event.getType()
        ));
    }

    @EventListener
    public void onKnowledgeRetired(KnowledgeRetiredEvent event) {
        broadcast("knowledge.retired", Map.of(
                "knowledgeId", event.getKnowledgeId().toString(),
                "name", event.getName()
        ));
    }

    @EventListener
    public void onRunProgress(RunProgressEvent event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", event.getRunId().toString());
        payload.put("agentId", event.getAgentId().toString());
        payload.put("iteration", event.getIteration());
        payload.put("kind", event.getKind().name());
        payload.put("seq", event.getSeq());
        if (event.getToolName() != null) {
            payload.put("toolName", event.getToolName());
        }
        String content = event.getContent();
        if (content != null && content.length() > 500) {
            content = content.substring(0, 500) + "...";
        }
        payload.put("content", content);
        broadcast("run.progress", payload);
    }

    @EventListener
    public void onRunIteration(RunIterationEvent event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", event.getRunId().toString());
        payload.put("agentId", event.getAgentId().toString());
        payload.put("iteration", event.getIteration());
        payload.put("maxIterations", event.getMaxIterations());
        if (event.getThinking() != null) {
            String truncated = event.getThinking().length() > 500
                    ? event.getThinking().substring(0, 500) + "..." : event.getThinking();
            payload.put("thinking", truncated);
        }
        if (!event.getToolCalls().isEmpty()) {
            payload.put("toolCalls", event.getToolCalls().stream().map(tc -> Map.of(
                    "name", tc.name(),
                    "arguments", tc.arguments() != null && tc.arguments().length() > 500
                            ? tc.arguments().substring(0, 500) + "..."
                            : tc.arguments() != null ? tc.arguments() : "",
                    "result", tc.result() != null && tc.result().length() > 500
                            ? tc.result().substring(0, 500) + "..." : tc.result() != null ? tc.result() : ""
            )).toList());
        }
        if (!event.getSkills().isEmpty()) {
            payload.put("skills", event.getSkills());
        }
        broadcast("run.iteration", payload);
    }

    @EventListener
    public void onKanbanItemCreated(KanbanItemCreatedEvent event) {
        broadcast("kanban.created", Map.of(
                "itemId", event.getItemId(),
                "title", event.getTitle(),
                "priority", event.getPriority()
        ));
    }

    @EventListener
    public void onKanbanItemTransitioned(KanbanItemTransitionedEvent event) {
        broadcast("kanban.transitioned", Map.of(
                "itemId", event.getItemId(),
                "fromStatus", event.getFromStatus(),
                "toStatus", event.getToStatus()
        ));
    }

    @EventListener
    public void onWorkflowAdvanced(WorkflowAdvancedEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflowId", event.getWorkflowId().toString());
        data.put("workflowName", event.getWorkflowName());
        data.put("completedStep", event.getCompletedStep());
        data.put("chainStatus", event.getChainStatus().name());
        if (event.getNextStep() >= 0) {
            data.put("nextStep", event.getNextStep());
        }
        broadcast("workflow.advanced", data);
    }

    private void broadcast(String type, Map<String, Object> data) {
        try {
            WsBroadcastEvent wsEvent = new WsBroadcastEvent(type, data, Instant.now().toString());
            messagingTemplate.convertAndSend("/topic/events", wsEvent);
        } catch (Exception e) {
            System.err.println("[WS Broadcast] Failed to send event: " + e.getMessage());
        }
    }
}