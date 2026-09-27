package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V60 provenance: every {@link Approval} carries an {@link ApprovalSource}. Rows created by the
 * legacy gate path — and rows that predate the column, which the migration backfills through the
 * database default — must read back as {@code LEGACY_GATE} so the legacy surfaces keep behaving
 * identically.
 */
@DataJpaTest
class ApprovalSourcePersistenceTest {

    @Autowired
    TestEntityManager entityManager;

    @Test
    void builder_withoutSource_defaultsToLegacyGate() {
        assertThat(Approval.builder().build().getSource()).isEqualTo(ApprovalSource.LEGACY_GATE);
    }

    @Test
    void builder_withAcpPermission_keepsSuppliedSource() {
        Approval approval = Approval.builder().source(ApprovalSource.ACP_PERMISSION).build();

        assertThat(approval.getSource()).isEqualTo(ApprovalSource.ACP_PERMISSION);
    }

    @Test
    void persistedRows_roundTripSource() {
        Approval legacy = Approval.builder().id(UUID.randomUUID()).runId(UUID.randomUUID()).build();
        Approval acp = Approval.builder().id(UUID.randomUUID()).runId(UUID.randomUUID())
                .source(ApprovalSource.ACP_PERMISSION).build();
        entityManager.persist(legacy);
        entityManager.persist(acp);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Approval.class, legacy.getId()).getSource())
                .isEqualTo(ApprovalSource.LEGACY_GATE);
        assertThat(entityManager.find(Approval.class, acp.getId()).getSource())
                .isEqualTo(ApprovalSource.ACP_PERMISSION);
    }
}
