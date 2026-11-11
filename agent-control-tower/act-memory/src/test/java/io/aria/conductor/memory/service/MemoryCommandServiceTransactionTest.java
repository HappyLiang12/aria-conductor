package io.aria.conductor.memory.service;

import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.MemoryJpaSliceConfig;
import io.aria.conductor.memory.dto.AddFactCommand;
import io.aria.conductor.memory.dto.CreateEntityCommand;
import io.aria.conductor.memory.dto.MemoryActor;
import io.aria.conductor.memory.dto.ProposalDecision;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    private MemoryProposalService proposals;
    private TransactionTemplate ownTransaction;

    @BeforeEach
    void setUp() {
        command = new MemoryCommandService(entities, facts, relations, links, assertions, new MemoryValueGuard(),
                new MemoryProposalWriter(facts, assertions), new MemoryCurrentValueSlot(entities), event -> { });
        proposals = new MemoryProposalService(facts, relations, entities, assertions,
                new MemoryCurrentValueSlot(entities));
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
     * The governed forget is one transaction: when the RETIRE assertion cannot be written,
     * the fact row must stay current rather than committing a closed row with no audit
     * entry (the regression the inline MCP repository write could not rule out).
     */
    @Test
    void aFailedRetireAssertionRollsBackTheRetirement() {
        UUID entityId = UUID.randomUUID();
        UUID factId = UUID.randomUUID();
        seedEntityAndCurrentFact(entityId, factId, "timezone");
        when(assertions.save(any(MemoryAssertion.class)))
                .thenThrow(new IllegalStateException("assertion write failed"));

        assertThatThrownBy(() -> ownTransaction.executeWithoutResult(status ->
                command.retireFact(factId, new MemoryActor(MemoryActorType.OPERATOR, "local"))))
                .isInstanceOf(IllegalStateException.class);

        ownTransaction.executeWithoutResult(status -> {
            MemoryFact after = facts.findById(factId).orElseThrow();
            assertThat(after.getStatus())
                    .as("the retirement and its assertion share one transaction")
                    .isEqualTo(FactStatus.ACTIVE);
            assertThat(after.getValidTo()).isNull();
        });
    }

    /**
     * An entity decision is one transaction over a batch: when a later fact's RETIRE assertion
     * cannot be written, the facts already retired in the same call and the entity row must roll
     * back, or the operator is left with a half-decided proposal. The success case in the mock
     * test is the positive control.
     */
    @Test
    void aFailedFactAssertionRollsBackTheWholeEntityRejection() {
        UUID entityId = UUID.randomUUID();
        seedEntityAndPendingFacts(entityId, "timezone", "language");
        when(assertions.save(any(MemoryAssertion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new IllegalStateException("assertion write failed"));

        assertThatThrownBy(() -> ownTransaction.executeWithoutResult(status -> proposals.decideProposal(
                entityId, ProposalDecision.REJECT, new MemoryActor(MemoryActorType.OPERATOR, "local"))))
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
}
