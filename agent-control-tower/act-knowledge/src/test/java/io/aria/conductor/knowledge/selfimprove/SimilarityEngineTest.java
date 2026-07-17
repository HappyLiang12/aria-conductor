package io.aria.conductor.knowledge.selfimprove;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarityEngineTest {

    private final SimilarityEngine engine = new SimilarityEngine();

    @Test
    void identicalTexts_similarityIsOne() {
        double s = engine.cosineSimilarity(
                "summarise the meeting notes",
                "summarise the meeting notes");
        assertThat(s).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void disjointTexts_similarityIsZero() {
        double s = engine.cosineSimilarity(
                "alpha beta gamma",
                "delta epsilon zeta");
        assertThat(s).isLessThan(0.05);
    }

    @Test
    void partiallyOverlappingTexts_inOpenZeroOneRange() {
        double s = engine.cosineSimilarity(
                "summarise meeting notes for the team",
                "summarise meeting agenda for the manager");
        assertThat(s).isGreaterThan(0.2).isLessThan(1.0);
    }

    @Test
    void emptyVsNonEmpty_returnsZero() {
        assertThat(engine.cosineSimilarity("", "anything")).isEqualTo(0.0);
        assertThat(engine.cosineSimilarity("", "")).isEqualTo(1.0);
        assertThat(engine.cosineSimilarity(null, "x")).isEqualTo(0.0);
    }

    @Test
    void findClusters_groupsCallsWithSameSignature() {
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID();
        PromptCall c1 = call(a1, "openai", "gpt-4", "summarise");
        PromptCall c2 = call(a2, "openai", "gpt-4", "summarise");
        PromptCall c3 = call(a1, "anthropic", "claude-3", "diff,patch,review");
        var clusters = engine.findSimilarClusters(List.of(c1, c2, c3), 0.85);
        // Two distinct signatures → two clusters; first has c1+c2.
        assertThat(clusters).hasSize(2);
        assertThat(clusters.stream().mapToInt(List::size).max().orElse(0)).isEqualTo(2);
    }

    @Test
    void isDuplicate_aboveThreshold_returnsTrue() {
        KnowledgeItem twin = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("invoice generator")
                .type(KnowledgeType.PROMPT)
                .description("Generates invoices for customers")
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        boolean dup = engine.isDuplicate(
                "invoice generator Generates invoices for customers",
                List.of(twin));
        assertThat(dup).isTrue();
    }

    @Test
    void isDuplicate_unrelated_returnsFalse() {
        KnowledgeItem item = KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("payroll runner")
                .type(KnowledgeType.PROMPT)
                .description("Computes monthly salary")
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1.0.0")
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
        boolean dup = engine.isDuplicate(
                "weather forecast renderer",
                List.of(item));
        assertThat(dup).isFalse();
    }

    @Test
    void buildTfIdfVector_isNotEmptyForTokenisedInput() {
        var v = engine.buildTfIdfVector("summarise meeting notes");
        assertThat(v).isNotEmpty();
    }

    private static PromptCall call(UUID agent, String provider, String model, String tools) {
        return PromptCall.builder()
                .agentId(agent)
                .runId(UUID.randomUUID())
                .provider(provider)
                .model(model)
                .outcome("success")
                .toolsUsed(tools)
                .createdAt(Instant.now())
                .build();
    }
}
