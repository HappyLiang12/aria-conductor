package io.aria.conductor.mcp.tools;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C4 ruling 7: explicit, reviewed tool policy. Every MCP tool is classified by
 * hand as {@link Category#WORKER_READ}, {@link Category#WORKER_WRITE} or
 * {@link Category#OPERATOR_ONLY}; there is no naming heuristic and no implicit
 * allowance:
 *
 * <ul>
 *   <li>An unknown tool name has no policy and is denied for workers.</li>
 *   <li>{@code WORKER_*} is granted only with a concrete in-repo consumer
 *       citation (role tool templates, workflow prompts or handler aliases);
 *       those entries may declare a scope parameter.</li>
 *   <li>Everything else — including the pinned denials {@code decide_approval},
 *       every provider/credential tool, agent lifecycle, run control, delegated
 *       runs, housekeeping and skill mutation — is OPERATOR_ONLY.</li>
 *   <li>Read-only does not waive scope: a worker may only read its own run and
 *       its own agent.</li>
 * </ul>
 *
 * <p>The full table with the citation of every worker-allowed entry is the
 * review artifact in task-C4-report.md; {@code ToolPolicyRegistryTest} keeps it
 * reconciled in both directions with the live {@code @Tool} methods.
 */
@Component
public class ToolPolicyRegistry {

    /** Tool classification. Unknown names are denied for workers, never defaulted. */
    public enum Category {
        WORKER_READ,
        WORKER_WRITE,
        OPERATOR_ONLY
    }

    /** Which identity a declared scope parameter is checked against. */
    public enum ScopeKind {
        RUN,
        AGENT
    }

    /**
     * One reviewed policy row.
     *
     * @param toolName   MCP tool name
     * @param category   classification
     * @param scopeKind  scope identity kind, or null when the tool declares no scope parameter
     * @param scopeParam invocation argument name carrying the scope id, or null
     * @param citation   why this classification is justified (file:line for worker-allowed rows)
     */
    public record Policy(String toolName, Category category, ScopeKind scopeKind, String scopeParam,
                         String citation) {
    }

    private static final Map<String, Policy> POLICIES = buildPolicies();

    /** Reviewed policy for a tool name, empty when the tool is unknown. */
    public Optional<Policy> lookup(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(POLICIES.get(toolName));
    }

    /** Immutable view of the whole reviewed map (57 rows). */
    public Map<String, Policy> policies() {
        return POLICIES;
    }

    private static Map<String, Policy> buildPolicies() {
        List<Policy> rows = List.of(
                // ── worker reads: cited worker-role consumers ───────────────────
                read("list_knowledge",
                        "V34__role_defaults_and_skill_templates.sql:16 ba role default"),
                read("query_knowledge",
                        "V34__role_defaults_and_skill_templates.sql:16,29,42 all worker roles"),
                read("list_kanban_items",
                        "V34__role_defaults_and_skill_templates.sql:17,30,43 all worker roles"),
                read("get_dod_status",
                        "V34__role_defaults_and_skill_templates.sql:18,31,44 all worker roles"),
                read("list_reports",
                        "V34__role_defaults_and_skill_templates.sql:18,45 ba+qa role defaults"),
                scoped("get_run", Category.WORKER_READ, ScopeKind.RUN, "id",
                        "docs/superpowers/specs/2026-09-17-qoder-cli-agent-core-design.md:302 "
                                + "(no cross-run impersonation); no in-repo worker consumer yet — flagged in task-C4-report.md"),
                scoped("get_agent", Category.WORKER_READ, ScopeKind.AGENT, "id",
                        "docs/superpowers/specs/2026-09-17-qoder-cli-agent-core-design.md:303 "
                                + "(scope cannot increase through agent paths); no in-repo worker consumer yet — flagged in task-C4-report.md"),

                // ── worker writes: cited worker-role consumers ──────────────────
                write("store_knowledge",
                        "V34__role_defaults_and_skill_templates.sql:16 (ba create_knowledge); "
                                + "V25__seed_additional_tools_and_fixes.sql:24 alias; "
                                + "KnowledgeToolHandler.java:37 maps store_knowledge to the same create handler"),
                write("review_knowledge",
                        "V34__role_defaults_and_skill_templates.sql:44 qa role default"),
                write("create_kanban_item",
                        "V34__role_defaults_and_skill_templates.sql:17 ba role default"),
                write("update_kanban_item",
                        "V34__role_defaults_and_skill_templates.sql:17,30,43 all worker roles"),
                write("transition_kanban_item",
                        "V34__role_defaults_and_skill_templates.sql:30,43 dev+qa role defaults"),
                write("init_dod",
                        "V34__role_defaults_and_skill_templates.sql:18,31,44 all worker roles"),
                write("submit_dod_review",
                        "V34__role_defaults_and_skill_templates.sql:31,44 dev+qa role defaults; "
                                + "V45__sdd_pipeline_prompts.sql:16 qa prompt"),
                write("generate_report",
                        "V34__role_defaults_and_skill_templates.sql:18,31,45; "
                                + "V40__sdd_workflow.sql:32 qa prompt; V45__sdd_pipeline_prompts.sql:15 qa prompt"),

                // ── operator-only: pinned denials ───────────────────────────────
                deny("decide_approval",
                        "pinned: workers never call decide_approval or answer approval questions as a human "
                                + "(design §6.2 line 301)"),
                deny("create_agent", "pinned: scope cannot increase through agent lifecycle (design §6.2 line 303)"),
                deny("update_agent", "pinned: scope cannot increase through agent lifecycle (design §6.2 line 303)"),
                deny("retire_agent", "pinned: retiring an agent is a governance-relaxing agent update (design §6.2 line 302)"),
                deny("run_agent", "pinned: scope cannot increase through delegated runs (design §6.2 line 303)"),
                deny("pause_run", "pinned: run control stays operator-side (design §6.2 line 308 protects the "
                        + "REST/MCP control-plane paths; §5.4 line 272 pause/resume are host state transitions)"),
                deny("resume_run", "pinned: run control stays operator-side (design §6.2 line 308 protects the "
                        + "REST/MCP control-plane paths; §5.4 line 272 pause/resume are host state transitions)"),
                deny("cancel_run", "pinned: cancellation is the user's action, not a worker capability "
                        + "(design §5.2 line 238 'A user cancelling the run terminates execution'); control-plane "
                        + "paths stay protected (design §6.2 line 308)"),
                deny("housekeeping_scan", "pinned: maintenance scanning is operator-side (design §6.2)"),
                deny("housekeeping_execute", "pinned: destructive maintenance is operator-only (design §6.2)"),
                deny("toggle_skill", "pinned: skill mutation changes a worker's credential/permission policy "
                        + "(design §6.2 line 301)"),
                deny("assign_skill", "pinned: skill mutation changes a worker's credential/permission policy "
                        + "(design §6.2 line 301)"),
                deny("unassign_skill", "pinned: skill mutation changes a worker's credential/permission policy "
                        + "(design §6.2 line 301)"),
                deny("list_llm_providers", "pinned: credential/policy surface has no worker access (design §6.3)"),
                deny("get_llm_provider", "pinned: credential/policy surface has no worker access (design §6.3)"),
                deny("create_llm_provider", "pinned: credential/policy surface has no worker access (design §6.3)"),
                deny("update_llm_provider", "pinned: credential/policy surface has no worker access (design §6.3)"),
                deny("delete_llm_provider", "pinned: credential/policy surface has no worker access (design §6.3)"),
                deny("activate_llm_provider", "pinned: credential/policy surface has no worker access (design §6.3)"),
                deny("test_llm_provider", "pinned: credential/policy surface has no worker access (design §6.3)"),

                // ── operator-only: reviewed default (no worker consumer exists) ──
                deny("list_workflow_templates", "default: workflow catalog is operator-side; no worker consumer in V34"),
                deny("instantiate_workflow_template", "default: workflow control is a control-plane action (design §6.2)"),
                deny("get_workflow", "default: workflow control is a control-plane action (design §6.2)"),
                deny("retire_knowledge", "default: no worker role retires knowledge (V34:16-18,29-31,42-45)"),
                deny("list_approvals", "default: approval visibility is operator-side (design §6.2 line 302)"),
                deny("list_agents", "default: agent inventory is control-plane; no worker consumer in V34"),
                deny("list_runs", "default: run inventory is control-plane; workers read get_run instead"),
                deny("list_running_runs", "default: run inventory is control-plane; no worker consumer in V34"),
                deny("get_dashboard_summary", "default: dashboard roll-up is operator-facing"),
                deny("amend_report", "default: only generate_report has a worker consumer (V34:18,31,45)"),
                deny("list_skills", "default: skill catalog is operator-side; no worker consumer in V34"),
                deny("get_skill", "default: skill catalog is operator-side; no worker consumer in V34"),
                deny("list_agent_skills", "default: skill assignments are operator-side; no worker consumer in V34"),
                deny("list_notifications", "default: assistant/notification surface is operator-facing"),
                deny("get_unread_notification_count", "default: assistant/notification surface is operator-facing"),
                deny("mark_notification_read", "default: assistant/notification surface is operator-facing"),
                deny("mark_all_notifications_read", "default: assistant/notification surface is operator-facing"),
                deny("list_scheduled_jobs", "default: scheduling surface is operator-facing"),
                deny("create_scheduled_job", "default: scheduling surface is operator-facing"),
                deny("cancel_scheduled_job", "default: scheduling surface is operator-facing"),
                deny("get_latest_conversation", "default: conversation history is operator-facing"),
                deny("get_conversation_timeline", "default: conversation history is operator-facing"));

        Map<String, Policy> byName = new LinkedHashMap<>();
        for (Policy row : rows) {
            Policy previous = byName.put(row.toolName(), row);
            if (previous != null) {
                throw new IllegalStateException("duplicate tool policy: " + row.toolName());
            }
        }
        return Map.copyOf(byName);
    }

    private static Policy read(String toolName, String citation) {
        return new Policy(toolName, Category.WORKER_READ, null, null, citation);
    }

    private static Policy write(String toolName, String citation) {
        return new Policy(toolName, Category.WORKER_WRITE, null, null, citation);
    }

    private static Policy scoped(String toolName, Category category, ScopeKind scopeKind, String scopeParam,
                                 String citation) {
        return new Policy(toolName, category, scopeKind, scopeParam, citation);
    }

    private static Policy deny(String toolName, String citation) {
        return new Policy(toolName, Category.OPERATOR_ONLY, null, null, citation);
    }
}
