package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.MemoryJpaSliceConfig;
import io.aria.conductor.memory.dto.CreateEntityCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the "one transaction per mutation" rule with a real persistence context:
 * an assertion write that fails must take the entity insert with it.
 * <p>
 * The command runs in its own {@code REQUIRES_NEW} transaction, because
 * {@code @DataJpaTest} otherwise wraps the test method in a transaction and a
 * rollback-only mark inside it would not actually undo the insert. The success case is
 * the positive control for the rollback case.
 */
@DataJpaTest
@ContextConfiguration(classes = MemoryJpaSliceConfig.class)
class MemoryCommandServiceTransactionTest {

    @Autowired
    MemoryEntityRepository entities;

    @Autowired
    MemoryFactRepository facts;

    @Autowired
    MemoryRelationRepository relations;

    @MockitoBean
    MemoryKnowledgeLinkRepository links;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    MemoryAssertionRepository assertions;

    private MemoryCommandService command;
    private TransactionTemplate ownTransaction;

    @BeforeEach
    void setUp() {
        command = new MemoryCommandService(entities, facts, relations, links, assertions, new MemoryValueGuard());
        ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void aFailedAssertionWriteRollsBackTheEntityInsert() {
        // The success case below commits for real, and @DataJpaTest only rolls back its own
        // test transaction, so start from a known empty table in a committed transaction.
        ownTransaction.executeWithoutResult(status -> entities.deleteAll());
        assertThat(entities.count()).as("baseline").isZero();
        when(assertions.save(any(MemoryAssertion.class)))
                .thenThrow(new IllegalStateException("assertion write failed"));

        assertThatThrownBy(() -> ownTransaction.executeWithoutResult(status -> command.createEntity(
                new CreateEntityCommand("PERSON", "operator", "Operator", Sensitivity.INTERNAL),
                new MemoryActor(MemoryActorType.OPERATOR, "local"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(entities.count())
                .as("the entity insert and its assertion share one transaction")
                .isZero();
    }

    @Test
    void aSuccessfulCommandCommitsWithItsAssertion() {
        when(assertions.save(any(MemoryAssertion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ownTransaction.executeWithoutResult(status -> command.createEntity(
                new CreateEntityCommand("PERSON", "operator", "Operator", Sensitivity.INTERNAL),
                new MemoryActor(MemoryActorType.OPERATOR, "local")));

        assertThat(entities.count()).isEqualTo(1);
        verify(assertions).save(any(MemoryAssertion.class));
    }
}
