package io.aria.conductor.mcp;

import java.util.List;
import java.util.Set;

/**
 * Curated inventory of every tool the embedded MCP endpoint must register.
 * The endpoint integration tests assert the live tool list equals this set, so
 * adding a platform capability without a matching MCP tool fails the build
 * visibly (design §4 parity harness), and removing or renaming one does too.
 */
final class McpToolInventory {

    static final Set<String> EXPECTED_TOOL_NAMES = Set.copyOf(List.of(
            // workflows
            "list_workflow_templates", "instantiate_workflow_template", "get_workflow",
            // knowledge
            "list_knowledge", "store_knowledge", "query_knowledge", "review_knowledge", "retire_knowledge",
            // approvals
            "list_approvals", "decide_approval",
            // agents
            "list_agents", "get_agent", "create_agent", "update_agent", "retire_agent",
            // runs
            "run_agent", "list_runs", "list_running_runs", "get_run",
            "pause_run", "resume_run", "cancel_run",
            // kanban
            "create_kanban_item", "list_kanban_items", "update_kanban_item", "transition_kanban_item",
            // dashboard
            "get_dashboard_summary",
            // definition of done
            "init_dod", "submit_dod_review", "get_dod_status",
            // reports
            "generate_report", "list_reports", "amend_report",
            // housekeeping / ops
            "housekeeping_scan", "housekeeping_execute",
            // skills
            "list_skills", "get_skill", "toggle_skill",
            "list_agent_skills", "assign_skill", "unassign_skill",
            // llm providers
            "list_llm_providers", "get_llm_provider", "create_llm_provider", "update_llm_provider",
            "delete_llm_provider", "activate_llm_provider", "test_llm_provider",
            // aria assistant (notifications, scheduled jobs, conversation history)
            "list_notifications", "get_unread_notification_count",
            "mark_notification_read", "mark_all_notifications_read",
            "list_scheduled_jobs", "create_scheduled_job", "cancel_scheduled_job",
            "get_latest_conversation", "get_conversation_timeline"));

    private McpToolInventory() {
    }
}
