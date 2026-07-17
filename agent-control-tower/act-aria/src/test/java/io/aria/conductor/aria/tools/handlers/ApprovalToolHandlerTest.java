package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
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
    @Mock private ApprovalGate approvalGate;
    @Mock private ApprovalRepository approvalRepository;
    private ApprovalToolHandler handler;

    @BeforeEach
    void setUp() { handler = new ApprovalToolHandler(approvalGate, approvalRepository); }

    @Test void listPendingShouldReturnText() {
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());
        String result = handler.execute(Map.of("toolName","list_pending_approvals"));
        assertThat(result).contains("No pending approvals");
    }

    @Test void decideApproveShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","approve"));
        verify(approvalGate).decideApproval(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideApprovedVariantShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","approved"));
        verify(approvalGate).decideApproval(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideYesShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","yes"));
        verify(approvalGate).decideApproval(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideTrueShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","true"));
        verify(approvalGate).decideApproval(id, true, "");
        assertThat(result).contains("approved");
    }

    @Test void decideDenyShouldWork() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","decide_approval","id",id.toString(),"decision","deny"));
        verify(approvalGate).decideApproval(id, false, "");
        assertThat(result).contains("denied");
    }
}
