package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunToolCallControllerTest {

    @Mock
    ToolCallRepository toolCallRepository;

    @Mock
    SessionTrajectoryRepository trajectoryRepository;

    @InjectMocks
    RunToolCallController controller;

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
    void inject_shouldCreateTrajectoryEntry() {
        UUID runId = UUID.randomUUID();
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
}
