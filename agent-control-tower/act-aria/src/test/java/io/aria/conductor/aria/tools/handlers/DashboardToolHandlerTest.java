package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardToolHandlerTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private RunRepository runRepository;
    @Mock private KnowledgeItemRepository knowledgeItemRepository;

    @InjectMocks
    private DashboardToolHandler handler;

    @Test
    void getDashboardSummaryShouldReturnCounts() {
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());

        String result = handler.execute(Map.of("toolName", "get_dashboard_summary"));

        assertTrue(result.contains("Pending Approvals:"));
        assertTrue(result.contains("Total Agents:"));
        assertTrue(result.contains("Total Runs:"));
        assertTrue(result.contains("Knowledge Items:"));
    }

    @Test
    void getDashboardSummaryWithNoPendingShouldReturnZero() {
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());

        String result = handler.execute(Map.of("toolName", "get_dashboard_summary"));

        assertTrue(result.contains("Pending Approvals: 0"));
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
