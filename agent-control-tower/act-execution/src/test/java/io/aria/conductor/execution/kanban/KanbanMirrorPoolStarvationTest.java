package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.listener.RunKanbanAutoCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The mirroring listeners must never need a second database connection while
 * the publisher still holds its own.
 *
 * <p>An after-commit listener runs before Spring releases the publisher's
 * connection, so a listener that opens its own transaction there asks the pool
 * for a second connection on the same thread. With as many concurrent
 * publishers as the pool has connections, every connection is held by a thread
 * that is waiting for one: the pool deadlocks until the connection timeout, the
 * mirrors fail, and the runs come back without their cards. Observed in CI as
 * the RACE spec's concurrent chain creation timing out at 15s against the
 * h2 profile's eight-connection pool.
 *
 * <p>The pool here is deliberately two: two publishers that are guaranteed to
 * hold a connection at the same moment are enough to pin the trap.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=2000",
})
class KanbanMirrorPoolStarvationTest {

    @TestConfiguration(proxyBeanMethods = false)
    @Import(KanbanMirrorConfig.class)
    static class MirrorConfig {
        @Bean
        RunKanbanAutoCreator runKanbanAutoCreator(KanbanService kanbanService,
                                                  KanbanRepository kanbanRepository,
                                                  RunRepository runRepository,
                                                  PlatformTransactionManager transactionManager,
                                                  @Qualifier("kanbanMirrorExecutor") ThreadPoolTaskExecutor mirrorExecutor) {
            return new RunKanbanAutoCreator(kanbanService, kanbanRepository, runRepository, transactionManager,
                    mirrorExecutor);
        }
    }

    private static final int PUBLISHERS = 2;

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private KanbanRepository kanbanRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void everyPublisherStillGetsItsCardWhenThePoolHasNoSpareConnection() throws Exception {
        CyclicBarrier bothHoldAConnection = new CyclicBarrier(PUBLISHERS);
        CountDownLatch published = new CountDownLatch(PUBLISHERS);
        List<UUID> runIds = Collections.synchronizedList(new ArrayList<>());
        ExecutorService threads = Executors.newFixedThreadPool(PUBLISHERS);
        try {
            for (int i = 0; i < PUBLISHERS; i++) {
                threads.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            Run run = runRepository.save(Run.builder()
                                    .agentId(UUID.randomUUID())
                                    .promptSeed("pool starvation probe")
                                    .status(RunStatus.PENDING)
                                    .build());
                            runIds.add(run.getId());
                            eventPublisher.publishEvent(new RunStartedEvent(this, run.getId(), run.getAgentId()));
                            // Every connection is now checked out by an open transaction.
                            awaitQuietly(bothHoldAConnection);
                        });
                    } finally {
                        published.countDown();
                    }
                });
            }

            assertThat(published.await(30, TimeUnit.SECONDS))
                    .as("publishing a run event must not block on the pool")
                    .isTrue();

            assertThat(runIds).hasSize(PUBLISHERS);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(runIds).allSatisfy(runId ->
                    assertThat(kanbanRepository.findByLinkedRunId(runId.toString()))
                            .as("card mirrored for run %s", runId)
                            .hasSize(1)));
        } finally {
            threads.shutdownNow();
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("publishers never met at the barrier", e);
        }
    }
}
