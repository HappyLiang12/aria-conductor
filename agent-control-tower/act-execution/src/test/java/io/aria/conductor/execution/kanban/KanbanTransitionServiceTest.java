package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.event.KanbanItemAssigningEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KanbanTransitionService}: every kanban transition must
 * trigger the matching run side effect (spec 4) — pickup dispatches a run,
 * dragging back pauses it, request-changes re-dispatches with the operator
 * feedback, cancel denies open asks and cancels the run.
 */
class KanbanTransitionServiceTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private KanbanRepository kanbanRepository;
    private KanbanService kanbanService;
    private RunService runService;
    private RunRepository runRepository;
    private AgentPickerService agentPicker;
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
        agentPicker = mock(AgentPickerService.class);
        approvalRepository = mock(ApprovalRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new KanbanTransitionService(kanbanRepository, kanbanService, runService,
                runRepository, agentPicker, approvalRepository, eventPublisher);

        card = KanbanItem.builder().id("c1").title("add CSV export")
                .status(KanbanStatus.TODO).priority(KanbanPriority.MEDIUM).build();
        when(kanbanRepository.findById("c1")).thenReturn(Optional.of(card));
        when(kanbanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kanbanService.transition(any(), any(), any())).thenAnswer(inv -> card);
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

    // ---- behavior 2: pickup failure ----

    @Test
    void pickupFailure_keepsCardInTodoAndRecordsAbbreviatedLastError() {
        when(agentPicker.pick(any(), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));
        when(runService.createRun(any(CreateRunRequest.class)))
                .thenThrow(new IllegalArgumentException("x".repeat(600)));

        KanbanItem result = service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        assertThat(result.getStatus()).isEqualTo(KanbanStatus.TODO);
        assertThat(result.getLastError()).hasSize(480);
        verify(kanbanService, never()).transition(any(), any(), any());
        verify(kanbanRepository).save(card);
    }

    @Test
    void pickupFailure_shortMessage_storedVerbatim() {
        when(agentPicker.pick(any(), anyString(), any()))
                .thenReturn(new AgentPickerService.Choice(AGENT_ID, "BA Agent"));
        when(runService.createRun(any(CreateRunRequest.class)))
                .thenThrow(new IllegalArgumentException("unhealthy agent"));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        assertThat(card.getLastError()).isEqualTo("unhealthy agent");
        assertThat(card.getStatus()).isEqualTo(KanbanStatus.TODO);
        verify(kanbanService, never()).transition(any(), any(), any());
    }

    // ---- behavior 3: drag back ----

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

    // ---- behavior 4: cancel ----

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

    // ---- behavior 5: request changes ----

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

    // ---- behavior 6: REVIEW -> IN_PROGRESS (approve & continue) ----

    @Test
    void reviewToInProgress_withPausedRun_resumesRun() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.PAUSED).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        verify(runService).resumeRun(RUN_ID);
        verify(runService, never()).createRun(any(CreateRunRequest.class));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
    }

    @Test
    void reviewToInProgress_withCompletedRun_transitionsWithoutResume() {
        card.setStatus(KanbanStatus.REVIEW);
        card.setLinkedRunId(RUN_ID.toString());
        when(runRepository.findById(RUN_ID))
                .thenReturn(Optional.of(Run.builder().status(RunStatus.COMPLETED).build()));

        service.transition("c1", TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS).build());

        verify(runService, never()).resumeRun(any(UUID.class));
        verify(kanbanService).transition("c1", KanbanStatus.IN_PROGRESS, null);
        assertThat(card.getLastError()).isNull();
    }
}
