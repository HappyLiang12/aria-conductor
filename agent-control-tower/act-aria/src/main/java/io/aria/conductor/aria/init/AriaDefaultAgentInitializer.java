package io.aria.conductor.aria.init;

import io.aria.conductor.common.model.*;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.aria.AriaConstants;
import io.aria.conductor.execution.adk.LangChainAdkProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ensures the Aria default agent exists at startup with all approved tools assigned.
 * Idempotent — safe to run on every startup.
 * Also migrates any agents still using the legacy "langchain" provider to "langchain".
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
            - start_run: Start a new agent run (requires agentId, prompt)
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
            """;

    private static final String ARIA_ROLE = "AI operator assistant for the Aria Conductor. Helps manage AI agents, execute commands, and answer system questions.";

    private static final String ARIA_CONFIG = "{\"maxToolCallRounds\":15}";

    private final AgentRepository agentRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final AgentToolRepository agentToolRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LangChainAdkProvider adkProvider;
    private final Environment environment;

    public AriaDefaultAgentInitializer(AgentRepository agentRepository,
                                       ToolDefinitionRepository toolDefinitionRepository,
                                       AgentToolRepository agentToolRepository,
                                       LlmProviderRepository llmProviderRepository,
                                       LangChainAdkProvider adkProvider,
                                       Environment environment) {
        this.agentRepository = agentRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.agentToolRepository = agentToolRepository;
        this.llmProviderRepository = llmProviderRepository;
        this.adkProvider = adkProvider;
        this.environment = environment;
    }

    private String buildAriaConfig() {
        try {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("maxToolCallRounds", 15);
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

        // 1. Upsert Aria agent
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID).orElse(null);
        String config = buildAriaConfig();
        if (aria == null) {
            aria = Agent.builder()
                    .id(AriaConstants.ARIA_AGENT_ID)
                    .name("Aria")
                    .role(ARIA_ROLE)
                    .agentType(AgentType.NATIVE)
                    .adkProvider("langchain")
                    .config(config)
                    .healthStatus(HealthStatus.HEALTHY)
                    .build();
            log.info("Aria agent created with id={}", AriaConstants.ARIA_AGENT_ID);
        } else {
            aria.setName("Aria");
            aria.setRole(ARIA_ROLE);
            aria.setAdkProvider("langchain");
            aria.setConfig(config);
            aria.setHealthStatus(HealthStatus.HEALTHY);
            log.info("Aria agent updated (existing found)");
        }
        aria.setUpdatedAt(Instant.now());
        agentRepository.save(aria);

        // 2. Migrate legacy agents from legacy provider to langchain
        List<Agent> legacyAgents = agentRepository.findAll().stream()
                .filter(a -> "langchain".equalsIgnoreCase(a.getAdkProvider()))
                .toList();
        if (!legacyAgents.isEmpty()) {
            log.warn("Migrating {} agent(s) from legacy provider to langchain provider: {}",
                    legacyAgents.size(),
                    legacyAgents.stream().map(a -> a.getId().toString().substring(0, 8) + "/" + a.getName()).toList());
            for (Agent agent : legacyAgents) {
                agent.setAdkProvider("langchain");
                agentRepository.save(agent);
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

        // 3. Assign all approved and enabled tools to Aria
        List<ToolDefinition> approvedTools = toolDefinitionRepository.findAllApprovedAndEnabled();
        int assigned = 0;
        for (ToolDefinition tool : approvedTools) {
            AgentToolId toolId = new AgentToolId(AriaConstants.ARIA_AGENT_ID.toString(), tool.getId());
            if (!agentToolRepository.existsById(toolId)) {
                AgentTool agentTool = new AgentTool();
                agentTool.setId(toolId);
                agentTool.setAssignedBy("system");
                agentToolRepository.save(agentTool);
                assigned++;
            }
        }
        log.info("Aria default agent initialized: {} tools assigned ({} already present), total approved tools: {}",
                assigned, approvedTools.size() - assigned, approvedTools.size());

        // 4. Pre-warm ADK instance for Aria to eliminate cold-start timeout on first request
        // Skip pre-warming in test/noop-llm profiles to avoid spawning real subprocess
        if (!isTestProfile()) {
            Agent ariaAgent = agentRepository.findById(AriaConstants.ARIA_AGENT_ID).orElse(null);
            if (ariaAgent != null) {
                try {
                    log.info("Pre-warming ADK instance for Aria...");
                    adkProvider.prepareAgent(AriaConstants.ARIA_AGENT_ID, ariaAgent);
                    log.info("ADK instance for Aria is ready (health check passed)");
                } catch (Exception e) {
                    log.error("ADK pre-warm failed — Aria cannot start: {}", e.getMessage());
                    throw new IllegalStateException("ADK pre-warm failed for Aria", e);
                }
            }
        } else {
            log.info("Skipping ADK pre-warm (test/noop-llm profile active)");
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
