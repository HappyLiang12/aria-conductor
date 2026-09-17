package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.AcpPermissionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence tests for {@link AcpPermissionRequestRepository}. The companion row is 1:1 with its
 * governance {@code Approval} (shared primary key) and carries the bridge correlation the ACP
 * coordinator deduplicates on — the unique constraint is enforced by the database, not by
 * application-side find-then-save, so a duplicate bridge event can never create a second ask.
 */
@DataJpaTest
class AcpPermissionRequestRepositoryTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2030-01-01T00:00:05Z");

    @Autowired
    AcpPermissionRequestRepository repository;

    @Autowired
    TestEntityManager entityManager;

    private AcpPermissionRequest request(UUID approvalId, UUID runId, String sessionId, String requestId) {
        return AcpPermissionRequest.builder()
                .approvalId(approvalId)
                .runId(runId)
                .agentId(UUID.randomUUID())
                .bridgeSessionId(sessionId)
                .bridgeRequestId(requestId)
                .toolCallId("tool-call-1")
                .toolName("write_file")
                .optionsJson("[{\"optionId\":\"allow-once\",\"kind\":\"allow_once\"}]")
                .requestDigest("digest-synthetic")
                .displayJson("{\"title\":\"Write file\"}")
                .expiresAt(EXPIRES_AT)
                .deliveryState("PENDING_DELIVERY")
                .selectedOptionId("allow-once")
                .deliveredAt(DELIVERED_AT)
                .build();
    }

    @Test
    void save_thenFindById_roundTripsFieldsIncludingTimestamps() {
        UUID approvalId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        repository.saveAndFlush(request(approvalId, runId, "session-1", "request-1"));

        AcpPermissionRequest found = repository.findById(approvalId).orElseThrow();

        assertThat(found.getRunId()).isEqualTo(runId);
        assertThat(found.getBridgeSessionId()).isEqualTo("session-1");
        assertThat(found.getBridgeRequestId()).isEqualTo("request-1");
        assertThat(found.getToolName()).isEqualTo("write_file");
        assertThat(found.getRequestDigest()).isEqualTo("digest-synthetic");
        assertThat(found.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(found.getDeliveredAt()).isEqualTo(DELIVERED_AT);
        assertThat(found.getDeliveryState()).isEqualTo("PENDING_DELIVERY");
        assertThat(found.getSelectedOptionId()).isEqualTo("allow-once");
    }

    @Test
    void duplicateCorrelation_differentApproval_isRejectedByTheUniqueConstraint() {
        repository.saveAndFlush(request(UUID.randomUUID(), UUID.randomUUID(), "session-1", "request-1"));

        AcpPermissionRequest duplicate =
                request(UUID.randomUUID(), UUID.randomUUID(), "session-1", "request-1");

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByApprovalIdIn_returnsOnlyTheRequestedIds() {
        UUID approval1 = UUID.randomUUID();
        UUID approval2 = UUID.randomUUID();
        repository.saveAllAndFlush(List.of(
                request(approval1, UUID.randomUUID(), "session-1", "request-1"),
                request(approval2, UUID.randomUUID(), "session-2", "request-2")));

        assertThat(repository.findByApprovalIdIn(List.of(approval1)))
                .singleElement()
                .extracting(AcpPermissionRequest::getApprovalId)
                .isEqualTo(approval1);
    }

    @Test
    void deleteByRunIdInBulk_removesOnlyTargetedRunsRows() {
        UUID approval1 = UUID.randomUUID();
        UUID approval2 = UUID.randomUUID();
        UUID approval3 = UUID.randomUUID();
        UUID purgedRun = UUID.randomUUID();
        UUID keptRun = UUID.randomUUID();
        repository.saveAllAndFlush(List.of(
                request(approval1, purgedRun, "session-1", "request-1"),
                request(approval2, purgedRun, "session-1", "request-2"),
                request(approval3, keptRun, "session-2", "request-3")));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.deleteByRunIdInBulk(List.of(purgedRun))).isEqualTo(2);
        assertThat(repository.deleteByRunIdInBulk(List.of())).isZero();

        assertThat(repository.findById(approval1)).isEmpty();
        assertThat(repository.findById(approval2)).isEmpty();
        assertThat(repository.findById(approval3)).isPresent();
        assertThat(repository.count()).isEqualTo(1);
    }
}
