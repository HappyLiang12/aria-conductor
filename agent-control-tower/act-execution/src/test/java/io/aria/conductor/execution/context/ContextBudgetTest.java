package io.aria.conductor.execution.context;

import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The budget mechanics of spec §8.2 steps 5-6: per-section caps cut whole
 * lines (never mid-line), history is trimmed oldest-first, and the recorded
 * drop reasons name ids and indexes instead of prompt text.
 */
class ContextBudgetTest {

    private static ContextSection section(String id, String title, String content) {
        return new ContextSection(id, title, content, Sensitivity.INTERNAL, List.of(), false, null);
    }

    private static ContextSection tools(int count) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < count; i++) {
            content.append("- tool_").append(i).append(" (WORKER_READ)\n");
        }
        return section("tools", "Tools", content.toString());
    }

    private static ContextMessage message(String content) {
        return new ContextMessage("user", content, null);
    }

    @Test
    void aSectionCapNeverCutsMidLine() {        ContextProperties properties = new ContextProperties();
        properties.setCaps(Map.of("tools", 60));

        BudgetResult result = new ContextBudget(properties, 24_000)
                .apply(List.of(tools(20)), List.of(), new ArrayList<>());

        ContextSection capped = result.sections().get(0);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.content().length()).isLessThanOrEqualTo(60);
        assertThat(capped.content().lines()).endsWith("- ... (truncated)");
        List<String> lines = capped.content().lines().toList();
        assertThat(lines.subList(0, lines.size() - 1))
                .isNotEmpty()
                .allMatch(line -> line.matches("^- tool_\\d+ \\(WORKER_READ\\)$"));
    }

    @Test
    void appliesBothCapsOldestFirstAndRecordsContentFreeMarkers() {
        ContextProperties properties = new ContextProperties();
        properties.setCaps(Map.of("history", 20));
        List<ContextMessage> history = List.of(
                message("aaaaaaaaaa"), message("bbbbbbbbbb"), message("cccccccccc"));

        // The 20-character history cap drops the oldest message; the 39-character
        // global cap (20 rendered goal characters plus 20 history characters) then
        // drops the next oldest one.
        BudgetResult result = new ContextBudget(properties, 39)
                .apply(List.of(section("goal", "Goal", "0123456789")), history, new ArrayList<>());

        assertThat(result.history()).extracting(ContextMessage::content)
                .containsExactly("cccccccccc");
        assertThat(result.dropped()).containsExactly("history:dropped:0", "history:dropped:1");
    }

    @Test
    void historyThatFitsTheHistoryCapIsKeptWhole() {
        ContextProperties properties = new ContextProperties();
        properties.setCaps(Map.of("history", 30));
        List<ContextMessage> history = List.of(
                message("aaaaaaaaaa"), message("bbbbbbbbbb"), message("cccccccccc"));

        BudgetResult result = new ContextBudget(properties, 24_000)
                .apply(List.of(), history, new ArrayList<>());

        assertThat(result.history()).hasSize(3);
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    void scalesOptionalSectionsProportionallyBeforeDroppingAnySection() {
        ContextProperties properties = new ContextProperties();
        String identity = "operator";
        String policy = "be safe";
        String skills = lines("a", 10);
        String knowledge = lines("k", 6);
        List<ContextSection> sections = List.of(
                section("identity", "Identity", identity),
                section("policy", "Policy", policy),
                section("skills", "Skills", skills),
                section("knowledge:1", "Knowledge", knowledge));

        // The four blocks render 403 characters; the 300-character cap has to be
        // reclaimed from the two optional sections alone.
        BudgetResult result = new ContextBudget(properties, 300)
                .apply(sections, List.of(), new ArrayList<>());

        assertThat(result.dropped()).isEmpty();
        assertThat(result.sections()).extracting(ContextSection::id)
                .containsExactly("identity", "policy", "skills", "knowledge:1");
        assertThat(result.sections().get(0).content()).isEqualTo(identity);
        assertThat(result.sections().get(0).truncated()).isFalse();
        assertThat(result.sections().get(1).content()).isEqualTo(policy);
        assertThat(result.sections().get(1).truncated()).isFalse();
        // Both optional sections were scaled, neither was dropped.
        assertThat(result.sections().subList(2, 4)).allSatisfy(scaled -> {
            assertThat(scaled.truncated()).isTrue();
            assertThat(scaled.content()).endsWith("- ... (truncated)").isNotBlank();
        });
        assertThat(ContextBudget.renderedChars(result.sections())).isLessThanOrEqualTo(300);
    }

    @Test
    void aSingleLineOptionalSectionIsNotScaledIntoAHalfLine() {
        ContextProperties properties = new ContextProperties();
        List<ContextSection> sections = List.of(
                section("identity", "Identity", "operator"),
                section("memory", "Memory", "one single fact line"));

        BudgetResult result = new ContextBudget(properties, 10)
                .apply(sections, List.of(), new ArrayList<>());

        // There is no line boundary to shrink to, so the drop order removes the
        // whole section instead of emitting a half line.
        assertThat(result.sections()).extracting(ContextSection::id).containsExactly("identity");
        assertThat(result.dropped()).contains("memory:budget");
    }

    private static String lines(String fill, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(fill.repeat(20)).append('\n');
        }
        return text.toString();
    }
}
