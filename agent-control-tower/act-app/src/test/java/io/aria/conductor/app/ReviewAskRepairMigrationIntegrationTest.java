package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V66 data repair end to end: a PENDING {@code LEGACY_GATE} ask
 * whose kanban card already sits in DONE/CANCELLED is settled to DENIED with
 * reason "settled by card state" (the stale-ask class observed live
 * 2026-10-03/04), while an ask on a still-open card and a native
 * {@code ACP_PERMISSION} ask obeying its own lifecycle are untouched.
 *
 * <p>Context and fixture style mirror
 * {@link RunDispatchGroupMigrationIntegrationTest}: the booted application
 * context, raw SQL over the real schema for the pre-state rows. Because the
 * repair only means anything against rows written before it, the context is
 * pinned to V65 ({@code spring.flyway.target}) so V66 stays pending while the
 * rows are seeded; the pending migration is then applied by a second Flyway
 * instance once the pre-state exists -- the staged-migrate pattern of
 * {@link ExecutionBindingMigrationIntegrationTest}.
 */
@SpringBootTest(
        classes = {ActApplication.class, NoopLlmTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:review_ask_repair;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.flyway.target=65"
        })
class ReviewAskRepairMigrationIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Test
    void v66SettlesStaleLegacyAsksOnSettledCardsAndNothingElse() {
        // Pre-state, written the raw way the stale rows reached production:
        // one card already in DONE and one still in REVIEW, each carrying a
        // PENDING legacy gate ask, plus a PENDING native ACP ask on the DONE
        // card (its lifecycle belongs to the permission coordinator).
        UUID agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agents (id, name, agent_type, health_status, created_at) "
                        + "VALUES (CAST(? AS UUID), ?, 'NATIVE', 'HEALTHY', CURRENT_TIMESTAMP)",
                agentId.toString(), "review-ask-repair-agent");

        // approvals.run_id carries an FK to runs, so the asks need a real run row.
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO runs (id, agent_id, status, created_at) "
                        + "VALUES (CAST(? AS UUID), CAST(? AS UUID), 'COMPLETED', CURRENT_TIMESTAMP)",
                runId.toString(), agentId.toString());

        UUID doneCardId = UUID.randomUUID();
        UUID reviewCardId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO kanban_items (id, title, status, created_at) "
                        + "VALUES (?, 'already settled', 'DONE', CURRENT_TIMESTAMP)",
                doneCardId.toString());
        jdbcTemplate.update(
                "INSERT INTO kanban_items (id, title, status, created_at) "
                        + "VALUES (?, 'still waiting', 'REVIEW', CURRENT_TIMESTAMP)",
                reviewCardId.toString());

        UUID doneCardLegacyAskId = UUID.randomUUID();
        UUID reviewCardLegacyAskId = UUID.randomUUID();
        UUID doneCardNativeAskId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO approvals (id, run_id, status, source, ask_type, kanban_item_id, context_md, requested_at) "
                        + "VALUES (CAST(? AS UUID), CAST(? AS UUID), 'PENDING', 'LEGACY_GATE', 'REVIEW_REQUEST', ?, ?, CURRENT_TIMESTAMP)",
                doneCardLegacyAskId.toString(), runId.toString(), doneCardId.toString(), "stale legacy ask on the DONE card");
        jdbcTemplate.update(
                "INSERT INTO approvals (id, run_id, status, source, ask_type, kanban_item_id, context_md, requested_at) "
                        + "VALUES (CAST(? AS UUID), CAST(? AS UUID), 'PENDING', 'LEGACY_GATE', 'REVIEW_REQUEST', ?, ?, CURRENT_TIMESTAMP)",
                reviewCardLegacyAskId.toString(), runId.toString(), reviewCardId.toString(), "legacy ask on the REVIEW card");
        jdbcTemplate.update(
                "INSERT INTO approvals (id, run_id, status, source, ask_type, kanban_item_id, context_md, requested_at) "
                        + "VALUES (CAST(? AS UUID), CAST(? AS UUID), 'PENDING', 'ACP_PERMISSION', 'APPROVAL', ?, ?, CURRENT_TIMESTAMP)",
                doneCardNativeAskId.toString(), runId.toString(), doneCardId.toString(), "native ask on the DONE card");

        // Apply the migration(s) the pinned context left pending (V66 today).
        // Pinned to V66: this test asserts the exact V66 contract, so later
        // migrations (V67+) must never be able to change its outcome.
        Flyway toV66 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("66"))
                .load();
        toV66.migrate();

        Map<String, Object> settled = approvalRow(doneCardLegacyAskId);
        assertThat(settled.get("status"))
                .as("the PENDING legacy ask whose card is already DONE is settled")
                .isEqualTo("DENIED");
        assertThat(settled.get("reason")).isEqualTo("settled by card state");
        assertThat(settled.get("decided_at"))
                .as("the settle is recorded as decided")
                .isNotNull();

        Map<String, Object> pendingLegacy = approvalRow(reviewCardLegacyAskId);
        assertThat(pendingLegacy.get("status"))
                .as("a legacy ask on a card still in REVIEW is not stale and stays PENDING")
                .isEqualTo("PENDING");
        assertThat(pendingLegacy.get("reason")).isNull();
        assertThat(pendingLegacy.get("decided_at")).isNull();

        Map<String, Object> nativeAsk = approvalRow(doneCardNativeAskId);
        assertThat(nativeAsk.get("status"))
                .as("native ACP permission asks own their lifecycle and are never card-repaired")
                .isEqualTo("PENDING");
        assertThat(nativeAsk.get("reason")).isNull();
        assertThat(nativeAsk.get("decided_at")).isNull();
    }

    private Map<String, Object> approvalRow(UUID approvalId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, reason, decided_at FROM approvals WHERE id = CAST(? AS UUID)",
                approvalId.toString());
    }
}
