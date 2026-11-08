package io.aria.conductor.aria.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test doubles shared by the sync-wait tests: a transaction manager that keeps
 * the real {@link TransactionSynchronizationManager} flag in step with Spring,
 * so a test can prove the sleep runs outside a transaction.
 */
final class AriaSyncRunWaiterTestSupport {

    private AriaSyncRunWaiterTestSupport() {
    }

    static TransactionTemplate template() {
        TransactionTemplate template = new TransactionTemplate(new FakeTxManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template;
    }

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
}
