package io.aria.conductor.aria.service;

import io.aria.conductor.aria.repository.AriaRunStateRepository;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F6 regression: the synchronous Aria chat polls another thread's run state for
 * up to a minute. The wait itself must never run inside an open transaction, or
 * the request pins a Hikari connection for the whole window (issue #58: a
 * "Previously reported leaked connection ... was returned to the pool" warning
 * after a long sync chat). Each read is its own short REQUIRES_NEW transaction;
 * the sleep between reads observes no transaction at all.
 */
class AriaSyncRunWaiterTest {

    private final AriaRunStateRepository states = mock(AriaRunStateRepository.class);

    /** Flips the real transaction-synchronization flag so assertions see what Spring would. */
    private static final class FakeTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        @Override
        public void rollback(TransactionStatus status) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static TransactionTemplate template() {
        TransactionTemplate template = new TransactionTemplate(new FakeTxManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template;
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    private static AriaRunStateRepository.RunSnapshot snapshot(RunStatus status, String output,
                                                               String errorCode, String errorMessage) {
        return new AriaRunStateRepository.RunSnapshot() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return output;
            }

            @Override
            public String getErrorCode() {
                return errorCode;
            }

            @Override
            public String getErrorMessage() {
                return errorMessage;
            }
        };
    }

    @Test
    void await_readsInsideAShortTransaction_butSleepsOutsideAnyTransaction() {
        UUID runId = UUID.randomUUID();
        List<Boolean> transactionActiveDuringRead = new ArrayList<>();
        List<Boolean> transactionActiveDuringSleep = new ArrayList<>();
        when(states.findSnapshotById(runId)).thenAnswer(inv -> {
            transactionActiveDuringRead.add(TransactionSynchronizationManager.isActualTransactionActive());
            return transactionActiveDuringRead.size() == 1
                    ? Optional.of(snapshot(RunStatus.PENDING, null, null, null))
                    : Optional.of(snapshot(RunStatus.COMPLETED, "done", null, null));
        });

        AriaSyncRunWaiter waiter = new AriaSyncRunWaiter(states, template(), millis -> {
            transactionActiveDuringSleep.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
        }, 1L, 5, 0L);

        AriaSyncRunWaiter.Outcome outcome = waiter.await(runId);

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.snapshot().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(transactionActiveDuringRead).isNotEmpty().allMatch(active -> active);
        assertThat(transactionActiveDuringSleep).isNotEmpty().allMatch(active -> !active);
    }

    @Test
    void await_returnsTimedOutOutcomeInsteadOfInventingTerminalState() {
        UUID runId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        when(states.findSnapshotById(runId)).thenAnswer(inv -> {
            reads.incrementAndGet();
            return Optional.of(snapshot(RunStatus.RUNNING, null, null, null));
        });

        AriaSyncRunWaiter waiter = new AriaSyncRunWaiter(states, template(), millis -> { },
                1L, 3, 0L);

        AriaSyncRunWaiter.Outcome outcome = waiter.await(runId);

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.snapshot().getStatus()).isEqualTo(RunStatus.RUNNING);
        // 3 polls in the window + one final grace read, never a fabricated COMPLETED.
        assertThat(reads).hasValue(4);
    }

    /**
     * F3/A4: a run that parks on a clarification is a returnable outcome for a
     * sync caller — the question is the answer. The waiter must hand the parked
     * snapshot back before sleeping, instead of polling the window away and
     * reporting a timeout on a run that is waiting for the operator.
     */
    @Test
    void aParkedRunReturnsWithoutExhaustingTheSyncWindow() {
        UUID runId = UUID.randomUUID();
        AriaRunStateRepository.RunSnapshot parked = snapshot(RunStatus.WAITING_INPUT, null, null, null);
        when(states.findSnapshotById(runId)).thenReturn(Optional.of(parked));
        AtomicInteger sleeps = new AtomicInteger();
        AriaSyncRunWaiter waiter = new AriaSyncRunWaiter(states, template(),
                millis -> { sleeps.incrementAndGet(); }, 500L, 120, 3_000L);

        AriaSyncRunWaiter.Outcome outcome = waiter.await(runId);

        assertThat(outcome.snapshot().getStatus()).isEqualTo(RunStatus.WAITING_INPUT);
        assertThat(outcome.timedOut()).isFalse();
        assertThat(sleeps.get()).isZero();
    }

    @Test
    void await_handlesMissingRunAsNonTerminal() {
        UUID runId = UUID.randomUUID();
        when(states.findSnapshotById(runId)).thenReturn(Optional.empty());

        AriaSyncRunWaiter waiter = new AriaSyncRunWaiter(states, template(), millis -> { },
                1L, 2, 0L);

        AriaSyncRunWaiter.Outcome outcome = waiter.await(runId);

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.snapshot()).isNull();
    }
}
