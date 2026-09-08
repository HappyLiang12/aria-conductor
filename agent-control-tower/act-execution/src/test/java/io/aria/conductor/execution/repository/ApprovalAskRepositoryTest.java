package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
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
}
