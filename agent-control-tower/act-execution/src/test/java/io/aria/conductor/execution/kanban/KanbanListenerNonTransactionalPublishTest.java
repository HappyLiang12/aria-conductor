package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.listener.RunKanbanAutoCreator;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Some production publishers run with no active transaction: {@code AgentLoopEngine}
 * publishes run events off its execution thread, {@code ZombieRunReaper} publishes
 * from a {@code @Scheduled} method, and {@code ApprovalGate} raises asks outside
 * {@code decideApproval}'s transaction. An after-commit listener without
 * {@code fallbackExecution} would silently drop those events — completed runs would
 * never reach REVIEW and engine-raised asks would never get a review card.
 *
 * <p>The other listener tests construct the listeners directly, so they cannot see
 * it; this one lets Spring register the listeners and publishes from a thread with
 * no transaction, the way the engine does.
 */
@SpringBootTest
@ActiveProfiles("test")
class KanbanListenerNonTransactionalPublishTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfig {

        @Bean
        RunKanbanAutoCreator runKanbanAutoCreator(KanbanService kanbanService,
                                                  KanbanRepository kanbanRepository,
                                                  RunRepository runRepository,
                                                  PlatformTransactionManager transactionManager) {
            // Runnable::run: these tests assert the mirror's effect right after
            // publishing, which the dedicated executor would defer. The executor
            // itself is covered by KanbanMirrorPoolStarvationTest.
            return new RunKanbanAutoCreator(kanbanService, kanbanRepository, runRepository, transactionManager,
                    Runnable::run);
        }

        @Bean
        KanbanReviewCardListener kanbanReviewCardListener(ApprovalRepository approvalRepository,
                                                         KanbanRepository kanbanRepository,
                                                         KanbanService kanbanService,
                                                         PlatformTransactionManager transactionManager) {
            return new KanbanReviewCardListener(
                    approvalRepository, kanbanRepository, kanbanService, transactionManager, Runnable::run);
        }

