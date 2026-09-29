package io.aria.conductor.app;

import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V62 permission-ledger round trip against the real migrated H2 schema: every
 * column of a native permission row and of a write grant is verified as stored,
 * the {@code (run, session, request)} correlation is unique, and the run-purge
 * delete (the Task-12 registration Task 14 consumes) removes exactly one run's
 * rows. The unit lanes cover the behaviour with mocked stores; this class is the
 * mapping/DDL proof.
 */
@Import(NoopLlmTestConfig.class)
class AcpPermissionLedgerIntegrationTest extends BaseH2IntegrationTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID OTHER_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID APPROVAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000703");
    private static final UUID LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000000704");
    private static final Instant CREATED_AT = Instant.parse("2026-09-22T12:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-22T12:05:00Z");

    @Autowired
    AcpPermissionRequestRepository ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void aNativePermissionRowRoundTripsEveryColumnAndItsCorrelationIsUnique() {
        ledger.saveAndFlush(AcpPermissionRequest.builder()
                .id(LEDGER_ID)
                .approvalId(APPROVAL_ID)
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(RUN_ID)
                .sessionId("ses_int_1")
                .requestId("0")
                .toolName("write_file")
                .target("NATIVE_TOOL")
                .argumentsDigest("sha256:abc")
                .argumentsJson("{\"path\":\"notes.txt\"}")
                .optionsJson("[{\"optionId\":\"cancel\",\"choice\":\"DENY\"}]")
                .expiresAt(EXPIRES_AT)
                .deliveryState("AWAITING_DECISION")
                .createdAt(CREATED_AT)
                .build());

        AcpPermissionRequest stored = ledger.findByApprovalId(APPROVAL_ID).orElseThrow();
        assertThat(stored.getId()).isEqualTo(LEDGER_ID);
        assertThat(stored.getKind()).isEqualTo(AcpPermissionRequest.Kind.NATIVE_PERMISSION);
        assertThat(stored.getRunId()).isEqualTo(RUN_ID);
        assertThat(stored.getSessionId()).isEqualTo("ses_int_1");
        assertThat(stored.getRequestId()).isEqualTo("0");
        assertThat(stored.getToolName()).isEqualTo("write_file");
        assertThat(stored.getTarget()).isEqualTo("NATIVE_TOOL");
        assertThat(stored.getArgumentsDigest()).isEqualTo("sha256:abc");
        assertThat(stored.getArgumentsJson()).isEqualTo("{\"path\":\"notes.txt\"}");
        assertThat(stored.getOptionsJson()).isEqualTo("[{\"optionId\":\"cancel\",\"choice\":\"DENY\"}]");
        assertThat(stored.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(stored.getDeliveryState()).isEqualTo("AWAITING_DECISION");
        assertThat(stored.getSelectedOptionId()).isNull();
        assertThat(stored.getDecidedAt()).isNull();
        assertThat(stored.getDeliveredAt()).isNull();
        assertThat(stored.getConsumedAt()).isNull();
        assertThat(stored.getVersion()).isEqualTo(0L);
        assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT);

        assertThat(rowCount(RUN_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT target FROM acp_permission_request WHERE run_id = CAST(? AS UUID)",
                String.class, RUN_ID.toString())).isEqualTo("NATIVE_TOOL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_state FROM acp_permission_request WHERE run_id = CAST(? AS UUID)",
                String.class, RUN_ID.toString())).isEqualTo("AWAITING_DECISION");

        // The same correlation cannot be recorded twice, whatever the payload says.
        assertThatThrownBy(() -> ledger.saveAndFlush(AcpPermissionRequest.builder()
                .id(UUID.randomUUID())
                .approvalId(UUID.randomUUID())
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(RUN_ID)
                .sessionId("ses_int_1")
                .requestId("0")
                .toolName("shell_exec")
                .target("NATIVE_TOOL")
                .argumentsDigest("sha256:def")
                .expiresAt(EXPIRES_AT)
                .deliveryState("AWAITING_DECISION")
                .createdAt(CREATED_AT)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rowCount(RUN_ID)).isEqualTo(1);
    }

    @Test
    void aWriteGrantRoundTripsItsConsumptionAndTheRunPurgeDeletesOneRunOnly() {
        AcpPermissionRequest grant = ledger.saveAndFlush(AcpPermissionRequest.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000705"))
                .kind(AcpPermissionRequest.Kind.WRITE_GRANT)
                .runId(RUN_ID)
                .sessionId("platform-mcp")
                .requestId("grant:sha256:abc")
                .toolName("write_file")
                .target("PLATFORM_MCP")
                .argumentsDigest("sha256:abc")
                .expiresAt(EXPIRES_AT)
                .deliveryState("DELIVERED")
                .createdAt(CREATED_AT)
                .build());
        grant.setConsumedAt(Instant.parse("2026-09-22T12:01:00Z"));
        ledger.saveAndFlush(grant);

        AcpPermissionRequest stored = ledger.findByRunIdAndSessionIdAndRequestId(
                RUN_ID, "platform-mcp", "grant:sha256:abc").orElseThrow();
        assertThat(stored.getKind()).isEqualTo(AcpPermissionRequest.Kind.WRITE_GRANT);
        assertThat(stored.getTarget()).isEqualTo("PLATFORM_MCP");
        assertThat(stored.getConsumedAt()).isEqualTo(Instant.parse("2026-09-22T12:01:00Z"));
        assertThat(stored.getVersion()).isEqualTo(1L);

        // Another run's grant must survive this run's purge.
        ledger.saveAndFlush(AcpPermissionRequest.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000706"))
                .kind(AcpPermissionRequest.Kind.WRITE_GRANT)
                .runId(OTHER_RUN_ID)
                .sessionId("platform-mcp")
                .requestId("grant:sha256:xyz")
                .toolName("write_file")
                .target("PLATFORM_MCP")
                .argumentsDigest("sha256:xyz")
                .expiresAt(EXPIRES_AT)
                .deliveryState("DELIVERED")
                .createdAt(CREATED_AT)
                .build());

        // Housekeeping's run-purge registration: a bulk delete inside one transaction.
        Integer deleted = new TransactionTemplate(transactionManager).execute(status ->
                ledger.deleteByRunIdInBulk(List.of(RUN_ID)));
        assertThat(deleted).isEqualTo(1);

        assertThat(rowCount(RUN_ID)).isEqualTo(0);
        assertThat(rowCount(OTHER_RUN_ID)).isEqualTo(1);
        assertThat(ledger.findByRunId(OTHER_RUN_ID)).hasSize(1);
    }

    private Integer rowCount(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acp_permission_request WHERE run_id = CAST(? AS UUID)",
                Integer.class, runId.toString());
    }
}
