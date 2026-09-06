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
        return RunProgressEventEntity.builder()
                .id(UUID.randomUUID()).runId(runId).agentId(UUID.randomUUID())
                .iteration(1).kind(kind).seq(seq).content("fragment " + seq)
                .toolName(null).createdAt(Instant.now())
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
