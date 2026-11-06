package io.aria.conductor.execution.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform-side read-only auto-approval policy (operator decision
 * 2026-09-29). The default list is pinned verbatim: adding a mutating or
 * operator-only tool to it is a governance change, not a rename.
 */
class PlatformMcpAutoApprovalTest {

    /** The reviewed read-only default of aria.mcp.auto-approve-read-tools. */
    private static final List<String> DEFAULT_READ_TOOLS = List.of(
            "list_agents", "get_agent", "list_approvals", "list_notifications",
            "get_unread_notification_count", "list_scheduled_jobs", "get_latest_conversation",
            "get_conversation_timeline", "get_dashboard_summary", "get_dod_status",
            "list_kanban_items", "list_knowledge", "query_knowledge", "list_llm_providers",
            "get_llm_provider", "list_reports", "list_runs", "list_running_runs", "get_run",
            "list_skills", "get_skill", "list_agent_skills", "list_workflow_templates",
            "get_workflow", "WebSearch", "WebFetch");

    /**
     * Every mutating / operator-only / gated tool the operator's decision lists
     * as never-in-the-default; housekeeping_scan stays gated on purpose even
     * though the MCP tool registry classifies it read-only.
     */
    private static final List<String> NEVER_IN_THE_DEFAULT = List.of(
            "decide_approval", "run_agent", "create_agent", "update_agent", "retire_agent",
            "create_kanban_item", "update_kanban_item", "transition_kanban_item",
            "store_knowledge", "review_knowledge", "retire_knowledge", "generate_report",
            "amend_report", "test_llm_provider", "create_llm_provider", "update_llm_provider",
            "delete_llm_provider", "activate_llm_provider", "pause_run", "resume_run",
            "cancel_run", "housekeeping_scan", "housekeeping_execute", "init_dod",
            "submit_dod_review", "instantiate_workflow_template", "toggle_skill", "assign_skill",
            "unassign_skill", "mark_notification_read", "mark_all_notifications_read",
            "create_scheduled_job", "cancel_scheduled_job");

    private static PlatformMcpAutoApproval policy(McpProperties mcp) {
        return new PlatformMcpAutoApproval(mcp);
    }

    @Test
    void defaultAllowlistIsExactlyTheReviewedReadOnlyTools() {
        McpProperties props = new McpProperties();
        assertThat(props.getAutoApproveReadTools()).containsExactlyElementsOf(DEFAULT_READ_TOOLS);
        assertThat(policy(props).allows("list_agents")).isTrue();
    }

    /**
     * The 2026-10-04 operator decision (D5): the default list also names the
     * cores' own read-only web tools, which carry no platform-MCP prefix — the
     * explicit listing is their provenance gate. {@code run_agent} stays off
     * the list and keeps the per-call operator approval.
     */
    @Test
    void theDefaultListAlsoNamesTheCoresOwnReadOnlyWebTools() {
        McpProperties props = new McpProperties();

        assertThat(props.getAutoApproveReadTools()).contains("WebSearch", "WebFetch");
        assertThat(props.getAutoApproveReadTools()).doesNotContain("run_agent");
        assertThat(policy(props).allows("WebSearch")).isTrue();
        assertThat(policy(props).allows("WebFetch")).isTrue();
    }

    @Test
    void theDefaultPolicyNeverAllowsAMutatingOrOperatorOnlyTool() {
        PlatformMcpAutoApproval policy = policy(new McpProperties());

        assertThat(NEVER_IN_THE_DEFAULT).allMatch(name -> !policy.allows(name));
        // The prefixed shape a core advertises is normalized the same way.
        assertThat(NEVER_IN_THE_DEFAULT)
                .allMatch(name -> !policy.allows("mcp__aria-conductor__" + name));
    }

    @Test
    void bareAndPrefixedReadOnlyNamesAreAllowed() {
        PlatformMcpAutoApproval policy = policy(new McpProperties());

        assertThat(policy.allows("list_agents")).isTrue();
        assertThat(policy.allows("mcp__aria-conductor__list_agents")).isTrue();
        assertThat(policy.allows("mcp__aria-conductor__get_run")).isTrue();
    }

    @Test
    void namesAreMatchedCaseInsensitively() {
        PlatformMcpAutoApproval policy = policy(new McpProperties());

        assertThat(policy.allows("List_Agents")).isTrue();
        assertThat(policy.allows("MCP__ARIA-CONDUCTOR__LIST_AGENTS")).isTrue();
    }

    /**
     * The native-ask trigger's provenance gate: only the exact namespace the
     * platform itself wires counts as a platform MCP call. The tool segment is
     * what the allowlist matches (case-insensitively); the namespace itself is
     * not matched loosely, so a look-alike name keeps the operator flow.
     */
    @Test
    void onlyThePlatformWiredNamespaceCarriesThePlatformMcpPrefix() {
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("mcp__aria-conductor__list_agents")).isTrue();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("mcp__aria-conductor__get_run")).isTrue();

        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("mcp__aria-conductor__")).isFalse();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("mcp__aria-conductor")).isFalse();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("mcp__aria-stub__aria_ping")).isFalse();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("list_agents")).isFalse();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("WebSearch")).isFalse();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix("MCP__ARIA-CONDUCTOR__LIST_AGENTS")).isFalse();
        assertThat(PlatformMcpAutoApproval.carriesPlatformMcpPrefix(null)).isFalse();
    }

    @Test
    void anEmptyConfiguredListDisablesThePolicy() {
        McpProperties props = new McpProperties();
        props.setAutoApproveReadTools(List.of());
        PlatformMcpAutoApproval policy = policy(props);

        assertThat(policy.allows("list_agents")).isFalse();
        assertThat(policy.allows("mcp__aria-conductor__list_agents")).isFalse();
        assertThat(policy.allows("get_run")).isFalse();
    }

    @Test
    void unknownNamesAreRefused() {
        PlatformMcpAutoApproval policy = policy(new McpProperties());

        assertThat(policy.allows("list_unicorns")).isFalse();
        assertThat(policy.allows("mcp__aria-conductor__list_unicorns")).isFalse();
        assertThat(policy.allows("mcp__aria-conductor")).isFalse();
        assertThat(policy.allows(null)).isFalse();
        assertThat(policy.allows("")).isFalse();
    }

    @Test
    void aConfiguredListReplacesTheDefault() {
        McpProperties props = new McpProperties();
        props.setAutoApproveReadTools(List.of("get_run"));
        PlatformMcpAutoApproval policy = policy(props);

        assertThat(policy.allows("get_run")).isTrue();
        assertThat(policy.allows("list_agents")).isFalse();
        assertThat(policy.allows("mcp__aria-conductor__store_knowledge")).isFalse();
    }
}
