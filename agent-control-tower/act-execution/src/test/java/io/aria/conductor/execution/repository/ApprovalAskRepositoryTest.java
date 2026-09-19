package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL ask surface on {@link Approval}: per-kanban-card finders and the bulk
 * deny used when a card's request changes (spec: kanban HITL redesign).
 */
class ApprovalAskRepositoryTest extends DataJpaTestBase {

    @Autowired
    private ApprovalRepository repository;

    private Approval saveAsk(String kanbanItemId, Approval.AskType askType) {
        return repository.save(Approval.builder()
                .runId(java.util.UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .askType(askType)
                .kanbanItemId(kanbanItemId)
                .contextMd("why we ask")
                .optionsJson("[{\"label\":\"yes\",\"suggested\":true}]")
                .build());
    }

    /**
     * A V60 ACP permission ask: owned by {@code AcpPermissionCoordinator} (run-end listener /
     * {@code cancelPendingForRun}), never by a legacy kanban card sweep.
     */
    private Approval saveAcpAsk(String kanbanItemId, Approval.AskType askType) {
        return repository.save(Approval.builder()
                .runId(java.util.UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .askType(askType)
                .kanbanItemId(kanbanItemId)
                .contextMd("why we ask")
                .optionsJson("[{\"label\":\"yes\",\"suggested\":true}]")
                .source(ApprovalSource.ACP_PERMISSION)
                .build());
    }

    @Test
    void findByKanbanItemIdReturnsAsks() {
        saveAsk("item-1", Approval.AskType.REVIEW_REQUEST);
        saveAsk("item-1", Approval.AskType.REVIEW_REQUEST);
        saveAsk("item-2", Approval.AskType.REVIEW_REQUEST);
        assertThat(repository.findByKanbanItemId("item-1")).hasSize(2);
    }

    @Test
    void denyPendingByKanbanItemIdOnlyTouchesPending() {
        Approval a = saveAsk("item-9", Approval.AskType.QUESTION);
        Approval b = saveAsk("item-9", Approval.AskType.QUESTION);
        b.setStatus(ApprovalStatus.APPROVED);
        repository.save(b);

        int denied = repository.denyPendingByKanbanItemId("item-9", "task cancelled", java.time.Instant.now());

        assertThat(denied).isEqualTo(1);
        // Bulk update bypasses the persistence context; force a real SQL round-trip.
        flushAndClear();
        assertThat(repository.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.DENIED);
    }

    @Test
    void markStaleByKanbanItemIdOnlyTouchesPending() {
        Approval a = saveAsk("item-7", Approval.AskType.QUESTION);
        Approval b = saveAsk("item-7", Approval.AskType.QUESTION);
        b.setStatus(ApprovalStatus.APPROVED);
        repository.save(b);

        int stale = repository.markStaleByKanbanItemId("item-7", java.time.Instant.now());

        assertThat(stale).isEqualTo(1);
        // Bulk update bypasses the persistence context; force a real SQL round-trip.
        flushAndClear();
        Approval reloaded = repository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(reloaded.getDecidedAt()).isNotNull();
    }

    /**
     * I1: an ACP ask is owned by {@code AcpPermissionCoordinator}; a card cancel sweeps only
     * legacy rows, otherwise the ask diverges from its companion with no delivery.
     */
    @Test
    void denyPendingByKanbanItemId_leavesAcpPermissionRowsToTheCoordinator() {
        Approval legacy = saveAsk("item-acp-deny", Approval.AskType.QUESTION);
        Approval acp = saveAcpAsk("item-acp-deny", Approval.AskType.QUESTION);

        int denied = repository.denyPendingByKanbanItemId("item-acp-deny", "task cancelled", java.time.Instant.now());

        assertThat(denied).isEqualTo(1);
        // Bulk update bypasses the persistence context; force a real SQL round-trip.
        flushAndClear();
        assertThat(repository.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(repository.findById(acp.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    /** I1: same shape for the request-changes sweep. */
    @Test
    void markStaleByKanbanItemId_leavesAcpPermissionRowsToTheCoordinator() {
        Approval legacy = saveAsk("item-acp-stale", Approval.AskType.QUESTION);
        Approval acp = saveAcpAsk("item-acp-stale", Approval.AskType.QUESTION);

        int stale = repository.markStaleByKanbanItemId("item-acp-stale", java.time.Instant.now());

        assertThat(stale).isEqualTo(1);
        // Bulk update bypasses the persistence context; force a real SQL round-trip.
        flushAndClear();
        assertThat(repository.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        Approval reloadedAcp = repository.findById(acp.getId()).orElseThrow();
        assertThat(reloadedAcp.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(reloadedAcp.getDecidedAt()).isNull();
    }
}
