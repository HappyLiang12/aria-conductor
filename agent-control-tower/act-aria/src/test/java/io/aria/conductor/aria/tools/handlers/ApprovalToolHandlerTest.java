package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.AcpDecisionRejectedException;
import io.aria.conductor.execution.approval.ApprovalDecisionService;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalToolHandlerTest {
    @Mock private ApprovalDecisionService approvalDecisionService;
    @Mock private ApprovalRepository approvalRepository;
    private ApprovalToolHandler handler;

    @BeforeEach
    void setUp() { handler = new ApprovalToolHandler(approvalDecisionService, approvalRepository); }

    @Test void listPendingShouldReturnText() {
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());
        String result = handler.execute(Map.of("toolName","list_pending_approvals"));
        assertThat(result).contains("No pending approvals");
    }

    @Test void decideApproveShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","approve"));
        verify(approvalDecisionService).decide(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideApprovedVariantShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","approved"));
        verify(approvalDecisionService).decide(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideYesShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","yes"));
        verify(approvalDecisionService).decide(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideTrueShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","true"));
        verify(approvalDecisionService).decide(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideDenyShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","deny"));
        verify(approvalDecisionService).decide(id, false, "");
        assertThat(result).contains("denied");
    }

    /** R20.3: the tool error carries the typed rejection's code and message. */
    @Test void decideAcpRejectionReportsTheCodeAndTheMessage() {
        UUID id = UUID.randomUUID();
        doThrow(new AcpDecisionRejectedException(AcpDecisionRejectedException.Code.EXPIRED,
                "ask expired before decision"))
                .when(approvalDecisionService).decide(id, true, "");
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","approve"));
        assertThat(result).isEqualTo("Error: EXPIRED: ask expired before decision");
    }

    @Test void decideOnReviewRequestAskIsRejectedForAgents() {
        // HITL governance: the kanban Review column is the human entry point, so a
        // REVIEW_REQUEST ask (even typed TOOL_CALL as the generic category) must be
        // decided by a person, not by an agent.
        UUID id = UUID.randomUUID();
        when(approvalRepository.findById(id)).thenReturn(Optional.of(Approval.builder()
                .id(id).runId(UUID.randomUUID()).status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .askType(Approval.AskType.REVIEW_REQUEST).build()));
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","approve"));
        assertThat(result).contains("human");
        verify(approvalDecisionService, never()).decide(any(), anyBoolean(), any());
    }
}
