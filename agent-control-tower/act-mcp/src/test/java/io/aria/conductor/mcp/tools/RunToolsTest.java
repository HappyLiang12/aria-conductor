package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunToolsTest {

    /** The dispatching turn the worker (sandbox core) credential is scoped to. */
    private static final UUID DISPATCHING_RUN = UUID.fromString("00000000-0000-0000-0000-000000000801");

    @Mock RunService runService;
    @Mock ApprovalRepository approvalRepository;
    McpProperties mcpProperties;
    RunTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new RunTools(runService, approvalRepository, mcpProperties);
    }

    private ToolContext context(ActorPrincipal actor) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(actor));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    @Test
    void runAgent_delegatesAndWraps() {
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).status(RunStatus.PENDING).build());

        String json = tools.runAgent(agentId, "fix the login bug", null, context(ActorPrincipal.operator(null)));

        assertThat(json).contains("\"ok\":true").contains("PENDING");
        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        assertThat(captor.getValue().getAgentId()).isEqualTo(agentId);
        assertThat(captor.getValue().getPromptSeed()).isEqualTo("fix the login bug");
        assertThat(captor.getValue().getMaxIterations()).isEqualTo(50);
    }

    @Test
    void runAgent_honoursExplicitMaxIterations() {
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).status(RunStatus.PENDING).build());

        tools.runAgent(UUID.randomUUID(), "prompt", 7, context(ActorPrincipal.operator(null)));

        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        assertThat(captor.getValue().getMaxIterations()).isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // dispatch-group stamping from the transport actor (T12 live-drill fix):
    // the SANDBOX surface is the run-scoped worker credential, so its runId is
    // the dispatching turn the batch-completion listener groups children by.
    // ------------------------------------------------------------------

    @Test
    void runAgent_workerActor_stampsTheDispatchGroupFromTheTransportActor() {
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).status(RunStatus.PENDING).build());
        ActorPrincipal worker = ActorPrincipal.worker(DISPATCHING_RUN, Instant.parse("2026-09-22T12:10:00Z"));

        String json = tools.runAgent(agentId, "research X", null, context(worker));

        assertThat(json).contains("\"ok\":true");
        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        assertThat(captor.getValue().getDispatchedByRunId()).isEqualTo(DISPATCHING_RUN);
    }

    @Test
    void runAgent_operatorActor_leavesTheDispatchGroupUnstamped() {
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).status(RunStatus.PENDING).build());

        tools.runAgent(agentId, "prompt", null, context(ActorPrincipal.operator(null)));

        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        assertThat(captor.getValue().getDispatchedByRunId()).isNull();
    }

    @Test
    void runAgent_withoutTransportActor_stillCreatesTheRunWithoutAStamp() {
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).status(RunStatus.PENDING).build());

        String json = tools.runAgent(agentId, "prompt", null, new ToolContext(Map.of()));

        assertThat(json).contains("\"ok\":true");
        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        assertThat(captor.getValue().getDispatchedByRunId()).isNull();
    }

    @Test
    void runAgent_mapsUnknownAgentToNotFound() {
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenThrow(new ResourceNotFoundException("Agent", agentId));

        String json = tools.runAgent(agentId, "prompt", null, context(ActorPrincipal.operator(null)));

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void runAgent_debugOn_stillHasNoStack() {
        // Fix round 7: debug mode logs the cause server-side; the tool answer is
        // always the truthful ok/errorType/message envelope.
        mcpProperties.setDebug(true);
        when(runService.createRun(any())).thenThrow(new IllegalArgumentException("Cannot create run for retired agent"));

        String json = tools.runAgent(UUID.randomUUID(), "prompt", null, context(ActorPrincipal.operator(null)));

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).doesNotContain("stackTrace");
        assertThat(json).doesNotContain("IllegalArgumentException");
    }

    @Test
    void listRuns_withNoFilters_listsAll() {
        when(runService.listRuns()).thenReturn(List.of(
                RunResponse.builder().id(UUID.randomUUID()).status(RunStatus.RUNNING).build()));

        String json = tools.listRuns(null, " ");

        assertThat(json).contains("\"ok\":true").contains("RUNNING");
        verify(runService).listRuns();
    }

    @Test
    void listRuns_withAgentAndStatus_usesCombinedQuery() {
        UUID agentId = UUID.randomUUID();
        when(runService.listRunsByAgentAndStatus(agentId, RunStatus.PAUSED)).thenReturn(List.of());

        String json = tools.listRuns(agentId.toString(), "paused");

        assertThat(json).contains("\"ok\":true");
        verify(runService).listRunsByAgentAndStatus(agentId, RunStatus.PAUSED);
        verify(runService, never()).listRuns();
    }

    @Test
    void listRuns_withAgentOnly_usesAgentQuery() {
        UUID agentId = UUID.randomUUID();
        when(runService.listRunsByAgent(agentId)).thenReturn(List.of());

        String json = tools.listRuns(agentId.toString(), null);

        assertThat(json).contains("\"ok\":true");
        verify(runService).listRunsByAgent(agentId);
        verify(runService, never()).listRunsByStatus(any());
    }

    @Test
    void listRuns_withStatusOnly_usesStatusQuery() {
        when(runService.listRunsByStatus(RunStatus.FAILED)).thenReturn(List.of());

        String json = tools.listRuns(null, "FAILED");

        assertThat(json).contains("\"ok\":true");
        verify(runService).listRunsByStatus(RunStatus.FAILED);
    }

    @Test
    void listRuns_mapsInvalidStatusToValidation() {
        String json = tools.listRuns(null, "SLEEPING");

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("ABORTED");
        verify(runService, never()).listRuns();
    }

    @Test
    void listRuns_mapsInvalidAgentIdToValidation() {
        String json = tools.listRuns("not-a-uuid", "RUNNING");

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("Expected a UUID");
    }

    @Test
    void listRunningRuns_filtersToActiveStatuses() {
        RunResponse running = RunResponse.builder().id(UUID.randomUUID()).status(RunStatus.RUNNING).build();
        RunResponse pending = RunResponse.builder().id(UUID.randomUUID()).status(RunStatus.PENDING).build();
        RunResponse done = RunResponse.builder().id(UUID.randomUUID()).status(RunStatus.COMPLETED).build();
        when(runService.listRuns()).thenReturn(List.of(running, pending, done));

        String json = tools.listRunningRuns();

        assertThat(json).contains("\"ok\":true").contains("RUNNING").contains("PENDING");
        assertThat(json).doesNotContain("COMPLETED");
    }

    @Test
    void getRun_returnsRunJson() {
        UUID id = UUID.randomUUID();
        when(runService.getRun(id)).thenReturn(RunResponse.builder()
                .id(id).status(RunStatus.RUNNING).iterationCount(3).build());

        String json = tools.getRun(id);

        assertThat(json).contains("\"ok\":true").contains("RUNNING").contains("\"iterationCount\":3");
    }

    @Test
    void getRun_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(runService.getRun(id)).thenThrow(new ResourceNotFoundException("Run", id));

        String json = tools.getRun(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void pauseRun_delegatesAndWraps() {
        UUID id = UUID.randomUUID();
        when(runService.pauseRun(id)).thenReturn(RunResponse.builder()
                .id(id).status(RunStatus.PAUSED).build());

        String json = tools.pauseRun(id);

        assertThat(json).contains("\"ok\":true").contains("PAUSED");
        verify(runService).pauseRun(id);
    }

    @Test
    void pauseRun_mapsInvalidTransitionToConflict() {
        UUID id = UUID.randomUUID();
        when(runService.pauseRun(id)).thenThrow(new InvalidStateTransitionException("Run", "COMPLETED", "PAUSED"));

        String json = tools.pauseRun(id);

        assertThat(json).contains("\"errorType\":\"CONFLICT\"");
    }

    @Test
    void resumeRun_withoutPendingApproval_resumes() {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findByRunId(id)).thenReturn(List.of());
        when(runService.resumeRun(id, "go on")).thenReturn(RunResponse.builder()
                .id(id).status(RunStatus.RUNNING).build());

        String json = tools.resumeRun(id, "go on");

        assertThat(json).contains("\"ok\":true").contains("RUNNING");
        verify(runService).resumeRun(id, "go on");
    }

    @Test
    void resumeRun_refusesWhileApprovalGatePending() {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findByRunId(id)).thenReturn(List.of(
                Approval.builder().runId(id).status(ApprovalStatus.PENDING)
                        .askType(Approval.AskType.APPROVAL).build()));

        String json = tools.resumeRun(id, null);

        assertThat(json).contains("\"errorType\":\"CONFLICT\"").contains("waiting for human approval");
        verify(runService, never()).resumeRun(any(), any());
    }

    @Test
    void resumeRun_allowsPendingReviewRequestAsk() {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findByRunId(id)).thenReturn(List.of(
                Approval.builder().runId(id).status(ApprovalStatus.PENDING)
                        .askType(Approval.AskType.REVIEW_REQUEST).build()));
        when(runService.resumeRun(eq(id), isNull())).thenReturn(RunResponse.builder()
                .id(id).status(RunStatus.RUNNING).build());

        String json = tools.resumeRun(id, null);

        assertThat(json).contains("\"ok\":true").contains("RUNNING");
        verify(runService).resumeRun(id, null);
    }

    @Test
    void cancelRun_delegatesAndWraps() {
        UUID id = UUID.randomUUID();
        when(runService.cancelRun(id)).thenReturn(RunResponse.builder()
                .id(id).status(RunStatus.CANCELLED).build());

        String json = tools.cancelRun(id);

        assertThat(json).contains("\"ok\":true").contains("CANCELLED");
        verify(runService).cancelRun(id);
    }

    @Test
    void cancelRun_mapsNotFoundToErrorType() {
        UUID id = UUID.randomUUID();
        when(runService.cancelRun(id)).thenThrow(new ResourceNotFoundException("Run", id));

        String json = tools.cancelRun(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }
}