        /**
         * Declared last, so it is registered after {@link RunKanbanAutoCreator} in
         * every test context and the multicaster invokes it after that listener: it
         * observes whether the chain survived a listener that failed.
         */
        @Bean
        CompletedRunProbe completedRunProbe() {
            return new CompletedRunProbe();
        }
    }

    static class CompletedRunProbe {
        private final List<UUID> completedRunIds = new CopyOnWriteArrayList<>();
        private final List<UUID> askedRunIds = new CopyOnWriteArrayList<>();

        @EventListener
        void onRunCompleted(RunCompletedEvent event) {
            completedRunIds.add(event.getRunId());
        }

        @EventListener
        void onApprovalRequested(ApprovalRequestedEvent event) {
            askedRunIds.add(event.getRunId());
        }

        List<UUID> completedRunIds() {
            return completedRunIds;
        }

        List<UUID> askedRunIds() {
            return askedRunIds;
        }
    }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private KanbanRepository kanbanRepository;
    @Autowired private ApprovalRepository approvalRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private CompletedRunProbe completedRunProbe;

    @Test
    void runCompletedPublishedWithoutATransactionStillReachesReview() {
        UUID runId = UUID.randomUUID();
        String cardId = saveCard(runId, KanbanStatus.IN_PROGRESS);
        assertNoActiveTransaction();

        eventPublisher.publishEvent(new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.COMPLETED));

        assertThat(statusOf(cardId)).isEqualTo(KanbanStatus.REVIEW);
    }

    @Test
    void runIterationPublishedWithoutATransactionStillStartsTheCard() {
        UUID runId = UUID.randomUUID();
        String cardId = saveCard(runId, KanbanStatus.TODO);
        assertNoActiveTransaction();

        eventPublisher.publishEvent(new RunIterationEvent(this, runId, UUID.randomUUID(), 1, 50));

        assertThat(statusOf(cardId)).isEqualTo(KanbanStatus.IN_PROGRESS);
    }

    @Test
    void approvalRequestedPublishedWithoutATransactionStillGetsAReviewCard() {
        UUID runId = UUID.randomUUID();
        // The run is the durable anchor the review card links to: create refuses
        // a link that resolves to nothing.
        runRepository.save(Run.builder().id(runId).agentId(UUID.randomUUID())
                .promptSeed("delete the branch").status(RunStatus.RUNNING).build());
        Approval approval = approvalRepository.save(Approval.builder()
                .runId(runId)
                .status(ApprovalStatus.PENDING)
                .content("Agent wants to delete the branch")
                .build());
        assertNoActiveTransaction();

        eventPublisher.publishEvent(new ApprovalRequestedEvent(this, approval.getId(), runId, null, "TOOL_CALL"));

        assertThat(kanbanRepository.findByLinkedRunId(runId.toString()))
                .singleElement()
                .satisfies(card -> assertThat(card.getStatus()).isEqualTo(KanbanStatus.REVIEW));
        assertThat(approvalRepository.findById(approval.getId()).orElseThrow().getKanbanItemId()).isNotNull();
    }

    @Test
    void aFailedCardMirrorDoesNotAbortTheListenerChain() {
        UUID runId = UUID.randomUUID();
        // The engine's auto-card for a run that fails before its first iteration:
        // still TODO when the completion mirror runs, and TODO → REVIEW is not a
        // legal move, so the mirror genuinely fails (the card stays behind).
        String cardId = saveCard(runId, KanbanStatus.TODO);
        assertNoActiveTransaction();

        // The failure must stay inside the creator. If it escapes, the multicaster
        // stops dispatching and every listener after it is skipped — the dashboard
        // never hears run.completed and workflow chains never advance (observed in
        // CI as an UnexpectedRollbackException at the publisher).
        assertThatCode(() -> eventPublisher.publishEvent(
                new RunCompletedEvent(this, runId, UUID.randomUUID(), RunStatus.FAILED)))
                .doesNotThrowAnyException();

        assertThat(statusOf(cardId)).isEqualTo(KanbanStatus.TODO);
        assertThat(completedRunProbe.completedRunIds()).contains(runId);
    }

    @Test
    void aFailedReviewCardMirrorDoesNotAbortTheListenerChain() {
        // The ask names a run the board cannot anchor, so the mirror's create is
        // refused (INVALID_RUN_LINK) — the forced failure stands in for any
        // rejection raised by the real services inside the mirror.
        UUID askedRunId = UUID.randomUUID();
        UUID approvalRunId = UUID.randomUUID();
        runRepository.save(Run.builder().id(approvalRunId).agentId(UUID.randomUUID())
                .promptSeed("delete the branch").status(RunStatus.RUNNING).build());
        Approval approval = approvalRepository.save(Approval.builder()
                .runId(approvalRunId)
                .status(ApprovalStatus.PENDING)
                .content("Agent wants to delete the branch")
                .build());
        assertNoActiveTransaction();

        assertThatCode(() -> eventPublisher.publishEvent(
                new ApprovalRequestedEvent(this, approval.getId(), askedRunId, null, "TOOL_CALL")))
                .doesNotThrowAnyException();

        assertThat(approvalRepository.findById(approval.getId()).orElseThrow().getKanbanItemId())
                .isNull();
        assertThat(completedRunProbe.askedRunIds()).contains(askedRunId);
    }

    private String saveCard(UUID runId, KanbanStatus status) {
        return kanbanRepository.save(KanbanItem.builder()
                .title("card for run " + runId.toString().substring(0, 8))
                .status(status)
                .priority(KanbanPriority.MEDIUM)
                .linkedRunId(runId.toString())
                .build()).getId();
    }

    private KanbanStatus statusOf(String cardId) {
        return kanbanRepository.findById(cardId).orElseThrow().getStatus();
    }

    private void assertNoActiveTransaction() {
        // Guards the scenario itself: a class-level @Transactional would hide the
        // regression this test exists for.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}
