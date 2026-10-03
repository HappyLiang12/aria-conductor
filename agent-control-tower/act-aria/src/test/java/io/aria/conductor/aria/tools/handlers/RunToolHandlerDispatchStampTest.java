package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 8 (aria turn resilience): runs dispatched through the run tool carry the
 * dispatching turn's run id ({@code dispatched_by_run_id}) so the batch of
 * children can later be grouped under that turn. The value comes from the
 * {@code _runContext} the ToolExecutionEngine injects into the tool arguments;
 * without it (direct/non-engine callers) the stamp stays null. No
 * conversationId is ever stamped on this path — children must not enter the
 * conversation timeline/context.
 */
@ExtendWith(MockitoExtension.class)
class RunToolHandlerDispatchStampTest {

    @Mock private RunService runService;
    @Mock private RunRepository runRepository;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AgentRepository agentRepository;

    private RunToolHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RunToolHandler(runService, runRepository, approvalRepository, agentRepository);
    }

    private void stubCreateRun() {
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).status(RunStatus.PENDING).iterationCount(0).build());
    }

    private CreateRunRequest captureCreateRunRequest() {
        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        return captor.getValue();
    }

    @Test
    void startRun_withRunContext_stampsTheDispatchingTurnRunId() {
        stubCreateRun();
        UUID agentId = UUID.randomUUID();
        UUID dispatchingRunId = UUID.randomUUID();
        RunContext ctx = new RunContext(dispatchingRunId, UUID.randomUUID(), null, null, 50);

        String result = handler.execute(Map.of(
                "toolName", "run_agent",
                "agentId", agentId.toString(),
                "prompt", "x",
                "_runContext", ctx));

        assertThat(result).contains("Run started:");
        CreateRunRequest req = captureCreateRunRequest();
        assertThat(req.getAgentId()).isEqualTo(agentId);
        assertThat(req.getDispatchedByRunId())
                .isEqualTo(ctx.getRunId())
                .isEqualTo(dispatchingRunId);
    }

    @Test
    void startRun_withoutRunContext_leavesTheStampNull() {
        stubCreateRun();
        UUID agentId = UUID.randomUUID();

        String result = handler.execute(Map.of(
                "toolName", "run_agent",
                "agentId", agentId.toString(),
                "prompt", "x"));

        assertThat(result).contains("Run started:");
        CreateRunRequest req = captureCreateRunRequest();
        assertThat(req.getDispatchedByRunId()).isNull();
    }

    @Test
    void startRun_withForeignRunContextValue_leavesTheStampNull() {
        stubCreateRun();
        Map<String, Object> args = new HashMap<>();
        args.put("toolName", "run_agent");
        args.put("agentId", UUID.randomUUID().toString());
        args.put("prompt", "x");
        args.put("_runContext", "not-a-run-context");

        String result = handler.execute(args);

        assertThat(result).contains("Run started:");
        CreateRunRequest req = captureCreateRunRequest();
        assertThat(req.getDispatchedByRunId()).isNull();
    }
}
