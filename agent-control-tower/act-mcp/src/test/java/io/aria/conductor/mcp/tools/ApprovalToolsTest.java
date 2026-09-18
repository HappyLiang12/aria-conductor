package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.controller.ApprovalController.ApprovalDetail;
import io.aria.conductor.execution.approval.AcpDecisionRejectedException;
import io.aria.conductor.execution.approval.ApprovalDecisionService;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalToolsTest {

    @Mock ApprovalDecisionService approvalDecisionService;
    @Mock ApprovalQueryService approvalQueryService;
    McpProperties mcpProperties;
    ApprovalTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ApprovalTools(approvalQueryService, approvalDecisionService, mcpProperties);
    }

    private ApprovalDetail detail(UUID id, String type, String status) {
        return new ApprovalDetail(id, UUID.randomUUID(), null, ApprovalStatus.valueOf(status),
                "Spec resubmitted", Instant.now(), null, Instant.now().plusSeconds(1800),
                type, "## spec", "MARKDOWN", UUID.randomUUID(), null, null, null,
                null, null, null, null, null,
                // V60 fields: source/deliveryState/displayJson — legacy fixture, no ACP companion
                null, null, null);
    }

    @Test
    void listApprovals_filtersPendingSpecReviews() {
        UUID id = UUID.randomUUID();
        when(approvalQueryService.list(ApprovalStatus.PENDING))
                .thenReturn(List.of(detail(id, "SPEC_REVIEW", "PENDING")));

        String json = tools.listApprovals("PENDING");

        assertThat(json).contains("SPEC_REVIEW").contains(id.toString());
    }

    @Test
    void decideApproval_delegates() {
        UUID id = UUID.randomUUID();

        String json = tools.decideApproval(id, true, "lgtm");

        verify(approvalDecisionService).decide(id, true, "lgtm");
        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void decideApproval_mapsIllegalArgument() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("Approval not found: " + id))
                .when(approvalDecisionService).decide(id, false, "nope");

        String json = tools.decideApproval(id, false, "nope");

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    /** R20.2: a typed ACP rejection surfaces as the tool error type of its code. */
    @Test
    void decideApproval_mapsTypedAcpRejectionWithItsCode() {
        UUID id = UUID.randomUUID();
        doThrow(new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.EXPIRED,
                "ask expired before decision"))
                .when(approvalDecisionService).decide(id, true, "late");

        String json = tools.decideApproval(id, true, "late");

        assertThat(json).contains("\"errorType\":\"EXPIRED\"")
                .contains("\"message\":\"ask expired before decision\"");
    }
}
