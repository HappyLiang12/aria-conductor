package io.aria.conductor.memory.service;

import io.aria.conductor.common.event.MemoryProposalCreatedEvent;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.MemoryJpaSliceConfig;
import io.aria.conductor.memory.dto.AddFactCommand;
import io.aria.conductor.memory.dto.CreateEntityCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.MemoryProposalView;
import io.aria.conductor.memory.dto.ProposalDecision;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import io.aria.conductor.memory.dto.SupersedeFactCommand;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryActorType;
import io.aria.conductor.memory.model.MemoryAssertion;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the "one transaction per mutation" rule with a real persistence context: an assertion
 * write that fails must take the write it audits with it.
 * <p>
 * Two shapes live here, and the difference is the point:
 * <ul>
 *   <li><strong>boundary tests</strong> — the rollback of one service call — run with
 *       {@code @Transactional(propagation = NOT_SUPPORTED)} through the service <em>bean</em>, so
 *       no ambient transaction exists and the only boundary left is the service's own
 *       {@code @Transactional}. A {@code new MemoryCommandService(...)} inside the test's own
 *       {@code REQUIRES_NEW} template proves nothing: it has no proxy, and the rollback it
 *       asserts is the template's. Deleting the production annotation must red these tests.</li>
 *   <li><strong>sequence tests</strong> — several commits observed in order — spread those commits
 *       over the test's own committed template and assert on the rows it can then read back.</li>
 * </ul>
 * Every rollback test comes with a positive control, so "zero rows" cannot be confused with "the
 * write never happened": the success case in this class for the entity insert, the seeded committed
 * fact for the retirement, and the approving-entity test in {@link MemoryProposalServiceTest} for
 * the batch decision.
 */
