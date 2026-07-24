package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Error paths and filtering behaviour of {@link RunToolHandler} not covered
 * by the base RunToolHandlerTest: not-found, missing/invalid parameters,
 * list_running_runs status filtering and exception mapping.
 */
@ExtendWith(MockitoExtension.class)
class RunToolHandlerEdgeCasesTest {

    @Mock private RunService runService;
    @Mock private RunRepository runRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AgentRepository agentRepository;

    @InjectMocks
    private RunToolHandler handler;

    private Run run(RunStatus status) {
        return Run.builder().id(UUID.randomUUID()).status(status).build();
    }

    @Test
    void getRun_notFoundReturnsError() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.empty());

        String result = handler.execute(Map.of("toolName", "get_run", "id", id.toString()));

        assertThat(result).isEqualTo("Error: Run not found: " + id);
    }

    @Test
    void getRun_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "get_run"));

        assertThat(result).startsWith("Error").contains("Missing required parameter: id");
        verifyNoInteractions(runRepository);
    }

    @Test
    void getRun_malformedUuidIsMappedToError() {
        String result = handler.execute(Map.of("toolName", "get_run", "id", "abc"));

        assertThat(result).startsWith("Error");
        verifyNoInteractions(runRepository);
    }

    @Test
    void runAgent_missingAgentIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "run_agent", "prompt", "do it"));

        assertThat(result).startsWith("Error").contains("agentId");
        verifyNoInteractions(runService);
    }

    @Test
    void runAgent_missingPromptReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "run_agent", "agentId", UUID.randomUUID().toString()));

        assertThat(result).startsWith("Error").contains("prompt");
        verifyNoInteractions(runService);
    }

    @Test
    void runAgent_unresolvableAgentNameReturnsNotFound() {
        when(agentRepository.findByName("ghost")).thenReturn(Optional.empty());

        String result = handler.execute(Map.of(
                "toolName", "run_agent", "agentId", "ghost", "prompt", "hello"));

        assertThat(result).isEqualTo("Error: Agent not found: ghost");
        verify(runService, never()).createRun(any());
    }

    @Test
    void listRunningRuns_keepsOnlyActiveStatuses() {
        Run running = run(RunStatus.RUNNING);
        Run paused = run(RunStatus.PAUSED);
        Run pending = run(RunStatus.PENDING);
        Run initializing = run(RunStatus.INITIALIZING);
        Run completed = run(RunStatus.COMPLETED);
        Run failed = run(RunStatus.FAILED);
        Run cancelled = run(RunStatus.CANCELLED);
        when(runRepository.findAll()).thenReturn(List.of(
                running, paused, pending, initializing, completed, failed, cancelled));

        String result = handler.execute(Map.of("toolName", "list_running_runs"));

        assertThat(result).contains("Running runs (4 total)");
        assertThat(result).contains(running.getId().toString())
                .contains(paused.getId().toString())
                .contains(pending.getId().toString())
                .contains(initializing.getId().toString());
        assertThat(result).doesNotContain(completed.getId().toString())
                .doesNotContain(failed.getId().toString())
                .doesNotContain(cancelled.getId().toString());
    }

    @Test
    void listRunningRuns_reportsWhenNothingActive() {
        when(runRepository.findAll()).thenReturn(List.of(run(RunStatus.COMPLETED)));

        String result = handler.execute(Map.of("toolName", "list_running_runs"));

        assertThat(result).isEqualTo("No running runs.");
    }

    @Test
    void pauseRun_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "pause_run"));

        assertThat(result).startsWith("Error").contains("id");
        verifyNoInteractions(runService);
    }

    @Test
    void cancelRun_missingIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "cancel_run"));

        assertThat(result).startsWith("Error").contains("id");
        verifyNoInteractions(runService);
    }

    @Test
    void resumeRun_passesInstructionThroughToService() {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findByRunId(id)).thenReturn(List.of());

        String result = handler.execute(Map.of(
                "toolName", "resume_run", "id", id.toString(), "instruction", "focus on tests"));

        verify(runService).resumeRun(id, "focus on tests");
        assertThat(result).contains("resumed");
    }

    @Test
    void resumeRun_allowedWhenApprovalsAlreadyDecided() {
        UUID id = UUID.randomUUID();
        when(approvalRepository.findByRunId(id)).thenReturn(List.of(
                Approval.builder().id(UUID.randomUUID()).runId(id)
                        .status(ApprovalStatus.APPROVED).build()));

        String result = handler.execute(Map.of("toolName", "resume_run", "id", id.toString()));

        verify(runService).resumeRun(id, null);
        assertThat(result).contains("resumed");
    }

    @Test
    void serviceExceptionIsMappedToErrorString() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("run already terminal"))
                .when(runService).cancelRun(id);

        String result = handler.execute(Map.of("toolName", "cancel_run", "id", id.toString()));

        assertThat(result).isEqualTo("Error: run already terminal");
    }

    @Test
    void missingToolNameReturnsUnknownToolError() {
        String result = handler.execute(Map.of());

        assertThat(result).isEqualTo("Error: Unknown tool: ");
    }
}
