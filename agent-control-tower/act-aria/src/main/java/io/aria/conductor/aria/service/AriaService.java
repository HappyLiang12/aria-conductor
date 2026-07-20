package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaAction;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.AriaConstants;
import io.aria.conductor.aria.intent.IntentClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.*;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import static io.aria.conductor.execution.engine.AgentLoopEngine.parseMaxIterationsFromConfig;
import io.aria.conductor.execution.llm.*;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.execution.tool.ToolExecutionResult;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.service.KnowledgeService;
import io.aria.conductor.common.model.PromptCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AriaService {

    private static final int MAX_HISTORY_TURNS = 20;
    // Bumped from 5 to 10 so multi-tool prompts (e.g. "create agent + start run + show approvals + dashboard summary")
    // can complete even when the LLM (e.g. glm-5.1) emits a single tool call per round.
    private static final int MAX_TOOL_CALL_ROUNDS = 15;

    private static final int MAX_KNOWLEDGE_ITEMS = 5;

    /**
     * Stage 1→2 of the self-improvement pipeline. After a multi-tool conversation we ask the
     * LLM to extract ONE reusable insight which we store as PENDING knowledge for human review.
     */
    private static final String EXTRACTION_PROMPT =
            "Based on the conversation above, extract ONE reusable insight or operational pattern "
          + "that would help in future interactions. Respond with ONLY the insight in 1-2 sentences. "
          + "If nothing reusable, respond with exactly 'NONE'.";

    private static final int MIN_TOOL_CALLS_FOR_EXTRACTION = 2;
    private static final int EXTRACTION_MAX_TOKENS = 200;

    /**
     * Token budget for the context-compaction summarization call. Keep small — we only need
     * 2-3 sentences of recap, not a full transcript.
     */
    private static final int COMPACTION_SUMMARY_TOKENS = 150;

    private static final String COMPACTION_PROMPT_PREFIX =
            "Summarize this conversation context in 2-3 sentences:\n";

    /** Marker prefix on the synthesized summary message so it is easy to identify in logs/UI. */
    private static final String COMPACTION_MARKER = "[Earlier context] ";

    private final AgentLoopEngine agentLoopEngine;
    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final IntentClassifier intentClassifier;
    private final ToolRegistry toolRegistryService;
    private final ToolExecutionEngine toolExecutionEngine;
    private final ObjectMapper toolMapper = new ObjectMapper();
    private final KnowledgeService knowledgeService;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final ToolCallRepository toolCallRepository;

    public AriaService(AgentLoopEngine agentLoopEngine,
                       AgentRepository agentRepository,
                       RunRepository runRepository,
                       LlmClient llmClient,
                       LlmProperties llmProperties,
                       IntentClassifier intentClassifier,
                       ToolRegistry toolRegistryService,
                       ToolExecutionEngine toolExecutionEngine,
                       KnowledgeService knowledgeService,
                       SessionTrajectoryRepository trajectoryRepository,
                       ToolCallRepository toolCallRepository) {
        this.agentLoopEngine = agentLoopEngine;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.intentClassifier = intentClassifier;
        this.toolRegistryService = toolRegistryService;
        this.toolExecutionEngine = toolExecutionEngine;
        this.knowledgeService = knowledgeService;
        this.trajectoryRepository = trajectoryRepository;
        this.toolCallRepository = toolCallRepository;
    }

    public AriaChatResponse chat(AriaChatRequest request) {
        String conversationId = request.getConversationId() != null ? request.getConversationId()
                : (request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
        String intent = intentClassifier.classify(request.getMessage());
        log.info("Aria chat (via AgentLoopEngine): conversationId={}, intent={}", conversationId, intent);

        // Look up Aria agent (created by AriaDefaultAgentInitializer at startup)
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID)
                .orElseThrow(() -> new IllegalStateException("Aria agent not found — ensure AriaDefaultAgentInitializer ran"));

        // Restore multi-turn conversation history from prior runs with same conversationId
        List<LlmMessage> priorMessages = loadConversationHistory(conversationId);

        // Build a Run — AgentLoopEngine will handle LLM calls and tool execution
        Run run = Run.builder()
                .agentId(AriaConstants.ARIA_AGENT_ID)
                .promptSeed(request.getMessage())
                .maxIterations(parseMaxIterationsFromConfig(aria, 0))
                .status(RunStatus.PENDING)
                .conversationId(conversationId)
                .build();
        run = runRepository.save(run);

        // Execute via unified engine with prior conversation context
        agentLoopEngine.startRun(run.getId(), priorMessages);

        // Poll for completion (synchronous non-streaming contract)
        int polls = 0;
        while (polls < 120) {
            Run current = runRepository.findById(run.getId()).orElse(null);
            if (current != null && (current.getStatus() == RunStatus.COMPLETED
                    || current.getStatus() == RunStatus.FAILED
                    || current.getStatus() == RunStatus.CANCELLED)) {
                String assistantMessage = current.getFinalOutput();
                // Populate actionsTaken from executed tool calls
                List<AriaAction> actions = buildActionsTaken(run.getId());
                // Empty-content fallback: synthesize from executed tool calls
                if (assistantMessage == null || assistantMessage.isBlank()) {
                    if (!actions.isEmpty()) {
                        assistantMessage = "Executed: " + actions.stream()
                                .map(AriaAction::description)
                                .collect(Collectors.joining(", "));
                    } else {
                        assistantMessage = "I've completed your request. Check the Run dashboard for details.";
                    }
                }
                return AriaChatResponse.builder()
                        .runId(run.getId().toString())
                        .conversationId(conversationId)
                        .message(assistantMessage)
                        .intent(intent)
                        .actionsTaken(actions)
                        .timestamp(Instant.now())
                        .build();
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            polls++;
        }
        // Timeout: grace wait for budget exhaustion summary (takes 2-5s for LLM call)
        log.warn("Aria sync chat exceeded the sync window for run {} — grace wait for final output", run.getId());
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        Run finalCheck = runRepository.findById(run.getId()).orElse(null);
        if (finalCheck != null && (finalCheck.getStatus() == RunStatus.COMPLETED
                || finalCheck.getStatus() == RunStatus.FAILED)) {
            String output = finalCheck.getFinalOutput();
            if (output != null && !output.isBlank()) {
                return AriaChatResponse.builder()
                        .runId(run.getId().toString())
                        .conversationId(conversationId)
                        .message(output)
                        .intent(intent)
                        .actionsTaken(buildActionsTaken(run.getId()))
                        .timestamp(Instant.now())
                        .build();
            }
        }
        // Still not done — return "Still working" as before
        log.warn("Aria sync chat: run {} still not complete after grace wait", run.getId());
        return AriaChatResponse.builder()
                .runId(run.getId().toString())
                .conversationId(conversationId)
                .message("Still working — reasoning models can take a little longer. The result will appear in the Runs dashboard, or use streaming chat for live updates.")
                .intent(intent)
                .actionsTaken(buildActionsTaken(run.getId()))
                .timestamp(Instant.now())
                .build();
    }

    String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
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
                """);

        // Inject approved knowledge context — single shared formatter (no longer duplicated).
        try {
            String knowledgePrompt = knowledgeService.buildKnowledgeContextPrompt(MAX_KNOWLEDGE_ITEMS);
            if (!knowledgePrompt.isBlank()) {
                sb.append(knowledgePrompt);
            }
        } catch (Exception e) {
            // Never let knowledge injection crash the chat
            log.warn("Failed to inject knowledge context: {}", e.getMessage());
        }

        return sb.toString();
    }

    /**
     * Load conversation history from prior runs with the same conversationId.
     */
    private List<LlmMessage> loadConversationHistory(String conversationId) {
        if (conversationId == null) return List.of();
        try {
            List<Run> priorRuns = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            if (priorRuns.isEmpty()) return List.of();
            // Exclude FAILED runs to prevent error-loop pollution of context
            List<UUID> priorRunIds = priorRuns.stream()
                    .filter(r -> r.getStatus() != RunStatus.FAILED)
                    .map(Run::getId).toList();
            if (priorRunIds.isEmpty()) return List.of();
            List<SessionTrajectory> trajectories = trajectoryRepository
                    .findByRunIdInOrderByTurnNumberAsc(priorRunIds);
            return trajectories.stream()
                    .filter(t -> "user".equals(t.getRole()) || "assistant".equals(t.getRole()))
                    .filter(t -> t.getContent() != null && !t.getContent().isBlank())
                    .map(t -> "user".equals(t.getRole())
                            ? LlmMessage.user(t.getContent())
                            : LlmMessage.assistant(t.getContent()))
                    .limit(40) // ~20 turns (user+assistant pairs)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to load conversation history: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Build actionsTaken list from completed tool calls for this run.
     */
    private List<AriaAction> buildActionsTaken(UUID runId) {
        try {
            return toolCallRepository.findByRunId(runId).stream()
                    .filter(tc -> tc.getStatus() == ToolCallStatus.COMPLETED)
                    .map(tc -> new AriaAction(
                            tc.getToolName(),
                            tc.getToolName(),
                            tc.getResult() != null ? tc.getResult() : ""))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to build actionsTaken: {}", e.getMessage());
            return List.of();
        }
    }

}
