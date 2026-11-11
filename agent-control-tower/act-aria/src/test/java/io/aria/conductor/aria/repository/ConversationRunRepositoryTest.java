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
import java.util.List;
import java.util.Optional;
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
    void removeTheCommittedFixtureAfterEach() {
        removeTheCommittedFixture();
    }

    /**
     * The slice's embedded schema is shared with the module's other data-jpa tests, so a
     * row this class committed is removed explicitly rather than left to rollback. Callable
     * from a test so the cleanup itself can be asserted instead of trusted.
     */
    private void removeTheCommittedFixture() {
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

    /**
     * T52 N-1: the cleanup must actually delete the committed fixture. The statement under
     * test nulls the conversation id, so a cleanup that looks the row up by that id finds
     * nothing; the row then survives into the shared embedded schema and is invisible to the
     * cleanup (harmless here because every other read in this module filters on the id, but a
     * no-op cleanup is what makes an unrelated suite order-dependent).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theCleanupRemovesTheRowTheDetachOrphaned() {
        UUID runId = ownTransaction.execute(status -> runs.saveAndFlush(run()).getId());
        assertThat(conversationRuns.detachByConversationId(conversationId)).isEqualTo(1);

        // Positive control for the old key: the lookup the cleanup used is empty because the
        // detach just nulled it, while the row itself is still committed and findable by id.
        // The results bind to locals because `execute`'s callback type is inferred from the
        // assertion target, and AssertJ's Predicate overloads make that inference ambiguous.
        List<Run> byConversation =
                ownTransaction.execute(status -> runs.findByConversationIdOrderByCreatedAtAsc(conversationId));
        assertThat(byConversation)
                .as("the conversation-keyed lookup is empty, so a cleanup keyed to it deletes nothing")
                .isEmpty();
        Optional<Run> stillThere = ownTransaction.execute(status -> runs.findById(runId));
        assertThat(stillThere)
                .as("the committed fixture is still there after the detach")
                .isPresent();

        removeTheCommittedFixture();

        Optional<Run> afterCleanup = ownTransaction.execute(status -> runs.findById(runId));
        assertThat(afterCleanup)
                .as("the cleanup removes the row by the id it was committed under")
                .isEmpty();
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
