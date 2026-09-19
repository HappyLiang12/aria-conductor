package io.aria.conductor.execution.repository;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.test.DataJpaTestBase;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Housekeeping S1: bulk {@code @Modifying} deletes used by the run purge must
 * remove exactly the targeted runs' child rows (and the runs themselves) in
 * one statement each, leaving unrelated rows untouched.
 */
class HousekeepingBulkDeleteSliceIntegrationTest extends DataJpaTestBase {

    @Autowired SessionTrajectoryRepository trajectoryRepository;
    @Autowired ToolCallRepository toolCallRepository;
    @Autowired PromptCallRepository promptCallRepository;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired AcpPermissionRequestRepository acpPermissionRequestRepository;
    @Autowired AgentSessionRepository agentSessionRepository;
    @Autowired RunRepository runRepository;

    private UUID seedRunWithChildren(RunStatus status) {
        UUID runId = UUID.randomUUID();
        entityManager.persist(TestDataBuilder.aRun().withId(runId).withStatus(status).build());
        entityManager.persist(SessionTrajectory.builder()
                .runId(runId).turnNumber(1).role("assistant").content("t").createdAt(Instant.now()).build());
        entityManager.persist(TestDataBuilder.aToolCall().withRunId(runId).build());
        entityManager.persist(TestDataBuilder.aPromptCall().withRunId(runId).build());
        Approval approval = TestDataBuilder.anApproval().withRunId(runId).build();
        entityManager.persist(approval);
        // V60 companion row: FK-references the seeded approval, so the purge must remove it too.
        entityManager.persist(acpRequest(approval.getId(), runId));
        entityManager.persist(TestDataBuilder.anAgentSession().withRunId(runId).build());
        return runId;
    }

    private AcpPermissionRequest acpRequest(UUID approvalId, UUID runId) {
        return AcpPermissionRequest.builder()
                .approvalId(approvalId)
                .runId(runId)
                .bridgeSessionId("session-" + runId)
                .bridgeRequestId("request-" + approvalId)
                .toolName("write_file")
                .requestDigest("digest-" + approvalId)
                .expiresAt(Instant.now().plusSeconds(600))
                .deliveryState("PENDING_DELIVERY")
                .build();
    }

    @Test
    void bulkDeletes_removeOnlyTargetedRunsChildRowsAndRuns() {
        UUID gone1 = seedRunWithChildren(RunStatus.COMPLETED);
        UUID gone2 = seedRunWithChildren(RunStatus.FAILED);
        UUID kept = seedRunWithChildren(RunStatus.COMPLETED);
        flushAndClear();

        List<UUID> targets = List.of(gone1, gone2);

        assertThat(trajectoryRepository.deleteByRunIdInBulk(targets)).isEqualTo(2);
        assertThat(toolCallRepository.deleteByRunIdInBulk(targets)).isEqualTo(2);
        assertThat(promptCallRepository.deleteByRunIdInBulk(targets)).isEqualTo(2);
        assertThat(acpPermissionRequestRepository.deleteByRunIdInBulk(targets)).isEqualTo(2);
        assertThat(approvalRepository.deleteByRunIdInBulk(targets)).isEqualTo(2);
        assertThat(agentSessionRepository.deleteByRunIdInBulk(targets)).isEqualTo(2);
        assertThat(runRepository.deleteByIdInBulk(targets)).isEqualTo(2);
        flushAndClear();

        // Targets fully purged, kept run + children intact (no orphans either way).
        assertThat(runRepository.findById(gone1)).isEmpty();
        assertThat(runRepository.findById(gone2)).isEmpty();
        assertThat(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(kept))).hasSize(1);
        assertThat(toolCallRepository.findByRunId(kept)).hasSize(1);
        assertThat(promptCallRepository.findByRunId(kept)).hasSize(1);
        assertThat(approvalRepository.findByRunId(kept)).hasSize(1);
        assertThat(acpPermissionRequestRepository.findAll())
                .extracting(AcpPermissionRequest::getRunId).containsExactly(kept);
        assertThat(agentSessionRepository.findById(kept)).isPresent();
        assertThat(runRepository.findById(kept)).isPresent();
    }

    @Test
    void bulkDeletes_onEmptyOrUnknownIds_areNoOps() {
        UUID kept = seedRunWithChildren(RunStatus.COMPLETED);
        flushAndClear();

        assertThat(trajectoryRepository.deleteByRunIdInBulk(List.of(UUID.randomUUID()))).isZero();
        assertThat(acpPermissionRequestRepository.deleteByRunIdInBulk(List.of(UUID.randomUUID()))).isZero();
        assertThat(acpPermissionRequestRepository.deleteByRunIdInBulk(List.of())).isZero();
        assertThat(runRepository.deleteByIdInBulk(List.of())).isZero();
        flushAndClear();

        assertThat(runRepository.findById(kept)).isPresent();
        assertThat(acpPermissionRequestRepository.findAll())
                .extracting(AcpPermissionRequest::getRunId).containsExactly(kept);
    }
}
