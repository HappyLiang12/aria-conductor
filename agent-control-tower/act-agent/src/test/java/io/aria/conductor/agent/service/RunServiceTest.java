package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock RunRepository runRepository;
    @Mock AgentService agentService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks RunService service;

    private Agent healthyAgent(UUID id) {
        return TestDataBuilder.anAgent().withId(id).withHealthStatus(HealthStatus.HEALTHY).build();
    }

    private void stubSaveReturnsArgument() {
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------------
    // createRun
    // ---------------------------------------------------------------------

    @Test
    void createRun_startsInPendingAndPublishesStartedEvent() {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(healthyAgent(agentId));
        stubSaveReturnsArgument();

        CreateRunRequest request = CreateRunRequest.builder()
                .agentId(agentId).promptSeed("do the thing").maxIterations(10).build();

        RunResponse response = service.createRun(request);

        assertThat(response.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(response.getAgentId()).isEqualTo(agentId);
        assertThat(response.getPromptSeed()).isEqualTo("do the thing");
        assertThat(response.getMaxIterations()).isEqualTo(10);

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(saved.getValue().getAgentId()).isEqualTo(agentId);

        ArgumentCaptor<RunStartedEvent> event = ArgumentCaptor.forClass(RunStartedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getAgentId()).isEqualTo(agentId);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -50})
    void createRun_nonPositiveMaxIterations_isClampedToZero(int requested) {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(healthyAgent(agentId));
        stubSaveReturnsArgument();

        CreateRunRequest request = CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").maxIterations(requested).build();

        RunResponse response = service.createRun(request);

        assertThat(response.getMaxIterations()).isZero();
    }

    @Test
    void createRun_positiveMaxIterations_isPreserved() {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(healthyAgent(agentId));
        stubSaveReturnsArgument();

        RunResponse response = service.createRun(CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").maxIterations(7).build());

        assertThat(response.getMaxIterations()).isEqualTo(7);
    }

    @Test
    void createRun_longPromptSeed_isStoredInFull() {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(healthyAgent(agentId));
        stubSaveReturnsArgument();
        String longPrompt = "x".repeat(300);

        RunResponse response = service.createRun(CreateRunRequest.builder()
                .agentId(agentId).promptSeed(longPrompt).maxIterations(3).build());

        assertThat(response.getPromptSeed()).isEqualTo(longPrompt);
    }

    @ParameterizedTest
    @EnumSource(value = HealthStatus.class, names = {"RETIRED", "UNHEALTHY"})
    void createRun_rejectsAgentThatIsNotUsable(HealthStatus status) {
        UUID agentId = UUID.randomUUID();
        Agent agent = TestDataBuilder.anAgent().withId(agentId).withHealthStatus(status).build();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(agent);

        CreateRunRequest request = CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").maxIterations(5).build();

        assertThatThrownBy(() -> service.createRun(request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @ParameterizedTest
    @EnumSource(value = HealthStatus.class, names = {"HEALTHY", "DEGRADED"})
    void createRun_allowsHealthyAndDegradedAgents(HealthStatus status) {
        UUID agentId = UUID.randomUUID();
        Agent agent = TestDataBuilder.anAgent().withId(agentId).withHealthStatus(status).build();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(agent);
        stubSaveReturnsArgument();

        RunResponse response = service.createRun(CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").maxIterations(5).build());

        assertThat(response.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void createRun_propagatesNotFoundWhenAgentMissing() {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId))
                .thenThrow(new ResourceNotFoundException("Agent", agentId));

        assertThatThrownBy(() -> service.createRun(CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").build()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(runRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // listing
    // ---------------------------------------------------------------------

    @Test
    void listRuns_mapsAllRunsToResponses() {
        Run a = TestDataBuilder.aRun().withPromptSeed("a").build();
        Run b = TestDataBuilder.aRun().withPromptSeed("b").build();
        when(runRepository.findAll()).thenReturn(List.of(a, b));

        List<RunResponse> result = service.listRuns();

        assertThat(result).extracting(RunResponse::getPromptSeed).containsExactly("a", "b");
    }

    @Test
    void listRunsByAgent_delegatesToAgentQuery() {
        UUID agentId = UUID.randomUUID();
        when(runRepository.findByAgentId(agentId))
                .thenReturn(List.of(TestDataBuilder.aRun().withAgentId(agentId).build()));

        List<RunResponse> result = service.listRunsByAgent(agentId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAgentId()).isEqualTo(agentId);
        verify(runRepository).findByAgentId(agentId);
    }

    @Test
    void listRunsByStatus_delegatesToStatusQuery() {
        when(runRepository.findByStatus(RunStatus.RUNNING))
                .thenReturn(List.of(TestDataBuilder.aRun().withStatus(RunStatus.RUNNING).build()));

        List<RunResponse> result = service.listRunsByStatus(RunStatus.RUNNING);

        assertThat(result).singleElement()
                .extracting(RunResponse::getStatus).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void listRunsByAgentAndStatus_delegatesToCompoundQuery() {
        UUID agentId = UUID.randomUUID();
        when(runRepository.findByAgentIdAndStatus(agentId, RunStatus.PAUSED))
                .thenReturn(List.of(TestDataBuilder.aRun().withAgentId(agentId)
                        .withStatus(RunStatus.PAUSED).build()));

        List<RunResponse> result = service.listRunsByAgentAndStatus(agentId, RunStatus.PAUSED);

        assertThat(result).hasSize(1);
        verify(runRepository).findByAgentIdAndStatus(agentId, RunStatus.PAUSED);
    }

    @Test
    void getRun_returnsMappedResponse() {
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING)
                .withTotalTokensUsed(123L).withIterationCount(4).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));

        RunResponse response = service.getRun(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(response.getTotalTokensUsed()).isEqualTo(123L);
        assertThat(response.getIterationCount()).isEqualTo(4);
    }

    @Test
    void getRun_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // ---------------------------------------------------------------------
    // transitions: pause / resume / cancel
    // ---------------------------------------------------------------------

    @Test
    void pauseRun_fromRunning_movesToPaused() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.pauseRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.PAUSED);
    }

    @Test
    void pauseRun_fromPending_isRejectedAndNotSaved() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PENDING).build()));

        assertThatThrownBy(() -> service.pauseRun(id))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(runRepository, never()).save(any());
    }

    @Test
    void resumeRun_fromPaused_movesToRunning() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PAUSED).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.resumeRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void resumeRun_withNewInstruction_overwritesPromptSeed() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PAUSED)
                        .withPromptSeed("original").build()));
        stubSaveReturnsArgument();

        RunResponse response = service.resumeRun(id, "revised instruction");

        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(response.getPromptSeed()).isEqualTo("revised instruction");
    }

    @Test
    void resumeRun_withBlankInstruction_keepsOriginalPrompt() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PAUSED)
                        .withPromptSeed("original").build()));
        stubSaveReturnsArgument();

        RunResponse response = service.resumeRun(id, "   ");

        assertThat(response.getPromptSeed()).isEqualTo("original");
    }

    @Test
    void cancelRun_fromPending_setsCompletedAtAndPublishesCompletedEvent() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                        .withStatus(RunStatus.PENDING).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.cancelRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(response.getCompletedAt()).isNotNull();

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(event.getValue().getAgentId()).isEqualTo(agentId);
    }

    @Test
    void cancelRun_alreadyCompleted_isRejected() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.COMPLETED).build()));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------------------------------------------------------------------
    // updateRunStatus
    // ---------------------------------------------------------------------

    @Test
    void updateRunStatus_toRunning_isNonTerminalAndPublishesNoCompletion() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.INITIALIZING).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.updateRunStatus(id, RunStatus.RUNNING);

        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(response.getCompletedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateRunStatus_toCompleted_setsCompletedAtAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.updateRunStatus(id, RunStatus.COMPLETED);

        assertThat(response.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(response.getCompletedAt()).isNotNull();

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void updateRunStatus_toFailed_setsCompletedAtAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.updateRunStatus(id, RunStatus.FAILED);

        assertThat(response.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(response.getCompletedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(RunCompletedEvent.class));
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,RUNNING",
            "PENDING,COMPLETED",
            "INITIALIZING,PAUSED",
            "RUNNING,INITIALIZING",
            "COMPLETED,RUNNING",
            "FAILED,RUNNING",
            "CANCELLED,RUNNING",
            "PAUSED,COMPLETED"
    })
    void updateRunStatus_invalidTransitions_areRejected(RunStatus from, RunStatus to) {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(from).build()));

        assertThatThrownBy(() -> service.updateRunStatus(id, to))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(runRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,INITIALIZING",
            "PENDING,CANCELLED",
            "INITIALIZING,RUNNING",
            "RUNNING,PAUSED",
            "PAUSED,RUNNING"
    })
    void updateRunStatus_validTransitions_areAccepted(RunStatus from, RunStatus to) {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(from).build()));
        stubSaveReturnsArgument();

        RunResponse response = service.updateRunStatus(id, to);

        assertThat(response.getStatus()).isEqualTo(to);
    }

    // ---------------------------------------------------------------------
    // countByStatus
    // ---------------------------------------------------------------------

    @Test
    void countByStatus_aggregatesEveryStatus() {
        for (RunStatus status : RunStatus.values()) {
            when(runRepository.countByStatus(status)).thenReturn((long) status.ordinal());
        }

        Map<RunStatus, Long> counts = service.countByStatus();

        assertThat(counts).hasSize(RunStatus.values().length);
        assertThat(counts.get(RunStatus.PENDING)).isEqualTo((long) RunStatus.PENDING.ordinal());
        assertThat(counts.get(RunStatus.CANCELLED)).isEqualTo((long) RunStatus.CANCELLED.ordinal());
    }
}
