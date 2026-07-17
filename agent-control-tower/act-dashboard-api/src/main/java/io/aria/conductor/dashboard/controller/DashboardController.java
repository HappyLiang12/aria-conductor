package io.aria.conductor.dashboard.controller;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.dashboard.dto.ActivityEvent;
import io.aria.conductor.dashboard.dto.AgentTelemetry;
import io.aria.conductor.dashboard.dto.DashboardSummary;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final ApprovalRepository approvalRepository;
    private final PromptCallRepository promptCallRepository;
    private final AuditEventRepository auditEventRepository;

    public DashboardController(AgentRepository agentRepository,
                                RunRepository runRepository,
                                ApprovalRepository approvalRepository,
                                PromptCallRepository promptCallRepository,
                                AuditEventRepository auditEventRepository) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.approvalRepository = approvalRepository;
        this.promptCallRepository = promptCallRepository;
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping("/summary")
    public DashboardSummary getSummary() {
        long activeAgents = agentRepository.countByHealthStatus(HealthStatus.HEALTHY)
                + agentRepository.countByHealthStatus(HealthStatus.DEGRADED);
        long runningRuns = runRepository.countByStatus(RunStatus.RUNNING);
        long pendingApprovals = approvalRepository.findByStatus(ApprovalStatus.PENDING).size();
        long totalTokensBurned = promptCallRepository.findAll()
                .stream()
                .mapToLong(p -> p.getInputTokens() + p.getOutputTokens())
                .sum();

        return new DashboardSummary(activeAgents, runningRuns, pendingApprovals, totalTokensBurned);
    }

    @GetMapping("/activity")
    public List<ActivityEvent> getRecentActivity() {
        return auditEventRepository.findTop20ByOrderByCreatedAtDesc()
                .stream()
                .map(e -> new ActivityEvent(
                        e.getEventType(),
                        e.getResourceType(),
                        e.getResourceId(),
                        e.getAction(),
                        e.getCreatedAt(),
                        e.getConversationId(),
                        e.getDetails()
                ))
                .toList();
    }

    @GetMapping("/agent-telemetry")
    public List<AgentTelemetry> getAgentTelemetry() {
        Instant startOfToday = ZonedDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant();

        List<Object[]> rows = promptCallRepository.aggregateByAgentSince(startOfToday);

        return rows.stream()
                .map(row -> new AgentTelemetry(
                        (UUID) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .toList();
    }
}