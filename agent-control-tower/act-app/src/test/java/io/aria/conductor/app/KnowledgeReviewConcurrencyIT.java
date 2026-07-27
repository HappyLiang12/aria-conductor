package io.aria.conductor.app;

import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 concurrency regression for {@link KnowledgeService#reviewKnowledge}.
 *
 * <p>Two opposing reviews (APPROVE + REJECT) are fired at the same PENDING item
 * simultaneously, repeated over many rounds against a real H2 datasource so the
 * transactions actually contend on the row.
 *
 * <p>This is a genuine failing-first test for the fix: with the pessimistic
 * {@code findByIdForUpdate} lock the two reviews serialise (exactly one wins,
 * the loser is rejected by the state guard), so {@code doubleWins == 0}. If the
 * lock is removed (i.e. {@code reviewKnowledge} reverts to {@code findById}),
 * both transactions read PENDING before either commits, both pass the guard and
 * both commit — producing {@code doubleWins > 0} and turning this test RED.
 */
class KnowledgeReviewConcurrencyIT extends BaseH2IntegrationTest {

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeItemRepository itemRepository;

    @Test
    void concurrentOpposingReviews_neverBothSucceed_lockSerialisesTheRace() throws Exception {
        int rounds = 24;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int doubleWins = 0;
        List<String> outcomes = new ArrayList<>();

        try {
            for (int i = 0; i < rounds; i++) {
                UUID id = knowledgeService.submitKnowledge(CreateKnowledgeRequest.builder()
                        .name("race-" + i + "-" + UUID.randomUUID())
                        .type(KnowledgeType.GUIDELINE)
                        .description("concurrency race probe")
                        .content("body")
                        .build()).getId();

                CountDownLatch go = new CountDownLatch(1);
                Future<Boolean> approve = pool.submit(review(id, ReviewDecisionRequest.ReviewDecision.APPROVED, go));
                Future<Boolean> reject = pool.submit(review(id, ReviewDecisionRequest.ReviewDecision.REJECTED, go));
                go.countDown();

                boolean approveWon = approve.get(20, TimeUnit.SECONDS);
                boolean rejectWon = reject.get(20, TimeUnit.SECONDS);

                if (approveWon && rejectWon) {
                    doubleWins++;
                }
                assertThat(approveWon || rejectWon)
                        .as("at least one reviewer must win for item " + id)
                        .isTrue();

                KnowledgeStatus finalStatus = itemRepository.findById(id).orElseThrow().getStatus();
                assertThat(finalStatus)
                        .as("final status must be a terminal decision for item " + id)
                        .isIn(KnowledgeStatus.APPROVED, KnowledgeStatus.REJECTED);
                outcomes.add(id + "->A=" + approveWon + ",R=" + rejectWon + ",final=" + finalStatus);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(doubleWins)
                .as("opposing APPROVE+REJECT BOTH committed on the same PENDING item (TOCTOU race); "
                        + "the PESSIMISTIC_WRITE lock in reviewKnowledge must serialise them. outcomes=" + outcomes)
                .isZero();
    }

    private Callable<Boolean> review(UUID id, ReviewDecisionRequest.ReviewDecision decision, CountDownLatch go) {
        return () -> {
            go.await();
            try {
                knowledgeService.reviewKnowledge(id, ReviewDecisionRequest.builder()
                        .decision(decision)
                        .reason("race")
                        .build());
                return true;
            } catch (RuntimeException e) {
                // Loser: InvalidStateTransitionException (guard) or a pessimistic-lock timeout.
                return false;
            }
        };
    }
}
