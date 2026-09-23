package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.security.ActorPrincipal;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * The reviewed tool policy of the platform MCP surface (spec §6, "explicit
 * policy, not naming heuristics"). Every tool the embedded MCP endpoint
 * registers is classified explicitly here; names starting with {@code list_},
 * {@code get_} or {@code query_} are evidence of nothing, and an unclassified
 * tool is GATED — never automatically allowed.
 *
 * <ul>
 *   <li>{@code WORKER_READ} — no mutation; the only class an execution boundary
 *       may allow automatically.</li>
 *   <li>{@code WORKER_WRITE} — a side-effecting call that requires a run-bound
 *       approve-once/deny decision and a consumed one-use grant at the
 *       execution boundary ({@code WriteGrantService.consume}).</li>
 *   <li>{@code OPERATOR_ONLY} — approval decisions, retirement execution,
 *       destructive batch maintenance and credential/provider management; a
 *       run-scoped worker credential is never sufficient.</li>
 *   <li>{@code GATED} — unclassified: the answer for every tool name that is not
 *       in the reviewed map, including names the registry has never seen.</li>
 * </ul>
 */
@Component
public class ToolPolicyRegistry {

    /** What authority a tool call requires. */
    public enum ToolPolicy {
        WORKER_READ,
        WORKER_WRITE,
        OPERATOR_ONLY,
        GATED
    }

    private static final Map<String, ToolPolicy> POLICIES = Map.ofEntries(
            // workflows
            Map.entry("list_workflow_templates", ToolPolicy.WORKER_READ),
            Map.entry("instantiate_workflow_template", ToolPolicy.WORKER_WRITE),
            Map.entry("get_workflow", ToolPolicy.WORKER_READ),
            // knowledge
            Map.entry("list_knowledge", ToolPolicy.WORKER_READ),
            Map.entry("store_knowledge", ToolPolicy.WORKER_WRITE),
            Map.entry("query_knowledge", ToolPolicy.WORKER_READ),
            Map.entry("review_knowledge", ToolPolicy.WORKER_WRITE),
            Map.entry("retire_knowledge", ToolPolicy.WORKER_WRITE),
            // approvals
            Map.entry("list_approvals", ToolPolicy.WORKER_READ),
            Map.entry("decide_approval", ToolPolicy.OPERATOR_ONLY),
            // agents
            Map.entry("list_agents", ToolPolicy.WORKER_READ),
            Map.entry("get_agent", ToolPolicy.WORKER_READ),
            Map.entry("create_agent", ToolPolicy.WORKER_WRITE),
            Map.entry("update_agent", ToolPolicy.WORKER_WRITE),
            Map.entry("retire_agent", ToolPolicy.OPERATOR_ONLY),
            // runs
            Map.entry("run_agent", ToolPolicy.WORKER_WRITE),
            Map.entry("list_runs", ToolPolicy.WORKER_READ),
            Map.entry("list_running_runs", ToolPolicy.WORKER_READ),
            Map.entry("get_run", ToolPolicy.WORKER_READ),
            Map.entry("pause_run", ToolPolicy.WORKER_WRITE),
            Map.entry("resume_run", ToolPolicy.WORKER_WRITE),
            Map.entry("cancel_run", ToolPolicy.WORKER_WRITE),
            // kanban
            Map.entry("create_kanban_item", ToolPolicy.WORKER_WRITE),
            Map.entry("list_kanban_items", ToolPolicy.WORKER_READ),
            Map.entry("update_kanban_item", ToolPolicy.WORKER_WRITE),
            Map.entry("transition_kanban_item", ToolPolicy.WORKER_WRITE),
            // dashboard
            Map.entry("get_dashboard_summary", ToolPolicy.WORKER_READ),
            // definition of done
            Map.entry("init_dod", ToolPolicy.WORKER_WRITE),
            Map.entry("submit_dod_review", ToolPolicy.WORKER_WRITE),
            Map.entry("get_dod_status", ToolPolicy.WORKER_READ),
            // reports
            Map.entry("generate_report", ToolPolicy.WORKER_WRITE),
            Map.entry("list_reports", ToolPolicy.WORKER_READ),
            Map.entry("amend_report", ToolPolicy.WORKER_WRITE),
            // housekeeping / ops
            Map.entry("housekeeping_scan", ToolPolicy.WORKER_READ),
            Map.entry("housekeeping_execute", ToolPolicy.OPERATOR_ONLY),
            // skills
            Map.entry("list_skills", ToolPolicy.WORKER_READ),
            Map.entry("get_skill", ToolPolicy.WORKER_READ),
            Map.entry("toggle_skill", ToolPolicy.WORKER_WRITE),
            Map.entry("list_agent_skills", ToolPolicy.WORKER_READ),
            Map.entry("assign_skill", ToolPolicy.WORKER_WRITE),
            Map.entry("unassign_skill", ToolPolicy.WORKER_WRITE),
            // llm providers (credential / core-selection management)
            Map.entry("list_llm_providers", ToolPolicy.WORKER_READ),
            Map.entry("get_llm_provider", ToolPolicy.WORKER_READ),
            Map.entry("create_llm_provider", ToolPolicy.OPERATOR_ONLY),
            Map.entry("update_llm_provider", ToolPolicy.OPERATOR_ONLY),
            Map.entry("delete_llm_provider", ToolPolicy.OPERATOR_ONLY),
            Map.entry("activate_llm_provider", ToolPolicy.OPERATOR_ONLY),
            Map.entry("test_llm_provider", ToolPolicy.OPERATOR_ONLY),
            // aria assistant surfaces
            Map.entry("list_notifications", ToolPolicy.WORKER_READ),
            Map.entry("get_unread_notification_count", ToolPolicy.WORKER_READ),
            Map.entry("mark_notification_read", ToolPolicy.WORKER_WRITE),
            Map.entry("mark_all_notifications_read", ToolPolicy.WORKER_WRITE),
            Map.entry("list_scheduled_jobs", ToolPolicy.WORKER_READ),
            Map.entry("create_scheduled_job", ToolPolicy.WORKER_WRITE),
            Map.entry("cancel_scheduled_job", ToolPolicy.WORKER_WRITE),
            Map.entry("get_latest_conversation", ToolPolicy.WORKER_READ),
            Map.entry("get_conversation_timeline", ToolPolicy.WORKER_READ));

    /** The reviewed classification; an unknown name is GATED, never guessed. */
    public ToolPolicy policyOf(String toolName) {
        if (toolName == null) {
            return ToolPolicy.GATED;
        }
        return POLICIES.getOrDefault(toolName, ToolPolicy.GATED);
    }

    /** True only for an explicitly classified read-only tool. */
    public boolean isAutomaticallyAllowed(String toolName) {
        return policyOf(toolName) == ToolPolicy.WORKER_READ;
    }

    /** True only for an explicitly classified operator-only tool. */
    public boolean requiresOperator(String toolName) {
        return policyOf(toolName) == ToolPolicy.OPERATOR_ONLY;
    }

    /**
     * Enforces the tool's reviewed policy against the transport-resolved actor.
     *
     * @throws SecurityException when the actor is not the operator for an
     *                           operator-only tool, and for every unclassified
     *                           (GATED) tool name
     */
    public void requireAuthority(String toolName, ActorPrincipal actor) {
        if (actor == null) {
            throw new SecurityException("Missing actor for tool '" + toolName + "'");
        }
        switch (policyOf(toolName)) {
            case OPERATOR_ONLY -> actor.requireOperator();
            case WORKER_READ, WORKER_WRITE -> {
                // Worker authority is transport-enforced; a write additionally
                // consumes a one-use grant at the execution boundary.
            }
            case GATED -> throw new SecurityException("Tool '" + toolName
                    + "' is not classified by the reviewed tool policy; unknown tools stay gated");
        }
    }

    /** The exact set of explicitly classified tool names (coverage guard for tests). */
    public Set<String> classifiedToolNames() {
        return POLICIES.keySet();
    }
}
