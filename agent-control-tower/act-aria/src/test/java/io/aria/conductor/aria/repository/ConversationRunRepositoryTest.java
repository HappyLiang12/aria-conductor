package io.aria.conductor.aria.repository;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T50 F-3: the conversation detach is a write of its own.
 *
 * <p>{@link ConversationRunRepository} extends the bare {@code Repository} marker, so it
 * inherits no transaction from Spring Data, and the bulk {@code UPDATE} declared no
 * {@code @Transactional} of its own. It worked only because its one caller — the Clear
 * route, {@code AriaConversationController.deleteConversation} — happens to open a
 * transaction; any other caller (a scheduler, a read path) would hit
 * {@code TransactionRequiredException}.
 *
 * <p>This test drives the repository bean with NO ambient transaction: the test method is
 * {@code NOT_SUPPORTED}, which suspends the {@code @DataJpaTest} transaction, and the
 * absence of an actual transaction is asserted before the call so the test cannot pass by
 * inheriting one. The in-transaction path is the other half of the contract and is covered
 * by {@code ConversationClearWriterWindowTest} and the Clear contract test.
 */
class ConversationRunRepositoryTest extends DataJpaTestBase {

    @Autowired
    private RunRepository runs;
    @Autowired
    private ConversationRunRepository conversationRuns;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate ownTransaction;
    /** Unique per test instance (and 36 chars: the column is {@code varchar(36)}). */
    private final String conversationId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void removeTheCommittedFixture() {
        // The slice's embedded schema is shared with the module's other data-jpa tests, so a
        // row this class committed is removed explicitly rather than left to rollback.
        ownTransaction.executeWithoutResult(status ->
                runs.deleteAll(runs.findByConversationIdOrderByCreatedAtAsc(conversationId)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theDetachOwnsItsTransactionWhenTheCallerHasNone() {
        UUID runId = ownTransaction.execute(status -> runs.saveAndFlush(run()).getId());
        assertThat(ownTransaction.execute(status -> runs.findById(runId).orElseThrow()).getConversationId())
                .as("positive control: the fixture really carries the conversation id")
                .isEqualTo(conversationId);

        // The premise: no transaction is active, so the write can only commit if the
        // repository method opens its own.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("the test's own @DataJpaTest transaction is suspended by NOT_SUPPORTED")
                .isFalse();

        int detached = conversationRuns.detachByConversationId(conversationId);

        assertThat(detached).isEqualTo(1);
        assertThat(ownTransaction.execute(status -> runs.findById(runId).orElseThrow()).getConversationId())
                .as("the detach committed without a caller-provided transaction")
                .isNull();
    }

    private Run run() {
        return Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .conversationId(conversationId)
                .status(RunStatus.COMPLETED)
                .promptSeed("a run whose link must be forgotten")
                .createdAt(Instant.parse("2026-11-12T01:00:00Z"))
                .build();
    }
}
