package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.AgentExecutionPolicy;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admission contract tests for {@link DefaultAgentExecutionPolicy} (plan section
 * 3.1): omitted values resolve to the documented defaults, explicit
 * unknown/removed/unsupported cores and modes are rejected with a precise error
 * and never substituted, and workspace selection is validated structurally
 * (worktree never falls back to Direct, Direct needs an explicitly selected
 * directory). The policy holds no credential or launch collaborator, so every
 * rejection below structurally precedes any binding or launch.
 */
class AgentExecutionPolicyTest {

    private static final String UNSUPPORTED_CORE = "Unsupported agent core: ";

    private static CoreCatalog productionCatalog() {
        return new CoreCatalog(Map.of(
                "qoder", Set.of(ExecutionMode.HOST, ExecutionMode.SANDBOX),
                "opencode", Set.of(ExecutionMode.HOST, ExecutionMode.SANDBOX)));
    }

    private static DefaultAgentExecutionPolicy policy() {
        return new DefaultAgentExecutionPolicy(productionCatalog());
    }

    @Test
    void anExplicitRemovedCoreDoesNotBecomeTheDefault() {
        var catalog = new CoreCatalog(java.util.Map.of(
                "qoder", java.util.Set.of(ExecutionMode.HOST, ExecutionMode.SANDBOX),
                "opencode", java.util.Set.of(ExecutionMode.HOST, ExecutionMode.SANDBOX)));
        var policy = new DefaultAgentExecutionPolicy(catalog);
        var request = new AgentExecutionSettings("langchain", ExecutionMode.HOST,
                WorkspaceMode.DIRECT, "C:/repo", null);
        assertThatThrownBy(() -> policy.normalize(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported agent core: langchain");
    }

    @Test
    void omittedSettingsResolveToTheDocumentedDefaults() {
        AgentExecutionPolicy policy = policy();

        var normalized = policy.normalize(new AgentExecutionSettings(null, null, null, null, null));

        assertThat(normalized.coreId()).isEqualTo("opencode");
        assertThat(normalized.executionMode()).isEqualTo(ExecutionMode.SANDBOX);
        assertThat(normalized.workspaceMode()).isNull();
        assertThat(normalized.workspacePath()).isNull();
        assertThat(normalized.workspaceBaseRef()).isNull();
    }

    @Test
    void explicitQoderHostAndOpencodeHostAreKeptVerbatim() {
        var qoderHost = new AgentExecutionSettings("qoder", ExecutionMode.HOST,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");
        var opencodeHost = new AgentExecutionSettings("opencode", ExecutionMode.HOST,
                WorkspaceMode.DIRECT, "C:/repo", null);

        assertThat(policy().normalize(qoderHost)).isEqualTo(qoderHost);
        assertThat(policy().normalize(opencodeHost)).isEqualTo(opencodeHost);
    }

    @Test
    void anExplicitlyEmptyCoreIdIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> policy().normalize(
                new AgentExecutionSettings("", ExecutionMode.HOST, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(UNSUPPORTED_CORE);
    }

    @Test
    void anUnregisteredDefaultCoreIsRejectedRatherThanSubstituted() {
        // qoder is available, yet the documented default (opencode) is absent:
        // the policy must reject instead of falling back to the other core.
        var policy = new DefaultAgentExecutionPolicy(new CoreCatalog(Map.of(
                "qoder", Set.of(ExecutionMode.HOST))));

        assertThatThrownBy(() -> policy.normalize(new AgentExecutionSettings(null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(UNSUPPORTED_CORE + "opencode");
    }

    @Test
    void aModeTheCoreDoesNotSupportIsRejectedWithTheExactPair() {
        var policy = new DefaultAgentExecutionPolicy(new CoreCatalog(Map.of(
                "qoder", Set.of(ExecutionMode.HOST),
                "opencode", Set.of(ExecutionMode.SANDBOX))));

        assertThatThrownBy(() -> policy.normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.SANDBOX, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported execution mode: qoder/SANDBOX");
        assertThatThrownBy(() -> policy.normalize(new AgentExecutionSettings(
                "opencode", ExecutionMode.HOST, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported execution mode: opencode/HOST");
    }

    @Test
    void worktreeWithoutARepositoryPathIsRejectedAndNeverFallsBackToDirect() {
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.WORKTREE, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Worktree workspace requires a repository path");
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.WORKTREE, "   ", "main")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Worktree workspace requires a repository path");
    }

    @Test
    void directWithoutAnExplicitlySelectedDirectoryIsRejected() {
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct workspace requires an explicitly selected directory");
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT, "  ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct workspace requires an explicitly selected directory");
    }

    @Test
    void aPathWithoutAWorkspaceModeDefaultsToWorktree() {
        var normalized = policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, null, "C:/projects/example", "main"));

        assertThat(normalized.workspaceMode()).isEqualTo(WorkspaceMode.WORKTREE);
        assertThat(normalized.workspacePath()).isEqualTo("C:/projects/example");
        assertThat(normalized.workspaceBaseRef()).isEqualTo("main");
    }

    @Test
    void baseRefIsOnlyAcceptedForWorktreeWorkspaces() {
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT, "C:/repo", "main")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base ref applies only to a worktree workspace");
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                null, ExecutionMode.SANDBOX, null, null, "main")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base ref requires a worktree workspace");
        assertThatThrownBy(() -> policy().normalize(new AgentExecutionSettings(
                "qoder", ExecutionMode.HOST, WorkspaceMode.WORKTREE, "C:/repo", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Worktree base ref must not be blank");
    }

    @Test
    void normalizationIsIdempotentSoAFrozenBindingIsStable() {
        var first = policy().normalize(new AgentExecutionSettings(
                null, null, null, "C:/projects/example", null));

        assertThat(policy().normalize(first)).isEqualTo(first);
    }

    @Test
    void nullSettingsAreRejected() {
        assertThatThrownBy(() -> policy().normalize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Agent execution settings are required");
    }

    @Test
    void catalogExposesImmutableCoreIdsAndModes() {
        Map<String, Set<ExecutionMode>> source = new LinkedHashMap<>();
        source.put("qoder", new LinkedHashSet<>(Set.of(ExecutionMode.HOST)));
        var catalog = new CoreCatalog(source);
        source.put("late-addition", Set.of(ExecutionMode.SANDBOX));

        assertThat(catalog.coreIds()).containsExactly("qoder");
        assertThat(catalog.modesFor("qoder")).containsExactly(ExecutionMode.HOST);
        assertThat(catalog.modesFor("opencode")).isEmpty();
        assertThat(catalog.supports("opencode", ExecutionMode.HOST)).isFalse();
        assertThat(catalog.supports(null, ExecutionMode.HOST)).isFalse();
        assertThat(catalog.supports("qoder", null)).isFalse();
        assertThatThrownBy(() -> catalog.coreIds().add("late-addition"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.modesFor("qoder").add(ExecutionMode.SANDBOX))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void catalogPreservesTheRegistrationOrderOfCoreIds() {
        // coreIds() is served as the provider inventory (and logged at cutover): the
        // order is the registration order, not the hash order Map.copyOf would give.
        Map<String, Set<ExecutionMode>> source = new LinkedHashMap<>();
        source.put("qoder", Set.of(ExecutionMode.HOST));
        source.put("opencode", Set.of(ExecutionMode.SANDBOX));

        var catalog = new CoreCatalog(source);

        assertThat(catalog.coreIds()).containsExactly("qoder", "opencode");
    }

    @Test
    void catalogRejectsNullAndBlankCoreIds() {
        assertThatThrownBy(() -> new CoreCatalog(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Core modes map is required");
        assertThatThrownBy(() -> new CoreCatalog(Map.of("", Set.of(ExecutionMode.HOST))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Core id must not be blank");

        Map<String, Set<ExecutionMode>> withNullModes = new HashMap<>();
        withNullModes.put("qoder", null);
        assertThatThrownBy(() -> new CoreCatalog(withNullModes))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Supported modes are required for core: qoder");
    }
}
