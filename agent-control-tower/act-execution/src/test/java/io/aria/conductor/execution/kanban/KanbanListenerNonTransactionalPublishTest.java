package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.listener.RunKanbanAutoCreator;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                                                  RunRepository runRepository) {
            return new RunKanbanAutoCreator(kanbanService, kanbanRepository, runRepository);
        }

        @Bean
        KanbanReviewCardListener kanbanReviewCardListener(ApprovalRepository approvalRepository,
                                                         KanbanRepository kanbanRepository,
                                                         KanbanService kanbanService) {
            return new KanbanReviewCardListener(approvalRepository, kanbanRepository, kanbanService);
        }
    }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private KanbanRepository kanbanRepository;
    @Autowired private ApprovalRepository approvalRepository;

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
