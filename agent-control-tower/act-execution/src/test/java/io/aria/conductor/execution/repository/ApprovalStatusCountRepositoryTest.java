package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural cover for {@link ApprovalRepository#countByStatus}: the P6 state section
 * shows a pending-approval count, and it must count rows rather than load them, per
 * status, returning zero for a status no approval carries.
 */
class ApprovalStatusCountRepositoryTest extends DataJpaTestBase {

    @Autowired
    private ApprovalRepository repository;

    @Test
    void countByStatusCountsOnlyTheRequestedStatusAndReturnsZeroWhenAbsent() {
        repository.save(approval(ApprovalStatus.PENDING));
        repository.save(approval(ApprovalStatus.PENDING));
        repository.save(approval(ApprovalStatus.APPROVED));
        repository.save(approval(ApprovalStatus.DENIED));
        flushAndClear();

        assertThat(repository.countByStatus(ApprovalStatus.PENDING)).isEqualTo(2);
        assertThat(repository.countByStatus(ApprovalStatus.APPROVED)).isEqualTo(1);
        assertThat(repository.countByStatus(ApprovalStatus.DENIED)).isEqualTo(1);
        assertThat(repository.countByStatus(ApprovalStatus.EXPIRED)).isZero();
    }

    private Approval approval(ApprovalStatus status) {
        return Approval.builder()
                .runId(UUID.randomUUID())
                .status(status)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .build();
    }
}
