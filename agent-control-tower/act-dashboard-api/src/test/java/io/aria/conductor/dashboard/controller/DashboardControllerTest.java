package io.aria.conductor.dashboard.controller;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.AuditEvent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link DashboardController} wired with the production
 * {@link GlobalExceptionHandler}. Focuses on the controller's aggregation/mapping logic
 * (active-agent summing, token summing, activity mapping, telemetry {@code Number} coercion)
 * rather than the repositories, which are mocked.
 */
class DashboardControllerTest {

    private AgentRepository agentRepository;
    private RunRepository runRepository;
    private ApprovalRepository approvalRepository;
    private PromptCallRepository promptCallRepository;
    private AuditEventRepository auditEventRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        runRepository = mock(RunRepository.class);
        approvalRepository = mock(ApprovalRepository.class);
        promptCallRepository = mock(PromptCallRepository.class);
        auditEventRepository = mock(AuditEventRepository.class);
        DashboardController controller = new DashboardController(agentRepository, runRepository,
                approvalRepository, promptCallRepository, auditEventRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new MockEnvironment()))
                .build();
    }

    private PromptCall promptCall(int in, int out) {
        return PromptCall.builder().inputTokens(in).outputTokens(out).build();
    }

    @Test
    void getSummary_sumsHealthyAndDegradedAgentsAndTokens() throws Exception {
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY)).thenReturn(3L);
        when(agentRepository.countByHealthStatus(HealthStatus.DEGRADED)).thenReturn(2L);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(4L);
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING))
                .thenReturn(List.of(new Approval(), new Approval()));
        when(promptCallRepository.findAll())
                .thenReturn(List.of(promptCall(100, 50), promptCall(10, 5)));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAgents").value(5))
                .andExpect(jsonPath("$.runningRuns").value(4))
                .andExpect(jsonPath("$.pendingApprovals").value(2))
                .andExpect(jsonPath("$.totalTokensBurned").value(165));
    }

    @Test
    void getSummary_withNoData_returnsZeros() throws Exception {
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY)).thenReturn(0L);
        when(agentRepository.countByHealthStatus(HealthStatus.DEGRADED)).thenReturn(0L);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(0L);
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());
        when(promptCallRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeAgents").value(0))
                .andExpect(jsonPath("$.totalTokensBurned").value(0));
    }

    @Test
    void getSummary_whenRepositoryThrows_maps500ViaGlobalHandler() throws Exception {
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY))
                .thenThrow(new RuntimeException("db offline"));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void getRecentActivity_mapsAuditEventFields() throws Exception {
        AuditEvent audit = AuditEvent.builder()
                .eventType("agent.created")
                .resourceType("Agent")
                .resourceId("a-1")
                .action("CREATE")
                .createdAt(Instant.parse("2025-01-01T12:00:00Z"))
                .conversationId("conv-1")
                .details("details here")
                .build();
        when(auditEventRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(audit));

        mockMvc.perform(get("/api/v1/dashboard/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("agent.created"))
                .andExpect(jsonPath("$[0].resourceType").value("Agent"))
                .andExpect(jsonPath("$[0].resourceId").value("a-1"))
                .andExpect(jsonPath("$[0].action").value("CREATE"))
                .andExpect(jsonPath("$[0].conversationId").value("conv-1"))
                .andExpect(jsonPath("$[0].details").value("details here"));
    }

    @Test
    void getRecentActivity_withNoEvents_returnsEmptyArray() throws Exception {
        when(auditEventRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAgentTelemetry_coercesNumberColumnsToLong() throws Exception {
        UUID agentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        // row[1] is a Long, row[2] is an Integer -> both must be coerced via Number#longValue().
        Object[] row = new Object[]{agentId, 1200L, 8};
        when(promptCallRepository.aggregateByAgentSince(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.<Object[]>of(row));

        mockMvc.perform(get("/api/v1/dashboard/agent-telemetry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$[0].totalTokensToday").value(1200))
                .andExpect(jsonPath("$[0].callCountToday").value(8));
    }

    @Test
    void getAgentTelemetry_withNoRows_returnsEmptyArray() throws Exception {
        when(promptCallRepository.aggregateByAgentSince(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/agent-telemetry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
