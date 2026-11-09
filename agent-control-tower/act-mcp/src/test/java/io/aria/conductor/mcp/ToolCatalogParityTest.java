package io.aria.conductor.mcp;

import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.catalog.ToolCatalog;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one tool catalog (spec P7): every registered MCP tool, the reviewed
 * policy, the one-line description and the auto-approve flag live in
 * {@link ToolCatalog}. The inventory, the policy registry and the
 * {@code aria.mcp.auto-approve-read-tools} default all derive from it, so an
 * added tool, a renamed tool or a swapped policy fails this test rather than
 * drifting the prompt.
 */
class ToolCatalogParityTest {

    private final ToolCatalog catalog = new ToolCatalog();

    @Test
    void inventoryIsExactlyTheCatalog() {
        assertThat(McpToolInventory.EXPECTED_TOOL_NAMES)
                .containsExactlyInAnyOrderElementsOf(catalog.names());
    }

    @Test
    void everyToolHasAReviewedPolicyAndTheAutoApproveSetIsReadOnly() {
        assertThat(catalog.tools()).allSatisfy(tool ->
                assertThat(tool.policy()).isNotEqualTo(ToolPolicy.GATED));
        assertThat(new McpProperties().getAutoApproveReadTools())
                .containsExactlyInAnyOrderElementsOf(catalog.autoApproveReadNames());
        assertThat(catalog.tools().stream()
                .filter(ToolDescriptor::autoApproveRead)
                .map(ToolDescriptor::policy))
                .allMatch(policy -> policy == ToolPolicy.WORKER_READ);
    }

