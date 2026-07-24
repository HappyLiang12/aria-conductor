package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Value rendering and failure mapping of {@link DashboardToolHandler};
 * the base test only asserts that the section labels are present.
 */
@ExtendWith(MockitoExtension.class)
class DashboardToolHandlerErrorPathTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private RunRepository runRepository;
    @Mock private KnowledgeItemRepository knowledgeItemRepository;

    @InjectMocks
    private DashboardToolHandler handler;

    @Test
    void summary_rendersEveryCountFromItsRepository() {
        when(agentRepository.count()).thenReturn(6L);
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY)).thenReturn(4L);
        when(runRepository.count()).thenReturn(42L);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(3L);
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(
                Approval.builder().id(UUID.randomUUID()).status(ApprovalStatus.PENDING).build(),
                Approval.builder().id(UUID.randomUUID()).status(ApprovalStatus.PENDING).build()));
        when(knowledgeItemRepository.count()).thenReturn(11L);

        String result = handler.execute(Map.of("toolName", "get_dashboard_summary"));

        assertThat(result).contains("Total Agents: 6");
        assertThat(result).contains("Healthy Agents: 4");
        assertThat(result).contains("Total Runs: 42");
        assertThat(result).contains("Running Runs: 3");
        assertThat(result).contains("Pending Approvals: 2");
        assertThat(result).contains("Knowledge Items: 11");
    }

    @Test
    void repositoryFailureIsMappedToErrorString() {
        when(agentRepository.count()).thenThrow(new RuntimeException("db unreachable"));

        String result = handler.execute(Map.of("toolName", "get_dashboard_summary"));

        assertThat(result).isEqualTo("Error: db unreachable");
    }

    @Test
    void missingToolNameReturnsUnknownToolError() {
        String result = handler.execute(Map.of());

        assertThat(result).isEqualTo("Error: Unknown tool: ");
    }
}
