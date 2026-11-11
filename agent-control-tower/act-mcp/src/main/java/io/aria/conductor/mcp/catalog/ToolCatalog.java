package io.aria.conductor.mcp.catalog;

import io.aria.conductor.common.tool.ToolCatalogProvider;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one reviewed tool catalog of the platform MCP surface (spec P7, §9).
 *
 * <p>Every registered MCP tool carries its reviewed {@link ToolPolicy}, its
 * one-line {@code @Tool} description and the auto-approve flag that the
 * {@code aria.mcp.auto-approve-read-tools} default names. The policy registry,
 * the inventory parity test and the generated prompt tool section read this
 * catalog, so adding a tool is one implementation bean plus one catalog entry.
 *
 * <p>The catalog also lists the cores' own read-only web tools
 * ({@code WebSearch}/{@code WebFetch}). They are not registered by this
 * server's MCP endpoint — {@link #names()} is the registered surface and is
 * what the inventory equals — but the 2026-10-04 operator decision (D5) names
 * them explicitly for auto-approval provenance, and the catalog is where that
 * reviewed read-only classification lives.
 */
@Component
public class ToolCatalog implements ToolCatalogProvider {

    /** Core-native read-only tools the auto-approve default lists (D5); not MCP-registered here. */
    private static final Set<String> NATIVE_READ_ONLY_TOOL_NAMES = Set.of("WebSearch", "WebFetch");

