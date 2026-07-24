package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunToolHandlerTest {
    @Mock private RunService runService;
    @Mock private RunRepository runRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AgentRepository agentRepository;
    private RunToolHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(approvalRepository.findByRunId(any())).thenReturn(List.of());
        handler = new RunToolHandler(runService, runRepository, approvalRepository, agentRepository);
    }

    @Test void startRunShouldReturnText() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RunResponse resp = RunResponse.builder().id(runId).status(RunStatus.PENDING).iterationCount(0).build();
        when(runService.createRun(any())).thenReturn(resp);
        String result = handler.execute(Map.of("toolName","run_agent","agentId",agentId.toString(),"prompt","test"));
        assertThat(result).contains("Run started:").contains(runId.toString());
    }

    @Test void listRunsShouldReturnText() {
        Run r = new Run();
        UUID rid = UUID.randomUUID();
        r.setId(rid);
        r.setStatus(RunStatus.RUNNING);
        when(runRepository.findAll()).thenReturn(List.of(r));
        String result = handler.execute(Map.of("toolName","list_runs"));
        assertThat(result).contains("Runs (1 total)");
    }

    @Test void getRunShouldReturnText() {
        UUID id = UUID.randomUUID();
        Run r = new Run(); r.setId(id); r.setStatus(RunStatus.COMPLETED);
        when(runRepository.findById(id)).thenReturn(Optional.of(r));
        String result = handler.execute(Map.of("toolName","get_run","id",id.toString()));
        assertThat(result).contains("Run:").contains(id.toString()).contains("COMPLETED");
    }

    @Test void getRunStatusShouldReturnText() {
        UUID id = UUID.randomUUID();
        Run r = new Run(); r.setId(id); r.setStatus(RunStatus.RUNNING);
        when(runRepository.findById(id)).thenReturn(Optional.of(r));
        String result = handler.execute(Map.of("toolName","get_run_status","id",id.toString()));
        assertThat(result).contains("Run:").contains("RUNNING");
    }

    @Test void pauseRunShouldReturnText() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","pause_run","id",id.toString()));
        verify(runService).pauseRun(id);
        assertThat(result).contains("paused");
    }

    @Test void resumeRunShouldReturnText() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","resume_run","id",id.toString()));
        verify(runService).resumeRun(eq(id), any());
        assertThat(result).contains("resumed");
    }

    @Test void resumeRunBlockedWhenApprovalPending() {
        UUID id = UUID.randomUUID();
        Approval pending = Approval.builder().runId(id).status(ApprovalStatus.PENDING).build();
        when(approvalRepository.findByRunId(id)).thenReturn(List.of(pending));
        String result = handler.execute(Map.of("toolName","resume_run","id",id.toString()));
        assertThat(result).contains("waiting for human approval");
        verify(runService, never()).resumeRun(any(), any());
    }

    @Test void cancelRunShouldReturnText() {
        UUID id = UUID.randomUUID();
        String result = handler.execute(Map.of("toolName","cancel_run","id",id.toString()));
        verify(runService).cancelRun(id);
        assertThat(result).contains("cancelled");
    }
}
