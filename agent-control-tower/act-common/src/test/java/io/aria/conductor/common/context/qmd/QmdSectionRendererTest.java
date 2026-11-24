package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QmdSectionRendererTest {

    @Test
    void rendersProvenanceAndTheHighestSensitivity() {
        QmdResult result = new QmdResult(List.of(
                new QmdHit("a", "qmd://memory-verified/a.md", "body a", 0.9, Sensitivity.INTERNAL),
                new QmdHit("b", "qmd://memory-verified/b.md", "body b", 0.8, Sensitivity.CONFIDENTIAL)),
                null);

        ContextSection section = QmdSectionRenderer
                .render("memory:qmd", "Verified memory", result, 4_000)
                .orElseThrow();

        assertThat(section.content()).contains("a [qmd://memory-verified/a.md]: body a");
        assertThat(section.sourceIds()).containsExactly(
                "qmd://memory-verified/a.md", "qmd://memory-verified/b.md");
        assertThat(section.sensitivity()).isEqualTo(Sensitivity.CONFIDENTIAL);
        assertThat(section.degradedReason()).isNull();
    }

    @Test
    void emptyOrDegradedResultsRenderNothing() {
        assertThat(QmdSectionRenderer.render("memory:qmd", "Memory", QmdResult.empty(), 4_000)).isEmpty();
        assertThat(QmdSectionRenderer.render("memory:qmd", "Memory", QmdResult.degraded("x"), 4_000)).isEmpty();
    }

    @Test
    void theCapTruncatesAtALineBoundary() {
        QmdResult result = new QmdResult(List.of(
                new QmdHit("a", "f", "x".repeat(80), 0.9, Sensitivity.INTERNAL),
                new QmdHit("b", "f", "y".repeat(80), 0.9, Sensitivity.INTERNAL)),
                null);

        ContextSection section = QmdSectionRenderer
                .render("memory:qmd", "Memory", result, 120)
                .orElseThrow();

        assertThat(section.content().length()).isLessThanOrEqualTo(120);
        assertThat(section.content()).contains("a [f]");
        assertThat(section.truncated()).isTrue();
    }
}