    private static final List<ToolDescriptor> TOOLS = List.of(
            // workflows
            read("list_workflow_templates",
                    "List APPROVED workflow templates. Optional intent keywords filter by name/description; blank lists all.",
                    true),
            write("instantiate_workflow_template",
                    "Instantiate an APPROVED workflow template into a runnable chain. Returns the chain JSON including id. Use list_workflow_templates first to obtain templateId."),
            read("get_workflow",
                    "Get a workflow chain by id: status (PENDING/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELLED), steps with runIds and output previews.",
                    true),
            // knowledge
            read("list_knowledge",
                    "List knowledge items. Optional type (SKILL/SCRIPT/PROMPT/TOOL/TEMPLATE/GUIDELINE/WORKFLOW/SPEC) and status (DRAFT/PENDING/APPROVED/REJECTED/RETIRED).",
                    true),
            write("store_knowledge",
                    "Submit a new knowledge item for human review. name and content are required; type defaults to SKILL (SKILL/SCRIPT/PROMPT/TOOL/TEMPLATE/GUIDELINE/WORKFLOW/SPEC). The item is stored as PENDING and is NOT usable by agents until a human approves it via review_knowledge."),
            read("query_knowledge",
                    "Agent-facing knowledge search: returns up to 20 APPROVED, non-retired items whose name, description or current version content contains the query (case-insensitive). A blank query returns the approved items unranked.",
                    true),
            write("review_knowledge",
                    "Approve or reject a PENDING knowledge item. decision is APPROVED or REJECTED; reason is optional and recorded for rejections. Only APPROVED items become usable by agents."),
            write("retire_knowledge",
                    "Retire an APPROVED knowledge item so agents can no longer use it. Only APPROVED items can be retired."),
            // memory (spec §9): a worker write is a PENDING proposal, an operator write is ACTIVE.
            // Decision (2026-11 review): every memory READ descriptor below stays
            // autoApproveRead=false even though its policy is WORKER_READ. Memory carries
            // learned operator/user facts rather than reference material, so the reviewed
            // default keeps an operator approval in front of every memory read; the flag is
            // deliberate, not an omission, and ToolCatalogParityTest pins it.
            write("remember_fact",
                    "Record one fact about a memory entity. A worker call is recorded as a PENDING extraction proposal that an operator must approve; an operator call writes ACTIVE memory. Returns the fact with id, status, origin, sensitivity and validity."),
            read("recall_memory",
                    "Recall ranked memory for a piece of text: matched entities and their current ACTIVE facts, filtered by the fail-closed INTERNAL sensitivity ceiling, each carrying id, origin, sensitivity and validity. Deterministic for the same state and query; never returns PENDING proposals.",
                    false),
            read("list_memory_entities",
                    "List memory entities at or below the INTERNAL sensitivity ceiling. Optional kind (e.g. PERSON, REPO, AGENT) and status (ACTIVE/MERGED/RETIRED; default ACTIVE); limit defaults to 50 and is capped at 200.",
                    false),
            read("get_memory_entity",
                    "Get one memory entity with its current ACTIVE facts at or below the INTERNAL sensitivity ceiling: kind, canonical key, status, origin, sensitivity, version, and each fact's id, origin, sensitivity and validity.",
                    false),
            operatorOnly("forget_fact",
                    "Retire one fact (valid_to=now) and record the retire assertion. Operator authority only: a run-scoped worker credential is refused with FORBIDDEN and the fact stays current."),
            read("list_memory_proposals",
                    "List memory proposals awaiting review at or below the INTERNAL sensitivity ceiling: PENDING facts and relations newest first, each with subjectType, id, origin, sensitivity and creation time. status defaults to PENDING.",
                    false),
            operatorOnly("decide_memory_proposal",
                    "Approve or reject a PENDING memory proposal. Operator authority only: the transport-resolved caller must be the authenticated OPERATOR — a run-scoped worker credential is refused with FORBIDDEN and no proposal changes state. Approval promotes the subject to ACTIVE and retires a conflicting current value."),
            read("get_context_manifest",
                    "Read every context assembly recorded for a run, in turn order: turnNumber, adapter, section ids with source ids and estimates, dropped sections with reasons, and degraded sections with reasons. Metadata only — prompt content is never stored or returned.",
                    false),
            // approvals
            read("list_approvals",
                    "List approval gates. Optional status (PENDING/APPROVED/DENIED/EXPIRED). SPEC_REVIEW approvals carry markdown content and knowledgeItemId; toolCallId is null for them.",
                    true),
            operatorOnly("decide_approval",
                    "Decide a PENDING approval gate (approve or deny). Operator authority only: the transport-resolved caller must be the authenticated OPERATOR — a run-scoped worker credential is refused with FORBIDDEN and no approval changes state."),
            // agents
            read("list_agents",
                    "List active agents (retired agents excluded). Use get_agent for full details of one agent.",
                    true),
            read("get_agent",
                    "Get an agent by id: name, type, role, model, provider, healthStatus and skills.",
                    true),
            write("create_agent",
                    "Create an agent on a governed core (qoder or opencode; opencode by default)."),
            write("update_agent",
                    "Update an agent. Only the fields you supply are applied; omitted fields keep their current value."),
            operatorOnly("retire_agent",
                    "Retire an agent (soft delete). Retired agents are excluded from list_agents and cannot start new runs."),
            // runs
            write("run_agent",
                    "Start a run for an agent with the given prompt. Returns the created run including its id. maxIterations is optional; omit it to use the service default."),
            read("list_runs",
                    "List runs. Optional agentId (UUID) filters by agent and optional status (PENDING/INITIALIZING/RUNNING/PAUSED/WAITING_INPUT/COMPLETED/FAILED/CANCELLED/ABORTED) filters by status; blank means no filter.",
                    true),
            read("list_running_runs",
                    "List runs that are still active: RUNNING, PENDING, INITIALIZING, PAUSED or WAITING_INPUT.",
                    true),
            read("get_run",
                    "Get a run by id: status, agentId, iteration count, tokens used, error message and final output.",
                    true),
            write("pause_run",
                    "Pause a running run. Only RUNNING runs can be paused."),
            write("resume_run",
                    "Resume a paused run, optionally replacing its prompt seed with instruction. Refused while a pending human approval gate exists — use decide_approval first."),
            write("cancel_run",
                    "Cancel a run. Terminal runs (COMPLETED/FAILED/CANCELLED) cannot be cancelled."),
            // kanban
            write("create_kanban_item",
                    "Create a kanban board item. Title is required; priority defaults to MEDIUM and status defaults to TODO. Returns the created item with id, status and priority."),
            read("list_kanban_items",
                    "List kanban board items, optionally filtered by status. Each item includes id, title, status, priority, assignee and pendingAskCount (HITL asks waiting on Review cards).",
                    true),
            write("update_kanban_item",
                    "Update an existing kanban item's title, description, priority, assignee or labels. Blank fields are left unchanged; status changes must go through transition_kanban_item."),
            write("transition_kanban_item",
                    "Move a kanban item to a new status. WARNING: this has side effects — it can spawn an agent run (TODO/IN_PROGRESS), resume or pause a linked run (REVIEW/BACKLOG/TODO), create HITL review asks (REVIEW) or cancel a run (CANCELLED). Repeating the current status is a no-op. A refused move answers an error envelope carrying the rejection code in errorType (NO_ELIGIBLE_AGENT, AGENT_NOT_ELIGIBLE, RUN_ALREADY_FINISHED, RUN_NOT_FOUND, CORRUPT_RUN_LINK, LINKED_RUN_ACTIVE) and the reason in message: a tool error here is a governance refusal, not a transport failure."),
            // dashboard
            read("get_dashboard_summary",
                    "Get the dashboard summary: active/healthy/degraded agents, running runs, pending approvals and total tokens burned. Same payload as GET /api/v1/dashboard/summary.",
                    true),
            // definition of done
            write("init_dod",
                    "Initialize the Definition-of-Done stage gate for a task (idempotent — returns the existing record when one exists). Optional taskType is recorded for reporting."),
            write("submit_dod_review",
                    "Submit a Definition-of-Done stage review for the task's current stage. passed=true advances the stage; passed=false on a required stage (dev/qa) blocks advancement and on an optional stage skips it."),
            read("get_dod_status",
                    "Get the Definition-of-Done status for a task: current stage, overall status, per-stage roll-up (PENDING/PASSED/FAILED/SKIPPED) and evidence count.",
                    true),
            // reports
            write("generate_report",
                    "Generate an HTML report from a title and description. Generation is LLM-backed and can take a while; the returned artifact carries id, version and status."),
            read("list_reports",
                    "List report artifacts, newest first. Each entry has id, title, version, status and owner.",
                    true),
            write("amend_report",
                    "Amend an existing report with an instruction, producing a new version. LLM-backed and can take a while."),
            // housekeeping / ops
            read("housekeeping_scan",
                    "Preview leftover resources by category (runs, stuck, kanban, agents, approvals) without deleting anything. Read-only; the stuck category is only reported when includeStuck is true.",
                    false),
            operatorOnly("housekeeping_execute",
                    "DESTRUCTIVE and irreversible: delete leftovers in the given categories (kanban, agents, approvals, stuck, runs). Requires confirm=true and at least one category. No human-approval gate is applied — the explicit confirm flag is the only gate."),
            // skills
            read("list_skills",
                    "List skills. Optional stage (SKILL/SCRIPT/WORKFLOW) and enabled flag (true = enabled only, false = disabled only); omit both to list all.",
                    true),
            read("get_skill",
                    "Get one skill by id: template, trigger conditions, examples, stage (SKILL/SCRIPT/WORKFLOW), enabled, tier and usageCount.",
                    true),
            write("toggle_skill",
                    "Flip a skill's enabled flag. Returns the skill with its new enabled state."),
            read("list_agent_skills",
                    "List the enabled skills assigned to an agent. Use list_agents to obtain agentId.",
                    true),
            write("assign_skill",
                    "Assign an enabled SKILL-stage skill to an agent (idempotent). Only approved/enabled SKILL-stage skills are assignable."),
            write("unassign_skill",
                    "Remove a skill from an agent (idempotent: unknown assignments are ignored)."),
            // llm providers (credential / core-selection management)
            read("list_llm_providers",
                    "List LLM providers. API keys are never returned raw; each response carries apiKeyMasked (**** + last 4 characters).",
                    true),
            read("get_llm_provider",
                    "Get one LLM provider by id. The API key is masked, never returned in full.",
                    true),
            operatorOnly("create_llm_provider",
                    "Create an LLM provider (created inactive). type is OPENAI, ANTHROPIC, AZURE or LOCAL. The apiKey is stored and returned masked; maxTokens defaults to 4096."),
            operatorOnly("update_llm_provider",
                    "Update an LLM provider. Omitted (null/blank) arguments keep their current value. type is OPENAI, ANTHROPIC, AZURE or LOCAL. The apiKey is stored and returned masked."),
            operatorOnly("delete_llm_provider",
                    "Delete an LLM provider by id."),
            operatorOnly("activate_llm_provider",
                    "Activate an LLM provider and deactivate the previously active one. The apiKey is returned masked."),
            operatorOnly("test_llm_provider",
                    "Test connectivity to an LLM provider by sending a minimal completion request. Returns reachable=false when the provider does not answer successfully."),
            // aria assistant surfaces
            read("list_notifications",
                    "List notifications, newest first. Optional page (default 0) and size (default 20).",
                    true),
            read("get_unread_notification_count",
                    "Return the number of unread notifications ({unreadCount}).",
                    true),
            write("mark_notification_read",
                    "Mark one notification as read by id."),
            write("mark_all_notifications_read",
                    "Mark every notification as read."),
            read("list_scheduled_jobs",
                    "List scheduled jobs. Optional category (REMINDER/MONITOR/BRIEF) and status (ACTIVE/PAUSED/COMPLETED); blank lists all.",
                    true),
            write("create_scheduled_job",
                    "Create an ACTIVE scheduled job that fires a notification. scheduleType ONE_SHOT uses the next cron match as a one-off delay; RECURRING repeats on the cron expression (5 fields, min hour day month weekday). category is REMINDER, MONITOR or BRIEF. Use cancel_scheduled_job to stop it."),
            write("cancel_scheduled_job",
                    "Cancel (delete) a scheduled job by id so it never fires again."),
            read("get_latest_conversation",
                    "Return the most recent Aria conversation (conversationId, lastMessageAt, runCount). data is null when no conversation exists yet.",
                    true),
            read("get_conversation_timeline",
                    "Return the full message timeline of a conversation across all its runs: role, content, timestamp, runId and error/retryPrompt per entry, ordered by run creation time then turn. A failed run adds a synthetic assistant entry (error=true, retryPrompt) directly after that run's entries. Returns an empty list for an unknown conversationId.",
                    true),
            // the cores' own read-only web tools (2026-10-04 operator decision, D5)
            read("WebSearch",
                    "Search the web. Core-native read-only tool, listed for auto-approval provenance.",
                    true),
            read("WebFetch",
                    "Fetch a web page. Core-native read-only tool, listed for auto-approval provenance.",
                    true));

