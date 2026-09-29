package io.aria.conductor.common.runtime;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot/default contract for the execution settings ledger (cross-core
 * Host/Sandbox plan, section 2.1). Values are carried verbatim -- normalization
 * is the {@link AgentExecutionPolicy} port's job, not this record's -- and
 * legacy agents keep explicitly unknown (null) execution metadata instead of an
 * inferred default.
 */
class ExecutionSettingsTest {

    @Test
    void explicitHostSettingsRemainDistinctFromSandbox() {
        var settings = new AgentExecutionSettings("qoder", ExecutionMode.HOST,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");
        assertThat(settings.coreId()).isEqualTo("qoder");
        assertThat(settings.executionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(settings.workspaceMode()).isEqualTo(WorkspaceMode.WORKTREE);
        assertThat(settings.workspaceBaseRef()).isEqualTo("main");
    }

    @Test
    void settingsSnapshotsCompareByValue() {
        var original = new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");
        var copy = new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");
        var differentMode = new AgentExecutionSettings("opencode", ExecutionMode.HOST,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");

        assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original);
        assertThat(differentMode).isNotEqualTo(original);
    }

    @Test
    void legacyUnknownSettingsStayNullInsteadOfBeingInferred() {
        var unknown = new AgentExecutionSettings(null, null, null, null, null);

        assertThat(unknown.coreId()).isNull();
        assertThat(unknown.executionMode()).isNull();
        assertThat(unknown.workspaceMode()).isNull();
        assertThat(unknown.workspacePath()).isNull();
        assertThat(unknown.workspaceBaseRef()).isNull();
    }

    @Test
    void agentExecutionMetadataIsExposedAsNullableColumns() {
        Agent legacy = Agent.builder().name("legacy").agentType(AgentType.NATIVE).build();
        assertThat(legacy.getExecutionMode()).isNull();
        assertThat(legacy.getWorkspaceMode()).isNull();
        assertThat(legacy.getWorkspacePath()).isNull();
        assertThat(legacy.getWorkspaceBaseRef()).isNull();

        Agent host = Agent.builder().name("host").agentType(AgentType.NATIVE)
                .executionMode(ExecutionMode.HOST)
                .workspaceMode(WorkspaceMode.DIRECT)
                .workspacePath("C:/projects/example")
                .workspaceBaseRef("main")
                .build();
        assertThat(host.getExecutionMode()).isEqualTo(ExecutionMode.HOST);
        assertThat(host.getWorkspaceMode()).isEqualTo(WorkspaceMode.DIRECT);
        assertThat(host.getWorkspacePath()).isEqualTo("C:/projects/example");
        assertThat(host.getWorkspaceBaseRef()).isEqualTo("main");
    }

    @Test
    void declaredModesAndWorkspaceKindsAreExactlyTheLedgerValues() {
        assertThat(ExecutionMode.values())
                .containsExactly(ExecutionMode.HOST, ExecutionMode.SANDBOX);
        assertThat(WorkspaceMode.values())
                .containsExactly(WorkspaceMode.WORKTREE, WorkspaceMode.DIRECT);
        assertThat(WorkspaceKind.values())
                .containsExactly(WorkspaceKind.SCRATCH, WorkspaceKind.WORKTREE,
                        WorkspaceKind.DIRECT, WorkspaceKind.SANDBOX_SNAPSHOT);
    }

    @Test
    void policyPortNormalizesThroughTheCommonInterface() {
        AgentExecutionPolicy passthrough = requested -> requested;
        var requested = new AgentExecutionSettings("qoder", ExecutionMode.HOST, null, null, null);

        assertThat(passthrough.normalize(requested)).isSameAs(requested);
    }
}
