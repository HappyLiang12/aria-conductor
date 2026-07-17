package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.execution.engine.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Records action executions as audit events.
 */
@Slf4j
@Component
public class AuditRecorder {

    private final ApplicationEventPublisher eventPublisher;

    public AuditRecorder(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void record(Action action, ActionClassification classification, ActionResult result, RunContext ctx) {
        record(action, classification, null, result, ctx);
    }

    /**
     * Stage-6 audit overload that also captures the AI verification verdict so the
     * audit trail covers the full 6-stage pipeline (classify → rules → AI → approval
     * → execute+shadow → audit).
     */
    public void record(Action action,
                       ActionClassification classification,
                       AiVerificationResult aiResult,
                       ActionResult result,
                       RunContext ctx) {
        String eventType = switch (result.status()) {
            case SUCCESS -> "ACTION_EXECUTED";
            case BLOCKED -> "ACTION_BLOCKED";
            case DENIED -> "ACTION_DENIED";
            case FAILED -> "ACTION_FAILED";
        };

        String aiOutcome = aiResult != null && aiResult.outcome() != null
                ? aiResult.outcome().name() : "SKIPPED";
        String details = String.format(
                "runId=%s, agentId=%s, conversationId=%s, action=%s, type=%s, risk=%s, ai=%s, result=%s, output=%s",
                ctx.getRunId(), ctx.getAgentId(),
                ctx.getConversationId() != null ? ctx.getConversationId() : "N/A",
                action.name(), action.type(),
                classification.riskLevel(), aiOutcome, result.status(),
                truncate(result.output() != null ? result.output() : result.error(), 300));

        log.info("Audit: {} — {}", eventType, details);

        eventPublisher.publishEvent(new AuditLogEvent(
                this,
                eventType,
                "Action",
                action.toolCallId() != null ? action.toolCallId() : action.name(),
                action.name(),
                details,
                ctx.getConversationId()
        ));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}