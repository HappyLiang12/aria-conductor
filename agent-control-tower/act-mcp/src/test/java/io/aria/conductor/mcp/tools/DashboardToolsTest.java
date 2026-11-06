package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardToolsTest {

    @Mock AgentRepository agentRepository;
    @Mock RunRepository runRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock PromptCallRepository promptCallRepository;
    McpProperties mcpProperties;
    DashboardTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new DashboardTools(agentRepository, runRepository, approvalRepository,
                promptCallRepository, mcpProperties);
    }

    @Test
    void getDashboardSummary_matchesRestSummaryShape() {
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY)).thenReturn(3L);
        when(agentRepository.countByHealthStatus(HealthStatus.DEGRADED)).thenReturn(1L);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(2L);
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING))
                .thenReturn(List.of(new Approval(), new Approval()));
        when(promptCallRepository.findAll()).thenReturn(List.of(
                PromptCall.builder().inputTokens(120).outputTokens(30).build(),
                PromptCall.builder().inputTokens(50).outputTokens(0).build()));

        String json = tools.getDashboardSummary();

        assertThat(json).contains("\"ok\":true")
                .contains("\"activeAgents\":3")
                .contains("\"healthyAgents\":3")
                .contains("\"degradedAgents\":1")
                .contains("\"runningRuns\":2")
                .contains("\"pendingApprovals\":2")
                .contains("\"totalTokensBurned\":200");
    }

    @Test
    void getDashboardSummary_mapsFailureWithoutStack() {
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY))
                .thenThrow(new RuntimeException("db down"));

        String json = tools.getDashboardSummary();

        assertThat(json).contains("\"errorType\":\"DASHBOARD_SUMMARY_FAILED\"").contains("db down");
        assertThat(json).doesNotContain("stackTrace");
    }
}
