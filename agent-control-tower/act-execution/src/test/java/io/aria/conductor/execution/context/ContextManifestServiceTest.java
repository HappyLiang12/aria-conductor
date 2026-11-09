package io.aria.conductor.execution.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manifest is the provenance record of one assembly (spec §6.6, §8.5): one
 * row per (run, turn), carrying ids, counts, estimates, drops and degradation
 * reasons — never prompt text. A second assembly for the same turn (a retry)
 * must not violate the unique key, so it updates the row it already owns.
 */
class ContextManifestServiceTest extends DataJpaTestBase {

    @Autowired
    ContextManifestRepository manifests;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aSecondAssemblyForTheSameTurnUpdatesThatTurnRow() {
        UUID runId = UUID.randomUUID();
        ContextManifestService service = new ContextManifestService(manifests, mapper);
        ContextRequest request = request(runId, 2);

        UUID first = service.record(request, assembled("policy:a", 10));
        flushAndClear();
        UUID second = service.record(request, assembled("policy:b", 20));
        flushAndClear();

        List<ContextManifest> rows = manifests.findByRunIdOrderByTurnNumberAsc(runId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(first).isEqualTo(second);
        assertThat(rows.get(0).getSectionsJson()).contains("policy:b");
        assertThat(rows.get(0).getEstimatedTokens()).isEqualTo(20);
    }

    @Test
    void differentTurnsAreDifferentRows() {
        UUID runId = UUID.randomUUID();
        ContextManifestService service = new ContextManifestService(manifests, mapper);

        service.record(request(runId, 0), assembled("policy:a", 10));
        flushAndClear();
        service.record(request(runId, 1), assembled("policy:b", 20));
        flushAndClear();

        assertThat(manifests.findByRunIdOrderByTurnNumberAsc(runId))
                .extracting(ContextManifest::getTurnNumber).containsExactly(0, 1);
    }

    @Test
    void theRecordCarriesIdsCountsReasonsAndNeverThePromptText() {
        UUID runId = UUID.randomUUID();
        ContextManifestService service = new ContextManifestService(manifests, mapper);
        ContextRequest request = request(runId, 3);
        ContextSection section = new ContextSection("policy.agent", "Policy", "secret prompt body",
                Sensitivity.INTERNAL, List.of("agent-1"), false, null);

        service.record(request, new AssembledContext("## Policy\nsecret prompt body\n\n",
                List.of(new ContextMessage("user", "hello", null)), 12,
                List.of(section), List.of("knowledge:1:sensitivity"), List.of("memory:error")));
        flushAndClear();

        ContextManifest row = manifests.findByRunIdOrderByTurnNumberAsc(runId).get(0);
        assertThat(row.getSectionsJson()).contains("policy.agent", "agent-1", "chars");
        assertThat(row.getSectionsJson()).doesNotContain("secret prompt body");
        assertThat(row.getDroppedJson()).contains("knowledge:1:sensitivity");
        assertThat(row.getDegradedJson()).contains("memory:error");
        assertThat(row.getMaxChars()).isEqualTo(24_000);
        assertThat(row.getAdapter()).isEqualTo("SYNC");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    private static ContextRequest request(UUID runId, int turnNumber) {
        return new ContextRequest(runId, "c", UUID.randomUUID(), ContextAdapter.SYNC, turnNumber,
                "ship it", null, Sensitivity.RESTRICTED, 24_000, null, "", List.of());
    }

    private static AssembledContext assembled(String sectionId, int estimatedTokens) {
        ContextSection section = new ContextSection(sectionId, "Policy", "be safe",
                Sensitivity.INTERNAL, List.of(), false, null);
        return new AssembledContext("## Policy\nbe safe\n\n", List.of(), estimatedTokens,
                List.of(section), List.of(), List.of());
    }
}
