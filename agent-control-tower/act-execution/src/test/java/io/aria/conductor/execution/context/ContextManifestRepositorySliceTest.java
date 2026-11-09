package io.aria.conductor.execution.context;

import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The manifest is the provenance record of one assembly: one row per turn of a run,
 * read back in turn order for {@code GET /api/v1/memory/runs/{id}/context-manifest}.
 * The run id and turn number are the row's identity, so the same turn can never be
 * recorded twice.
 */
class ContextManifestRepositorySliceTest extends DataJpaTestBase {

    @Autowired
    ContextManifestRepository manifests;

    @Test
    void manifestsForOneRunAreReturnedInTurnOrder() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        manifests.saveAndFlush(manifest(runId, 2));
        manifests.saveAndFlush(manifest(otherRunId, 0));
        manifests.saveAndFlush(manifest(runId, 0));
        manifests.saveAndFlush(manifest(runId, 1));
        flushAndClear();

        var rows = manifests.findByRunIdOrderByTurnNumberAsc(runId);

        assertThat(rows).extracting(ContextManifest::getTurnNumber).containsExactly(0, 1, 2);
        assertThat(rows).allMatch(row -> runId.equals(row.getRunId()));
        assertThat(rows.get(0).getSectionsJson()).as("section ids, not prompt text")
                .contains("policy", "goal");
    }

    @Test
    void turnNumberIsUniquePerRun() {
        UUID runId = UUID.randomUUID();
        manifests.saveAndFlush(manifest(runId, 0));

        assertThatThrownBy(() -> manifests.saveAndFlush(manifest(runId, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameTurnNumberIsAllowedOnAnotherRun() {
        manifests.saveAndFlush(manifest(UUID.randomUUID(), 0));
        manifests.saveAndFlush(manifest(UUID.randomUUID(), 0));

        assertThat(manifests.count()).isEqualTo(2);
    }

    private ContextManifest manifest(UUID runId, int turnNumber) {
        return ContextManifest.builder()
                .runId(runId)
                .turnNumber(turnNumber)
                .conversationId("conv-" + runId)
                .agentId(UUID.randomUUID())
                .adapter("CORE")
                .sectionsJson("[{\"id\":\"policy\"},{\"id\":\"goal\"}]")
                .droppedJson("[]")
                .degradedJson("[]")
                .maxChars(24_000)
                .estimatedTokens(turnNumber * 100)
                .createdAt(Instant.now())
                .build();
    }
}
