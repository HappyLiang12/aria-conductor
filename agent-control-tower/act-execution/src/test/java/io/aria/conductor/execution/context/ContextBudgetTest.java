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

    @Test
    void aOneCharacterOverflowNeverEmptiesASingleLineSectionThatEndsWithANewline() {
        ContextProperties properties = new ContextProperties();
        String memory = "one single fact line\n";
        List<ContextSection> sections = List.of(
                section("identity", "Identity", "operator"),
                section("memory", "Memory", memory));

        // The two blocks render 55 characters against a 54-character budget, so
        // the overflow is one character: the section survives whole or is dropped
        // as a whole, never emptied and never reduced to a bare marker.
        BudgetResult result = new ContextBudget(properties, 54)
                .apply(sections, List.of(), new ArrayList<>());

        assertThat(result.sections()).allSatisfy(kept -> assertThat(kept.content())
                .isNotBlank()
                .doesNotStartWith(ContextBudget.TRUNCATION_MARKER));
        assertThat(result.sections()).extracting(ContextSection::id).contains("identity");
        ContextSection kept = result.sections().stream()
                .filter(candidate -> candidate.id().equals("memory"))
                .findFirst()
                .orElse(null);
        if (kept == null) {
            assertThat(result.dropped()).contains("memory:budget");
        } else {
            assertThat(kept.content()).isEqualTo(memory);
        }
    }

    @Test
    void aMultiLineOptionalSectionStillScalesOnWholeLines() {
        ContextProperties properties = new ContextProperties();
        String skills = lines("a", 10);
        List<ContextSection> sections = List.of(
                section("identity", "Identity", "operator"),
                section("skills", "Skills", skills));

        // identity renders 22 characters and skills 222, so the 200-character
        // budget has to reclaim 44 from skills alone.
        BudgetResult result = new ContextBudget(properties, 200)
                .apply(sections, List.of(), new ArrayList<>());

        assertThat(result.dropped()).isEmpty();
        ContextSection scaled = result.sections().get(1);
        assertThat(scaled.truncated()).isTrue();
        List<String> body = scaled.content().lines().toList();
        assertThat(body).hasSizeGreaterThan(1);
        assertThat(body.get(body.size() - 1)).isEqualTo(ContextBudget.TRUNCATION_MARKER);
        assertThat(body.subList(0, body.size() - 1))
                .isNotEmpty()
                .allMatch(line -> line.equals("a".repeat(20)));
        assertThat(ContextBudget.renderedChars(result.sections())).isLessThanOrEqualTo(200);
    }

    @Test
    void anOverflowTooLargeToKeepAWholeLineLeavesTheSectionIntact() {
        ContextProperties properties = new ContextProperties();
        String state = lines("s", 10);
        List<ContextSection> sections = List.of(
                section("identity", "Identity", "operator"),
                section("state", "State", state));

        // The reclaim request is larger than the section: cutting it would leave
        // only the marker (and state has no drop-order slot), so it stays whole.
        BudgetResult result = new ContextBudget(properties, 40)
                .apply(sections, List.of(), new ArrayList<>());

        assertThat(result.sections()).allSatisfy(kept -> assertThat(kept.content())
                .isNotBlank()
                .doesNotStartWith(ContextBudget.TRUNCATION_MARKER));
        ContextSection kept = result.sections().get(1);
        assertThat(kept.id()).isEqualTo("state");
        assertThat(kept.content()).isEqualTo(state);
        assertThat(kept.truncated()).isFalse();
    }

    @Test
    void requiredSectionsStayByteIdenticalWhenOptionalSectionsAreScaled() {
        ContextProperties properties = new ContextProperties();
        String identity = "operator";
        String policy = "be safe";
        String goal = "0123456789";
        List<ContextSection> sections = List.of(
                section("identity", "Identity", identity),
                section("policy", "Policy", policy),
                section("goal", "Goal", goal),
                section("skills", "Skills", lines("a", 10)),
                section("memory", "Memory", lines("m", 6)));

        BudgetResult result = new ContextBudget(properties, 300)
                .apply(sections, List.of(), new ArrayList<>());

        assertThat(result.sections().subList(0, 3))
                .allSatisfy(required -> assertThat(required.truncated()).isFalse());
        assertThat(result.sections().get(0).content()).isEqualTo(identity);
        assertThat(result.sections().get(1).content()).isEqualTo(policy);
        assertThat(result.sections().get(2).content()).isEqualTo(goal);
    }

    private static String lines(String fill, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(fill.repeat(20)).append('\n');
        }
        return text.toString();
    }
}
