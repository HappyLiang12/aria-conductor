package io.aria.conductor.app;

import io.aria.conductor.agent.controller.RunController;
import io.aria.conductor.agent.dto.RunProgressEventDto;
import io.aria.conductor.common.model.RunProgressEventEntity;
import io.aria.conductor.common.repository.RunProgressEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards V75: the persisted progress observation is the WHOLE observation, so its
 * column must hold more than the 65,535 BYTES of MariaDB {@code TEXT} that V51
 * declared. The fixture is deterministic: 65,535 ASCII characters, one multi-byte
 * character exactly at the 64 KiB boundary, then a readable tail — 65,547 characters
 * and 65,548 bytes in utf8mb4, i.e. larger than the old column in both measures.
 *
 * <p>H2 MODE=MySQL cannot demonstrate the failing half of the boundary: it maps both
 * {@code TEXT} and {@code MEDIUMTEXT} to {@code CHARACTER VARYING(1000000000)}, so a
 * 70,000-character insert succeeds before V75 as well. The failing half is proven on
 * MariaDB 11.4 with the real V51 table: the same insert into {@code TEXT} fails with
 * {@code ERROR 1406 (22001): Data too long for column 'content' at row 1}, and after
 * V75 the column reads {@code mediumtext} and the row round-trips with 65,555 chars /
 * 65,556 bytes and the intact tail. This test proves the H2 half: the migration
 * applies, is re-appliable, and the row round-trips through the repository and the
 * {@code GET /runs/{id}/progress} mapping (entity -> DTO) without truncation.
 */
@Import(NoopLlmTestConfig.class)
class V75RunProgressContentWidthMigrationTest extends BaseH2IntegrationTest {

    /** 65,535 ASCII + 'é' (2 bytes) + tail = 65,547 chars / 65,548 utf8mb4 bytes. */
    private static final String OBSERVATION = "a".repeat(65_535) + "é" + "TAIL-ANSWER";

    @Autowired
    Flyway flyway;

    @Autowired
    RunProgressEventRepository repository;

    @Autowired
    RunController runController;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void v75IsTheHeadAppliedByAFreshDatabase() {
        assertThat(flyway.info().current().getVersion().getVersion())
                .as("a fresh database must reach the widening migration")
                .isEqualTo("75");
    }

    @Test
    void theContentColumnCanHoldAtLeastMediumtextCapacity() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT data_type, is_nullable, character_maximum_length FROM information_schema.columns"
                        + " WHERE table_name = 'RUN_PROGRESS_EVENTS' AND column_name = 'CONTENT'");

        assertThat(columns).as("run_progress_events.content exists").hasSize(1);
        assertThat(columns.get(0).get("IS_NULLABLE"))
                .as("the widening keeps V51's nullable contract: a row may carry no content")
                .isEqualTo("YES");
        // H2 reports CHARACTER VARYING(1000000000) for MEDIUMTEXT; MariaDB reports
        // mediumtext / 16777215. Both are >= the MEDIUMTEXT capacity the migration targets,
        // which is what makes the >64 KiB row below storable.
        assertThat(((Number) columns.get(0).get("CHARACTER_MAXIMUM_LENGTH")).longValue())
                .as("content is at least MEDIUMTEXT wide, not the 65,535 bytes of TEXT")
                .isGreaterThanOrEqualTo(16_777_215L);
    }

    @Test
    void aRowLargerThan64KiBRoundTripsThroughTheRepositoryAndTheProgressReplay() {
        UUID runId = UUID.randomUUID();
        repository.save(observation(runId, 1L));

        List<RunProgressEventEntity> stored = repository.findByRunIdOrderBySeqAsc(runId);
        assertThat(stored).as("the whole observation is one row").hasSize(1);
        assertThat(stored.get(0).getContent())
                .as("the repository read back every character")
                .hasSize(OBSERVATION.length())
                .endsWith("TAIL-ANSWER")
                .isEqualTo(OBSERVATION);

        // GET /runs/{id}/progress semantics: the same row through the controller's
        // entity -> DTO mapping, which is what a reopened drawer replays.
        List<RunProgressEventDto> replay = runController.getRunProgress(runId, 0L).getBody();
        assertThat(replay).as("one observation is replayed").hasSize(1);
        RunProgressEventDto dto = replay.get(0);
        assertThat(dto.content()).isEqualTo(OBSERVATION);
        assertThat(dto.content().length())
                .as("the replayed observation is larger than 64 KiB of characters")
                .isGreaterThan(65_535);
        assertThat(dto.content()).endsWith("TAIL-ANSWER");
    }

    /**
     * The migration chain re-applies the script the way a Flyway {@code repair} after a
     * half-run migration would (the V74 precedent): MODIFY COLUMN to the same type is a
     * no-op, so a second application must neither fail nor truncate the widened column.
     */
    @Test
    void reapplyingTheMigrationIsIdempotentAndKeepsTheWidenedColumn() throws SQLException {
        applyV75();
        applyV75();

        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = 'RUN_PROGRESS_EVENTS' AND column_name = 'CONTENT'"))
                .hasSize(1);

        UUID runId = UUID.randomUUID();
        repository.save(observation(runId, 1L));

        assertThat(repository.findByRunIdOrderBySeqAsc(runId))
                .singleElement()
                .extracting(RunProgressEventEntity::getContent)
                .isEqualTo(OBSERVATION);
    }

    private RunProgressEventEntity observation(UUID runId, long seq) {
        return RunProgressEventEntity.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .agentId(UUID.randomUUID())
                .iteration(1)
                .kind("ANSWER")
                .seq(seq)
                .content(OBSERVATION)
                .partId("prt_" + seq)
                .createdAt(Instant.now())
                .build();
    }

    private void applyV75() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V75__run_progress_content_mediumtext.sql"));
        }
    }
}