    /**
     * Snapshot over every entry, not a sample: exchanging {@code WORKER_READ}
     * and {@code WORKER_WRITE} on any single tool changes this map and fails.
     */
    @Test
    void theFullNameToPolicyMapIsPinned() {
        Map<String, ToolPolicy> expected = new LinkedHashMap<>();
        // workflows
        expected.put("list_workflow_templates", ToolPolicy.WORKER_READ);
        expected.put("instantiate_workflow_template", ToolPolicy.WORKER_WRITE);
        expected.put("get_workflow", ToolPolicy.WORKER_READ);
        // knowledge
        expected.put("list_knowledge", ToolPolicy.WORKER_READ);
        expected.put("store_knowledge", ToolPolicy.WORKER_WRITE);
        expected.put("query_knowledge", ToolPolicy.WORKER_READ);
        expected.put("review_knowledge", ToolPolicy.WORKER_WRITE);
        expected.put("retire_knowledge", ToolPolicy.WORKER_WRITE);
        // approvals
        expected.put("list_approvals", ToolPolicy.WORKER_READ);
        expected.put("decide_approval", ToolPolicy.OPERATOR_ONLY);
        // agents
        expected.put("list_agents", ToolPolicy.WORKER_READ);
        expected.put("get_agent", ToolPolicy.WORKER_READ);
        expected.put("create_agent", ToolPolicy.WORKER_WRITE);
        expected.put("update_agent", ToolPolicy.WORKER_WRITE);
        expected.put("retire_agent", ToolPolicy.OPERATOR_ONLY);
        // runs
        expected.put("run_agent", ToolPolicy.WORKER_WRITE);
        expected.put("list_runs", ToolPolicy.WORKER_READ);
        expected.put("list_running_runs", ToolPolicy.WORKER_READ);
        expected.put("get_run", ToolPolicy.WORKER_READ);
        expected.put("pause_run", ToolPolicy.WORKER_WRITE);
        expected.put("resume_run", ToolPolicy.WORKER_WRITE);
        expected.put("cancel_run", ToolPolicy.WORKER_WRITE);
        // kanban
        expected.put("create_kanban_item", ToolPolicy.WORKER_WRITE);
        expected.put("list_kanban_items", ToolPolicy.WORKER_READ);
        expected.put("update_kanban_item", ToolPolicy.WORKER_WRITE);
        expected.put("transition_kanban_item", ToolPolicy.WORKER_WRITE);
        // dashboard
        expected.put("get_dashboard_summary", ToolPolicy.WORKER_READ);
        // definition of done
        expected.put("init_dod", ToolPolicy.WORKER_WRITE);
        expected.put("submit_dod_review", ToolPolicy.WORKER_WRITE);
        expected.put("get_dod_status", ToolPolicy.WORKER_READ);
        // reports
        expected.put("generate_report", ToolPolicy.WORKER_WRITE);
        expected.put("list_reports", ToolPolicy.WORKER_READ);
        expected.put("amend_report", ToolPolicy.WORKER_WRITE);
        // housekeeping / ops
        expected.put("housekeeping_scan", ToolPolicy.WORKER_READ);
        expected.put("housekeeping_execute", ToolPolicy.OPERATOR_ONLY);
        // skills
        expected.put("list_skills", ToolPolicy.WORKER_READ);
        expected.put("get_skill", ToolPolicy.WORKER_READ);
        expected.put("toggle_skill", ToolPolicy.WORKER_WRITE);
        expected.put("list_agent_skills", ToolPolicy.WORKER_READ);
        expected.put("assign_skill", ToolPolicy.WORKER_WRITE);
        expected.put("unassign_skill", ToolPolicy.WORKER_WRITE);
        // llm providers (credential / core-selection management)
        expected.put("list_llm_providers", ToolPolicy.WORKER_READ);
        expected.put("get_llm_provider", ToolPolicy.WORKER_READ);
        expected.put("create_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("update_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("delete_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("activate_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("test_llm_provider", ToolPolicy.OPERATOR_ONLY);
        // aria assistant surfaces
        expected.put("list_notifications", ToolPolicy.WORKER_READ);
        expected.put("get_unread_notification_count", ToolPolicy.WORKER_READ);
        expected.put("mark_notification_read", ToolPolicy.WORKER_WRITE);
        expected.put("mark_all_notifications_read", ToolPolicy.WORKER_WRITE);
        expected.put("list_scheduled_jobs", ToolPolicy.WORKER_READ);
        expected.put("create_scheduled_job", ToolPolicy.WORKER_WRITE);
        expected.put("cancel_scheduled_job", ToolPolicy.WORKER_WRITE);
        expected.put("get_latest_conversation", ToolPolicy.WORKER_READ);
        expected.put("get_conversation_timeline", ToolPolicy.WORKER_READ);
        // the cores' own read-only web tools, listed explicitly for
        // auto-approval provenance by the 2026-10-04 operator decision (D5)
        expected.put("WebSearch", ToolPolicy.WORKER_READ);
        expected.put("WebFetch", ToolPolicy.WORKER_READ);

        Map<String, ToolPolicy> actual = catalog.tools().stream()
                .collect(Collectors.toMap(ToolDescriptor::name, ToolDescriptor::policy));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** Every catalog entry flagged for auto-approval is a read-only tool. */
    @Test
    void autoApproveReadImpliesWorkerRead() {
        assertThat(catalog.tools())
                .filteredOn(ToolDescriptor::autoApproveRead)
                .isNotEmpty()
                .allSatisfy(tool -> assertThat(tool.policy()).isEqualTo(ToolPolicy.WORKER_READ));
        assertThat(catalog.autoApproveReadNames())
                .containsExactlyInAnyOrderElementsOf(catalog.tools().stream()
                        .filter(ToolDescriptor::autoApproveRead)
                        .map(ToolDescriptor::name)
                        .toList());
    }

    /**
     * The configured default names only tools that exist in the catalog with
     * the reviewed read policy: a name that is not a WORKER_READ tool (a
     * mutating, operator-only or unclassified name) fails here.
     */
    @Test
    void everyConfiguredAutoApproveNameIsACatalogWorkerReadTool() {
        for (String name : new McpProperties().getAutoApproveReadTools()) {
            ToolDescriptor tool = catalog.find(name)
                    .orElseThrow(() -> new AssertionError(
                            "auto-approve entry '" + name + "' is not a catalog tool"));
            assertThat(tool.policy())
                    .as("auto-approve entry '%s'", name)
                    .isEqualTo(ToolPolicy.WORKER_READ);
        }
    }

    @Test
    void everyDescriptorCarriesItsOneLineToolDescription() {
        assertThat(catalog.tools()).allSatisfy(tool -> {
            assertThat(tool.description()).as("description of '%s'", tool.name()).isNotBlank();
            assertThat(tool.description()).as("description of '%s'", tool.name()).doesNotContain("\n");
        });
    }

    /**
     * The catalog covers the registered MCP surface plus the cores' own
     * read-only web tools; only the registered names are the inventory.
     */
    @Test
    void onlyTheNativeReadOnlyToolsAreOutsideTheRegisteredNames() {
        assertThat(catalog.tools().stream()
                .map(ToolDescriptor::name)
                .filter(name -> !catalog.names().contains(name)))
                .containsExactlyInAnyOrder("WebSearch", "WebFetch");
        assertThat(catalog.names()).hasSize(McpToolInventory.EXPECTED_TOOL_NAMES.size());
    }
}
