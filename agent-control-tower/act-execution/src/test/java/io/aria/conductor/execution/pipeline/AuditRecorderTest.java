package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.execution.engine.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Verifies the audit event payload emitted by {@link AuditRecorder} for every
 * verdict combination of the 6-stage pipeline.
 *
 * <p>Key invariants pinned here:
 * <ul>
 *   <li>{@code result.status()} maps to the correct {@code eventType} string.</li>
 *   <li>The audit {@code resourceId} is the tool-call id when present, else the action name.</li>
 *   <li>The {@code details} string carries run/agent/conversation ids plus the AI verdict
 *       (SKIPPED when no AI result was produced) and the (truncated) output.</li>
 *   <li>The event {@code conversationId} is passed through verbatim (nullable), while the
 *       details render "N/A" for a null conversation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditRecorderTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    private RunContext ctx(String conversationId) {
        return new RunContext(runId, agentId, null, null, 50, conversationId);
    }

    private AuditLogEvent capture() {
        ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @ParameterizedTest(name = "{0} result -> eventType {1}")
    @CsvSource({
            "SUCCESS,ACTION_EXECUTED",
            "BLOCKED,ACTION_BLOCKED",
            "DENIED,ACTION_DENIED",
            "FAILED,ACTION_FAILED"
    })
    void record_mapsResultStatusToEventType(ActionResult.Status status, String expectedEventType) {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("do_thing", ActionType.WRITE, "{}", "tc-42");
        ActionResult result = switch (status) {
            case SUCCESS -> ActionResult.success("done");
            case BLOCKED -> ActionResult.blocked("nope");
            case DENIED -> ActionResult.denied("denied");
            case FAILED -> ActionResult.failed("boom");
        };

        recorder.record(action, ActionClassification.mediumRisk("WRITE"), result, ctx("conv-1"));

        AuditLogEvent event = capture();
        assertThat(event.getEventType()).isEqualTo(expectedEventType);
        assertThat(event.getResourceType()).isEqualTo("Action");
        assertThat(event.getAction()).isEqualTo("do_thing");
    }

    @Test
    void record_usesToolCallIdAsResourceId_whenPresent() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("write_file", ActionType.WRITE, "{}", "tool-call-99");

        recorder.record(action, ActionClassification.mediumRisk("WRITE"), ActionResult.success("ok"), ctx("c"));

        assertThat(capture().getResourceId()).isEqualTo("tool-call-99");
    }

    @Test
    void record_fallsBackToActionNameAsResourceId_whenToolCallIdNull() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("write_file", ActionType.WRITE, "{}", null);

        recorder.record(action, ActionClassification.mediumRisk("WRITE"), ActionResult.success("ok"), ctx("c"));

        assertThat(capture().getResourceId()).isEqualTo("write_file");
    }

    @Test
    void record_detailsCarryContextRiskAndOutput() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("drop_table", ActionType.HIGH_RISK, "{\"t\":1}", "tc-1");

        recorder.record(action, ActionClassification.highRisk("DESTRUCTIVE"),
                AiVerificationResult.warn("borderline", 0.6), ActionResult.success("rows=3"), ctx("conv-x"));

        String details = capture().getDetails();
        assertThat(details)
                .contains("runId=" + runId)
                .contains("agentId=" + agentId)
                .contains("conversationId=conv-x")
                .contains("action=drop_table")
                .contains("type=HIGH_RISK")
                .contains("risk=HIGH")
                .contains("ai=WARN")
                .contains("result=SUCCESS")
                .contains("output=rows=3");
    }

    @Test
    void record_aiOutcomeIsSkipped_whenAiResultNull() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("read_file", ActionType.READ, "{}", "tc-1");

        // The 3-arg overload passes a null AiVerificationResult.
        recorder.record(action, ActionClassification.lowRisk("READ"), ActionResult.success("data"), ctx("c"));

        assertThat(capture().getDetails()).contains("ai=SKIPPED");
    }

    @Test
    void record_conversationIdRendersNA_whenNull_butEventConversationIdIsNull() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("read_file", ActionType.READ, "{}", "tc-1");

        recorder.record(action, ActionClassification.lowRisk("READ"), ActionResult.success("d"), ctx(null));

        AuditLogEvent event = capture();
        assertThat(event.getDetails()).contains("conversationId=N/A");
        assertThat(event.getConversationId()).isNull();
    }

    @Test
    void record_usesErrorTextForOutput_whenOutputNull() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("write_file", ActionType.WRITE, "{}", "tc-1");

        // blocked() carries the reason in error() and leaves output() null.
        recorder.record(action, ActionClassification.mediumRisk("WRITE"),
                ActionResult.blocked("budget exhausted"), ctx("c"));

        assertThat(capture().getDetails()).contains("output=budget exhausted");
    }

    @Test
    void record_truncatesLongOutputTo300CharsWithEllipsis() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("read_file", ActionType.READ, "{}", "tc-1");
        String longOutput = "x".repeat(500);

        recorder.record(action, ActionClassification.lowRisk("READ"),
                ActionResult.success(longOutput), ctx("c"));

        String details = capture().getDetails();
        // 300 kept chars + ellipsis marker; the full 500 must not appear.
        assertThat(details).contains("output=" + "x".repeat(300) + "...");
        assertThat(details).doesNotContain("x".repeat(301));
    }

    @Test
    void record_rendersAiOutcomeName_forEscalate() {
        AuditRecorder recorder = new AuditRecorder(eventPublisher);
        Action action = new Action("write_config", ActionType.WRITE, "{}", "tc-1");

        recorder.record(action, ActionClassification.mediumRisk("WRITE"),
                AiVerificationResult.escalate("needs human", 0.9), ActionResult.denied("nope"), ctx("c"));

        assertThat(capture().getDetails()).contains("ai=ESCALATE").contains("result=DENIED");
    }
}
