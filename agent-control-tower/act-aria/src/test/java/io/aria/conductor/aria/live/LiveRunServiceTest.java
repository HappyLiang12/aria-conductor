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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 *
 * <p>Settling is a single conditional statement, not a read-then-save: an operator
 * cancel that commits first wins, and this service then reports the cancelled state
 * instead of overwriting it. The settle also owns the session slot, so a turn cannot
 * leak its settled row into the next one.
 */
@ExtendWith(MockitoExtension.class)
class LiveRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T09:15:00Z");

    private static final List<RunStatus> TERMINAL =
            List.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

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
        assertThat(run.getPromptSeed()).startsWith(LiveRunService.LIVE_SEED_PREFIX);
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
        when(runRepository.findStateById(first)).thenReturn(Optional.of(state(RunStatus.RUNNING)));

        UUID second = service.findOrCreateForToolTurn(session, "get_time");

        assertThat(session.runId()).isEqualTo(first);
        assertThat(second).isEqualTo(first);
        verify(runRepository, times(1)).save(any());
    }

    @Test
    void aParkedRunStaysTheTurnsRowUntilTheApprovalIsAnswered() {
        UUID parked = UUID.randomUUID();
        when(runRepository.findStateById(parked)).thenReturn(Optional.of(state(RunStatus.WAITING_INPUT)));
        LiveSession session = session("conv-7");
        session.runId(parked);

        assertThat(service.findOrCreateForToolTurn(session, "get_run")).isEqualTo(parked);
        verify(runRepository, never()).save(any());
    }

    @Test
    void aRunThatLeftTheLiveStatesIsNeverReused() {
        stubSave();
        UUID cancelled = UUID.randomUUID();
        when(runRepository.findStateById(cancelled)).thenReturn(Optional.of(state(RunStatus.CANCELLED)));
        LiveSession session = session("conv-7");
        session.runId(cancelled);

        UUID fresh = service.findOrCreateForToolTurn(session, "get_run");

        assertThat(fresh).isNotEqualTo(cancelled);
        assertThat(session.runId()).isEqualTo(fresh);
    }

    @Test
    void aSlotPointingAtAMissingRunYieldsAFreshRun() {
        stubSave();
        UUID gone = UUID.randomUUID();
        when(runRepository.findStateById(gone)).thenReturn(Optional.empty());
        LiveSession session = session("conv-7");
        session.runId(gone);

        assertThat(service.findOrCreateForToolTurn(session, "get_run")).isNotEqualTo(gone);
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
        UUID first = service.findOrCreateForToolTurn(session, "get_run");
        when(runRepository.findStateById(first)).thenReturn(Optional.of(state(RunStatus.RUNNING)));

        service.findOrCreateForToolTurn(session, "list_agents");

        assertThat(published).hasSize(1);
    }

    @Test
    void settleWritesTheTerminalStatusWithTheConditionalWriterAndNeverSaves() {
        UUID runId = UUID.randomUUID();
        stubTerminalWrite(1);
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.COMPLETED)));

        assertThat(service.settle(null, runId, RunStatus.COMPLETED, null))
                .isEqualTo(RunStatus.COMPLETED);

        verify(runRepository).completeIfNotTerminal(eq(runId), eq(RunStatus.COMPLETED), eq(0), eq(0L),
                eq(NOW), isNull(), isNull(), isNull(), isNull(), eq(NOW), eq(TERMINAL));
        verify(runRepository, never()).save(any());
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    @Test
    void settleRecordsTheReasonOnAFailedRun() {
        UUID runId = UUID.randomUUID();
        stubTerminalWrite(1);
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.FAILED)));

        service.settle(null, runId, RunStatus.FAILED, "bridge closed");

        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(runRepository).completeIfNotTerminal(eq(runId), eq(RunStatus.FAILED), eq(0), eq(0L),
                eq(NOW), isNull(), errorMessage.capture(), isNull(), isNull(), eq(NOW), eq(TERMINAL));
        assertThat(errorMessage.getValue()).isEqualTo("bridge closed");
    }

    /**
     * Task 4 parks the run as WAITING_INPUT while an approval is pending; a parked run
     * is alive, so settle must not stamp a completion time on it — and it must only
     * park a RUNNING row.
     */
    @Test
    void settleParksARunningRunWithTheExactCurrentStateWriter() {
        UUID runId = UUID.randomUUID();
        when(runRepository.updateStatusIfCurrentIn(runId, RunStatus.WAITING_INPUT, null, NOW,
                List.of(RunStatus.RUNNING))).thenReturn(1);
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.WAITING_INPUT)));

        assertThat(service.settle(null, runId, RunStatus.WAITING_INPUT, "approval pending"))
                .isEqualTo(RunStatus.WAITING_INPUT);

        verify(runRepository, never()).completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                any(), any(), any(), any(), any(), any(), any());
        verify(runRepository, never()).save(any());
    }

    /**
     * The finding this pins: a cancel committed between the read and the settle must
     * survive. The conditional write reports 0 rows, so the service claims nothing and
     * hands back the committed state instead of resurrecting the run.
     */
    @Test
    void settleDoesNotResurrectARunCancelledBeforeTheConditionalWrite() {
        UUID runId = UUID.randomUUID();
        stubTerminalWrite(0);
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.CANCELLED)));

        RunStatus settled = service.settle(null, runId, RunStatus.COMPLETED, null);

        assertThat(settled).isEqualTo(RunStatus.CANCELLED);
        verify(runRepository, times(1)).completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                any(), any(), any(), any(), any(), any(), any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void settleDoesNotOverwriteAnAlreadyTerminalRun() {
        UUID runId = UUID.randomUUID();
        stubTerminalWrite(0);
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.COMPLETED)));

        assertThat(service.settle(null, runId, RunStatus.FAILED, "late failure"))
                .isEqualTo(RunStatus.COMPLETED);
        verify(runRepository, never()).save(any());
    }

    @Test
    void settleIgnoresAnUnknownRunInsteadOfThrowingAtTheSocket() {
        UUID runId = UUID.randomUUID();
        stubTerminalWrite(0);
        when(runRepository.findStateById(runId)).thenReturn(Optional.empty());

        assertThat(service.settle(null, runId, RunStatus.COMPLETED, null)).isNull();
        verify(runRepository, never()).save(any());
    }

    @Test
    void settleIgnoresAStatusItCannotOwnInsteadOfWritingItBlindly() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.RUNNING)));

        assertThat(service.settle(null, runId, RunStatus.RUNNING, null)).isEqualTo(RunStatus.RUNNING);

        verify(runRepository, never()).save(any());
        verify(runRepository, never()).completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                any(), any(), any(), any(), any(), any(), any());
        verify(runRepository, never()).updateStatusIfCurrentIn(any(), any(), any(), any(), any());
    }

    @Test
    void settleWithNoIdOrStatusTouchesNothing() {
        assertThat(service.settle(session("conv-7"), null, RunStatus.COMPLETED, null)).isNull();
        assertThat(service.settle(session("conv-7"), UUID.randomUUID(), null, null)).isNull();
        verifyNoInteractions(runRepository);
    }

    /**
     * The idempotency the review asked for: the slot is cleared by settle itself, so the
     * caller need not remember — a turn that reuses the socket afterwards gets a new row.
     */
    @Test
    void settleClearsTheSessionSlotSoTheNextToolCallStartsANewTurn() {
        stubSave();
        LiveSession session = session("conv-7");
        UUID first = service.findOrCreateForToolTurn(session, "get_run");
        stubTerminalWrite(1);
        when(runRepository.findStateById(first)).thenReturn(Optional.of(state(RunStatus.COMPLETED)));

        service.settle(session, first, RunStatus.COMPLETED, null);

        assertThat(session.runId()).isNull();

        UUID second = service.findOrCreateForToolTurn(session, "get_run");

        assertThat(second).isNotEqualTo(first);
        verify(runRepository, times(2)).save(any());
    }

    /** A settle for another socket's run must not drop this turn's row. */
    @Test
    void settleLeavesASlotThatHoldsADifferentRunAlone() {
        UUID mine = UUID.randomUUID();
        LiveSession session = session("conv-7");
        session.runId(mine);
        stubTerminalWrite(0);
        UUID foreign = UUID.randomUUID();
        when(runRepository.findStateById(foreign)).thenReturn(Optional.of(state(RunStatus.COMPLETED)));

        service.settle(session, foreign, RunStatus.COMPLETED, null);

        assertThat(session.runId()).isEqualTo(mine);
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

    private void stubTerminalWrite(int rows) {
        when(runRepository.completeIfNotTerminal(any(), any(), anyInt(), anyLong(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(rows);
    }

    private static RunRepository.RunStateView state(RunStatus status) {
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

    private static LiveSession session(String conversationId) {
        // The run slot is the only state these tests touch; no socket is involved.
        return new LiveSession("s-" + UUID.randomUUID(), conversationId, null, null);
    }
}
