package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * The decision-path lookup of one approval under a database write lock. The
 * plain finder leaves two overlapping operator decisions (double-click, client
 * retry) free to both observe PENDING, so the second could re-enter the gate
 * after the first settled and re-issue — and re-arm — the one-use grant of the
 * identical call. The row write-lock is held until the deciding transaction
 * commits, so the second decider blocks, then observes the settled row and
 * refuses. It lives beside the coordinator (not in the shared
 * {@code execution.repository} surface) because it exists only for that path;
 * the lock semantics are the same idiom as {@code KnowledgeItemRepository}.
 */
public interface ApprovalDecisionLockRepository extends Repository<Approval, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id")
    Optional<Approval> findByIdForDecision(@Param("id") UUID id);
}