    private static final Map<String, ToolDescriptor> BY_NAME = TOOLS.stream()
            .collect(Collectors.toUnmodifiableMap(ToolDescriptor::name, Function.identity()));

    private static final Set<String> REGISTERED_NAMES = TOOLS.stream()
            .map(ToolDescriptor::name)
            .filter(name -> !NATIVE_READ_ONLY_TOOL_NAMES.contains(name))
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> AUTO_APPROVE_READ_NAMES = TOOLS.stream()
            .filter(ToolDescriptor::autoApproveRead)
            .map(ToolDescriptor::name)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public List<ToolDescriptor> tools() {
        return TOOLS;
    }

    /**
     * The names the embedded MCP endpoint registers — the inventory parity
     * surface. The core-native read-only entries are catalogued but not
     * registered by this server.
     */
    public Set<String> names() {
        return REGISTERED_NAMES;
    }

    /**
     * The reviewed read-only tools the auto-approve default names, including
     * the cores' own read-only web tools. Every entry is a WORKER_READ tool.
     */
    public Set<String> autoApproveReadNames() {
        return AUTO_APPROVE_READ_NAMES;
    }

    /** The descriptor for {@code toolName}, or empty for an unknown or null name. */
    public Optional<ToolDescriptor> find(String toolName) {
        return toolName == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(toolName));
    }

    private static ToolDescriptor read(String name, String description, boolean autoApproveRead) {
        return new ToolDescriptor(name, description, ToolPolicy.WORKER_READ, autoApproveRead);
    }

    private static ToolDescriptor write(String name, String description) {
        return new ToolDescriptor(name, description, ToolPolicy.WORKER_WRITE, false);
    }

    private static ToolDescriptor operatorOnly(String name, String description) {
        return new ToolDescriptor(name, description, ToolPolicy.OPERATOR_ONLY, false);
    }
}
