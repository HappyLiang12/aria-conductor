package io.aria.conductor.aria.listener;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.persistence.AriaNotificationRepository;
import io.aria.conductor.aria.service.NotificationService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Completion wake (Feature B2): the Aria conversation dispatched a batch of
 * child runs and then went quiet. When the LAST child of a dispatch group
 * reaches a terminal state, exactly one notification lands on the dispatching
 * turn's conversation, so the operator can trigger the synthesis.
 *
 * <p>The group is the set of runs stamped with the same
 * {@code dispatchedByRunId} (the dispatching turn's runId); the conversation is
 * resolved through the parent run, never from the children (children carry no
 * conversationId by design). Dedupe is a check-then-insert scoped PER dispatch
 * group: the group id heads the body verbatim as {@code Batch <group-id>:}, and
 * the existence check looks for that marker, so a later batch in the same
 * conversation notifies again while a replayed completion of an
 * already-notified group stays suppressed. The residual simultaneous-completion
 * race — two last children committing at once both passing the check — is
 * accepted.
 */
@Slf4j
@Component
public class RunBatchCompletionListener {

    private static final String NOTIFICATION_TYPE = "run.batch.completed";
    private static final String RESOURCE_TYPE_CONVERSATION = "CONVERSATION";
    private static final int MAX_BODY_ENTRIES = 5;

    /** End states: no further execution is expected (ABORTED is a failure end state). */
    private static final Set<RunStatus> TERMINAL_STATUSES = EnumSet.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED);

    private final RunRepository runRepository;
    private final AriaNotificationRepository notificationRepository;
    private final NotificationService notificationService;

    public RunBatchCompletionListener(RunRepository runRepository,
                                      AriaNotificationRepository notificationRepository,
                                      NotificationService notificationService) {
        this.runRepository = runRepository;
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunCompleted(RunCompletedEvent event) {
        try {
            notifyIfBatchCompleted(event);
        } catch (Exception e) {
            // A listener must never break the publisher (nor the listeners after it).
            log.warn("Batch-completion watcher failed for run {}: {}", event.getRunId(), e.getMessage());
        }
    }

    private void notifyIfBatchCompleted(RunCompletedEvent event) {
        Run completed = runRepository.findById(event.getRunId()).orElse(null);
        if (completed == null) {
            log.warn("Batch-completion watcher saw RunCompletedEvent for unknown run {}", event.getRunId());
            return;
        }
        UUID dispatchedByRunId = completed.getDispatchedByRunId();
        if (dispatchedByRunId == null) {
            return; // not a dispatched child — nothing to watch
        }
        List<Run> group = runRepository.findByDispatchedByRunId(dispatchedByRunId);
        if (group.isEmpty() || !group.stream().allMatch(r -> TERMINAL_STATUSES.contains(r.getStatus()))) {
            return; // siblings still in flight
        }
        Run parent = runRepository.findById(dispatchedByRunId).orElse(null);
        if (parent == null || parent.getConversationId() == null) {
            log.warn("Dispatched batch {} has no parent run with a conversation — skipping notification",
                    dispatchedByRunId);
            return;
        }
        String conversationId = parent.getConversationId();
        if (notificationRepository.existsByTypeAndResourceIdAndBodyContaining(
                NOTIFICATION_TYPE, conversationId, dispatchedByRunId.toString())) {
            return; // this dispatch group was already notified
        }
        List<Run> ordered = group.stream()
                .sorted(Comparator.comparing(Run::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        notificationService.create(NOTIFICATION_TYPE, buildTitle(ordered),
                buildBody(dispatchedByRunId, ordered), RESOURCE_TYPE_CONVERSATION, conversationId);
    }

    /** {@code 子任務批次完成（N 個：成功 X／失敗 Y）} — Y counts every terminal-but-not-COMPLETED child. */
    private static String buildTitle(List<Run> group) {
        long completed = group.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED).count();
        long failed = group.size() - completed;
        return String.format("子任務批次完成（%d 個：成功 %d／失敗 %d）", group.size(), completed, failed);
    }

    /**
     * {@code Batch <dispatchedByRunId>: Runs: <id:status>, ...} in createdAt order, clipped to
     * five entries with a trailing "…". The leading group-id marker is what the dedupe finder
     * matches on, scoping the guard per dispatch group.
     */
    private static String buildBody(UUID dispatchedByRunId, List<Run> ordered) {
        String entries = ordered.stream()
                .limit(MAX_BODY_ENTRIES)
                .map(r -> r.getId() + ":" + r.getStatus())
                .collect(Collectors.joining(", "));
        return "Batch " + dispatchedByRunId + ": Runs: " + entries
                + (ordered.size() > MAX_BODY_ENTRIES ? ", …" : "");
    }
}
