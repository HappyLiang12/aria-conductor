package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.KanbanItemAssigningEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.exception.PickupRejectedException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.listener.RunKanbanAutoCreator;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KanbanTransitionService}: every kanban transition must
 * trigger the matching run side effect (spec 4) — pickup dispatches a run,
 * dragging to TODO or BACKLOG pauses the linked run, request-changes
 * re-dispatches with the operator feedback, cancel denies open asks and cancels
 * the run. Eligibility failures are pre-validated by the evaluator before the
 * createRun proxy is crossed (no rollback-only surprises) and answered with a
 * 4xx rejection — a synchronous action carries its answer in the response, so
 * no lastError is written; unexpected createRun failures propagate.
 */
class KanbanTransitionServiceTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private RunService runService;
    private RunRepository runRepository;
    private AgentRepository agentRepository;
    private AgentPickerService agentPicker;
    private AgentPickupEligibility eligibility;
    private ApprovalRepository approvalRepository;
    private ApplicationEventPublisher eventPublisher;
    private KanbanTransitionService service;

    private KanbanItem card;

    @BeforeEach
    void setUp() {
        kanbanRepository = mock(KanbanRepository.class);
        kanbanService = mock(KanbanService.class);
        runService = mock(RunService.class);
        runRepository = mock(RunRepository.class);
        agentRepository = mock(AgentRepository.class);
        agentPicker = mock(AgentPickerService.class);
        eligibility = new AgentPickupEligibility();
        approvalRepository = mock(ApprovalRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new KanbanTransitionService(kanbanRepository, kanbanService, runService,
                runRepository, agentRepository, agentPicker, eligibility, approvalRepository, eventPublisher);

        card = KanbanItem.builder().id("c1").title("add CSV export")
                .status(KanbanStatus.TODO).priority(KanbanPriority.MEDIUM).build();
        when(kanbanRepository.findById("c1")).thenReturn(Optional.of(card));
        when(kanbanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kanbanService.transition(any(), any(), any())).thenAnswer(inv -> {
            card.setStatus(inv.getArgument(1));
            return card;
        });
        // Default: the linked agent is eligible (tests override when probing pre-validation).
        when(agentRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(agentWithStatus(inv.getArgument(0), HealthStatus.HEALTHY)));
        // Default: createRun dispatches a run with the well-known id.
        when(runService.createRun(any(CreateRunRequest.class)))
                .thenReturn(RunResponse.builder().id(RUN_ID).build());
    }

    private Agent agentWithStatus(UUID id, HealthStatus status) {
        return Agent.builder().id(id).name("BA Agent").healthStatus(status).build();
    }

    /** The shared card in REVIEW, linked to the given run link. */
    private void reviewCard(String linkedRunId) {
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(linkedRunId);
    }

    /** The shared card in IN_PROGRESS, linked to the given run link. */
    private void inProgressCard(String linkedRunId) {
        card.setStatus(KanbanStatus.IN_PROGRESS);
        card.setLinkedRunId(linkedRunId);
    }

    /** The shared card in DONE, carrying the finished run and the agent that ran it. */
    private void doneCard(String linkedRunId, String linkedAgentId) {
        card.setStatus(KanbanStatus.DONE);
        card.setLinkedRunId(linkedRunId);
        card.setLinkedAgentId(linkedAgentId);
    }

    /**
     * Creator wired to a no-op transaction manager. {@link RunKanbanAutoCreator}
     * runs its mirroring inside a TransactionTemplate so a failed transition can
     * be caught outside the transaction boundary; these tests only care that the
     * mirror still runs, not what the transaction does.
     */
    private RunKanbanAutoCreator newAutoCreator() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new RunKanbanAutoCreator(kanbanService, kanbanRepository, runRepository, transactionManager,
                Runnable::run);
    }

    // ---- behavior 1: TODO pickup ----

    @Test
    void todoPickup_publishesAssigningEventAndDispatchesRun() {
        card.setAgentTemplateId("ba-agent");
        when(agentPicker.pick(eq("ba-agent"), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(KanbanItemAssigningEvent.class);
        assertThat(((KanbanItemAssigningEvent) eventCaptor.getValue()).getItemId()).isEqualTo("c1");

        // Template hint falls back to the item's own agentTemplateId.
        verify(agentPicker).pick("ba-agent", "add CSV export", null);
        assertThat(card.getLinkedAgentId()).isEqualTo(AGENT_ID.toString());
        assertThat(card.getAssignee()).isEqualTo("BA Agent");
        assertThat(card.getAgentTemplateId()).isEqualTo("ba-agent");

        ArgumentCaptor<CreateRunRequest> runCaptor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getAgentId()).isEqualTo(AGENT_ID);
        assertThat(runCaptor.getValue().getPromptSeed()).contains("add CSV export");
        // Orchestrator-created runs suppress the auto-card: the pickup owns linkage.
        assertThat(runCaptor.getValue().isSuppressAutoCard()).isTrue();
        // The created run is linked back onto the card before the IN_PROGRESS move.
        assertThat(card.getLinkedRunId()).isEqualTo(RUN_ID.toString());

        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    @Test
    void todoPickup_requestTemplateHintOverridesItemTemplate() {
        card.setAgentTemplateId("default-agent");
        when(agentPicker.pick(eq("ba-agent"), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).agentTemplateId("ba-agent").build());

        verify(agentPicker).pick(eq("ba-agent"), anyString(), any());
        assertThat(card.getAgentTemplateId()).isEqualTo("ba-agent");
    }

    @Test
    void todoPickup_withExistingAssignee_skipsAssignmentButStillDispatches() {
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(AGENT_ID.toString());

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        // Manual re-dispatch: no assign phase, but a new run is still created.
        verify(eventPublisher, never()).publishEvent(any(KanbanItemAssigningEvent.class));
        verify(agentPicker, never()).pick(any(), any(), any());
        verify(runService).createRun(any(CreateRunRequest.class));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    @Test
    void pickup_assigneeWithoutLinkedAgent_assignsAndDispatches() {
        // Aria / MCP create_kanban_item can set a display assignee without an
        // agent id. The assign phase must run — otherwise the eligibility check
        // hits UUID.fromString(null) (NPE) and the card can never be dispatched.
        card.setAssignee("Someone");
        when(agentPicker.pick(isNull(), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));

        KanbanItem result = service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        verify(agentPicker).pick(isNull(), anyString(), any());
        assertThat(result.getLinkedAgentId()).isEqualTo(AGENT_ID.toString());
        assertThat(result.getAssignee()).isEqualTo("BA Agent");
        verify(runService).createRun(any(CreateRunRequest.class));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    // ---- behavior 2: ineligible agent is rejected before createRun is crossed ----

    @Test
    void pickupFailure_unhealthyAgent_rejectedWithReasonsBeforeCreateRun() {
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(AGENT_ID.toString());
        when(agentRepository.findById(AGENT_ID))
                .thenReturn(Optional.of(agentWithStatus(AGENT_ID, HealthStatus.UNHEALTHY)));

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("AGENT_NOT_ELIGIBLE");
                    assertThat(rejected.details()).containsEntry("agentId", AGENT_ID.toString());
                    assertThat(rejected.details().get("reasons")).isEqualTo(List.of("UNHEALTHY"));
                });
        // The transaction proxy must never be crossed with a doomed request, and a
        // synchronous rejection is answered by the response — not by the card.
        verify(runService, never()).createRun(any(CreateRunRequest.class));
        verify(kanbanService, never()).transition(any(), any(), any());
        verify(kanbanRepository, never()).save(any());
    }

    @Test
    void pickupFailure_retiredAgent_rejectedWithReasonsBeforeCreateRun() {
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(AGENT_ID.toString());
        when(agentRepository.findById(AGENT_ID))
                .thenReturn(Optional.of(agentWithStatus(AGENT_ID, HealthStatus.RETIRED)));

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("AGENT_NOT_ELIGIBLE");
                    assertThat(rejected.details().get("reasons")).isEqualTo(List.of("RETIRED"));
                });
        verify(runService, never()).createRun(any(CreateRunRequest.class));
        verify(kanbanRepository, never()).save(any());
    }

    @Test
    void pickupFailure_missingAgent_rejectedWithAgentId() {
        UUID missing = UUID.randomUUID();
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(missing.toString());
        when(agentRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("AGENT_NOT_ELIGIBLE");
                    assertThat(rejected.details()).containsEntry("agentId", missing.toString());
                });
        verify(runService, never()).createRun(any(CreateRunRequest.class));
    }

    @Test
    void pickupCreateRunUnexpectedFailure_propagatesAndSkipsTransition() {
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(AGENT_ID.toString()); // pre-validation passes (HEALTHY)
        when(runService.createRun(any(CreateRunRequest.class)))
                .thenThrow(new IllegalStateException("db connection lost"));

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db connection lost");

        // Unexpected failures roll the whole transition back: no IN_PROGRESS move.
        verify(kanbanService, never()).transition(any(), any(), any());
        assertThat(card.getStatus()).isEqualTo(KanbanStatus.TODO);
    }

    @Test
    void pickupOnEmptyPoolRejectsWithNoEligibleAgent() {
        when(agentPicker.pick(any(), anyString(), any())).thenThrow(new PickupRejectedException(
                "NO_ELIGIBLE_AGENT",
                "No pickup-eligible agent: 1 agent(s) evaluated and all excluded (Aria: RESERVED_OPERATOR_AGENT)",
                Map.of("evaluated", 1, "excluded",
                        List.of(Map.of("name", "Aria", "reasons", List.of("RESERVED_OPERATOR_AGENT"))))));

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("NO_ELIGIBLE_AGENT");
                    assertThat(rejected.details()).containsEntry("evaluated", 1);
                });

        verify(runService, never()).createRun(any(CreateRunRequest.class));
        // No IN_PROGRESS transition after the failed assign phase, and nothing
        // recorded on the card: the caller got the answer in the response.
        verify(kanbanService, never()).transition(any(), any(), any());
        verify(kanbanRepository, never()).save(any());
    }

    // ---- behavior 3: no-op guard ----

    @Test
    void sameStatusTransition_isNoOpWithoutSideEffects() {
        card.setStatus(KanbanStatus.IN_PROGRESS);
        card.setLinkedRunId(RUN_ID.toString());
        card.setLastError("previous pickup hiccup");
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.RUNNING).build()));

        KanbanItem result = service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        assertThat(result).isSameAs(card);
        // Truly no-op: no pause, no run dispatch, no card transition, and
        // lastError is not cleared either.
        verifyNoInteractions(runService, agentPicker);
        verify(kanbanService, never()).transition(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(card.getLastError()).isEqualTo("previous pickup hiccup");
    }

    @Test
    void transition_nullStatus_throwsBeforeAnyLookup() {
        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Target status is required");
        verify(kanbanRepository, never()).findById(any());
    }

    // ---- behavior 4: drag back ----

    @Test
    void dragBackFromInProgress_pausesRunningRun() {
        card.setStatus(KanbanStatus.IN_PROGRESS);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.RUNNING).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.BACKLOG).comment("reprioritise").build());

        verify(runService).pauseRun(RUN_ID);
        verify(kanbanService).transition("c1", KanbanStatus.BACKLOG, "reprioritise");
    }

    @Test
    void dragBackFromInProgress_withoutLinkedRun_transitionsWithoutPause() {
        card.setStatus(KanbanStatus.IN_PROGRESS);

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.BACKLOG).build());

        verify(runService, never()).pauseRun(any());
        verify(kanbanService).transition("c1", KanbanStatus.BACKLOG, null);
    }

    @Test
    void inProgressToTodo_pausesRunWithoutRedispatch() {
        card.setStatus(KanbanStatus.IN_PROGRESS);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.RUNNING).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).comment("needs rework").build());

        verify(runService).pauseRun(RUN_ID);
        verify(kanbanService).transition("c1", KanbanStatus.TODO, "needs rework");
        // Dragging back must not dispatch a new run.
        verify(runService, never()).createRun(any(CreateRunRequest.class));
    }

    @Test
    void leavingInProgressTowardsTodoPausesARunningRunAndDetaches() {
        inProgressCard("00000000-0000-0000-0000-0000000000ad");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000ad")))
                .thenReturn(Optional.of(Run.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-0000000000ad"))
                        .status(RunStatus.RUNNING).build()));

        KanbanItem moved = service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).build());

        verify(runService).pauseRun(UUID.fromString("00000000-0000-0000-0000-0000000000ad"));
        // The cleared link is persisted before the card moves: a parked card must
        // not keep looking like the owner of a live run.
        verify(kanbanRepository).save(card);
        assertThat(moved.getLinkedRunId()).isNull();
    }

    @Test
    void leavingInProgressTowardsTodoCancelsANotYetRunningRun() {
        inProgressCard("00000000-0000-0000-0000-0000000000ae");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000ae")))
                .thenReturn(Optional.of(Run.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-0000000000ae"))
                        .status(RunStatus.INITIALIZING).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.BACKLOG).build());

        // PENDING and INITIALIZING cannot be paused (RunStatus forbids it); cancelling
        // is the only legal stop and nothing has been produced yet.
        verify(runService).cancelRun(UUID.fromString("00000000-0000-0000-0000-0000000000ae"));
        verify(runService, never()).pauseRun(any());
        assertThat(card.getLinkedRunId()).isNull();
    }

    @Test
    void detachClearsTheLinkEvenWhenTheRunIsGone() {
        inProgressCard("00000000-0000-0000-0000-0000000000af");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000af")))
                .thenReturn(Optional.empty());

        KanbanItem moved = service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).build());

        assertThat(moved.getLinkedRunId()).isNull();
        verify(runService, never()).pauseRun(any());
        verify(runService, never()).cancelRun(any());
    }

    @Test
    void leavingInProgressTowardsTodoLeavesAPausedRunAlone() {
        inProgressCard("00000000-0000-0000-0000-0000000000b0");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000b0")))
                .thenReturn(Optional.of(Run.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-0000000000b0"))
                        .status(RunStatus.PAUSED).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).build());

        verify(runService, never()).pauseRun(any());
        verify(runService, never()).cancelRun(any());
        assertThat(card.getLinkedRunId()).isNull();
    }

    /**
     * The chain a parked card must break: RunService.createRun publishes
     * RunStartedEvent while the run is still PENDING, so RunKanbanAutoCreator
     * auto-creates a TODO card linked to it. Parking that card to BACKLOG
     * cancels the PENDING run, and cancelRun publishes
     * RunCompletedEvent(CANCELLED); should the link survive, the cancellation
     * the stop itself caused drags the parked card into CANCELLED/Archived.
     */
    @Test
    void parkingATodoCardDetachesTheRunItsStopCancelled() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        card.setStatus(KanbanStatus.TODO);
        card.setLinkedRunId(runId.toString());
        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder()
                .id(runId).status(RunStatus.PENDING).build()));
        // The listener finds exactly the cards the run link still points at:
        // this one while the link lives, nothing once the stop detaches it.
        when(kanbanRepository.findByLinkedRunId(anyString())).thenAnswer(inv ->
                runId.toString().equals(card.getLinkedRunId()) ? List.of(card) : List.of());
        RunKanbanAutoCreator autoCreator = newAutoCreator();

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.BACKLOG).comment("park it").build());

        // The stop cancelled the not-yet-running run — that cancellation is the
        // event the card would be re-targeted by.
        verify(runService).cancelRun(runId);
        assertThat(card.getLinkedRunId()).isNull();

        autoCreator.onRunCompleted(new RunCompletedEvent(this, runId, AGENT_ID, RunStatus.CANCELLED));

        assertThat(card.getStatus()).isEqualTo(KanbanStatus.BACKLOG);
        verify(kanbanService, never()).transition(eq("c1"), eq(KanbanStatus.CANCELLED), anyString());
    }

    /**
     * Same chain from REVIEW: the stop pauses a RUNNING run (no event) and must
     * not leave a claim on it, or an out-of-band resume can complete the run and
     * drag the parked card to REVIEW.
     */
    @Test
    void parkingAReviewCardDetachesTheRunItsStopPaused() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(runId.toString());
        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder()
                .id(runId).status(RunStatus.RUNNING).build()));
        when(kanbanRepository.findByLinkedRunId(anyString())).thenAnswer(inv ->
                runId.toString().equals(card.getLinkedRunId()) ? List.of(card) : List.of());
        RunKanbanAutoCreator autoCreator = newAutoCreator();

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.BACKLOG).build());

        verify(runService).pauseRun(runId);
        assertThat(card.getLinkedRunId()).isNull();

        autoCreator.onRunCompleted(new RunCompletedEvent(this, runId, AGENT_ID, RunStatus.COMPLETED));

        assertThat(card.getStatus()).isEqualTo(KanbanStatus.BACKLOG);
    }

    @Test
    void doneToTodo_redoesWithAFreshRun() {
        // Defect D3: a finished card is re-doable. Redo normalizes DONE -> TODO
        // and then behaves exactly like a normal dispatch (fresh run).
        card.setStatus(KanbanStatus.DONE);
        card.setAssignee("dev-agent");
        card.setLinkedAgentId(AGENT_ID.toString());
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.COMPLETED).build()));
        // The re-open drops the finished run and the agent that ran it, so the
        // pickup re-assigns before dispatching.
        when(agentPicker.pick(any(), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).comment("redo after demo").build());

        // The completed run is never paused/resumed; a NEW run carries the redo.
        verify(runService, never()).pauseRun(any());
        verify(runService, never()).resumeRun(any());
        verify(runService).createRun(any(CreateRunRequest.class));
        assertThat(card.getLinkedAgentId()).isEqualTo(AGENT_ID.toString());
        verify(kanbanService).transition("c1", KanbanStatus.TODO, "redo after demo");
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, "redo after demo");
    }

    @Test
    void doneToBacklog_queuesWithoutAnyRun() {
        card.setStatus(KanbanStatus.DONE);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.COMPLETED).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.BACKLOG).comment("park it").build());

        verify(runService, never()).createRun(any(CreateRunRequest.class));
        verify(runService, never()).pauseRun(any());
        verify(kanbanService).transition("c1", KanbanStatus.BACKLOG, "park it");
    }

    @Test
    void reopeningDoneClearsStaleRunAndAgentLinksBeforeDispatch() {
        doneCard("00000000-0000-0000-0000-0000000000b1", "00000000-0000-0000-0000-0000000000b2");
        when(agentPicker.pick(any(), anyString(), any())).thenThrow(
                new PickupRejectedException("NO_ELIGIBLE_AGENT", "none", Map.of()));

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).build()))
                .isInstanceOf(PickupRejectedException.class);

        assertThat(card.getLinkedRunId()).isNull();
        assertThat(card.getLinkedAgentId()).isNull();
    }

    @Test
    void doneToInProgress_isRejected() {
        card.setStatus(KanbanStatus.DONE);

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(runService, never()).createRun(any(CreateRunRequest.class));
    }

    @Test
    void backlogToTodo_normalizesThenPicksUp() {
        card.setStatus(KanbanStatus.BACKLOG);
        card.setAgentTemplateId("ba-agent");
        when(agentPicker.pick(eq("ba-agent"), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).comment("ready now").build());

        // Intermediate BACKLOG -> TODO normalization precedes the pickup's
        // TODO -> IN_PROGRESS dispatch step.
        verify(kanbanService).transition("c1", KanbanStatus.TODO, "ready now");
        verify(runService).createRun(any(CreateRunRequest.class));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, "ready now");
    }

    @Test
    void backlogToInProgress_isRejected_routesThroughTodoFirst() {
        card.setStatus(KanbanStatus.BACKLOG);

        // D3: Backlog never executes — direct dispatch is rejected on all
        // surfaces; the card must be routed through Todo to pick up.
        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Route the card through Todo first — Backlog items do not dispatch directly");

        verify(kanbanService, never()).transition(any(), any(), any());
        verify(runService, never()).createRun(any(CreateRunRequest.class));
    }

    @Test
    void terminalCardToTodo_rejectsBeforePickupSideEffects() {
        card.setStatus(KanbanStatus.CANCELLED);

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid kanban transition: CANCELLED -> TODO");

        verify(runService, never()).createRun(any(CreateRunRequest.class));
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    // ---- behavior 5: cancel ----

    @Test
    void cancelFromReview_deniesPendingAsksAndCancelsRun() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.RUNNING).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.CANCELLED).build());

        verify(approvalRepository).denyPendingByKanbanItemId(eq("c1"), eq("task cancelled"), any());
        verify(runService).cancelRun(RUN_ID);
        verify(kanbanService).transition("c1", KanbanStatus.CANCELLED, null);
    }

    @Test
    void cancel_transitionsCardBeforeCancellingRun() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.RUNNING).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.CANCELLED).build());

        // The card must land on CANCELLED before any listener racing the run
        // cancellation can observe the card mid-flight.
        InOrder inOrder = inOrder(kanbanService, runService);
        inOrder.verify(kanbanService).transition("c1", KanbanStatus.CANCELLED, null);
        inOrder.verify(runService).cancelRun(RUN_ID);
    }

    @Test
    void cancelFromReview_withCompletedRun_doesNotCancelRun() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.COMPLETED).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.CANCELLED).build());

        verify(approvalRepository).denyPendingByKanbanItemId(eq("c1"), eq("task cancelled"), any());
        verify(runService, never()).cancelRun(any());
        verify(kanbanService).transition("c1", KanbanStatus.CANCELLED, null);
    }

    // ---- behavior 6: request changes ----

    @Test
    void reviewToTodoWithFeedback_marksAsksStaleAndRedispatches() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(AGENT_ID.toString());

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).feedback("use semicolons").comment("changes requested").build());

        verify(approvalRepository).markStaleByKanbanItemId(eq("c1"), any());
        ArgumentCaptor<CreateRunRequest> runCaptor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getPromptSeed()).contains("use semicolons");
        verify(kanbanService).transition("c1", KanbanStatus.TODO, "changes requested");
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, "changes requested");
    }

    @Test
    void reviewToTodoWithFeedback_unassignedCard_picksAgentThenRedispatches() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setAgentTemplateId("ba-agent");
        when(agentPicker.pick(eq("ba-agent"), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.TODO).feedback("redo").build());

        verify(approvalRepository).markStaleByKanbanItemId(eq("c1"), any());
        assertThat(card.getLinkedAgentId()).isEqualTo(AGENT_ID.toString());
        verify(kanbanService).transition("c1", KanbanStatus.TODO, null);
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    // ---- behavior 7: REVIEW -> IN_PROGRESS (approve & continue) ----

    @Test
    void reviewToInProgressOnFinishedRunIsRejected() {
        reviewCard("00000000-0000-0000-0000-0000000000ab");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000ab")))
                .thenReturn(Optional.of(Run.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-0000000000ab"))
                        .status(RunStatus.COMPLETED).build()));

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isEqualTo("RUN_ALREADY_FINISHED"));

        // The rejection is answered by the response, not by the card: it stays in
        // REVIEW with nothing recorded on it.
        assertThat(card.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        assertThat(card.getLastError()).isNull();
        verify(runService, never()).resumeRun(any());
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    @Test
    void reviewToInProgressOnCorruptLinkIsRejected() {
        reviewCard("not-a-uuid");

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isEqualTo("CORRUPT_RUN_LINK"));

        assertThat(card.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        verify(runRepository, never()).findById(any());
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    @Test
    void reviewToInProgressWithoutLinkedRunIsRejected() {
        // A whitespace-only link is blank by the dispatch-intent predicate but used
        // to persist verbatim and reach IN_PROGRESS with no resolvable run.
        reviewCard("   ");

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> assertThat(((PickupRejectedException) e).code())
                        .isEqualTo("RUN_NOT_FOUND"));

        assertThat(card.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        verify(runRepository, never()).findById(any());
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    @Test
    void reviewToInProgressOnMissingRunIsRejected() {
        reviewCard("00000000-0000-0000-0000-0000000000aa");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000aa")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build()))
                .isInstanceOf(PickupRejectedException.class)
                .satisfies(e -> {
                    PickupRejectedException rejected = (PickupRejectedException) e;
                    assertThat(rejected.code()).isEqualTo("RUN_NOT_FOUND");
                    assertThat(rejected.details())
                            .containsEntry("runId", "00000000-0000-0000-0000-0000000000aa");
                });

        assertThat(card.getStatus()).isEqualTo(KanbanStatus.REVIEW);
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    @Test
    void reviewToInProgressOnPausedRunResumes() {
        reviewCard("00000000-0000-0000-0000-0000000000ac");
        when(runRepository.findById(UUID.fromString("00000000-0000-0000-0000-0000000000ac")))
                .thenReturn(Optional.of(Run.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-0000000000ac"))
                        .status(RunStatus.PAUSED).build()));

        KanbanItem moved = service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        assertThat(moved.getStatus()).isEqualTo(KanbanStatus.IN_PROGRESS);
        verify(runService).resumeRun(UUID.fromString("00000000-0000-0000-0000-0000000000ac"));
        verify(runService, never()).createRun(any(CreateRunRequest.class));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    // ---- behavior 8: prompt seed caps ----

    @Test
    void pickup_promptSeedSectionsAreCapped() {
        card.setTitle("T".repeat(250));
        card.setDescription("D".repeat(4100));
        card.setAssignee("BA Agent");
        card.setLinkedAgentId(AGENT_ID.toString());

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).feedback("F".repeat(2100)).build());

        ArgumentCaptor<CreateRunRequest> runCaptor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(runCaptor.capture());
        String seed = runCaptor.getValue().getPromptSeed();
        assertThat(seed).startsWith("Kanban task: " + "T".repeat(200));
        assertThat(seed).contains("\n\nDescription:\n" + "D".repeat(4000));
        assertThat(seed).contains("\n\nOperator feedback on the previous attempt:\n" + "F".repeat(2000));
        // Delimiters are unchanged; no section exceeds its cap.
        assertThat(seed).doesNotContain("T".repeat(201));
        assertThat(seed).doesNotContain("D".repeat(4001));
        assertThat(seed).doesNotContain("F".repeat(2001));
    }
}
