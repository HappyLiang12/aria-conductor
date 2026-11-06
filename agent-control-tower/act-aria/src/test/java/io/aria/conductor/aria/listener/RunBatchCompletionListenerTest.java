package io.aria.conductor.aria.listener;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.persistence.AriaNotificationRepository;
import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Completion wake (T9): when the LAST child of a dispatch group reaches a
 * terminal state, exactly one notification lands on the parent's conversation.
 * Dedupe is scoped PER dispatch group (the group id heads the body as the
 * {@code Batch <dispatchedByRunId>:} marker), so a later batch in the same
 * conversation notifies again. All scenarios call the listener method directly
 * with mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
class RunBatchCompletionListenerTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    @Mock RunRepository runRepository;
    @Mock AriaNotificationRepository notificationRepository;
    @Mock NotificationService notificationService;
    @InjectMocks RunBatchCompletionListener listener;

    private static Run child(UUID id, UUID dispatchedByRunId, RunStatus status, Instant createdAt) {
        return Run.builder()
                .id(id)
                .agentId(UUID.randomUUID())
                .status(status)
                .dispatchedByRunId(dispatchedByRunId)
                .createdAt(createdAt)
                .build();
    }

    private static Run parent(UUID id) {
        return Run.builder()
                .id(id)
                .agentId(UUID.randomUUID())
                .status(RunStatus.COMPLETED)
                .conversationId(CONVERSATION_ID)
                .createdAt(T0.minusSeconds(60))
                .build();
    }

    @Test
    void completedChildWithRunningSibling_createsNoNotification() {
        UUID parentId = UUID.randomUUID();
        Run finished = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0);
        Run running = child(UUID.randomUUID(), parentId, RunStatus.RUNNING, T0.plusSeconds(1));
        when(runRepository.findById(finished.getId())).thenReturn(Optional.of(finished));
        when(runRepository.findByDispatchedByRunId(parentId)).thenReturn(List.of(finished, running));

        listener.onRunCompleted(new RunCompletedEvent(
                this, finished.getId(), finished.getAgentId(), RunStatus.COMPLETED));

        verifyNoInteractions(notificationService);
    }

    @Test
    void lastChildCompletes_createsOneNotificationWithExactTitleAndResource() {
        UUID parentId = UUID.randomUUID();
        Run c1 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0);
        Run c2 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0.plusSeconds(1));
        Run c3 = child(UUID.randomUUID(), parentId, RunStatus.FAILED, T0.plusSeconds(2));
        when(runRepository.findById(c1.getId())).thenReturn(Optional.of(c1));
        // Deliberately out of createdAt order: the listener must order the body itself.
        when(runRepository.findByDispatchedByRunId(parentId)).thenReturn(List.of(c2, c3, c1));
        when(runRepository.findById(parentId)).thenReturn(Optional.of(parent(parentId)));
        when(notificationRepository.existsByTypeAndResourceIdAndBodyContaining(
                "run.batch.completed", CONVERSATION_ID, parentId.toString())).thenReturn(false);

        listener.onRunCompleted(new RunCompletedEvent(
                this, c1.getId(), c1.getAgentId(), RunStatus.COMPLETED));

        verify(notificationService, times(1)).create(
                "run.batch.completed",
                "子任務批次完成（3 個：成功 2／失敗 1）",
                "Batch " + parentId + ": Runs: " + c1.getId() + ":COMPLETED, "
                        + c2.getId() + ":COMPLETED, " + c3.getId() + ":FAILED",
                "CONVERSATION", CONVERSATION_ID);
    }

    @Test
    void replayedCompletion_afterGroupAlreadyNotified_isSuppressedByDedupe() {
        UUID parentId = UUID.randomUUID();
        Run c1 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0);
        Run c2 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0.plusSeconds(1));
        Run c3 = child(UUID.randomUUID(), parentId, RunStatus.FAILED, T0.plusSeconds(2));
        when(runRepository.findById(c3.getId())).thenReturn(Optional.of(c3));
        when(runRepository.findByDispatchedByRunId(parentId)).thenReturn(List.of(c1, c2, c3));
        when(runRepository.findById(parentId)).thenReturn(Optional.of(parent(parentId)));
        when(notificationRepository.existsByTypeAndResourceIdAndBodyContaining(
                "run.batch.completed", CONVERSATION_ID, parentId.toString())).thenReturn(true);

        // Replay of a completion for an already-notified group must not create a second one.
        listener.onRunCompleted(new RunCompletedEvent(
                this, c3.getId(), c3.getAgentId(), RunStatus.FAILED));

        verify(notificationRepository).existsByTypeAndResourceIdAndBodyContaining(
                "run.batch.completed", CONVERSATION_ID, parentId.toString());
        verify(notificationService, never()).create(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void secondBatchInTheSameConversation_notifiesAgainWithItsOwnGroupMarker() {
        // REGRESSION (R-ATR3): batch 1 (firstParentId) was already notified earlier, so its
        // "Batch <firstParentId>:" marker sits in the store — the old conversation-scoped dedupe
        // would suppress the operator's rerun batch (secondParentId) forever. The finder stub
        // simulates the real store: only the first group's marker exists.
        UUID firstParentId = UUID.randomUUID();
        UUID secondParentId = UUID.randomUUID();
        Run d1 = child(UUID.randomUUID(), secondParentId, RunStatus.COMPLETED, T0);
        Run d2 = child(UUID.randomUUID(), secondParentId, RunStatus.FAILED, T0.plusSeconds(1));
        when(runRepository.findById(d2.getId())).thenReturn(Optional.of(d2));
        when(runRepository.findByDispatchedByRunId(secondParentId)).thenReturn(List.of(d1, d2));
        when(runRepository.findById(secondParentId)).thenReturn(Optional.of(parent(secondParentId)));
        when(notificationRepository.existsByTypeAndResourceIdAndBodyContaining(
                eq("run.batch.completed"), eq(CONVERSATION_ID), anyString()))
                .thenAnswer(invocation -> firstParentId.toString().equals(invocation.getArgument(2)));

        listener.onRunCompleted(new RunCompletedEvent(
                this, d2.getId(), d2.getAgentId(), RunStatus.FAILED));

        verify(notificationRepository).existsByTypeAndResourceIdAndBodyContaining(
                "run.batch.completed", CONVERSATION_ID, secondParentId.toString());
        verify(notificationService, times(1)).create(
                "run.batch.completed",
                "子任務批次完成（2 個：成功 1／失敗 1）",
                "Batch " + secondParentId + ": Runs: " + d1.getId() + ":COMPLETED, "
                        + d2.getId() + ":FAILED",
                "CONVERSATION", CONVERSATION_ID);
    }

    @Test
    void runWithoutDispatchGroup_isIgnored() {
        Run standalone = Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .status(RunStatus.COMPLETED)
                .createdAt(T0)
                .build();
        when(runRepository.findById(standalone.getId())).thenReturn(Optional.of(standalone));

        listener.onRunCompleted(new RunCompletedEvent(
                this, standalone.getId(), standalone.getAgentId(), RunStatus.COMPLETED));

        verifyNoInteractions(notificationService);
        verifyNoInteractions(notificationRepository);
    }

    @Test
    void bodyClipsAfterFiveEntries_andEveryNonCompletedTerminalCountsAsFailure() {
        UUID parentId = UUID.randomUUID();
        Run c1 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0);
        Run c2 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0.plusSeconds(1));
        Run c3 = child(UUID.randomUUID(), parentId, RunStatus.FAILED, T0.plusSeconds(2));
        Run c4 = child(UUID.randomUUID(), parentId, RunStatus.ABORTED, T0.plusSeconds(3));
        Run c5 = child(UUID.randomUUID(), parentId, RunStatus.CANCELLED, T0.plusSeconds(4));
        Run c6 = child(UUID.randomUUID(), parentId, RunStatus.COMPLETED, T0.plusSeconds(5));
        when(runRepository.findById(c6.getId())).thenReturn(Optional.of(c6));
        when(runRepository.findByDispatchedByRunId(parentId))
                .thenReturn(List.of(c1, c2, c3, c4, c5, c6));
        when(runRepository.findById(parentId)).thenReturn(Optional.of(parent(parentId)));
        when(notificationRepository.existsByTypeAndResourceIdAndBodyContaining(
                "run.batch.completed", CONVERSATION_ID, parentId.toString())).thenReturn(false);

        listener.onRunCompleted(new RunCompletedEvent(
                this, c6.getId(), c6.getAgentId(), RunStatus.COMPLETED));

        verify(notificationService, times(1)).create(
                "run.batch.completed",
                "子任務批次完成（6 個：成功 3／失敗 3）",
                "Batch " + parentId + ": Runs: " + c1.getId() + ":COMPLETED, "
                        + c2.getId() + ":COMPLETED, " + c3.getId() + ":FAILED, "
                        + c4.getId() + ":ABORTED, " + c5.getId() + ":CANCELLED, …",
                "CONVERSATION", CONVERSATION_ID);
    }
}
