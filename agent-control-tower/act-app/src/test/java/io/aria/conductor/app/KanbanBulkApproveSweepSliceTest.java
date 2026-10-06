package io.aria.conductor.app;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B task 2 pin: the kanban card bulk-approve sweep
 * ({@link ApprovalRepository#approvePendingByKanbanItemId}) settles only
 * {@code LEGACY_GATE} asks. A {@code CLARIFICATION} ask is provenance-stamped
 * and answered via {@code /approvals/{id}/answer} or the run finalize path, so
 * a card bulk-approve must leave it PENDING — the same ownership rule the
 * sweep already applies to {@code ACP_PERMISSION} asks (mirrors the deny-side
 * pin in act-execution's {@code ApprovalAskRepositoryTest}).
 *
 * <p>Flyway is disabled for the slice and Hibernate create-drop DDL provides the schema:
 * act-app's test config defaults to {@code ddl-auto: none} + Flyway (the MariaDB-flavored
 * migrations cannot run on {@code @DataJpaTest}'s embedded plain-H2 datasource), and the
 * shared {@link DataJpaTestBase} is module-agnostic and stays untouched.
 */
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class KanbanBulkApproveSweepSliceTest extends DataJpaTestBase {

    @Autowired
    private ApprovalRepository repository;

    @Test
    void cardBulkApproveNeverTouchesClarificationAsks() {
        Approval legacyAsk = repository.save(Approval.builder()
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.REVIEW_REQUEST)
                .kanbanItemId("item-clarify")
                .source(ApprovalSource.LEGACY_GATE)
                .build());
        Approval clarificationAsk = repository.save(Approval.builder()
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .kanbanItemId("item-clarify")
                .source(ApprovalSource.CLARIFICATION)
                .build());

        int swept = repository.approvePendingByKanbanItemId(
                "item-clarify", "accepted by card decision", Instant.now());

        assertThat(swept).isEqualTo(1);
        // Bulk update bypasses the persistence context; force a real SQL round-trip.
        flushAndClear();
        assertThat(repository.findById(legacyAsk.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);
        assertThat(repository.findById(clarificationAsk.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }
}