@DataJpaTest
@ContextConfiguration(classes = MemoryJpaSliceConfig.class)
@Import(MemoryCommandServiceTransactionTest.TransactionalCommandServiceConfig.class)
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

    /**
     * The command service behind its Spring transaction proxy. The @Transactional annotation on a
     * {@code new MemoryCommandService(...)} is inert, so the service-owned transaction boundary can
     * only be tested through a bean: the rejected-first-fact test and the three rollback tests below
     * drive this instance with no ambient transaction, so a rollback there can only come from the
     * service's own annotation.
     */
    @Autowired
    MemoryCommandService transactionalCommand;

    /** The proposal service behind its proxy: {@code decideProposal}'s boundary is the one below. */
    @Autowired
    MemoryProposalService transactionalProposals;

    @MockitoBean
    MemoryAssertionRepository assertions;

    private MemoryCommandService command;
    private MemoryProposalService proposals;
    private TransactionTemplate ownTransaction;

    @BeforeEach
    void setUp() {
        command = new MemoryCommandService(entities, facts, relations, links, assertions, new MemoryValueGuard(),
                new MemoryProposalWriter(facts, assertions, entities), new MemoryCurrentValueSlot(entities), event -> { });
        proposals = new MemoryProposalService(facts, relations, entities, assertions,
                new MemoryCurrentValueSlot(entities));
        ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The entity insert and its CREATE assertion are one transaction. Driven through the service
     * <em>bean</em> with NO ambient transaction (the class-level {@code @DataJpaTest} transaction is
     * suspended), so the rollback can only come from {@code createEntity}'s own
     * {@code @Transactional}: the earlier version of this test wrapped the manually constructed
     * service in its own {@code REQUIRES_NEW} template, and deleting the production annotation left
     * it green because the template did the rolling back.
     * <p>
     * {@link #aSuccessfulCommandCommitsWithItsAssertion} is the positive control: the insert is
     * otherwise observable, so a zero count here means "rolled back", not "never written".
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedAssertionWriteRollsBackTheEntityInsert() {
        // The success case below commits for real, and this test runs outside the test transaction,
        // so start from a known empty table in a committed transaction.
        ownTransaction.executeWithoutResult(status -> entities.deleteAll());
        assertThat(entities.count()).as("baseline").isZero();
        when(assertions.save(any(MemoryAssertion.class)))
                .thenThrow(new IllegalStateException("assertion write failed"));

        assertThatThrownBy(() -> transactionalCommand.createEntity(
                new CreateEntityCommand("PERSON", "operator", "Operator", Sensitivity.INTERNAL),
                new MemoryActor(MemoryActorType.OPERATOR, "local")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(entities.count())
                .as("the entity insert and its assertion share one transaction, and it is the service's")
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

    /**
     * Task 14 atomicity: the unknown-key producer creates the entity and its first fact in ONE
     * transaction. A fact the value guard rejects (a credential-like value) must therefore leave
     * no PENDING entity behind — a proposal card the operator can never decide is worse than no
     * card, and the entity row would be exactly that.
     * <p>
     * The call runs with NO ambient transaction (the class-level {@code @DataJpaTest} transaction
     * is suspended here) and through the service <em>bean</em>, not the manually constructed
     * instance: a {@code @Transactional} annotation on {@code new MemoryCommandService(...)} is
     * inert, and the earlier version of this test asserted a rollback produced by its own
     * {@code REQUIRES_NEW} template. Deleting the service annotation must red this test.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRejectedFirstFactLeavesNoPendingEntityBehind() {
        ownTransaction.executeWithoutResult(status -> {
            facts.deleteAll();
            entities.deleteAll();
        });
        assertThat(entities.count()).as("baseline").isZero();
        assertThat(facts.count()).as("baseline").isZero();

        assertThatThrownBy(() -> transactionalCommand.addFactByKey(
                "PERSON", "person:careful",
                new AddFactCommand("preferred_editor", "sk-live-0123456789abcdef", "STRING",
                        Sensitivity.INTERNAL, null),
                new MemoryActor(MemoryActorType.EXTRACTION, UUID.randomUUID().toString())))
                .isInstanceOf(MemoryValidationException.class);

        assertThat(entities.count())
                .as("a rejected first fact must not leave the PENDING entity behind")
                .isZero();
        assertThat(facts.count()).as("nor its fact").isZero();
    }

    /**
     * The service invariant is one current ACTIVE value per (entity, fact key) — spec §6.3,
     * enforced by the service rather than a partial unique index because H2 and MariaDB
     * differ there. This drives two real transactions at the same key and asserts the
     * invariant holds: exactly one write wins and the loser gets a governed conflict, never
     * a second current value.
     */
    @Test
    void twoRacingActiveWritesCannotBothLand() throws Exception {
        UUID entityId = UUID.randomUUID();
        ownTransaction.executeWithoutResult(status -> entities.saveAndFlush(MemoryEntity.builder()
                .id(entityId)
                .kind("PERSON")
                .canonicalKey("operator-" + entityId)
                .displayName("Operator")
                .build()));
        when(assertions.save(any(MemoryAssertion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> raceWrite(entityId, "UTC", start));
            Future<String> second = pool.submit(() -> raceWrite(entityId, "Asia/Hong_Kong", start));
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .as("one writer claims the slot and the other is refused")
                    .containsExactlyInAnyOrder("WRITTEN", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        ownTransaction.executeWithoutResult(status -> assertThat(
                facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .as("never two current values for one key")
                .hasSize(1));
    }

    private String raceWrite(UUID entityId, String value, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            ownTransaction.executeWithoutResult(status -> command.addFact(entityId,
                    new AddFactCommand("timezone", value, "STRING", Sensitivity.INTERNAL, null),
                    new MemoryActor(MemoryActorType.OPERATOR, "local")));
            return "WRITTEN";
        } catch (MemoryConflictException e) {
            return "CONFLICT";
        }
    }

    /**
     * Cross-path race, addFact vs approval, on the same (entity, factKey). Both write a
     * current ACTIVE row, so both must claim the entity slot. The addFact holds the slot
     * (its version bump is flushed and uncommitted) while the approval reads the same
     * version and then tries to claim it: the approval's UPDATE matches no row once the
     * addFact commits, and that loss is the governed conflict. Exactly one current row may
     * survive.
     */
    @Test
    void addFactAndAnApprovalOfTheSameKeyNeverBothLandCurrent() throws Exception {
        UUID entityId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        seedEntityAndPendingProposal(entityId, proposalId, "timezone");
        when(assertions.save(any(MemoryAssertion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch slotHeld = new CountDownLatch(1);
        CountDownLatch approvalRead = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> approval = pool.submit(() -> {
                if (!slotHeld.await(30, TimeUnit.SECONDS)) {
                    return "NEVER_STARTED";
                }
                try {
                    ownTransaction.executeWithoutResult(status -> {
                        // Read the entity at the version the racing addFact read. The service
                        // reuses this persistence context, so its claim carries the stale
                        // version into the UPDATE.
                        entities.findById(entityId).orElseThrow();
                        approvalRead.countDown();
                        proposals.decideProposal(proposalId, ProposalDecision.APPROVE,
                                new MemoryActor(MemoryActorType.OPERATOR, "local"));
                    });
                    return "APPROVED";
                } catch (MemoryConflictException e) {
                    return "CONFLICT";
                }
            });

            ownTransaction.executeWithoutResult(status -> {
                command.addFact(entityId,
                        new AddFactCommand("timezone", "Asia/Hong_Kong", "STRING", Sensitivity.INTERNAL, null),
                        new MemoryActor(MemoryActorType.OPERATOR, "local"));
                slotHeld.countDown();
                assertThat(await(approvalRead))
                        .as("the approval read the entity before the addFact committed")
                        .isTrue();
            });

            assertThat(approval.get(30, TimeUnit.SECONDS))
                    .as("the approval loses the entity slot to the addFact")
                    .isEqualTo("CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        ownTransaction.executeWithoutResult(status -> assertThat(
                facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .as("never two current values for one fact key, whichever path wrote first")
                .hasSize(1));
    }

    /**
     * Cross-path race, addFact vs supersede, on one entity. The supersede opens a new
     * current row, so it claims the same entity slot: reading the entity before the addFact
     * commits must make the supersede's claim lose. The guard is the entity row, so the two
     * paths serialise per entity — that coarseness is the same one two addFacts already pay.
     */
    @Test
    void addFactAndASupersedeOnTheSameEntityNeverBothCommit() throws Exception {
        UUID entityId = UUID.randomUUID();
        UUID currentFactId = UUID.randomUUID();
        seedEntityAndCurrentFact(entityId, currentFactId, "timezone");
        when(assertions.save(any(MemoryAssertion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch slotHeld = new CountDownLatch(1);
        CountDownLatch supersedeRead = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> supersede = pool.submit(() -> {
                if (!slotHeld.await(30, TimeUnit.SECONDS)) {
                    return "NEVER_STARTED";
                }
                try {
                    ownTransaction.executeWithoutResult(status -> {
                        facts.findById(currentFactId).orElseThrow();
                        entities.findById(entityId).orElseThrow();
                        supersedeRead.countDown();
                        command.supersedeFact(currentFactId,
                                new SupersedeFactCommand("Asia/Hong_Kong", 0, null),
                                new MemoryActor(MemoryActorType.OPERATOR, "local"));
                    });
                    return "SUPERSEDED";
                } catch (MemoryConflictException e) {
                    return "CONFLICT";
                }
            });

            ownTransaction.executeWithoutResult(status -> {
                command.addFact(entityId,
                        new AddFactCommand("os", "linux", "STRING", Sensitivity.INTERNAL, null),
                        new MemoryActor(MemoryActorType.OPERATOR, "local"));
                slotHeld.countDown();
                assertThat(await(supersedeRead))
                        .as("the supersede read the entity before the addFact committed")
                        .isTrue();
            });

            assertThat(supersede.get(30, TimeUnit.SECONDS))
                    .as("the supersede loses the entity slot to the addFact")
                    .isEqualTo("CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        ownTransaction.executeWithoutResult(status -> assertThat(
                facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(entityId, FactStatus.ACTIVE))
                .as("one current value per key after the race")
                .hasSize(2)
                .extracting(MemoryFact::getFactKey)
                .containsExactlyInAnyOrder("os", "timezone"));
    }

    /**
     * The governed forget is one transaction: when the RETIRE assertion cannot be written, the fact
     * row must stay current rather than committing a closed row with no audit entry (the regression
     * the inline MCP repository write could not rule out).
     * <p>
     * Same shape as {@link #aFailedAssertionWriteRollsBackTheEntityInsert}: the service bean, no
     * ambient transaction, so {@code retireFact}'s own {@code @Transactional} is the boundary under
     * test and deleting it reds this test. The committed row is the positive control — it is read
     * back and asserted in both fields a retirement would change.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedRetireAssertionRollsBackTheRetirement() {
        UUID entityId = UUID.randomUUID();
        UUID factId = UUID.randomUUID();
        seedEntityAndCurrentFact(entityId, factId, "timezone");
        when(assertions.save(any(MemoryAssertion.class)))
                .thenThrow(new IllegalStateException("assertion write failed"));

        assertThatThrownBy(() -> transactionalCommand.retireFact(factId,
                new MemoryActor(MemoryActorType.OPERATOR, "local")))
                .isInstanceOf(IllegalStateException.class);

        ownTransaction.executeWithoutResult(status -> {
            MemoryFact after = facts.findById(factId).orElseThrow();
            assertThat(after.getStatus())
                    .as("the retirement and its assertion share one transaction, owned by the service")
                    .isEqualTo(FactStatus.ACTIVE);
            assertThat(after.getValidTo()).as("and the row is still current").isNull();
        });
    }

    /**
     * An entity decision is one transaction over a batch: when a later fact's RETIRE assertion
     * cannot be written, the facts already retired in the same call and the entity row must roll
     * back, or the operator is left with a half-decided proposal. The success case in the mock
     * test is the positive control.
     * <p>
     * Same shape as {@link #aFailedAssertionWriteRollsBackTheEntityInsert}: the proposal service
     * bean, no ambient transaction, so {@code decideProposal}'s own {@code @Transactional} is the
     * boundary under test and deleting it reds this test (the first fact's retirement commits and
     * the batch is no longer whole).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedFactAssertionRollsBackTheWholeEntityRejection() {
        UUID entityId = UUID.randomUUID();
        seedEntityAndPendingFacts(entityId, "timezone", "language");
        when(assertions.save(any(MemoryAssertion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new IllegalStateException("assertion write failed"));

        assertThatThrownBy(() -> transactionalProposals.decideProposal(
                entityId, ProposalDecision.REJECT, new MemoryActor(MemoryActorType.OPERATOR, "local")))
                .isInstanceOf(IllegalStateException.class);

        ownTransaction.executeWithoutResult(status -> {
            assertThat(entities.findById(entityId).orElseThrow().getStatus())
                    .as("the entity decision and its fact batch share one transaction")
                    .isEqualTo(EntityStatus.PENDING);
            assertThat(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                    entityId, FactStatus.PENDING))
                    .as("a partially retired batch is never committed")
                    .hasSize(2);
        });
    }

    /**
     * Task 14's acceptance shape, at service level over real persistence: ONE worker
     * {@code remember_fact} naming an unknown canonical key produces a PENDING entity, a
     * PENDING fact under it and one ENTITY proposal event; the entity decision (not a single
     * fact decision — the fact is refused while its parent is PENDING) promotes both; and
     * recall then returns the entity and its fact. This is the test that proves the Wave C
     * proposal surface is not a dead feature.
     */
    @Test
    void theUnknownKeyPathProducesAProposalThatOneEntityDecisionPromotesIntoRecall() {
        List<ApplicationEvent> published = new ArrayList<>();
        MemoryCommandService producer = new MemoryCommandService(entities, facts, relations, links, assertions,
                new MemoryValueGuard(), new MemoryProposalWriter(facts, assertions, entities),
                new MemoryCurrentValueSlot(entities), event -> published.add((ApplicationEvent) event));
        MemoryProposalService reviewer = new MemoryProposalService(facts, relations, entities, assertions,
                new MemoryCurrentValueSlot(entities));
        MemoryRecallService recall = new MemoryRecallService(entities, facts, relations,
                new MemoryMentionExtractor());
        when(assertions.save(any(MemoryAssertion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UUID runId = UUID.randomUUID();
        MemoryActor worker = new MemoryActor(MemoryActorType.EXTRACTION, runId.toString());

        MemoryFact proposal = producer.addFactByKey("PERSON", "person:happy",
                new AddFactCommand("preferred_editor", "IntelliJ IDEA", "STRING", Sensitivity.INTERNAL, null),
                worker);

        MemoryEntity pendingEntity = entities.findById(proposal.getEntityId()).orElseThrow();
        assertThat(pendingEntity.getStatus()).isEqualTo(EntityStatus.PENDING);
        assertThat(pendingEntity.getCanonicalKey()).isEqualTo("person:happy");
        assertThat(proposal.getStatus()).isEqualTo(FactStatus.PENDING);
        assertThat(published).as("one event for the entity proposal, none for the fact").singleElement()
                .satisfies(event -> {
                    MemoryProposalCreatedEvent created = (MemoryProposalCreatedEvent) event;
                    assertThat(created.getSubjectType()).isEqualTo("ENTITY");
                    assertThat(created.getProposalId()).isEqualTo(pendingEntity.getId());
                    assertThat(created.getFactKey()).isEqualTo("person:happy");
                    assertThat(created.getRunId()).isEqualTo(runId);
                });
        assertThat(recall.recall(new RecallQuery("person:happy", Sensitivity.INTERNAL, null)).entities())
                .as("an unapproved proposal is never recall material")
                .isEmpty();

        reviewer.decideProposal(pendingEntity.getId(), ProposalDecision.APPROVE,
                new MemoryActor(MemoryActorType.OPERATOR, "operator-session"));

        assertThat(entities.findById(pendingEntity.getId()).orElseThrow().getStatus())
                .isEqualTo(EntityStatus.ACTIVE);
        assertThat(facts.findById(proposal.getId()).orElseThrow().getStatus()).isEqualTo(FactStatus.ACTIVE);
        RecallResult recalled = recall.recall(new RecallQuery("person:happy", Sensitivity.INTERNAL, null));
        assertThat(recalled.entities()).extracting(MemoryEntityView::canonicalKey).contains("person:happy");
        assertThat(recalled.facts()).extracting(MemoryFactView::factKey).contains("preferred_editor");
    }

    /**
     * T29 Item 3 (P3), the whole sequence over real persistence. An extraction proposes a fact
     * under an ACTIVE entity; the operator retires the entity (lifecycle retire writes no fact
     * row, so the PENDING fact stays PENDING); the same key is extracted again; the operator
     * approves the re-proposed entity. Before the fix the decision promoted the stale PENDING fact
     * the current extraction never proposed. The re-proposal must retire it, keep it out of the
     * card's batch, and the approval must promote nothing.
     */
    @Test
    void aReproposalDoesNotInheritAStalePendingFactIntoTheEntityBatch() {
        when(assertions.save(any(MemoryAssertion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MemoryActor worker = new MemoryActor(MemoryActorType.EXTRACTION, UUID.randomUUID().toString());
        MemoryActor operator = new MemoryActor(MemoryActorType.OPERATOR, "operator-session");
        // This test commits for real (the sequence spans several transactions), so it starts and
        // ends from an empty table: the row count is what a neighbouring test asserts on.
        ownTransaction.executeWithoutResult(status -> {
            facts.deleteAll();
            entities.deleteAll();
        });
        try {
            MemoryEntity active = ownTransaction.execute(status -> entities.saveAndFlush(MemoryEntity.builder()
                    .id(UUID.randomUUID())
                    .kind("PERSON")
                    .canonicalKey("person:happy")
                    .displayName("Happy")
                    .status(EntityStatus.ACTIVE)
                    .origin(MemoryOrigin.OPERATOR)
                    .build()));
            MemoryFact stale = ownTransaction.execute(status -> command.addFact(active.getId(),
                    new AddFactCommand("preferred_editor", "vim", "STRING", Sensitivity.INTERNAL, "run-ref"),
                    worker));
            assertThat(stale.getStatus()).isEqualTo(FactStatus.PENDING);

            ownTransaction.executeWithoutResult(status -> {
                MemoryEntity retiring = entities.findById(active.getId()).orElseThrow();
                retiring.setStatus(EntityStatus.RETIRED);
                entities.saveAndFlush(retiring);
            });

            ownTransaction.executeWithoutResult(status -> command.createEntity(
                    new CreateEntityCommand("PERSON", "person:happy", "Happy", Sensitivity.INTERNAL), worker));

            ownTransaction.executeWithoutResult(status -> {
                List<MemoryProposalView> batch =
                        proposals.listProposalsForEntity(FactStatus.PENDING, active.getId(), operator);
                assertThat(batch).extracting(MemoryProposalView::subjectType)
                        .as("the new batch is the proposal the current extraction created")
                        .containsExactly("ENTITY");
                assertThat(batch).extracting(MemoryProposalView::id)
                        .as("the stale fact the current extraction did not propose is not in the batch")
                        .doesNotContain(stale.getId().toString());
                assertThat(facts.findById(stale.getId()).orElseThrow().getStatus())
                        .as("the stale PENDING row is closed by the re-proposal")
                        .isEqualTo(FactStatus.RETIRED);
            });

            ownTransaction.executeWithoutResult(status ->
                    proposals.decideProposal(active.getId(), ProposalDecision.APPROVE, operator));

            ownTransaction.executeWithoutResult(status -> assertThat(
                    facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                            active.getId(), FactStatus.ACTIVE))
                    .as("the approval promotes nothing the current extraction did not propose")
                    .isEmpty());
        } finally {
            ownTransaction.executeWithoutResult(status -> {
                facts.deleteAll();
                entities.deleteAll();
            });
        }
    }

    /**
     * Item 2 (this lane). The entity card's read and {@code decideEntity}'s batch must be the SAME
     * set, so the read has to select with the decision's own predicate: {@code status = PENDING AND
     * valid_to IS NULL}. T29 Item 1 removed the relation branch from the read; the predicate was not
     * applied, so a PENDING fact that is already closed ({@code valid_to} stamped — a row a
     * re-proposal or an earlier decision left behind) was listed on the card while the decision
     * skipped it, which is the same under-reporting the card was fixed for.
     * <p>
     * Both directions in one test: the open PENDING fact IS in the batch (positive control), the
     * closed one is NOT, and the ids the decision actually promotes are exactly the ids the read
     * showed — the set equality, observed in the database rather than assumed.
     */
    @Test
    void theEntityBatchReadIsExactlyTheSetTheEntityDecisionDecides() {
        UUID entityId = UUID.randomUUID();
        UUID openFactId = UUID.randomUUID();
        UUID closedFactId = UUID.randomUUID();
        MemoryActor operator = new MemoryActor(MemoryActorType.OPERATOR, "operator-session");
        ownTransaction.executeWithoutResult(status -> {
            entities.saveAndFlush(MemoryEntity.builder()
                    .id(entityId)
                    .kind("PERSON")
                    .canonicalKey("person-" + entityId)
                    .displayName("Happy")
                    .status(EntityStatus.PENDING)
                    .origin(MemoryOrigin.EXTRACTION)
                    .build());
            facts.saveAndFlush(pendingFact(openFactId, entityId, "timezone", null));
            facts.saveAndFlush(pendingFact(closedFactId, entityId, "language", Instant.now()));
        });

        List<MemoryProposalView> batch =
                proposals.listProposalsForEntity(FactStatus.PENDING, entityId, operator);

        assertThat(factIds(batch))
                .as("an open PENDING fact is batch material")
                .containsExactly(openFactId.toString());
        assertThat(factIds(batch))
                .as("a PENDING fact that is already closed is not what the decision decides")
                .doesNotContain(closedFactId.toString());
        assertThat(batch).extracting(MemoryProposalView::subjectType)
                .as("the batch is the facts plus the entity row")
                .contains("ENTITY");

        ownTransaction.executeWithoutResult(status ->
                proposals.decideProposal(entityId, ProposalDecision.APPROVE, operator));

        ownTransaction.executeWithoutResult(status -> {
            assertThat(facts.findByEntityIdAndStatusAndValidToIsNullOrderByUpdatedAtDesc(
                    entityId, FactStatus.ACTIVE))
                    .as("the decision promoted exactly the fact the card showed")
                    .extracting(fact -> fact.getId().toString())
                    .containsExactlyInAnyOrderElementsOf(factIds(batch));
            assertThat(facts.findById(closedFactId).orElseThrow().getStatus())
                    .as("the closed row the decision skips is left alone")
                    .isEqualTo(FactStatus.PENDING);
        });
    }

    private static List<String> factIds(List<MemoryProposalView> batch) {
        List<String> ids = new ArrayList<>();
        for (MemoryProposalView view : batch) {
            if ("FACT".equals(view.subjectType())) {
                ids.add(view.id());
            }
        }
        return ids;
    }

    /** A PENDING fact of the entity; {@code validTo} null is an open batch row, set is a closed one. */
    private static MemoryFact pendingFact(UUID id, UUID entityId, String factKey, Instant validTo) {
        return MemoryFact.builder()
                .id(id)
                .entityId(entityId)
                .factKey(factKey)
                .valueText("value")
                .valueType("STRING")
                .status(FactStatus.PENDING)
                .origin(MemoryOrigin.EXTRACTION)
                .sensitivity(Sensitivity.INTERNAL)
                .validFrom(Instant.now())
                .validTo(validTo)
                .build();
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void seedEntityAndPendingFacts(UUID entityId, String... factKeys) {
        ownTransaction.executeWithoutResult(status -> {
            entities.saveAndFlush(MemoryEntity.builder()
                    .id(entityId)
                    .kind("PERSON")
                    .canonicalKey("person-" + entityId)
                    .displayName("Happy")
                    .status(EntityStatus.PENDING)
                    .origin(MemoryOrigin.EXTRACTION)
                    .build());
            for (String factKey : factKeys) {
                facts.saveAndFlush(MemoryFact.builder()
                        .id(UUID.randomUUID())
                        .entityId(entityId)
                        .factKey(factKey)
                        .valueText("value")
                        .valueType("STRING")
                        .status(FactStatus.PENDING)
                        .origin(MemoryOrigin.EXTRACTION)
                        .sensitivity(Sensitivity.INTERNAL)
                        .validFrom(Instant.now())
                        .build());
            }
        });
    }

    private void seedEntityAndPendingProposal(UUID entityId, UUID proposalId, String factKey) {
        ownTransaction.executeWithoutResult(status -> {
            entities.saveAndFlush(MemoryEntity.builder()
                    .id(entityId)
                    .kind("PERSON")
                    .canonicalKey("operator-" + entityId)
                    .displayName("Operator")
                    .build());
            facts.saveAndFlush(MemoryFact.builder()
                    .id(proposalId)
                    .entityId(entityId)
                    .factKey(factKey)
                    .valueText("UTC")
                    .valueType("STRING")
                    .status(FactStatus.PENDING)
                    .origin(MemoryOrigin.EXTRACTION)
                    .sensitivity(Sensitivity.INTERNAL)
                    .validFrom(Instant.now())
                    .build());
        });
    }

    private void seedEntityAndCurrentFact(UUID entityId, UUID factId, String factKey) {
        ownTransaction.executeWithoutResult(status -> {
            entities.saveAndFlush(MemoryEntity.builder()
                    .id(entityId)
                    .kind("PERSON")
                    .canonicalKey("operator-" + entityId)
                    .displayName("Operator")
                    .build());
            facts.saveAndFlush(MemoryFact.builder()
                    .id(factId)
                    .entityId(entityId)
                    .factKey(factKey)
                    .valueText("UTC")
                    .valueType("STRING")
                    .status(FactStatus.ACTIVE)
                    .origin(MemoryOrigin.OPERATOR)
                    .sensitivity(Sensitivity.INTERNAL)
                    .validFrom(Instant.now())
                    .build());
        });
    }

    /**
     * Exposes the two services as beans so the container's transaction proxy applies their
     * {@code @Transactional} annotations. Neither has an interface, so the default CGLIB proxy of
     * the auto-configuration is what makes the boundary real.
     */
    @TestConfiguration
    static class TransactionalCommandServiceConfig {

        @Bean
        MemoryCommandService transactionalCommandService(MemoryEntityRepository entities,
                                                         MemoryFactRepository facts,
                                                         MemoryRelationRepository relations,
                                                         MemoryKnowledgeLinkRepository links,
                                                         MemoryAssertionRepository assertions,
                                                         ApplicationEventPublisher events) {
            return new MemoryCommandService(entities, facts, relations, links, assertions, new MemoryValueGuard(),
                    new MemoryProposalWriter(facts, assertions, entities), new MemoryCurrentValueSlot(entities), events);
        }

        @Bean
        MemoryProposalService transactionalProposalService(MemoryFactRepository facts,
                                                           MemoryRelationRepository relations,
                                                           MemoryEntityRepository entities,
                                                           MemoryAssertionRepository assertions) {
            return new MemoryProposalService(facts, relations, entities, assertions,
                    new MemoryCurrentValueSlot(entities));
        }
    }
}
