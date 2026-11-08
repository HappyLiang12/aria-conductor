package io.aria.conductor.execution.controller;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunToolCallControllerTest {

    @Mock
    ToolCallRepository toolCallRepository;

    @Mock
    SessionTrajectoryRepository trajectoryRepository;

    @Mock
    RunRepository runRepository;

    @Mock
    RunInputCoordinator runInputs;

    @InjectMocks
    RunToolCallController controller;

    private static Run run(UUID id, RunStatus status) {
        return Run.builder()
                .id(id)
                .agentId(UUID.randomUUID())
                .status(status)
                .build();
    }

    @Test
    void getToolCalls_shouldReturnList_whenRunExists() {
        UUID runId = UUID.randomUUID();
        ToolCall call = ToolCall.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .toolName("search")
                .arguments("{\"query\":\"test\"}")
                .status(ToolCallStatus.COMPLETED)
                .latencyMs(100)
                .createdAt(Instant.now())
                .build();
        when(toolCallRepository.findByRunId(runId)).thenReturn(List.of(call));

        ResponseEntity<?> response = controller.getToolCalls(runId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<?> body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        verify(toolCallRepository).findByRunId(runId);
    }

    @Test
    void getToolCalls_shouldReturnEmptyList_whenNoToolCalls() {
        UUID runId = UUID.randomUUID();
        when(toolCallRepository.findByRunId(runId)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getToolCalls(runId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<?> body = (List<?>) response.getBody();
        assertThat(body).isEmpty();
        verify(toolCallRepository).findByRunId(runId);
    }

    @Test
    void getTrajectory_shouldReturnList_whenRunHasTrajectory() {
        UUID runId = UUID.randomUUID();
        SessionTrajectory entry = SessionTrajectory.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .turnNumber(1)
                .role("user")
                .content("Hello")
                .createdAt(Instant.now())
                .build();
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of(entry));

        ResponseEntity<?> response = controller.getTrajectory(runId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<?> body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        verify(trajectoryRepository).findByRunIdOrderByTurnNumberAsc(runId);
    }

    @Test
    void getTrajectory_shouldReturnEmptyList_whenNoTrajectory() {
        UUID runId = UUID.randomUUID();
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getTrajectory(runId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<?> body = (List<?>) response.getBody();
        assertThat(body).isEmpty();
        verify(trajectoryRepository).findByRunIdOrderByTurnNumberAsc(runId);
    }

    @Test
    void inject_shouldCreateTrajectoryEntryOnATerminalRun() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(run(runId, RunStatus.COMPLETED)));
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(5);
        when(trajectoryRepository.save(org.mockito.ArgumentMatchers.any(SessionTrajectory.class)))
                .thenAnswer(inv -> {
                    SessionTrajectory t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    t.setCreatedAt(Instant.now());
                    return t;
                });

        InjectMessageRequest request = new InjectMessageRequest("Hello agent", "human");
        ResponseEntity<?> response = controller.injectMessage(runId, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        verify(trajectoryRepository).findMaxTurnNumberByRunId(runId);
        verify(trajectoryRepository).save(org.mockito.ArgumentMatchers.any(SessionTrajectory.class));
    }

    @Test
    void inject_shouldDefaultToUserRole_whenRoleIsNull() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(run(runId, RunStatus.FAILED)));
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(0);
        when(trajectoryRepository.save(org.mockito.ArgumentMatchers.any(SessionTrajectory.class)))
                .thenAnswer(inv -> {
                    SessionTrajectory t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    t.setCreatedAt(Instant.now());
                    return t;
                });

        InjectMessageRequest request = new InjectMessageRequest("Hello", null);
        ResponseEntity<?> response = controller.injectMessage(runId, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    /**
     * F16: live delivery is unavailable for a running core that is not parked on
     * an operator-input ask, so the endpoint must refuse (409) instead of
     * pretending the message reached the run.
     */
    @Test
    void inject_onRunningRunThatIsNotWaiting_returns409AndPersistsNothing() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(run(runId, RunStatus.RUNNING)));
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "Run " + runId + " is not waiting for operator input"))
                .when(runInputs).submitAnswer(runId, "hint");

        ResponseEntity<?> response = controller.injectMessage(runId,
                new InjectMessageRequest("hint", "user"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        String body = response.getBody().toString();
        assertThat(body).contains("LIVE_INJECT_UNAVAILABLE").contains("not waiting");
        verify(trajectoryRepository, never()).save(any());
        verify(trajectoryRepository, never()).findMaxTurnNumberByRunId(runId);
    }

    /** A parked run is the delivery seam that exists: the answer wakes it. */
    @Test
    void inject_onParkedRun_deliversToTheLiveWaiter() {
        UUID runId = UUID.randomUUID();
        RunInputCoordinator inputs = new RunInputCoordinator(event -> { });
        CompletableFuture<RunInputCoordinator.OperatorInput> parked = inputs.requestInput(runId, "which issue?");
        RunToolCallController liveController = new RunToolCallController(
                toolCallRepository, trajectoryRepository, runRepository, inputs);
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(run(runId, RunStatus.WAITING_INPUT)));
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(1);
        when(trajectoryRepository.save(any(SessionTrajectory.class))).thenAnswer(inv -> {
            SessionTrajectory t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            t.setCreatedAt(Instant.now());
            return t;
        });

        ResponseEntity<?> response = liveController.injectMessage(runId,
                new InjectMessageRequest("issue #81", "user"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(parked).isCompletedWithValue(new RunInputCoordinator.OperatorInput("issue #81", false));
        assertThat(((io.aria.conductor.common.model.TrajectoryResponse) response.getBody()).getDelivery())
                .isEqualTo("LIVE");
    }

    /** Terminal runs document trajectory-only behavior instead of implying delivery. */
    @Test
    void inject_onTerminalRun_marksTheRowTrajectoryOnly() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(run(runId, RunStatus.COMPLETED)));
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(2);
        when(trajectoryRepository.save(any(SessionTrajectory.class))).thenAnswer(inv -> {
            SessionTrajectory t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            t.setCreatedAt(Instant.now());
            return t;
        });

        ResponseEntity<?> response = controller.injectMessage(runId,
                new InjectMessageRequest("post-mortem note", "user"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((io.aria.conductor.common.model.TrajectoryResponse) response.getBody()).getDelivery())
                .isEqualTo("TRAJECTORY_ONLY");
        verify(runInputs, never()).submitAnswer(any(), any());
    }

    @Test
    void inject_onUnknownRun_returns404() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.injectMessage(runId,
                new InjectMessageRequest("hello", "user"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
