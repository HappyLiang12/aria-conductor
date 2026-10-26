package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.exception.ActionableIllegalArgumentException;
import io.aria.conductor.common.exception.ErrorSurface;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.port.RunBindingReaderPort;
import io.aria.conductor.common.port.RunRuntimeControlPort;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock RunRepository runRepository;
    @Mock AgentService agentService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock org.springframework.beans.factory.ObjectProvider<RunRuntimeControlPort> runtimeControlProvider;
    @Mock RunRuntimeControlPort runtimeControl;
    @Mock RunBindingReaderPort bindingReader;

    private RunService service;

    @BeforeEach
    void setUp() {
        // The real short-transaction writer over the mocked repository/publisher: the
        // service's orchestration (verified stop -> one conditional write -> one event)
        // is exercised end to end. The SQL condition itself is proven against real H2
        // by RunRepositoryCancellationIntegrationTest.
        service = new RunService(runRepository, agentService, eventPublisher, runtimeControlProvider,
                bindingReader, new RunCancellationWriter(runRepository, eventPublisher));
    }

    private Agent healthyAgent(UUID id) {
        return TestDataBuilder.anAgent().withId(id).withHealthStatus(HealthStatus.HEALTHY).build();
    }

    private void stubSaveReturnsArgument() {
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Mirrors the repository's atomic conditional cancel onto the in-memory run double. */
    private void stubCancelUpdateWins() {
        when(runRepository.cancelIfCancelable(any(UUID.class), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    Instant completedAt = invocation.getArgument(2);
                    Instant updatedAt = invocation.getArgument(3);
                    Run current = runRepository.findById(id).orElse(null);
                    if (current == null || !RunService.cancelableStatuses().contains(current.getStatus())) {
                        return 0;
                    }
                    current.setStatus(RunStatus.CANCELLED);
                    current.setCompletedAt(completedAt);
                    current.setUpdatedAt(updatedAt);
                    return 1;
                });
    }

    /** Mirrors the repository's atomic conditional status write (M4) onto the in-memory run double. */
    private void stubConditionalStatusUpdate() {
        when(runRepository.updateStatusIfCurrentIn(any(UUID.class), any(RunStatus.class), any(),
                any(Instant.class), any())).thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    RunStatus to = invocation.getArgument(1);
                    String promptSeed = invocation.getArgument(2);
                    Instant updatedAt = invocation.getArgument(3);
                    java.util.List<RunStatus> allowedFrom = invocation.getArgument(4);
                    Run current = runRepository.findById(id).orElse(null);
                    if (current == null || !allowedFrom.contains(current.getStatus())) {
                        return 0;
                    }
                    current.setStatus(to);
                    if (promptSeed != null) {
                        current.setPromptSeed(promptSeed);
                    }
                    current.setUpdatedAt(updatedAt);
                    return 1;
                });
    }

    private static RunRepository.RunStateView stateView(RunStatus status) {
        return new RunRepository.RunStateView() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return null;
            }
        };
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

        String expectedCode = status == HealthStatus.RETIRED ? "AGENT_RETIRED" : "AGENT_UNHEALTHY";
        assertThatThrownBy(() -> service.createRun(request))
                .isInstanceOfSatisfying(ActionableIllegalArgumentException.class, rejection -> {
                    assertThat(rejection.code()).isEqualTo(expectedCode);
                    assertThat(rejection.surface()).isEqualTo(ErrorSurface.CREW);
                });
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createRun_propagatesSuppressAutoCardFlagToStartedEvent() {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(healthyAgent(agentId));
        stubSaveReturnsArgument();

        service.createRun(CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").suppressAutoCard(true).build());

        ArgumentCaptor<RunStartedEvent> event = ArgumentCaptor.forClass(RunStartedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().isSuppressAutoCard()).isTrue();
    }

    @Test
    void createRun_defaultSuppressAutoCardFlagIsFalse() {
        UUID agentId = UUID.randomUUID();
        when(agentService.findAgentOrThrow(agentId)).thenReturn(healthyAgent(agentId));
        stubSaveReturnsArgument();

        service.createRun(CreateRunRequest.builder()
                .agentId(agentId).promptSeed("p").build());

        ArgumentCaptor<RunStartedEvent> event = ArgumentCaptor.forClass(RunStartedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().isSuppressAutoCard()).isFalse();
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

    @Test
    void runResponseCarriesThePersistedErrorIdentity() {
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withStatus(RunStatus.FAILED).build();
        run.setErrorMessage("Run deadline reached at 2026-10-20T12:00:00Z.");
        run.setErrorCode("RUN_DEADLINE_REACHED");
        run.setErrorSurface("SYSTEM_CONFIG");
        when(runRepository.findById(id)).thenReturn(Optional.of(run));

        RunResponse response = service.getRun(id);

        assertThat(response.getErrorMessage()).isEqualTo("Run deadline reached at 2026-10-20T12:00:00Z.");
        assertThat(response.getErrorCode()).isEqualTo("RUN_DEADLINE_REACHED");
        assertThat(response.getErrorSurface()).isEqualTo("SYSTEM_CONFIG");
    }

    // ---------------------------------------------------------------------
    // frozen runtime on the response (Fleet Runtime Governance)
    // ---------------------------------------------------------------------

    @Test
    void runResponseCarriesTheFrozenRuntime() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        when(bindingReader.findByRunId(id)).thenReturn(Optional.of(RunExecutionBinding.builder()
                .runId(id).coreId("qoder").executionMode(ExecutionMode.HOST).build()));

        RunResponse response = service.getRun(id);

        assertThat(response.getCoreId()).isEqualTo("qoder");
        assertThat(response.getExecutionMode()).isEqualTo("HOST");
    }

    @Test
    void runResponseWithoutABindingCarriesNullRuntime() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        when(bindingReader.findByRunId(id)).thenReturn(Optional.empty());

        RunResponse response = service.getRun(id);

        assertThat(response.getCoreId()).isNull();
        assertThat(response.getExecutionMode()).isNull();
    }

    @Test
    void runResponseOfARunWithNoBindingReaderAvailable_carriesNullRuntime() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        RunService readerless = new RunService(runRepository, agentService, eventPublisher);

        RunResponse response = readerless.getRun(id);

        assertThat(response.getCoreId()).isNull();
        assertThat(response.getExecutionMode()).isNull();
    }

    @Test
    void listRuns_batchesTheBindingLookupIntoOneQuery() {
        Run a = TestDataBuilder.aRun().withPromptSeed("a").build();
        Run b = TestDataBuilder.aRun().withPromptSeed("b").build();
        when(runRepository.findAll()).thenReturn(List.of(a, b));
        when(bindingReader.findByRunIdIn(List.of(a.getId(), b.getId())))
                .thenReturn(List.of(RunExecutionBinding.builder()
                        .runId(a.getId()).coreId("qoder").executionMode(ExecutionMode.SANDBOX).build()));

        List<RunResponse> result = service.listRuns();

        assertThat(result).extracting(RunResponse::getCoreId).containsExactly("qoder", null);
        verify(bindingReader).findByRunIdIn(List.of(a.getId(), b.getId()));
        verify(bindingReader, never()).findByRunId(any());
    }

    // ---------------------------------------------------------------------
    // transitions: pause / resume / cancel
    // ---------------------------------------------------------------------

    @Test
    void pauseRun_fromRunning_movesToPaused() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        stubConditionalStatusUpdate();

        RunResponse response = service.pauseRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.PAUSED);
    }

    /**
     * M4 (#111 fix round 2): the PAUSED record is a single conditional write guarded by the
     * committed row, never a read-then-save of the entity (which could resurrect a CANCELLED
     * that committed while the verified pause was in flight).
     */
    @Test
    void pauseRun_writesPausedConditionallyOnTheCommittedRowAndNeverResavesTheEntity() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.RUNNING).build()));
        stubConditionalStatusUpdate();

        service.pauseRun(id);

        verify(runRepository).updateStatusIfCurrentIn(eq(id), eq(RunStatus.PAUSED), isNull(),
                any(Instant.class), eq(java.util.List.of(RunStatus.RUNNING)));
        verify(runRepository, never()).save(any(Run.class));
    }

    @Test
    void pauseRun_whenTheCommittedRowTurnedTerminalDuringTheVerifiedPause_refusesAndOverwritesNothing() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.RUNNING).build()));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        // The core verified the pause, but the run finalized CANCELLED while it was in flight.
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.CANCELLED)));

        // Lane H2 finding 1: the pause DID happen, so the refusal may not claim
        // "nothing was paused" -- it names the committed state and the verified action.
        assertThatThrownBy(() -> service.pauseRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " reached CANCELLED while the verified pause completed;"
                        + " the runtime was paused but the run record was not changed.");
        verify(runtimeControl).pause(id);
        verify(runRepository, never()).save(any(Run.class));
    }

    /**
     * Lane H3 finding P3-A: the verified wording is about the action the runtime performed,
     * so it does not depend on the committed state being terminal. A pause whose conditional
     * write loses to a live state (the run was parked on a clarification question while the
     * pause was in flight) must not fall back to the bare state-machine arrow
     * {@code WAITING_INPUT -> PAUSED}, which reads as if no pause ever happened.
     */
    @Test
    void pauseRun_whenTheCommittedRowIsNonTerminalDuringTheVerifiedPause_namesTheStateAndTheVerifiedAction() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.RUNNING).build()));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        // The core verified the pause, but the run was parked on a clarification question
        // before the conditional write landed: the write loses to a NON-terminal state.
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.WAITING_INPUT)));

        assertThatThrownBy(() -> service.pauseRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " reached WAITING_INPUT while the verified pause completed;"
                        + " the runtime was paused but the run record was not changed.");
        verify(runtimeControl).pause(id);
        verify(runRepository, never()).save(any(Run.class));
    }

    @Test
    void pauseRun_fromPending_isRejectedAndNotSaved() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PENDING).build()));

        assertThatThrownBy(() -> service.pauseRun(id))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(runRepository, never()).save(any());
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    // ── fix round 7: a coordinator-owned run is paused only on a verified ack ──

    @Test
    void pauseRun_ofACoordinatorOwnedRun_verifiesTheRuntimeBeforePersistingPaused() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.RUNNING).build()));
        stubConditionalStatusUpdate();
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);

        RunResponse response = service.pauseRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.PAUSED);
        verify(runtimeControl).pause(id);
        verify(runRepository).updateStatusIfCurrentIn(eq(id), eq(RunStatus.PAUSED), isNull(),
                any(Instant.class), eq(java.util.List.of(RunStatus.RUNNING)));
    }

    @Test
    void pauseRun_whenTheRuntimeRefuses_staysRunningAndPersistsNothing() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.RUNNING).build()));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "Run " + id + " was not paused: the run-owned core did not verify the pause"))
                .when(runtimeControl).pause(id);

        assertThatThrownBy(() -> service.pauseRun(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not verify the pause");
        verify(runRepository, never()).save(any());
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    @Test
    void pauseRun_ofARunNoRuntimeOwns_keepsTheRecordedTransition() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.RUNNING).build()));
        stubConditionalStatusUpdate();
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(false);

        RunResponse response = service.pauseRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.PAUSED);
        verify(runtimeControl, never()).pause(any());
    }

    @Test
    void resumeRun_ofACoordinatorOwnedRun_verifiesTheRuntimeBeforePersistingRunning() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.PAUSED).build()));
        stubConditionalStatusUpdate();
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);

        RunResponse response = service.resumeRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(runtimeControl).resume(id);
        verify(runRepository).updateStatusIfCurrentIn(eq(id), eq(RunStatus.RUNNING), isNull(),
                any(Instant.class),
                eq(java.util.List.of(RunStatus.INITIALIZING, RunStatus.PAUSED, RunStatus.WAITING_INPUT)));
    }

    /**
     * M4 (#111 fix round 2): a resume whose committed row turned terminal is refused, and the
     * stale entity is never saved over it.
     */
    @Test
    void resumeRun_whenTheCommittedRowTurnedTerminalDuringTheVerifiedResume_refusesAndOverwritesNothing() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.PAUSED).build()));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.COMPLETED)));

        // Lane H2 finding 1: the verified resume DID happen; the refusal says so and
        // names the state the run reached instead of claiming nothing was resumed.
        assertThatThrownBy(() -> service.resumeRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " reached COMPLETED while the verified resume completed;"
                        + " the runtime was resumed but the run record was not changed.");
        verify(runtimeControl).resume(id);
        verify(runRepository, never()).save(any(Run.class));
    }

    /**
     * Lane H3 finding P3-A, the resume half: a verified resume that loses the conditional
     * write to a non-terminal state keeps the verified wording too — naming the state the
     * run reached and the action the runtime performed, never the bare arrow
     * {@code WAITING_INPUT -> RUNNING}.
     */
    @Test
    void resumeRun_whenTheCommittedRowIsNonTerminalDuringTheVerifiedResume_namesTheStateAndTheVerifiedAction() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.PAUSED).build()));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        // The core verified the resume, but another lifecycle write landed first and the
        // conditional write lost to a NON-terminal state.
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.WAITING_INPUT)));

        assertThatThrownBy(() -> service.resumeRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " reached WAITING_INPUT while the verified resume completed;"
                        + " the runtime was resumed but the run record was not changed.");
        verify(runtimeControl).resume(id);
        verify(runRepository, never()).save(any(Run.class));
    }

    @Test
    void resumeRun_whenTheRuntimeRefuses_staysPausedAndPersistsNothing() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(TestDataBuilder.aRun()
                .withId(id).withAgentId(agentId).withStatus(RunStatus.PAUSED).build()));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "Run " + id + " was not resumed: the run-owned core did not verify the resume"))
                .when(runtimeControl).resume(id);

        assertThatThrownBy(() -> service.resumeRun(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not verify the resume");
        verify(runRepository, never()).save(any());
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    @Test
    void resumeRun_fromPaused_movesToRunning() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PAUSED).build()));
        stubConditionalStatusUpdate();

        RunResponse response = service.resumeRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void resumeRun_withNewInstruction_overwritesPromptSeed() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PAUSED)
                        .withPromptSeed("original").build()));
        stubConditionalStatusUpdate();

        RunResponse response = service.resumeRun(id, "revised instruction");

        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(response.getPromptSeed()).isEqualTo("revised instruction");
        verify(runRepository, never()).save(any(Run.class));
    }

    @Test
    void resumeRun_withBlankInstruction_keepsOriginalPrompt() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.PAUSED)
                        .withPromptSeed("original").build()));
        stubConditionalStatusUpdate();

        RunResponse response = service.resumeRun(id, "   ");

        assertThat(response.getPromptSeed()).isEqualTo("original");
    }

    @Test
    void cancelRun_fromPending_writesTheConditionalCancelAndPublishesCompletedEvent() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.PENDING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        stubCancelUpdateWins();

        RunResponse response = service.cancelRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(response.getCompletedAt()).isNotNull();

        verify(runRepository).cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses()));
        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.getCompletedAt()).isNotNull();

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(event.getValue().getAgentId()).isEqualTo(agentId);
    }

    // Lane H defect 2: 409 stays the right status for "nothing was stopped", but the
    // message must state what actually stands -- the terminal status, when the run got
    // there, and that no core was touched -- instead of the state-machine arrow.
    @Test
    void cancelRun_onAnAlreadyCancelledRun_namesTheStatusItsCompletionTimeAndThatNothingWasStopped() {
        UUID id = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-10-24T10:15:00Z");
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.CANCELLED)
                        .withCompletedAt(completedAt).build()));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already CANCELLED (completed at 2026-10-24T10:15:00Z);"
                        + " nothing was stopped.");
        verify(runRepository, never()).cancelIfCancelable(any(), any(), any(), any(), anyList());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_onAnAlreadyFailedRunWithoutACompletionTime_namesTheStatusOnly() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.FAILED).build()));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already FAILED; nothing was stopped.");
    }

    @Test
    void pauseRun_onATerminalRun_saysItIsAlreadyTerminalAndNothingWasPaused() {
        UUID id = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-10-24T10:20:00Z");
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.COMPLETED)
                        .withCompletedAt(completedAt).build()));

        assertThatThrownBy(() -> service.pauseRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already COMPLETED (completed at 2026-10-24T10:20:00Z);"
                        + " nothing was paused.");
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    @Test
    void resumeRun_onATerminalRun_saysItIsAlreadyTerminalAndNothingWasResumed() {
        UUID id = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-10-24T10:25:00Z");
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.CANCELLED)
                        .withCompletedAt(completedAt).build()));

        assertThatThrownBy(() -> service.resumeRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already CANCELLED (completed at 2026-10-24T10:25:00Z);"
                        + " nothing was resumed.");
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    @Test
    void cancelRun_alreadyCompleted_isRejected() {
        UUID id = UUID.randomUUID();
        when(runRepository.findById(id)).thenReturn(Optional.of(
                TestDataBuilder.aRun().withId(id).withStatus(RunStatus.COMPLETED).build()));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already COMPLETED; nothing was stopped.");
        verify(runRepository, never()).cancelIfCancelable(any(), any(), any(), any(), anyList());
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_fromAborted_writesTheConditionalCancelAndPublishesCompletedEvent() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.ABORTED).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        stubCancelUpdateWins();

        RunResponse response = service.cancelRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(response.getCompletedAt()).isNotNull();
        // ABORTED stays cancelable: the atomic update's set is derived from VALID_TRANSITIONS.
        verify(runRepository).cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses()));

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(event.getValue().getAgentId()).isEqualTo(agentId);
    }

    @Test
    void cancelableStatuses_areExactlyTheStatusesWhoseTransitionsAllowCancel() {
        assertThat(RunService.cancelableStatuses()).containsExactlyInAnyOrder(
                RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.RUNNING,
                RunStatus.PAUSED, RunStatus.WAITING_INPUT, RunStatus.ABORTED);
    }

    // ── #111: a coordinator-owned run is cancelled only after a verified stop ──

    @Test
    void cancelRun_ofACoordinatorOwnedRun_verifiesTheRuntimeBeforePersistingCancelled() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        stubCancelUpdateWins();
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);

        RunResponse response = service.cancelRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(response.getCompletedAt()).isNotNull();

        // The verified stop returns before the one conditional write is attempted.
        InOrder persisted = inOrder(runtimeControl, runRepository);
        persisted.verify(runtimeControl).cancel(id);
        persisted.verify(runRepository).cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses()));

        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.getCompletedAt()).isNotNull();

        ArgumentCaptor<RunCompletedEvent> events = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getAllValues()).hasSize(1);
        assertThat(events.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancelRun_whenTheRuntimeRefuses_doesNotPersistCancelledAndSurfacesTheReason() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        doThrow(new IllegalStateException(
                        "Run " + id + " was not stopped: core reported RUNNING (verified=false)"))
                .when(runtimeControl).cancel(id);
        // The committed state is still cancelable, so the refusal reason must survive.
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.RUNNING)));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run " + id + " was not stopped: core reported RUNNING (verified=false)");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getCompletedAt()).isNull();
        // A refused (or slow) stop never reaches the writer.
        verify(runRepository, never()).cancelIfCancelable(any(), any(), any(), any(), anyList());
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_whenTheRuntimeDisappearsAfterTheRunFinalized_surfacesTheTerminalTransition() {
        // A runtime disappearing between owns() and cancel() is a benign race when the
        // run finalized first: the operator must see the terminal outcome, not the
        // missing-runtime message.
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        doThrow(new IllegalStateException(
                        "Run " + id + " has no run-owned runtime in this process; there is nothing to cancel"))
                .when(runtimeControl).cancel(id);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.COMPLETED)));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already COMPLETED; nothing was stopped.");
        verify(runRepository, never()).cancelIfCancelable(any(), any(), any(), any(), anyList());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_whenTheOwnedRuntimeVanishesWithANonTerminalRow_recordsTheTransition() {
        // #111 parked/exited runtime: owns() said true, then the runtime exited in
        // the window before cancel(). The committed row is still non-terminal, so
        // there is no live core left to stop -- the recorded transition must still
        // be written, not answered with the missing-runtime refusal.
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        stubCancelUpdateWins();
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true, false);
        doThrow(new IllegalStateException(
                        "Run " + id + " has no run-owned runtime in this process; there is nothing to cancel"))
                .when(runtimeControl).cancel(id);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.RUNNING)));

        RunResponse response = service.cancelRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(response.getCompletedAt()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verify(runtimeControl).cancel(id);
        verify(runRepository).cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses()));

        ArgumentCaptor<RunCompletedEvent> events = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getAllValues()).hasSize(1);
        assertThat(events.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(events.getValue().getAgentId()).isEqualTo(agentId);
    }

    @Test
    void cancelRun_ofARunNoRuntimeOwns_keepsTheRecordedTransition() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        stubCancelUpdateWins();
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(false);

        RunResponse response = service.cancelRun(id);

        assertThat(response.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(response.getCompletedAt()).isNotNull();
        verify(runtimeControl, never()).cancel(any());
        verify(runRepository).cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses()));

        ArgumentCaptor<RunCompletedEvent> event = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancelRun_whenTheRunFinalizesDuringTheVerifiedStop_doesNotOverwriteTerminalState() {
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run running = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(running));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(true);
        // The database refuses the terminal write: the run COMPLETED while the stop was in flight.
        when(runRepository.cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenReturn(0);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.COMPLETED)));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                // Lane H2 finding 1: this test used to verify that the verified stop ran
                // and still demand "nothing was stopped". The stop DID happen; the refusal
                // must say the runtime was stopped and only the record was left alone.
                .hasMessage("Run " + id + " reached COMPLETED while the verified cancel completed;"
                        + " the runtime was stopped but the run record was not changed.");
        assertThat(running.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(runtimeControl).cancel(id);
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_whenANonOwnedRunFinalizesBeforeTheRecordedTransition_doesNotOverwriteTerminalState() {
        // C2: the run finalizes between the first read and the owns() check. There is no
        // verified stop on this branch, so the atomic update is the only guard.
        UUID agentId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Run running = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(running));
        when(runtimeControlProvider.getIfAvailable()).thenReturn(runtimeControl);
        when(runtimeControl.owns(agentId, id)).thenReturn(false);
        when(runRepository.cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenReturn(0);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.COMPLETED)));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already COMPLETED; nothing was stopped.");
        verify(runtimeControl, never()).cancel(any());
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_whenAConcurrentCancelAlreadyWon_publishesNoSecondEvent() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Run run = TestDataBuilder.aRun().withId(id).withAgentId(agentId)
                .withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        when(runRepository.cancelIfCancelable(eq(id), eq(RunStatus.CANCELLED),
                any(Instant.class), any(Instant.class), eq(RunService.cancelableStatuses())))
                .thenReturn(0);
        when(runRepository.findStateById(id)).thenReturn(Optional.of(stateView(RunStatus.CANCELLED)));

        assertThatThrownBy(() -> service.cancelRun(id))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Run " + id + " is already CANCELLED; nothing was stopped.");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void cancelRun_isNotSupported_soTheVerifiedStopRunsOutsideTheDatabaseTransaction() throws Exception {
        Transactional annotation = RunService.class
                .getMethod("cancelRun", UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    // ---------------------------------------------------------------------
    // The recorded state machine (#111 fix round 2, M3): RunService.updateRunStatus was a
    // caller-less read-then-save terminal writer that bypassed this branch's own one-conditional-
    // writer invariant, so it is deleted. The table it guarded is asserted here at the surviving
    // authority; the terminal writes themselves live in RunCancellationWriter.cancel (CANCELLED,
    // covered by cancelRun_* above and RunCancellationWriterTest) and the engine's
    // completeIfNotTerminal (COMPLETED/FAILED, covered by the AgentLoopEngine suites).
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "PENDING,RUNNING",
            "PENDING,COMPLETED",
            "INITIALIZING,PAUSED",
            "RUNNING,INITIALIZING",
            "COMPLETED,RUNNING",
            "FAILED,RUNNING",
            "CANCELLED,RUNNING",
            "PAUSED,COMPLETED",
            "WAITING_INPUT,PAUSED",
            "WAITING_INPUT,ABORTED",
            "WAITING_INPUT,INITIALIZING",
            "WAITING_INPUT,PENDING",
            "PAUSED,WAITING_INPUT"
    })
    void recordedStateMachine_invalidTransitions_areRejected(RunStatus from, RunStatus to) {
        assertThat(RunService.allowsTransition(from, to)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,INITIALIZING",
            "PENDING,CANCELLED",
            "INITIALIZING,RUNNING",
            "RUNNING,PAUSED",
            "RUNNING,ABORTED",
            "PAUSED,RUNNING",
            "ABORTED,CANCELLED",
            "RUNNING,WAITING_INPUT",
            "WAITING_INPUT,RUNNING",
            "WAITING_INPUT,COMPLETED",
            "WAITING_INPUT,FAILED",
            "WAITING_INPUT,CANCELLED"
    })
    void recordedStateMachine_validTransitions_areAccepted(RunStatus from, RunStatus to) {
        assertThat(RunService.allowsTransition(from, to)).isTrue();
    }

    /** M4: the conditional write's WHERE set comes from the same table, so it cannot drift from it. */
    @Test
    void transitionSources_areExactlyTheStatusesThatMayMoveToTheTarget() {
        assertThat(RunService.transitionSources(RunStatus.PAUSED)).containsExactly(RunStatus.RUNNING);
        assertThat(RunService.transitionSources(RunStatus.RUNNING)).containsExactlyInAnyOrder(
                RunStatus.INITIALIZING, RunStatus.PAUSED, RunStatus.WAITING_INPUT);
        assertThat(RunService.transitionSources(RunStatus.CANCELLED))
                .containsExactlyInAnyOrderElementsOf(RunService.cancelableStatuses());
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
