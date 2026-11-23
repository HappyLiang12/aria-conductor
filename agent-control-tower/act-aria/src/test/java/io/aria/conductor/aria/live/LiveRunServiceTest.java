package io.aria.conductor.aria.live;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The lazy live run (spec L4): a turn that calls at least one tool owns exactly one
 * {@code Run} row, created on the first call, and a turn that calls none creates
 * nothing. The row is written with {@code RunRepository.save} only — never through
 * {@code RunService.createRun}, whose {@code RunStartedEvent} would dispatch the
 * execution engine against a prompt seed no core ever runs. The audit event makes the
 * run visible in the dashboard trail like any other run.
 */
@ExtendWith(MockitoExtension.class)
class LiveRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T09:15:00Z");

    @Mock
    private RunRepository runRepository;

    private final List<ApplicationEvent> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher =
            event -> published.add((ApplicationEvent) event);

    private LiveRunService service;

    @BeforeEach
    void setUp() {
        service = new LiveRunService(runRepository, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theFirstToolCallSavesOneRunningRunWithTheConversationAndTheLiveSeed() {
        stubSave();

        UUID runId = service.findOrCreateForToolTurn(session("conv-7"), "get_run");

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        Run run = saved.getValue();
        assertThat(run.getAgentId()).isEqualTo(AriaConstants.ARIA_AGENT_ID);
        assertThat(run.getConversationId()).isEqualTo("conv-7");
        assertThat(run.getPromptSeed()).isEqualTo("[live] tool: get_run");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getMaxIterations()).isPositive();
        assertThat(run.getCreatedAt()).isEqualTo(NOW);
        assertThat(run.getId()).isEqualTo(runId);
    }

    @Test
    void theFirstToolCallStoresTheRunOnTheSessionAndReusesItForTheRestOfTheTurn() {
        stubSave();
        LiveSession session = session("conv-7");

        UUID first = service.findOrCreateForToolTurn(session, "get_run");
        UUID second = service.findOrCreateForToolTurn(session, "get_time");

        assertThat(session.runId()).isEqualTo(first);
        assertThat(second).isEqualTo(first);
        verify(runRepository).save(any());
    }

    @Test
    void aNewTurnOnTheSameSocketCreatesItsOwnRun() {
        stubSave();
        LiveSession session = session("conv-7");
        UUID firstTurn = service.findOrCreateForToolTurn(session, "get_run");

        // The handler clears the slot when the turn settles (Task 4); the next turn's
        // first tool call then owns a new row.
        session.runId(null);
        UUID secondTurn = service.findOrCreateForToolTurn(session, "get_run");

        assertThat(secondTurn).isNotEqualTo(firstTurn);
    }

    @Test
    void aTurnWithoutAToolCallCreatesNoRun() {
        LiveSession session = session("conv-7");

        assertThat(session.runId()).isNull();
        verifyNoInteractions(runRepository);
        assertThat(published).isEmpty();
    }

    @Test
    void theLazyRunPublishesAnAuditEventAndNoRunStartedEvent() {
        stubSave();

        UUID runId = service.findOrCreateForToolTurn(session("conv-7"), "get_run");

        assertThat(published)
                .as("a RunStartedEvent would dispatch the engine; the live run must never do that")
                .noneMatch(event -> event instanceof RunStartedEvent || event instanceof RunCompletedEvent)
                .singleElement()
                .isInstanceOf(AuditLogEvent.class);
        AuditLogEvent audit = (AuditLogEvent) published.get(0);
        assertThat(audit.getEventType()).isEqualTo("RUN");
        assertThat(audit.getResourceType()).isEqualTo("Run");
        assertThat(audit.getResourceId()).isEqualTo(runId.toString());
        assertThat(audit.getAction()).isEqualTo("START");
        assertThat(audit.getDetails()).isEqualTo("Run started (live tool turn)");
        assertThat(audit.getConversationId()).isEqualTo("conv-7");
    }

    @Test
    void theSecondCallInTheTurnPublishesNoSecondAuditEvent() {
        stubSave();
        LiveSession session = session("conv-7");

        service.findOrCreateForToolTurn(session, "get_run");
        service.findOrCreateForToolTurn(session, "list_agents");

        assertThat(published).hasSize(1);
    }

    @Test
    void settleWritesTheTerminalStatusAndTheCompletionTime() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.RUNNING).createdAt(NOW).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.settle(runId, RunStatus.COMPLETED, null);

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getValue().getCompletedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getErrorMessage()).isNull();
    }

    @Test
    void settleRecordsTheReasonOnAFailedRun() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.RUNNING).createdAt(NOW).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.settle(runId, RunStatus.FAILED, "bridge closed");

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("bridge closed");
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
    }

    /**
     * Task 4 parks the run as WAITING_INPUT while an approval is pending; a parked run
     * is alive, so settle must not stamp a completion time on it.
     */
    @Test
    void settleDoesNotCompleteARunThatIsOnlyParked() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.RUNNING).createdAt(NOW).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.settle(runId, RunStatus.WAITING_INPUT, "approval pending");

        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_INPUT);
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void settleIgnoresAnUnknownRunInsteadOfThrowingAtTheSocket() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        service.settle(runId, RunStatus.COMPLETED, null);

        verify(runRepository, never()).save(any());
        assertThat(published).isEmpty();
    }

    @Test
    void settleDoesNotOverwriteAnAlreadyTerminalRun() {
        UUID runId = UUID.randomUUID();
        Run run = Run.builder().id(runId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.COMPLETED).createdAt(NOW).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        service.settle(runId, RunStatus.FAILED, "late failure");

        verify(runRepository, never()).save(any());
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    private void stubSave() {
        when(runRepository.save(any(Run.class))).thenAnswer(invocation -> {
            Run run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID());
            }
            return run;
        });
    }

    private static LiveSession session(String conversationId) {
        // The run slot is the only state these tests touch; no socket is involved.
        return new LiveSession("s-" + UUID.randomUUID(), conversationId, null, null);
    }
}
