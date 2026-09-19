package io.aria.conductor.common.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the approval {@code source} carried by {@link ApprovalRequestedEvent}. The two legacy
 * constructors must keep classifying their asks as {@code LEGACY_GATE} so every existing call site
 * stays untouched, while the full constructor carries the persisted {@code ApprovalSource} name
 * (ACP asks use {@code ACP_PERMISSION}) for listeners that need to tell the two apart.
 */
class ApprovalRequestedEventTest {

    private final UUID approvalId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID toolCallId = UUID.randomUUID();

    @Test
    void fourArgConstructor_defaultsTypeToToolCallAndSourceToLegacyGate() {
        ApprovalRequestedEvent event = new ApprovalRequestedEvent(this, approvalId, runId, toolCallId);

        assertThat(event.getApprovalId()).isEqualTo(approvalId);
        assertThat(event.getRunId()).isEqualTo(runId);
        assertThat(event.getToolCallId()).isEqualTo(toolCallId);
        assertThat(event.getApprovalType()).isEqualTo("TOOL_CALL");
        assertThat(event.getApprovalSource()).isEqualTo("LEGACY_GATE");
    }

    @Test
    void fiveArgConstructor_defaultsSourceToLegacyGate() {
        ApprovalRequestedEvent event =
                new ApprovalRequestedEvent(this, approvalId, runId, toolCallId, "SPEC_REVIEW");

        assertThat(event.getApprovalType()).isEqualTo("SPEC_REVIEW");
        assertThat(event.getApprovalSource()).isEqualTo("LEGACY_GATE");
    }

    @Test
    void fullConstructor_carriesTheAcpSourceAndAllowsANullToolCallId() {
        // ACP asks correlate on string tool-call ids, so the persisted Approval keeps toolCallId null.
        ApprovalRequestedEvent event =
                new ApprovalRequestedEvent(this, approvalId, runId, null, "TOOL_CALL", "ACP_PERMISSION");

        assertThat(event.getApprovalId()).isEqualTo(approvalId);
        assertThat(event.getToolCallId()).isNull();
        assertThat(event.getApprovalType()).isEqualTo("TOOL_CALL");
        assertThat(event.getApprovalSource()).isEqualTo("ACP_PERMISSION");
    }

    @Test
    void eventSource_remainsThePublisherObject() {
        ApprovalRequestedEvent event =
                new ApprovalRequestedEvent(this, approvalId, runId, null, "TOOL_CALL", "ACP_PERMISSION");

        // The framework's event-source accessor must keep returning the publisher; the approval
        // source travels under getApprovalSource() so the two never collide.
        assertThat(event.getSource()).isSameAs(this);
    }
}
