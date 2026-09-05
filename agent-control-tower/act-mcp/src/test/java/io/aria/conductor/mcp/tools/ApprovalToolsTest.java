package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.controller.ApprovalController.ApprovalDetail;
import io.aria.conductor.execution.approval.ApprovalGate;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalToolsTest {

    @Mock ApprovalGate approvalGate;
    @Mock ApprovalQueryService approvalQueryService;
    McpProperties mcpProperties;
    ApprovalTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ApprovalTools(approvalQueryService, approvalGate, mcpProperties);
    }

    private ApprovalDetail detail(UUID id, String type, String status) {
        return new ApprovalDetail(id, UUID.randomUUID(), null, ApprovalStatus.valueOf(status),
                "Spec resubmitted", Instant.now(), null, Instant.now().plusSeconds(1800),
                type, "## spec", "MARKDOWN", UUID.randomUUID(), null, null, null);
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

        verify(approvalGate).decideApproval(eq(id), anyBoolean(), eq("lgtm"));
        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void decideApproval_mapsIllegalArgument() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("Approval not found: " + id))
                .when(approvalGate).decideApproval(eq(id), anyBoolean(), eq("nope"));

        String json = tools.decideApproval(id, false, "nope");

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
