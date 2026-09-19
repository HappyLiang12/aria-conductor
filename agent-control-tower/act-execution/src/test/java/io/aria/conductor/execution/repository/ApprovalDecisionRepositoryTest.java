package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the persistence-context contract the ACP decision path's loser re-read depends on:
 * {@link ApprovalRepository#decidePendingById} is {@code @Modifying(clearAutomatically = true)},
 * so after its 0-row conditional update the caller's next {@code findById} must observe the
 * database state and not its own stale managed instance. {@code ApprovalDecisionService}
 * classifies a lost race from exactly that re-read ({@code ALREADY_DECIDED} vs {@code EXPIRED}).
 * <p>
 * The shared test persistence context stands in for the production request-scoped (OSIV) one;
 * the missing {@code flushAndClear()} is the point of this test.
 */
class ApprovalDecisionRepositoryTest extends DataJpaTestBase {

    @Autowired
    private ApprovalRepository repository;

    @Test
    void decidePendingById_afterAZeroRowUpdate_theLoserRereadSeesTheDatabaseState() {
        Approval a = repository.saveAndFlush(Approval.builder()
                .runId(java.util.UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .source(ApprovalSource.LEGACY_GATE)
                .build());

        // The competing writer wins first, in the same transaction: the row is now EXPIRED.
        assertThat(repository.expirePendingById(a.getId(), "superseded", java.time.Instant.now()))
                .isEqualTo(1);
        // The decision loses the race; clearAutomatically must evict the managed PENDING instance.
        assertThat(repository.decidePendingById(a.getId(), ApprovalStatus.APPROVED, "operator",
                java.time.Instant.now())).isZero();
        // Deliberately no flushAndClear(): the re-read must see the database, not the stale copy.
        assertThat(repository.findById(a.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
    }
}
