package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.MemoryJpaSliceConfig;
import io.aria.conductor.memory.dto.AddFactCommand;
import io.aria.conductor.memory.dto.CreateEntityCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemorySubjectType;
import io.aria.conductor.memory.repository.MemoryAssertionRepository;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.repository.MemoryKnowledgeLinkRepository;
import io.aria.conductor.memory.repository.MemoryRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The concurrent entity-create race of {@code createEntity}, against a real persistence context
 * and the real {@code uq_memory_entity} index (spec :325-326: "A concurrent insert that loses the
 * unique constraint is caught and re-read, never surfaced as a duplicate").
 * <p>
 * The ordering is deterministic, not timed. Two production facts are reproduced by the test's
 * repository decorator: the loser's identity probe runs before the winner's row is committed (so
 * the probe legitimately misses), and the winner's own {@code createEntity} commits before the
 * loser's INSERT reaches the database. Both writes are real service calls through the real
 * repositories, the colliding INSERT is a real duplicate-key violation, and the read-back is a
 * real query — nothing about the constraint or the transaction is stubbed.
 * <p>
 * Why the loser cannot simply return the winner: once the flush raises the constraint violation,
 * Hibernate and the driver have already marked the loser's transaction for rollback
 * ({@link MemoryProposalWriter}'s class comment states the same rule for the {@code proposal_hash}
 * race) — measured here, before this test settled: the read-back on that session re-raised the same
 * violation, and once the read moved into a fresh transaction the enclosing commit answered
 * {@code UnexpectedRollbackException}. So the winner is read in a fresh transaction, named in the
 * governed conflict, and the caller's retry — a real second call, asserted below — resolves through
 * the ordinary identity probe.
 */
@DataJpaTest
@ContextConfiguration(classes = MemoryJpaSliceConfig.class)
@Import(MemoryCreateRaceSliceTest.WriterConfig.class)
class MemoryCreateRaceSliceTest {

    @Autowired
    MemoryEntityRepository entities;

    @Autowired
    MemoryFactRepository facts;

    @Autowired
    MemoryRelationRepository relations;

    @Autowired
    MemoryKnowledgeLinkRepository links;

    @Autowired
    MemoryAssertionRepository assertions;

    @Autowired
    MemoryProposalWriter proposalWriter;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate ownTransaction;
    private final List<Object> published = new ArrayList<>();

    /** The writer must be the proxied bean (see {@link #command(MemoryEntityRepository)}). */
    @TestConfiguration
    static class WriterConfig {
        @Bean
        MemoryProposalWriter memoryProposalWriter(MemoryFactRepository facts,
                                                  MemoryAssertionRepository assertions,
                                                  MemoryEntityRepository entities) {
            return new MemoryProposalWriter(facts, assertions, entities);
        }
    }

    @BeforeEach
    void setUp() {
        ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ownTransaction.executeWithoutResult(status -> {
            facts.deleteAll();
            assertions.deleteAll();
            entities.deleteAll();
        });
        published.clear();
    }

    @Test
    void theLoserOfTheEntityInsertNamesTheWinnerAndWritesNothing() {
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, UUID.randomUUID().toString());
        MemoryCommandService winnerCommand = command(entities);
        UUID[] winnerId = new UUID[1];
        MemoryEntityRepository racing = racingRepository(winnerCommand, extraction, winnerId);
        MemoryCommandService loser = command(racing);
        CreateEntityCommand proposal =
                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL);

        assertThatThrownBy(() -> ownTransaction.execute(status -> loser.createEntity(proposal, extraction)))
                .as("the loser surfaces a governed conflict, never a raw duplicate")
                .isInstanceOf(MemoryConflictException.class)
                .hasMessageContaining("created concurrently")
                .hasMessageContaining(winnerId[0].toString());

        List<MemoryEntity> rows = entities.findAll();
        assertThat(rows).as("exactly one row for the key: the loser inserted none").hasSize(1);
        assertThat(rows.get(0).getId()).as("the committed row is the winner's").isEqualTo(winnerId[0]);
        assertThat(assertions.findAll())
                .as("the winner wrote its CREATE assertion once; the loser duplicated nothing")
                .hasSize(1);
        assertThat(published)
                .as("the proposal card is published once for the winner, never for the loser")
                .hasSize(1);

        // A retry is what resolves the loser: the ordinary identity probe now finds the winner, so
        // no enforcement is lost by refusing instead of returning a row from a doomed transaction.
        MemoryEntity retried = ownTransaction.execute(status -> command(entities).createEntity(proposal, extraction));
        assertThat(retried.getId())
                .as("the retry reads the winner back through the ordinary probe")
                .isEqualTo(winnerId[0]);
        assertThat(entities.findAll()).as("and writes no second row").hasSize(1);
    }

    /**
     * The re-proposal rule of the fact probe, end to end: an OPEN proposal is a no-op, a DECIDED one
     * is not. With the whole-history probe the second half silently returned the RETIRED row, wrote
     * no PENDING fact, and the entity card's batch was empty — the operator was asked to decide a
     * card with nothing in it while the worker's value was dropped and `remember_fact` answered ok.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRejectedProposalCanBeProposedAgainWhileAnOpenOneCannot() {
        UUID runId = UUID.randomUUID();
        MemoryActor extraction = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());
        MemoryActor operator = new MemoryActor(MemoryActorType.OPERATOR, "local");
        MemoryCommandService command = command(entities);
        MemoryProposalService proposals = new MemoryProposalService(facts, relations, entities, assertions,
                new MemoryCurrentValueSlot(entities));
        AddFactCommand value = new AddFactCommand("preferred_editor", "vim", "STRING", Sensitivity.INTERNAL,
                "mcp:memory");

        MemoryFact first = ownTransaction.execute(status -> command.addFactByKey(
                "PERSON", "person:happy", value, extraction));
        MemoryFact repeat = ownTransaction.execute(status -> command.addFactByKey(
                "PERSON", "person:happy", value, extraction));
        assertThat(repeat.getId())
                .as("a repeat while the proposal is still OPEN is the same proposal, not a second row")
                .isEqualTo(first.getId());
        assertThat(openFactCount(first.getEntityId()))
                .as("still exactly one open PENDING fact").isEqualTo(1);

        ownTransaction.executeWithoutResult(status ->
                proposals.decideProposal(first.getEntityId(), ProposalDecision.REJECT, operator));
        MemoryEntity retiredEntity = entities.findById(first.getEntityId()).orElseThrow();
        assertThat(retiredEntity.getStatus()).as("the rejection retired the entity").isEqualTo(EntityStatus.RETIRED);
        MemoryFact rejected = facts.findById(first.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(FactStatus.RETIRED);
        assertThat(rejected.getValidTo()).as("the rejected row is closed but keeps its hash").isNotNull();

        // The same value, the same source, the same key: the identical command.
        MemoryFact reproposed = ownTransaction.execute(status -> command.addFactByKey(
                "PERSON", "person:happy", value, extraction));

        assertThat(reproposed.getId())
                .as("a decided value re-proposed is a NEW proposal, not the retired row")
                .isNotEqualTo(first.getId());
        assertThat(reproposed.getStatus()).isEqualTo(FactStatus.PENDING);
        assertThat(reproposed.getValidTo()).as("a fresh PENDING fact carries no closed window").isNull();

        List<MemoryProposalView> batch = proposals.listProposalsForEntity(
                FactStatus.PENDING, first.getEntityId(), operator);
        List<String> batchFacts = batch.stream()
                .filter(view -> MemorySubjectType.FACT.name().equals(view.subjectType()))
                .map(MemoryProposalView::id)
                .toList();
        assertThat(batchFacts)
                .as("the card's batch is exactly the re-proposal, so the decision has something to decide")
                .containsExactly(reproposed.getId().toString());

        ownTransaction.executeWithoutResult(status ->
                proposals.decideProposal(first.getEntityId(), ProposalDecision.APPROVE, operator));

        assertThat(facts.findById(reproposed.getId()).orElseThrow().getStatus())
                .as("approving the entity promotes the fact the batch read returned")
                .isEqualTo(FactStatus.ACTIVE);
        assertThat(facts.findById(first.getId()).orElseThrow().getStatus())
                .as("and leaves the rejected value decided").isEqualTo(FactStatus.RETIRED);
    }

    private long openFactCount(UUID entityId) {
        return facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.PENDING).size();
    }

    /**
     * The real command service of this slice: real repositories, the real proposal writer (its
     * own {@code REQUIRES_NEW} transaction included) and a publisher the test can count.
     * <p>
     * The writer is the <em>bean</em>, not a {@code new MemoryProposalWriter(...)}: the fresh
     * transaction that reads the winner back is exactly the annotation on that class, and a
     * hand-constructed instance carries no proxy, so its {@code REQUIRES_NEW} would be inert and
     * the read would re-flush the poisoned persistence context.
     */
    private MemoryCommandService command(MemoryEntityRepository entityRepository) {
        return new MemoryCommandService(entityRepository, facts, relations, links, assertions,
                new MemoryValueGuard(), proposalWriter,
                new MemoryCurrentValueSlot(entityRepository), published::add);
    }

    /**
     * The ordering device described in the class comment. {@code findByKindAndCanonicalKey} answers
     * empty on the loser's first (identity) probe only, and the loser's first insert call — {@code
     * save} or {@code saveAndFlush}, whichever the production code makes — first commits the
     * winner's own {@code createEntity}. Everything else is delegated to the real repository, and
     * the winner's id is reported back so a vacuous "the loser inserted its own row" pass is
     * impossible.
     */
    private MemoryEntityRepository racingRepository(MemoryCommandService winner, MemoryActor actor,
                                                    UUID[] winnerId) {
        AtomicBoolean probeMissed = new AtomicBoolean();
        AtomicBoolean winnerCommitted = new AtomicBoolean();
        return (MemoryEntityRepository) Proxy.newProxyInstance(
                MemoryEntityRepository.class.getClassLoader(),
                new Class<?>[] {MemoryEntityRepository.class},
                (proxy, method, args) -> {
                    if ("findByKindAndCanonicalKey".equals(method.getName())
                            && probeMissed.compareAndSet(false, true)) {
                        return Optional.<MemoryEntity>empty();
                    }
                    boolean insertCall = "save".equals(method.getName()) || "saveAndFlush".equals(method.getName());
                    if (insertCall && winnerCommitted.compareAndSet(false, true)) {
                        winnerId[0] = ownTransaction.execute(status -> winner.createEntity(
                                new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL),
                                actor).getId());
                    }
                    try {
                        return method.invoke(entities, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
