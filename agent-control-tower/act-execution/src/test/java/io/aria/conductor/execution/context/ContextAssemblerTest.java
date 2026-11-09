package io.aria.conductor.execution.context;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The assembler is the one place the prompt envelope is built (spec §8.2): it
 * orders contributors, enforces the provider ceiling, budgets history before
 * system sections, and never drops identity, policy or goal.
 *
 * <p>Budget accounting: a section costs its rendered block
 * ({@code "## <title>\n<content>\n\n"}) and a history message costs its content,
 * so the thresholds below are the characters actually sent.
 */
class ContextAssemblerTest {

    private final ProviderCeilingResolver ceilings = mock(ProviderCeilingResolver.class);
    private final ContextProperties properties = new ContextProperties();

    private ContextAssembler assembler(ContextContributor... contributors) {
        return new ContextAssembler(List.of(contributors), ceilings, properties);
    }

    private ContextRequest request(int maxChars) {
        return new ContextRequest(UUID.randomUUID(), "c", UUID.randomUUID(),
                ContextAdapter.SYNC, 1, "goal", null, Sensitivity.RESTRICTED, maxChars,
                null, null, List.of());
    }

    private static ContextSection section(String id, String title, String content) {
        return new ContextSection(id, title, content, Sensitivity.INTERNAL, List.of(), false, null);
    }

    private static FakeContributor fake(String id, int order, boolean appliesTo, boolean required,
                                        List<ContextSection> sections) {
        return new FakeContributor(id, order, appliesTo, required, sections, null);
    }

    @Test
    void ordersByContributorOrderAndSkipsNotApplicable() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "operator")));
        ContextContributor policy = fake("policy", 20, true, true,
                List.of(section("policy", "policy", "be safe")));
        ContextContributor memory = fake("memory", 40, false, false,
                List.of(section("memory", "memory", "a fact")));

        AssembledContext result = assembler(identity, policy, memory).assemble(request(1_000), List.of());

        assertThat(result.systemText().indexOf("## identity"))
                .isLessThan(result.systemText().indexOf("## policy"));
        assertThat(result.systemText()).doesNotContain("## memory");
    }

    @Test
    void dropsSectionsAboveTheProviderCeilingAndRecordsTheReason() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor knowledge = fake("knowledge", 50, true, false,
                List.of(new ContextSection("knowledge:1", "Secret", "classified",
                        Sensitivity.RESTRICTED, List.of("k1"), false, null)));

        AssembledContext result = assembler(knowledge).assemble(request(1_000), List.of());

        assertThat(result.systemText()).doesNotContain("classified");
        assertThat(result.dropped()).contains("knowledge:1:sensitivity");
    }

    @Test
    void trimsHistoryBeforeDroppingSystemSections() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        // 26 + 24 rendered chars: with the 12 characters of history below the
        // envelope is 62 characters, so exactly the oldest message must go.
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "012345678901")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "memory", "012345678901")));
        List<ContextMessage> history = List.of(
                new ContextMessage("user", "oldest", null),
                new ContextMessage("assistant", "newest", null));

        AssembledContext result = assembler(identity, memory).assemble(request(60), history);

        assertThat(result.history()).hasSize(1);
        assertThat(result.history().get(0).content()).isEqualTo("newest");
        assertThat(result.systemText()).contains("## identity");
        assertThat(result.dropped()).contains("history:dropped:0");
        assertThat(result.dropped()).doesNotContain("memory:budget");
        // Spec §6.6/§8.2 step 8: a drop reason is an id and an index, never prompt text.
        assertThat(result.dropped()).noneMatch(reason ->
                reason.contains("oldest") || reason.contains("newest"));
    }

    @Test
    void neverDropsIdentityPolicyOrGoal() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "012345678901")));
        ContextContributor policy = fake("policy", 20, true, true,
                List.of(section("policy", "policy", "012345678901")));
        ContextContributor goal = fake("goal", 30, true, true,
                List.of(section("goal", "goal", "012345678901")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "memory", "012345678901")));

        AssembledContext result = assembler(identity, policy, goal, memory).assemble(request(20), List.of());

        assertThat(result.systemText()).contains("## identity", "## policy", "## goal");
        assertThat(result.systemText()).doesNotContain("## memory");
        assertThat(result.dropped()).contains("memory:budget");
    }

    @Test
    void dropsOptionalGroupsInTheDeclaredOrderBeforeTouchingRequiredSections() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "operator")));
        ContextContributor workspace = fake("workspace", 80, true, false,
                List.of(section("workspace", "Workspace", "repo@main")));
        ContextContributor skills = fake("skills", 60, true, false,
                List.of(section("skills", "Skills", "- **tdd**: test first")));
        ContextContributor knowledge = fake("knowledge", 50, true, false,
                List.of(section("knowledge:1", "Knowledge", "a rule")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "Memory", "a fact")));

        AssembledContext result = assembler(identity, memory, knowledge, skills, workspace)
                .assemble(request(10), List.of());

        assertThat(result.dropped()).containsExactly(
                "workspace:budget", "skills:budget", "knowledge:1:budget", "memory:budget");
        assertThat(result.systemText()).contains("## identity");
    }

    @Test
    void aRequiredContributorFailureFailsTheAssembly() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = new FakeContributor("identity", 10, true, true,
                List.of(), new IllegalStateException("down"));

        assertThatThrownBy(() -> assembler(identity).assemble(request(1_000), List.of()))
                .isInstanceOf(ContextAssemblyException.class)
                .hasMessageContaining("identity");
    }

    @Test
    void aRequiredContributorThatProducesNothingFailsTheAssembly() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor goal = fake("goal", 30, true, true, List.of());

        assertThatThrownBy(() -> assembler(goal).assemble(request(1_000), List.of()))
                .isInstanceOf(ContextAssemblyException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void anOptionalContributorFailureDegradesOnlyThatSection() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor identity = fake("identity", 10, true, true,
                List.of(section("identity", "identity", "operator")));
        ContextContributor memory = new FakeContributor("memory", 40, true, false,
                List.of(), new IllegalStateException("down"));

        AssembledContext result = assembler(identity, memory).assemble(request(1_000), List.of());

        assertThat(result.systemText()).contains("## identity");
        assertThat(result.degraded()).contains("memory:error");
    }

    @Test
    void rendersHeadingPerSectionAndKeepsHistoryOutOfSystemText() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextContributor goal = fake("goal", 30, true, true,
                List.of(section("goal", "Goal", "do the thing")));

        AssembledContext result = assembler(goal).assemble(request(1_000),
                List.of(new ContextMessage("user", "earlier", null)));

        assertThat(result.systemText()).isEqualTo("## Goal\ndo the thing\n\n");
        assertThat(result.systemText()).doesNotContain("earlier");
        assertThat(result.history()).hasSize(1);
        assertThat(result.estimatedTokens()).isEqualTo((int) Math.ceil(("## Goal\ndo the thing\n\n".length() + 7) / 3.5));
    }

    @Test
    void capsASectionByItsGroup_andAdottedIdResolvesToItsGroup() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        properties.setCaps(Map.of("policy", 5, "memory", 4));
        ContextContributor policy = fake("policy", 20, true, true,
                List.of(section("policy.agent", "Policy", "012345678901")));
        ContextContributor memory = fake("memory", 40, true, false,
                List.of(section("memory", "Memory", "012345678901")));

        AssembledContext result = assembler(policy, memory).assemble(request(1_000), List.of());

        assertThat(result.sections()).extracting(ContextSection::content)
                .containsExactly("01234", "0123");
        assertThat(result.sections()).allMatch(ContextSection::truncated);
    }
}
