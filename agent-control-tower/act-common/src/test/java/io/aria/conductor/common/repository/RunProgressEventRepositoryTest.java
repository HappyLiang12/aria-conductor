package io.aria.conductor.common.repository;

import io.aria.conductor.common.model.RunProgressEventEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RunProgressEventRepositoryTest {

    @Autowired
    RunProgressEventRepository repository;

    private RunProgressEventEntity entry(UUID runId, long seq, String kind) {
        return entry(runId, seq, kind, null);
    }

    private RunProgressEventEntity entry(UUID runId, long seq, String kind, String partId) {
        return RunProgressEventEntity.builder()
                .id(UUID.randomUUID()).runId(runId).agentId(UUID.randomUUID())
                .iteration(1).kind(kind).seq(seq).content("fragment " + seq)
                .toolName(null).partId(partId).createdAt(Instant.now())
                .build();
    }

    @Test
    void findByRunIdAndSeqAfterOrderBySeqAsc_returnsOrderedBacklog() {
        UUID runId = UUID.randomUUID();
        repository.saveAll(List.of(entry(runId, 3, "THINKING"), entry(runId, 1, "THINKING"), entry(runId, 2, "TOOL_CALL")));

        List<RunProgressEventEntity> all = repository.findByRunIdOrderBySeqAsc(runId);
        List<RunProgressEventEntity> after = repository.findByRunIdAndSeqAfterOrderBySeqAsc(runId, 1L);

        assertThat(all).extracting(RunProgressEventEntity::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(after).extracting(RunProgressEventEntity::getSeq).containsExactly(2L, 3L);
    }

    /**
     * Issue #103: a row is an observation of a part, so two observations of ONE
     * growing part must both be stored, both carry the same part_id, and both come
     * back in seq order — the REST replay hands the client the whole growth and the
     * client collapses it. A repository that overwrote the earlier row would make the
     * persisted backlog disagree with what the live stream showed.
     */
    @Test
    void successiveObservationsOfOnePartRoundTripWithTheirIdentityAndOrder() {
        UUID runId = UUID.randomUUID();
        repository.saveAll(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(runId)
                        .agentId(UUID.randomUUID()).iteration(1).kind("ANSWER").seq(1L)
                        .content("Consensus needs").partId("prt_1").createdAt(Instant.now()).build(),
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(runId)
                        .agentId(UUID.randomUUID()).iteration(1).kind("ANSWER").seq(2L)
                        .content("Consensus needs a majority.").partId("prt_1")
                        .createdAt(Instant.now()).build(),
                entry(runId, 3, "TOOL_RESULT", "prt_2")));

        List<RunProgressEventEntity> backlog = repository.findByRunIdOrderBySeqAsc(runId);

        assertThat(backlog).extracting(RunProgressEventEntity::getPartId)
                .containsExactly("prt_1", "prt_1", "prt_2");
        assertThat(backlog).extracting(RunProgressEventEntity::getSeq)
                .containsExactly(1L, 2L, 3L);
        assertThat(backlog.get(1).getContent()).isEqualTo("Consensus needs a majority.");
        assertThat(repository.findByRunIdAndSeqAfterOrderBySeqAsc(runId, 1L))
                .extracting(RunProgressEventEntity::getPartId)
                .as("an incremental replay from a watermark still names the part")
                .containsExactly("prt_1", "prt_2");
    }

    /**
     * part_id is nullable by contract (V74): a row written before the column existed
     * and a row from a core that reports no part id both read back as NULL, so
     * historical runs replay with seq as their only identity.
     */
    @Test
    void aRowWithNoPartIdPersistsAndReadsBackNull() {
        UUID runId = UUID.randomUUID();
        repository.save(entry(runId, 1, "THINKING"));

        assertThat(repository.findByRunIdOrderBySeqAsc(runId))
                .extracting(RunProgressEventEntity::getPartId)
                .containsOnlyNulls();
    }

    /**
     * The kind column is a VARCHAR(32) with no CHECK constraint, so a new kind is
     * only proven persistable by round-tripping it: issue #103's ANSWER rows must
     * survive the same (runId, seq) backlog replay as every other kind.
     */
    @Test
    void answerKind_roundTripsThroughTheOrderedBacklog() {
        UUID runId = UUID.randomUUID();
        repository.saveAll(List.of(entry(runId, 1, "THINKING"), entry(runId, 2, "ANSWER"),
                entry(runId, 3, "TOOL_RESULT")));

        assertThat(repository.findByRunIdOrderBySeqAsc(runId))
                .extracting(RunProgressEventEntity::getKind)
                .containsExactly("THINKING", "ANSWER", "TOOL_RESULT");
        assertThat(repository.findByRunIdAndSeqAfterOrderBySeqAsc(runId, 1L))
                .extracting(RunProgressEventEntity::getKind)
                .containsExactly("ANSWER", "TOOL_RESULT");
    }

    @Test
    void deleteByCreatedAtBefore_removesOnlyOldRows() {
        UUID runId = UUID.randomUUID();
        RunProgressEventEntity old = entry(runId, 1, "THINKING");
        old.setCreatedAt(Instant.now().minusSeconds(86400 * 30));
        RunProgressEventEntity fresh = entry(runId, 2, "THINKING");
        repository.saveAll(List.of(old, fresh));

        int removed = repository.deleteByCreatedAtBefore(Instant.now().minusSeconds(86400 * 8));

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findAll()).extracting(RunProgressEventEntity::getId).containsOnly(fresh.getId());
    }

    @Test
    void deleteByCreatedAtBefore_emptyTable_returnsZero() {
        assertThat(repository.deleteByCreatedAtBefore(Instant.now())).isZero();
    }

    @Test
    void crossRun_isolation_deleteAndQuery() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        repository.saveAll(List.of(entry(runA, 1, "THINKING"), entry(runB, 1, "THINKING")));

        assertThat(repository.findByRunIdAndSeqAfterOrderBySeqAsc(runA, 0L))
                .allSatisfy(e -> assertThat(e.getRunId()).isEqualTo(runA));
        assertThat(repository.deleteByCreatedAtBefore(Instant.now().plusSeconds(60))).isEqualTo(2);
        assertThat(repository.findByRunIdOrderBySeqAsc(runB)).isEmpty();
    }
}
