package io.aria.conductor.aria.init;

import io.aria.conductor.common.model.*;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.AdkSystemProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ensures the Aria default agent exists at startup with all approved tools assigned.
 * Idempotent — safe to run on every startup. The managed agent/config write is
 * CREATE-only: an existing Aria record is left untouched so operator edits
 * (config, name, role, adkProvider) survive restarts.
 * Also migrates the system-seeded BA/Dev/QA role agents (V42) still on the legacy
 * hardcoded "langchain" provider to the configured default provider
 * (adk.default-provider) — operator-created agents are never re-pointed.
 * A scheduled reconciler retries the pre-warm while Aria is DEGRADED and re-stamps
 * HEALTHY on the first success, so a transient boot failure is not terminal.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AriaDefaultAgentInitializer implements ApplicationRunner {

    private static final String ARIA_SYSTEM_PROMPT = """
            You are Aria, the AI operator assistant for the Aria Conductor.
            You help operators manage their fleet of AI agents by providing information,
            executing commands, and answering questions about the system.

            ## Available Tools

            **Agents:**
            - list_agents: List all agents with their status
            - get_agent: Get details for a specific agent (requires id)
            - create_agent: Create a new AI agent (requires name, role)
            - update_agent: Update an agent's name, description, or role (requires id)
            - retire_agent: Retire/soft-delete an agent (requires id)

            **Runs (Agent Execution):**
            - run_agent: Start a new agent run (requires agentId, prompt)
            - list_runs: List all runs
            - list_running_runs: List currently active runs
            - get_run: Get run details including iterations and tokens (requires id)
            - pause_run: Pause a running execution (requires id)
            - resume_run: Resume a paused run, optionally with new instruction (requires id, optional instruction)
            - cancel_run: Cancel a run (requires id)

            **Approvals:**
            - list_pending_approvals: List actions waiting for human approval
            - decide_approval: Approve or reject a pending action (requires id, decision, reason)

            **Knowledge:**
            - store_knowledge: Save new knowledge as PENDING (requires name, content, type). Valid types: SKILL, SCRIPT, PROMPT, TOOL, TEMPLATE, GUIDELINE
            - list_knowledge: List all knowledge items with lifecycle status (operator view)
            - query_knowledge: Search APPROVED knowledge only (agent-facing, returns only approved non-retired)
            - review_knowledge: Approve or reject pending knowledge (requires id, decision, optional reason)
            - retire_knowledge: Retire a knowledge item (requires id)

            **Kanban:**
            - create_kanban_item: Create a task item (requires title, optional priority/assignee/description)
            - list_kanban_items: List all kanban items (optional status filter)
            - update_kanban_item: Update an existing item's metadata — title, priority, assignee, or description (requires id). USE THIS TOOL whenever the user asks to "update", "edit", "change", "modify", "rename", or "reassign" a kanban item's fields.
            - transition_kanban_item: Change item status (requires id, status). Valid transitions: TODO→IN_PROGRESS/BLOCKED/CANCELLED, IN_PROGRESS→DONE/BLOCKED/CANCELLED, BLOCKED→TODO/IN_PROGRESS/CANCELLED

            **Definition of Done (DoD):**
            - init_dod: Initialize DoD tracking for a task (requires taskId)
            - submit_dod_review: Submit a DoD stage review (requires taskId, passed, evidence)
            - get_dod_status: Get current DoD status for a task (requires taskId)

            **Reports:**
            - generate_report: Generate a new report (requires title, description)
            - list_reports: List all reports
            - amend_report: Amend an existing report (requires reportId, instruction)

            **Dashboard:**
            - get_dashboard_summary: Get system overview statistics

            **Workflows (multi-agent orchestration):**
            - create_workflow: Create and START an executable BA->Dev->QA style chain (requires name + a steps[] array of {agent, promptTemplate}, or a yaml definition). USE THIS — not store_knowledge — when the user asks to build or run a multi-step / multi-agent workflow. Each step's agent may be an id, name, or role; use {previousOutput} in a step's promptTemplate to pass the prior step's result forward.
            - get_workflow: Get a workflow chain's status and per-step progress (requires id)
            - list_workflows: List all workflow chains
            - cancel_workflow: Cancel a running/pending workflow (requires id)
            - retry_workflow_step: Retry a failed step in a failed workflow (requires id, stepIndex)
            - instantiate_template: Start a governed workflow TEMPLATE by its knowledge item id (templateId) with
              optional parameters (e.g. {"issueRef": "#12"}). USE THIS for the spec-driven development loop.

            **Housekeeping:**
            - housekeeping_scan: Read-only scan of leftovers (terminal runs, stuck paused runs, finished kanban
              cards, leftover e2e/unhealthy agents, expired approvals). Report the counts before cleaning.
            - housekeeping_execute: Request cleanup of selected categories (runs/stuck/kanban/agents/approvals)
              with optional exclusions (ids to keep). ALWAYS requires human approval; never self-approve.

            - Spec-driven development: to run the BA->Dev->QA development loop on a GitHub issue, find the
              approved "development-workflow" template and instantiate it with an issueRef parameter via
              instantiate_template. The loop pauses for human spec approval (SPEC_REVIEW), then routes on the
              QA verdict. Users can copy the template knowledge item and edit its YAML to customise their own workflow.
              NEVER use create_workflow for the BA->Dev->QA loop; always use instantiate_template.
              Always pass issueRepo (owner/repo) and repoUrl parameters when instantiating the development-workflow template. When a SPEC_REVIEW rejection contains user answers, carry them into the resubmission; answer trivial questions yourself from the issue body before escalating to the user.

            ## Rules

            IMPORTANT — knowledge governance:
            Newly stored knowledge is always PENDING and requires human review before agents
            can use it. Never claim that a freshly stored item is already active.

            IMPORTANT — multi-step requests:
            When the user's request implies multiple distinct actions, you MUST call every
            required tool, one after another. Do not stop after the first tool call. Only
            produce your final answer once every requested action has been executed.
            If you cannot complete an action, state it clearly — never fabricate success.

            IMPORTANT — sequential execution:
            Execute ALL requested actions in the order specified. If one fails, report the
            failure and continue with remaining actions. Never skip an action silently.

            Always be helpful, concise, and proactive. Use tools to get real data rather than guessing.
            Format responses clearly with bullet points or tables when listing items.
            IMPORTANT — you are an orchestrator, not a worker:
            You do NOT have file, shell, git, or raw-HTTP tools. You MUST NOT attempt development work
            (reading/editing files, running commands, cloning, committing, pushing, opening pull requests)
            yourself. For any coding/development/fix task, DELEGATE it: call create_agent with role exactly
            "dev" (then run_agent), or run_agent on an existing worker agent, passing a detailed prompt that
            describes the task. The worker agent performs the clone/edit/commit/push/PR under governance.

            IMPORTANT — delegation:
            When the user explicitly asks to "delegate", "run", "execute", "have an agent do", or "ask <agent>",
            you MUST call run_agent (with the correct agentId from list_agents + a detailed prompt).
            When the user asks to "create an agent", you MUST call create_agent.
            NEVER answer delegation requests with text alone — always use the tool.

            IMPORTANT — no guessing:
            Never guess repository names, URLs, or resource identifiers. If you need a name or ID,
            call the appropriate list tool first (list_agents, list_knowledge, etc.) to discover it.
            Do not invent repository names or URLs.
            """;

    private static final String ARIA_ROLE = "AI operator assistant for the Aria Conductor. Helps manage AI agents, execute commands, and answer system questions.";

    private static final String ARIA_CONFIG = "{\"maxToolCallRounds\":15}";

    /**
     * Orchestration-only tool allowlist for Aria (#25). Aria is the operator assistant and must
     * delegate development work (clone/edit/commit/push/PR) to worker agents — so the git pack,
     * file, shell and raw-HTTP tools are deliberately EXCLUDED here. Names not present in the DB
     * are simply ignored; any granted tool outside this set is pruned at startup (idempotent).
     */
    private static final Set<String> ARIA_ORCHESTRATION_TOOLS = Set.of(
            // agents
            "list_agents", "get_agent", "create_agent", "update_agent", "retire_agent", "delete_agent",
            // runs
            "run_agent", "get_run_status", "list_runs", "list_running_runs", "get_run",
            "pause_run", "resume_run", "cancel_run",
            // approvals
            "list_pending_approvals", "decide_approval",
            // knowledge
            "store_knowledge", "create_knowledge", "list_knowledge", "query_knowledge",
            "search_knowledge", "review_knowledge", "retire_knowledge",
            // kanban
            "create_kanban_item", "list_kanban_items", "update_kanban_item", "transition_kanban_item",
            // definition of done
            "init_dod", "submit_dod_review", "get_dod_status",
            // reports
            "generate_report", "list_reports", "amend_report",
            // dashboard
            "get_dashboard_summary",
            // workflows (BA->Dev->QA multi-agent orchestration)
            "create_workflow", "get_workflow", "list_workflows", "cancel_workflow", "retry_workflow_step",
            "instantiate_template",
            // web (issue/content discovery for orchestration)
            "web_search", "web_fetch",
            // HITL
            "request_approval",
            // housekeeping (operator cleanup; execute is approval-gated)
            "housekeeping_scan", "housekeeping_execute");

    /** The legacy hardcoded provider name; agents still on it are migrated to the configured default. */
    private static final String LEGACY_PROVIDER = "langchain";

    /** Interval of the DEGRADED-recovery reconciler (60s), also used as its initial delay. */
    private static final long DEGRADED_RECOVERY_INTERVAL_MS = 60_000L;

    /**
     * Agent ids seeded by V42__seed_sdd_role_agents.sql (SDD BA/DEV/QA). These are the
     * ONLY agents the legacy-provider migration may re-point: operator-created or
     * operator-re-pointed agents (e.g. a worker explicitly set to langchain via
     * PUT /api/v1/agents) keep their provider across restarts.
     */
    private static final Set<UUID> SEEDED_SDD_ROLE_AGENT_IDS = Set.of(
            UUID.fromString("ba000000-0000-0000-0000-000000000001"),
            UUID.fromString("de000000-0000-0000-0000-000000000002"),
            UUID.fromString("aa000000-0000-0000-0000-000000000003"));

    private final AgentRepository agentRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final AgentToolRepository agentToolRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final AdkProviderRegistry adkProviderRegistry;
    private final Environment environment;
    private final String defaultProvider;

    public AriaDefaultAgentInitializer(AgentRepository agentRepository,
                                       ToolDefinitionRepository toolDefinitionRepository,
                                       AgentToolRepository agentToolRepository,
                                       LlmProviderRepository llmProviderRepository,
                                       AdkProviderRegistry adkProviderRegistry,
                                       Environment environment,
                                       AdkSystemProperties adkSystemProperties) {
        this.agentRepository = agentRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.agentToolRepository = agentToolRepository;
        this.llmProviderRepository = llmProviderRepository;
        this.adkProviderRegistry = adkProviderRegistry;
        this.environment = environment;
        String configured = adkSystemProperties != null ? adkSystemProperties.getDefaultProvider() : null;
        this.defaultProvider = (configured == null || configured.isBlank()) ? LEGACY_PROVIDER : configured;
    }

    private String buildAriaConfig() {
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("maxToolCallRounds", 15);
            // Aria is the interactive operator assistant: the human just sent the chat message,
            // so a separate task-level approval gate before every LLM call is redundant and would
            // block the SSE stream indefinitely (the operator is already present). Opt out.
            cfg.put("taskApprovalRequired", false);
            cfg.put("systemPrompt", ARIA_SYSTEM_PROMPT);
            return new ObjectMapper().writeValueAsString(cfg);
        } catch (Exception e) {
            log.warn("Failed to build Aria config, using fallback", e);
            return "{\"maxToolCallRounds\":15}";
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing Aria default agent...");

        // 1. Ensure the Aria agent exists. The managed write (name/role/provider/config)
        //    is CREATE-only: an existing record is left untouched so operator edits —
        //    notably taskApprovalRequired in config and the adkProvider choice — survive
        //    restarts. Health reconciliation happens below and via recoverDegradedAria().
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID).orElse(null);
        if (aria == null) {
            aria = Agent.builder()
                    .id(AriaConstants.ARIA_AGENT_ID)
                    .name("Aria")
                    .role(ARIA_ROLE)
                    .agentType(AgentType.NATIVE)
                    .adkProvider(defaultProvider)
                    .config(buildAriaConfig())
                    .healthStatus(HealthStatus.HEALTHY)
                    .build();
            aria.setUpdatedAt(Instant.now());
            agentRepository.save(aria);
            log.info("Aria agent created with id={}", AriaConstants.ARIA_AGENT_ID);
        } else {
            log.info("Aria agent already exists (id={}) — leaving operator config untouched", AriaConstants.ARIA_AGENT_ID);
        }

        // 2. Migrate the system-seeded SDD role agents still on the legacy hardcoded
        //    provider to the configured default. When the default IS langchain this is
        //    a no-op (preserves historical behaviour); when the default is opencode it
        //    re-points the seeded BA/Dev/QA agents to the sandbox. Operator-created
        //    agents (any id outside SEEDED_SDD_ROLE_AGENT_IDS) are never touched.
        if (!LEGACY_PROVIDER.equalsIgnoreCase(defaultProvider)) {
            List<Agent> legacyAgents = agentRepository.findAll().stream()
                    .filter(a -> LEGACY_PROVIDER.equalsIgnoreCase(a.getAdkProvider()))
                    .filter(a -> a.getId() != null && SEEDED_SDD_ROLE_AGENT_IDS.contains(a.getId()))
                    .toList();
            if (!legacyAgents.isEmpty()) {
                log.warn("Migrating {} seeded agent(s) from legacy '{}' provider to '{}': {}",
                        legacyAgents.size(),
                        LEGACY_PROVIDER,
                        defaultProvider,
                        legacyAgents.stream().map(a -> a.getId().toString().substring(0, 8) + "/" + a.getName()).toList());
                for (Agent agent : legacyAgents) {
                    agent.setAdkProvider(defaultProvider);
                    agentRepository.save(agent);
                }
            }
        }

        // 2.5: Ensure at least one LLM provider is active (generic, provider-agnostic)
        if (llmProviderRepository.findByActiveTrue().isEmpty()) {
            String apiKey = System.getenv("LLM_API_KEY");
            String baseUrl = System.getenv("LLM_BASE_URL");
            String model = System.getenv("LLM_MODEL");
            if (apiKey != null && !apiKey.isBlank()) {
                String name = inferProviderName(baseUrl);
                LlmProviderType type = inferProviderType(baseUrl);
                LlmProvider provider = LlmProvider.builder()
                        .name(name)
                        .type(type)
                        .baseUrl(baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                        .apiKey(apiKey)
                        .defaultModel(model != null ? model : "gpt-4o")
                        .defaultMaxTokens(4096)
                        .active(true)
                        .build();
                llmProviderRepository.save(provider);
                log.info("Auto-created and ACTIVATED {} LLM provider from environment (LLM_API_KEY set).", name);
            } else {
                log.info("No active LLM provider and LLM_API_KEY not set. Configure via POST /api/v1/llm-providers or set LLM_API_KEY/LLM_BASE_URL/LLM_MODEL env vars.");
            }
        }

        // 3. Assign Aria the orchestration-only tool set (#25) and prune anything outside it.
        // Aria orchestrates workers; she must NOT hold git/file/shell/http tools herself.
        List<ToolDefinition> approvedTools = toolDefinitionRepository.findAllApprovedAndEnabled();
        Map<String, String> orchestrationToolIds = approvedTools.stream()
                .filter(t -> ARIA_ORCHESTRATION_TOOLS.contains(t.getName()))
                .collect(Collectors.toMap(ToolDefinition::getName, ToolDefinition::getId, (a, b) -> a));
        int assigned = 0;
        for (String toolId : orchestrationToolIds.values()) {
            AgentToolId atId = new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), toolId);
            if (!agentToolRepository.existsById(atId)) {
                AgentTool agentTool = new AgentTool();
                agentTool.setId(atId);
                agentTool.setAssignedBy("system");
                agentToolRepository.save(agentTool);
                assigned++;
            }
        }
        // Prune any previously-granted tools that fall outside the orchestration allowlist (idempotent).
        Set<String> allowedIds = Set.copyOf(orchestrationToolIds.values());
        List<String> existingToolIds = agentToolRepository.findToolIdsByAgentId(AriaConstants.ARIA_AGENT_ID.toString());
        int pruned = 0;
        for (String existingToolId : existingToolIds) {
            if (!allowedIds.contains(existingToolId)) {
                agentToolRepository.deleteById(new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), existingToolId));
                pruned++;
            }
        }
        log.info("Aria default agent initialized: {} orchestration tools assigned ({} already present), {} pruned, total approved tools: {}",
                assigned, orchestrationToolIds.size() - assigned, pruned, approvedTools.size());

        // 4. Pre-warm ADK instance for Aria to eliminate cold-start timeout on first request
        // Skip pre-warming in test/noop-llm profiles to avoid spawning real subprocess
        if (!isTestProfile()) {
            if (aria != null) {
                try {
                    log.info("Pre-warming ADK instance for Aria...");
                    // Route through the registry so the Aria agent's own provider is used
                    adkProviderRegistry.resolve(aria).prepareAgent(AriaConstants.ARIA_AGENT_ID, aria);
                    log.info("ADK instance for Aria is ready (health check passed)");
                } catch (Exception e) {
                    // Transient pre-warm failures (e.g. OpenSandbox not reachable yet on CI/local,
                    // ADK venv still warming up) must NOT kill the whole backend. The provider
                    // creates the instance lazily on first real use (executeTask/call ->
                    // getOrPrepare/getOrStartInstance), so Aria just starts degraded here —
                    // and recoverDegradedAria() retries below until it succeeds.
                    log.error("ADK pre-warm failed for Aria (agent id={}, provider={}) — continuing startup in degraded state. "
                                    + "The instance is created lazily on first use; if runs keep failing check: "
                                    + "opencode → OpenSandbox server reachable (SANDBOX/OPENCODE sandbox server URL, e.g. localhost:8090); "
                                    + "langchain → ADK venv present and langchain-adk server reachable. Cause: {}",
                            AriaConstants.ARIA_AGENT_ID, aria.getAdkProvider(), e.getMessage(), e);
                    aria.setHealthStatus(HealthStatus.DEGRADED);
                    aria.setUpdatedAt(Instant.now());
                    agentRepository.save(aria);
                }
            }
        } else {
            log.info("Skipping ADK pre-warm (test/noop-llm profile active)");
        }
    }

    /**
     * Recovery reconciler for a DEGRADED Aria (e.g. the OpenSandbox/ADK backend was not
     * reachable during the boot pre-warm). Every {@link #DEGRADED_RECOVERY_INTERVAL_MS}
     * it retries the pre-warm; on the first success the agent is re-stamped HEALTHY.
     * A still-failing pre-warm keeps DEGRADED and never throws out of the scheduled
     * context. No-op for missing/HEALTHY agents and in test/noop-llm profiles
     * (mirroring the boot pre-warm skip). Tests invoke the method directly.
     */
    @Scheduled(initialDelay = DEGRADED_RECOVERY_INTERVAL_MS, fixedDelay = DEGRADED_RECOVERY_INTERVAL_MS)
    public void recoverDegradedAria() {
        if (isTestProfile()) {
            return;
        }
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID).orElse(null);
        if (aria == null || aria.getHealthStatus() != HealthStatus.DEGRADED) {
            return;
        }
        try {
            log.info("Aria is DEGRADED — retrying ADK pre-warm (provider={})...", aria.getAdkProvider());
            adkProviderRegistry.resolve(aria).prepareAgent(AriaConstants.ARIA_AGENT_ID, aria);
            aria.setHealthStatus(HealthStatus.HEALTHY);
            aria.setUpdatedAt(Instant.now());
            agentRepository.save(aria);
            log.info("Aria ADK pre-warm recovered — health re-stamped HEALTHY");
        } catch (Exception e) {
            log.warn("Aria recovery pre-warm still failing ({}) — remaining DEGRADED; will retry in {}ms",
                    e.getMessage(), DEGRADED_RECOVERY_INTERVAL_MS);
        }
    }

    private boolean isTestProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("test".equals(profile) || "noop-llm".equals(profile)) {
                return true;
            }
        }
        return false;
    }

    private static String inferProviderName(String baseUrl) {
        if (baseUrl == null) return "Default";
        if (baseUrl.contains("deepseek")) return "DeepSeek";
        if (baseUrl.contains("openai")) return "OpenAI";
        if (baseUrl.contains("anthropic")) return "Anthropic";
        return "Custom";
    }

    private static LlmProviderType inferProviderType(String baseUrl) {
        return LlmProviderType.OPENAI;
    }
}
