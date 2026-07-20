package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunIterationEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.exception.BudgetExceededException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.llm.LlmToolCall;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionExecutionPipeline;
import io.aria.conductor.execution.pipeline.ActionResult;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.tool.AgentToolResolver;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.SkillContextProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Agent loop engine — orchestrates the iterative agent execution cycle.
 * Uses virtual threads so CompletableFuture blocking doesn't pin OS threads.
 */
@Slf4j
@Component
public class AgentLoopEngine {

    private static final java.util.regex.Pattern ACTION_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\b(create|start|update|delete|remove|add|run|" +
                            "execute|generate|build|deploy|cancel|pause|resume|retire|" +
                            "store|query|amend|init|submit|transition|approve|reject)\\b");

    private final RunRepository runRepository;
    private final AgentRepository agentRepository;
    private final AdkProviderRegistry adkProviderRegistry;
    private final SessionStateManager sessionStateManager;
    private final ActionExecutionPipeline actionPipeline;
    private final CircuitBreaker circuitBreaker;
    private final ApprovalGate approvalGate;
    private final PromptCallRepository promptCallRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final ToolCallRepository toolCallRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkflowService workflowService;
    private final WorkflowChainRepository workflowChainRepository;
    private final AgentToolResolver agentToolResolver;
    private final ToolRegistry toolRegistry;
    private final KnowledgeContextProvider knowledgeProvider;
    private final SkillContextProvider skillProvider;

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, RunContext> activeContexts = new ConcurrentHashMap<>();

    public AgentLoopEngine(RunRepository runRepository,
                           AgentRepository agentRepository,
                           AdkProviderRegistry adkProviderRegistry,
                           SessionStateManager sessionStateManager,
                           ActionExecutionPipeline actionPipeline,
                           CircuitBreaker circuitBreaker,
                           ApprovalGate approvalGate,
                           PromptCallRepository promptCallRepository,
                           SessionTrajectoryRepository trajectoryRepository,
                           ToolCallRepository toolCallRepository,
                           ApplicationEventPublisher eventPublisher,
                           WorkflowService workflowService,
                           WorkflowChainRepository workflowChainRepository,
                           AgentToolResolver agentToolResolver,
                           ToolRegistry toolRegistry,
                           KnowledgeContextProvider knowledgeProvider,
                           SkillContextProvider skillProvider) {
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.adkProviderRegistry = adkProviderRegistry;
        this.sessionStateManager = sessionStateManager;
        this.actionPipeline = actionPipeline;
        this.circuitBreaker = circuitBreaker;
        this.approvalGate = approvalGate;
        this.promptCallRepository = promptCallRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.toolCallRepository = toolCallRepository;
        this.eventPublisher = eventPublisher;
        this.workflowService = workflowService;
        this.workflowChainRepository = workflowChainRepository;
        this.agentToolResolver = agentToolResolver;
        this.toolRegistry = toolRegistry;
        this.knowledgeProvider = knowledgeProvider;
        this.skillProvider = skillProvider;
    }

    /**
     * Start a run — loads agent, creates session, enters iteration loop on virtual thread.
     */
    public void startRun(UUID runId) {
        startRunInternal(runId, null, List.of());
    }

    /**
     * Start a run with pre-loaded conversation history.
     * Used by AriaService to restore multi-turn context from prior runs.
     *
     * @param runId          the Run UUID (pre-saved with conversationId set)
     * @param initialContext prior conversation messages to inject before the prompt
     */
    public void startRun(UUID runId, List<LlmMessage> initialContext) {
        startRunInternal(runId, null, initialContext != null ? initialContext : List.of());
    }

    /**
     * Start a streaming run — same as {@link #startRun} but bridges SSE events
     * to the provided emitter. The caller (AriaStreamService) is responsible for
     * emitter lifecycle management (timeout, error, completion).
     *
     * @param runId           the Run UUID (pre-saved with conversationId set)
     * @param emitter         SSE emitter for client streaming; events are silently
     *                        dropped if emitter is null or client disconnects
     * @param initialContext  frontend-provided history messages (system + user/assistant pairs);
     *                        used instead of DB trajectory for the first iteration
     * @param intent          classified intent for SSE done event
     */
    public void startRunStream(UUID runId, SseEmitter emitter, List<LlmMessage> initialContext, String intent) {
        startRunInternal(runId, emitter, initialContext != null ? initialContext : List.of(), intent);
    }

    private void startRunInternal(UUID runId, @Nullable SseEmitter emitter, List<LlmMessage> initialContext) {
        startRunInternal(runId, emitter, initialContext, null);
    }

    private void startRunInternal(UUID runId, @Nullable SseEmitter emitter, List<LlmMessage> initialContext, @Nullable String intent) {
        log.info("Starting run: runId={}, streaming={}", runId, emitter != null);

        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Run", runId));

        if (run.getStatus() != RunStatus.PENDING && run.getStatus() != RunStatus.PAUSED) {
            throw new IllegalStateException("Cannot start run in status: " + run.getStatus());
        }

        Agent agent = agentRepository.findById(run.getAgentId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", run.getAgentId()));

        // Resolve the effective maxIterations: an explicit run-level value (>0, set by the caller)
        // is a hard cap over the agent's configured maxToolCallRounds; a value of 0 means "not set",
        // in which case the agent config (or a global default) is used. Persist the resolved value
        // back onto the run so the stored record and UI reflect the effective cap.
        int maxIterations = parseMaxIterationsFromConfig(agent, run.getMaxIterations());
        if (run.getMaxIterations() != maxIterations) {
            run.setMaxIterations(maxIterations);
        }

        // Update run status to INITIALIZING
        updateRunStatus(run, RunStatus.INITIALIZING);

        // Create/load session
        AgentSession session = sessionStateManager.loadOrCreateSession(runId, agent.getId());

        // Create run context with the resolved maxIterations
        RunContext ctx = new RunContext(runId, agent.getId(), agent, session, maxIterations, run.getConversationId());
        if (intent != null) {
            ctx.setIntent(intent);
        }
        activeContexts.put(runId, ctx);

        // Launch loop on virtual thread
        virtualThreadExecutor.execute(() -> {
            try {
                executeRunLoop(ctx, run, emitter, initialContext);
            } catch (Exception e) {
                log.error("Run loop failed unexpectedly: runId={}", runId, e);
                handleRunFailure(runId, e.getMessage());
                tryEmit(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            } finally {
                // Ensure emitter is completed for streaming runs
                if (emitter != null) {
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * Pause a running run.
     */
    public void pauseRun(UUID runId) {
        RunContext ctx = activeContexts.get(runId);
        if (ctx == null) {
            log.warn("Cannot pause run — no active context: runId={}", runId);
            return;
        }
        ctx.pause();
        updateRunStatusDirect(runId, RunStatus.PAUSED);
        sessionStateManager.updateSessionStatus(runId, SessionStatus.PAUSED);
        log.info("Run paused: runId={}", runId);
    }

    /**
     * Resume a paused run.
     */
    public void resumeRun(UUID runId) {
        RunContext ctx = activeContexts.get(runId);
        if (ctx == null) {
            log.warn("Cannot resume run — no active context: runId={}", runId);
            return;
        }
        ctx.resume();
        updateRunStatusDirect(runId, RunStatus.RUNNING);
        sessionStateManager.updateSessionStatus(runId, SessionStatus.ACTIVE);
        log.info("Run resumed: runId={}", runId);
    }

    /**
     * Cancel a running run. Signals the loop thread via volatile flag and updates DB.
     */
    public void cancelRun(UUID runId) {
        RunContext ctx = activeContexts.get(runId);
        if (ctx == null) {
            log.warn("Cannot cancel run — no active context: runId={}", runId);
            // Guard: only overwrite if the run is still in a cancellable state
            runRepository.findById(runId).ifPresent(run -> {
                if (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED) {
                    run.setStatus(RunStatus.CANCELLED);
                    runRepository.save(run);
                }
            });
            return;
        }
        ctx.setCancelled(true);
        if (ctx.isPaused()) {
            ctx.resume(); // unblocks awaitResume() so thread checks isCancelled()
        }
        updateRunStatusDirect(runId, RunStatus.CANCELLED);
        sessionStateManager.updateSessionStatus(runId, SessionStatus.CANCELLED);
        log.info("Run cancelled: runId={}", runId);
    }

    /** Check if a run has an active execution context (used by ZombieRunReaper). */
    public boolean hasActiveContext(UUID runId) {
        return activeContexts.containsKey(runId);
    }

    /**
     * Execute a workflow directly from a YAML template, bypassing Aria LLM orchestration.
     * Each step creates a Run that executes sequentially on a virtual thread.
     * <p>
     * The YAML is parsed into {@link WorkflowStep}s, parameters are substituted,
     * and the result is delegated to {@link WorkflowService#createAndStart} which
     * handles chain persistence, first-step start, and subsequent step advancement
     * via WorkflowAutoChainer.
     *
     * @param yamlContent the YAML workflow template content
     * @param parameters  optional parameter map for {@code {key}} substitution in prompt templates
     * @return the created {@link WorkflowChain} entity
     */
    public WorkflowChain executeWorkflowFromYaml(String yamlContent, Map<String, String> parameters) {
        log.info("Executing workflow from YAML template, content length={}", yamlContent.length());

        // Parse YAML to steps
        List<WorkflowStep> steps = parseYamlToSteps(yamlContent);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("YAML template contains no steps");
        }

        // Substitute parameters in each step's promptTemplate
        if (parameters != null) {
            for (WorkflowStep step : steps) {
                String prompt = step.getPromptTemplate();
                if (prompt != null) {
                    for (Map.Entry<String, String> entry : parameters.entrySet()) {
                        if ("{previousOutput}".equals("{" + entry.getKey() + "}")) continue;
                        prompt = prompt.replace("{" + entry.getKey() + "}",
                                entry.getValue() != null ? entry.getValue() : "");
                    }
                    step.setPromptTemplate(prompt);
                }
            }
        }

        // Build CreateWorkflowRequest
        List<CreateWorkflowRequest.StepDef> stepDefs = steps.stream()
                .map(s -> CreateWorkflowRequest.StepDef.builder()
                        .agentId(s.getAgentId())
                        .promptTemplate(s.getPromptTemplate())
                        .maxIterations(s.getMaxIterations())
                        .build())
                .toList();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("yaml-workflow-" + Instant.now().toEpochMilli())
                .steps(stepDefs)
                .build();

        // Delegate to WorkflowService which handles chain creation + first step start.
        // WorkflowAutoChainer will advance subsequent steps when runs complete.
        WorkflowResponse response = workflowService.createAndStart(request);

        // Load and return the full chain entity
        return workflowChainRepository.findById(response.getId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", response.getId()));
    }

    /**
     * Parse a YAML template string into a list of {@link WorkflowStep}s.
     * Uses SnakeYAML directly to avoid a circular dependency on act-knowledge.
     */
    @SuppressWarnings("unchecked")
    private List<WorkflowStep> parseYamlToSteps(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) return Collections.emptyList();

        Yaml yaml = new Yaml();
        Map<String, Object> doc = yaml.load(yamlContent);
        if (doc == null) return Collections.emptyList();

        List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) doc.get("steps");
        if (rawSteps == null || rawSteps.isEmpty()) return Collections.emptyList();

        List<WorkflowStep> result = new ArrayList<>();
        for (Map<String, Object> raw : rawSteps) {
            WorkflowStep step = new WorkflowStep();

            // Resolve agent ID
            Object agentIdVal = raw.get("agent_id");
            if (agentIdVal != null && !agentIdVal.toString().isBlank()) {
                try {
                    step.setAgentId(UUID.fromString(agentIdVal.toString()));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid agent_id '{}', will attempt role resolution", agentIdVal);
                    UUID resolved = resolveAgentIdByRole(raw.get("agent_role") != null ? raw.get("agent_role").toString() : null);
                    step.setAgentId(resolved);
                }
            } else {
                // Fall back to role-based resolution
                String role = raw.get("agent_role") != null ? raw.get("agent_role").toString() : null;
                step.setAgentId(resolveAgentIdByRole(role));
            }

            if (step.getAgentId() == null) {
                String role = raw.get("agent_role") != null ? raw.get("agent_role").toString() : "unknown";
                throw new IllegalArgumentException(
                    "Cannot resolve agent for YAML step: role='" + role + "'. " +
                    "Provide a valid agent_id or agent_role that matches an existing agent.");
            }

            Object promptVal = raw.get("prompt_template");
            step.setPromptTemplate(promptVal != null ? promptVal.toString() : "");

            Object maxIter = raw.get("max_iterations");
            if (maxIter instanceof Number) {
                step.setMaxIterations(((Number) maxIter).intValue());
            } else {
                step.setMaxIterations(3);
            }

            step.setStatus(WorkflowStep.Status.PENDING);
            result.add(step);
        }
        return result;
    }

    /**
     * Resolve an agent ID from a role string.
     */
    private UUID resolveAgentIdByRole(String role) {
        if (role == null || role.isBlank()) {
            log.warn("No agent_role provided, cannot resolve agent ID");
            return null;
        }
        return agentRepository.findByRole(role).stream()
                .findFirst()
                .map(Agent::getId)
                .orElseGet(() -> {
                    log.warn("No agent found for role '{}'", role);
                    return null;
                });
    }

    // ---- Main loop ----

    private void executeRunLoop(RunContext ctx, Run run, @Nullable SseEmitter emitter, List<LlmMessage> initialContext) {
        log.info("Entering run loop: runId={}, agentId={}", ctx.getRunId(), ctx.getAgentId());

        // SSE: notify client that processing has started
        tryEmit(emitter, "thinking", Map.of("status", "processing"));

        // Update to RUNNING
        updateRunStatusDirect(ctx.getRunId(), RunStatus.RUNNING);

        // Persist initial context as trajectories so buildMessages() always has context.
        // Streaming path: use frontend-provided history + system prompt from initialContext.
        // Non-streaming path (empty initialContext): fall back to promptSeed.
        if (initialContext != null && !initialContext.isEmpty()) {
            try {
                int turn = 1;
                for (LlmMessage msg : initialContext) {
                    String role = msg.role() != null ? msg.role() : "user";
                    // Skip system messages — buildMessages() already constructs
                    // the system prompt from agent config. Persisting a second
                    // system message would cause duplicate system prompts.
                    if ("system".equals(role)) continue;
                    trajectoryRepository.save(SessionTrajectory.builder()
                            .runId(ctx.getRunId())
                            .turnNumber(turn++)
                            .role(role)
                            .content(msg.content())
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to persist initialContext trajectories: {}", e.getMessage());
            }
        } else if (run.getPromptSeed() != null && !run.getPromptSeed().isBlank()) {
            try {
                trajectoryRepository.save(SessionTrajectory.builder()
                        .runId(ctx.getRunId())
                        .turnNumber(1)
                        .role("user")
                        .content(run.getPromptSeed())
                        .build());
            } catch (Exception e) {
                log.warn("Failed to persist promptSeed trajectory: {}", e.getMessage());
            }
        }

        try {
            while (!ctx.isCancelled() && ctx.getIterationCount() < ctx.getMaxIterations()) {
                // Check pause
                if (ctx.isPaused()) {
                    ctx.awaitResume();
                    if (ctx.isCancelled()) break;
                }

                // Check circuit breaker
                circuitBreaker.check(ctx);

                // Execute one iteration
                boolean shouldContinue = executeIteration(ctx, emitter);

                if (!shouldContinue) {
                    log.info("Run loop ending naturally: runId={}, iterations={}", ctx.getRunId(), ctx.getIterationCount());
                    break;
                }

                // Persist session every 5 iterations
                if (ctx.getIterationCount() % 5 == 0) {
                    sessionStateManager.persistSession(ctx.getRunId());
                }
            }

            // Budget exhaustion: if loop stopped because of iteration limit, get honest summary
            if (ctx.getIterationCount() >= ctx.getMaxIterations() && !ctx.isCancelled()) {
                log.info("Budget exhausted for runId={} — requesting honest summary", ctx.getRunId());
                try {
                    List<LlmMessage> summaryMessages = new ArrayList<>(buildMessages(ctx));
                    summaryMessages.add(LlmMessage.system(
                            "You ran out of tool-call budget. List ONLY what was actually done "
                                    + "based on tool results above. Clearly state what could NOT be completed."));
                    AdkProvider summaryProvider = adkProviderRegistry.resolve(ctx.getAgent());
                    LlmResponse summaryResponse = summaryProvider.call(
                            ctx.getAgentId(), summaryMessages, List.of()); // no tools
                    ctx.addTokensUsed(summaryResponse.inputTokens(), summaryResponse.outputTokens());
                    if (summaryResponse.content() != null && !summaryResponse.content().isBlank()) {
                        ctx.setLastAssistantResponse(summaryResponse.content());
                    }
                } catch (Exception e) {
                    log.warn("Budget summary call failed for runId={}: {}", ctx.getRunId(), e.getMessage());
                }
            }

            // Complete the run
            // SSE: emit final message and done before cleanup
            String finalOutput = ctx.getLastAssistantResponse();
            if (finalOutput != null && !finalOutput.isBlank()) {
                tryEmit(emitter, "message", Map.of("content", finalOutput));
            }
            tryEmit(emitter, "done", donePayload(ctx, null));
            RunStatus finalStatus = ctx.isCancelled() ? RunStatus.CANCELLED : RunStatus.COMPLETED;
            completeRun(ctx, finalStatus);
        } catch (BudgetExceededException e) {
            log.error("Circuit breaker tripped for run {}: {}", ctx.getRunId(), e.getMessage());
            ctx.addError(e.getMessage());
            tryEmit(emitter, "done", donePayload(ctx, e.getMessage()));
            tryEmit(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "Budget exceeded"));
            completeRun(ctx, RunStatus.FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Run interrupted: runId={}", ctx.getRunId());
            tryEmit(emitter, "done", donePayload(ctx, "Run interrupted"));
            tryEmit(emitter, "error", Map.of("message", "Run interrupted"));
            completeRun(ctx, RunStatus.CANCELLED);
        } catch (Exception e) {
            log.error("Run loop error: runId={}", ctx.getRunId(), e);
            ctx.addError(e.getMessage());
            String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            tryEmit(emitter, "done", donePayload(ctx, errMsg));
            tryEmit(emitter, "error", Map.of("message", errMsg));
            completeRun(ctx, RunStatus.FAILED);
        }
    }

    /**
     * Execute a single iteration of the agent loop.
     * @return true if the loop should continue, false if it should stop
     */
    private boolean executeIteration(RunContext ctx, @Nullable SseEmitter emitter) {
        ctx.incrementIteration();
        int iteration = ctx.getIterationCount();
        log.info("Iteration {} starting: runId={}", iteration, ctx.getRunId());

        try {
            // Build messages from session history
            List<LlmMessage> messages = buildMessages(ctx);

            // Call LLM via the resolved ADK provider
            AdkProvider adkProvider = adkProviderRegistry.resolve(ctx.getAgent());

            // Resolve tools for this agent
            List<Map<String, Object>> toolsPayload = List.of();
            try {
                List<ToolDefinition> agentTools = agentToolResolver.resolveForAgent(ctx.getAgent());
                if (!agentTools.isEmpty()) {
                    toolsPayload = toolRegistry.buildToolsPayloadForIds(
                            agentTools.stream().map(ToolDefinition::getId).toList());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve tools for agent {}: {}", ctx.getAgentId(), e.getMessage());
            }

            // Anti-hallucination guard: when agent has no tools, prevent it from claiming file access
            if (toolsPayload.isEmpty()) {
                messages.add(LlmMessage.system(
                        "You have NO tools available. You MUST NOT claim to have read, written, or executed any file. "
                        + "Only provide analysis and recommendations based on information in this conversation. "
                        + "If asked to read a file, state that you cannot access files and suggest the operator do it."));
            }

            LlmResponse response = adkProvider.call(ctx.getAgentId(), messages, toolsPayload);

            // Update token tracking
            ctx.addTokensUsed(response.inputTokens(), response.outputTokens());

            // Record prompt call
            recordPromptCall(ctx, response);

            // Precompute turn number once
            int baseTurn = trajectoryRepository.findMaxTurnNumberByRunId(ctx.getRunId()) + 1;

            // Parse actions from response
            List<Action> actions = adkProvider.parseActionsFromResponse(response);

            // Hallucination guard: if no tool calls but prompt matches action verbs, retry once with nudge
            if (actions.isEmpty() && looksLikeActionRequest(ctx)) {
                log.info("Hallucination guard triggered — injecting nudge for runId={}", ctx.getRunId());
                List<LlmMessage> retryMessages = new ArrayList<>(messages);
                retryMessages.add(LlmMessage.system(
                        "IMPORTANT: Use the available tools to fulfill this request. "
                                + "Do not just describe — actually call the tools."));
                LlmResponse retryResponse = adkProvider.call(ctx.getAgentId(), retryMessages, toolsPayload);
                ctx.addTokensUsed(retryResponse.inputTokens(), retryResponse.outputTokens());
                actions = adkProvider.parseActionsFromResponse(retryResponse);
                if (retryResponse.content() != null && !retryResponse.content().isBlank()) {
                    response = retryResponse;
                }
            }

            // Record the authoritative trajectory AFTER the guard decision
            recordTrajectory(ctx, response, baseTurn);

            // Capture the assistant's response as the latest output
            if (response.content() != null && !response.content().isBlank()) {
                ctx.setLastAssistantResponse(response.content());
            }

            // Execute actions through pipeline
            List<ActionResult> results = new ArrayList<>();
            for (Action action : actions) {
                // 1. Create ToolCall entity with status=PENDING.
                // ApprovalGate will update the status to EXECUTING or DENIED.
                ToolCall toolCall = ToolCall.builder()
                        .runId(ctx.getRunId())
                        .toolName(action.name())
                        .arguments(action.arguments())
                        .status(ToolCallStatus.PENDING)
                        .build();
                toolCall = toolCallRepository.save(toolCall);
                ctx.setCurrentToolCallId(toolCall.getId());

                // SSE: notify client which tool is being executed
                tryEmit(emitter, "tool_call", Map.of(
                        "name", action.name(),
                        "status", "executing"
                ));

                // 2. Execute through pipeline (may block on approval)
                long start = System.currentTimeMillis();
                ActionResult result = actionPipeline.execute(action, ctx);
                long end = System.currentTimeMillis();

                // Reload tool call from DB — ApprovalGate may have updated status to DENIED
                toolCall = toolCallRepository.findById(toolCall.getId()).orElse(toolCall);

                // PENDING means approval was not required or was auto-approved
                if (toolCall.getStatus() == ToolCallStatus.PENDING) {
                    if (result.status() == ActionResult.Status.BLOCKED) {
                        // Blocked by rule verification or AI safety before execution
                        toolCall.setResult(result.error());
                        toolCall.setStatus(ToolCallStatus.FAILED);
                    } else {
                        // Auto-approved — no approval required, proceed to execution
                        toolCall.setStatus(ToolCallStatus.EXECUTING);
                    }
                }
                // EXECUTING means approved and now being executed
                if (toolCall.getStatus() == ToolCallStatus.EXECUTING) {
                    if (result.status() == ActionResult.Status.SUCCESS) {
                        toolCall.setResult(result.output());
                        toolCall.setStatus(ToolCallStatus.COMPLETED);
                    } else if (result.status() == ActionResult.Status.FAILED) {
                        toolCall.setResult("ERROR: " + (result.error() != null ? result.error() : "Unknown error"));
                        toolCall.setStatus(ToolCallStatus.FAILED);
                    }
                }
                // DENIED status is set by ApprovalGate — leave unchanged
                toolCall.setLatencyMs((int)(end - start));
                toolCallRepository.save(toolCall);

                // SSE: notify client of tool result
                String toolResultPreview = result.output() != null ? result.output() : (result.error() != null ? "Error: " + result.error() : "");
                tryEmit(emitter, "tool_result", Map.of(
                        "name", action.name(),
                        "result", truncateForSse(toolResultPreview, 200)
                ));

                results.add(result);

                if (result.status() == ActionResult.Status.FAILED) {
                    ctx.addError("Action " + action.name() + " failed: " + result.error());
                }
            }

            // Consecutive same-error early termination: prevents infinite retry loops
            // (e.g., "File not found" repeated 15 times wasting 100K+ tokens)
            boolean allSameError = !results.isEmpty() && results.stream()
                    .allMatch(r -> r.status() == ActionResult.Status.FAILED);
            if (allSameError) {
                String firstError = results.get(0).error() != null ? results.get(0).error() : "";
                boolean allIdentical = results.stream()
                        .allMatch(r -> firstError.equals(r.error() != null ? r.error() : ""));
                if (allIdentical && !firstError.isEmpty()) {
                    if (firstError.equals(ctx.getLastToolError())) {
                        ctx.setConsecutiveSameErrorCount(ctx.getConsecutiveSameErrorCount() + 1);
                    } else {
                        ctx.setLastToolError(firstError);
                        ctx.setConsecutiveSameErrorCount(1);
                    }
                    if (ctx.getConsecutiveSameErrorCount() >= 3) {
                        log.warn("Run {} hit 3 consecutive identical tool errors — terminating early: {}",
                                ctx.getRunId(), firstError);
                        ctx.setLastAssistantResponse("Stopped: repeated tool failure — " + firstError
                                + "\nPlease check the configuration or provide the correct path.");
                        return false; // exit the iteration loop
                    }
                }
            } else {
                ctx.setLastToolError(null);
                ctx.setConsecutiveSameErrorCount(0);
            }

            // Update session
            sessionStateManager.updateSession(ctx.getRunId(), response, results);

            // Publish iteration event for live dashboard updates
            try {
                // Cache skill names once per run (skills don't change mid-execution)
                if (ctx.getCachedSkillNames() == null) {
                    try {
                        ctx.setCachedSkillNames(skillProvider.getEnabledSkillsForAgent(
                                ctx.getAgentId().toString()).stream().map(SkillContext::name).toList());
                    } catch (Exception e) {
                        ctx.setCachedSkillNames(List.of());
                    }
                }

                // Build tool call details from this iteration's actions and results
                List<RunIterationEvent.ToolCallDetail> details = new ArrayList<>();
                for (int i = 0; i < actions.size(); i++) {
                    ActionResult r = i < results.size() ? results.get(i) : null;
                    String resultPreview = r != null
                            ? (r.status() == ActionResult.Status.SUCCESS
                                    ? (r.output() != null ? truncateForSse(r.output(), 200) : "")
                                    : "Error: " + (r.error() != null ? r.error() : "unknown"))
                            : "";
                    details.add(new RunIterationEvent.ToolCallDetail(
                            actions.get(i).name(), actions.get(i).arguments(), resultPreview));
                }

                eventPublisher.publishEvent(new RunIterationEvent(
                        this, ctx.getRunId(), ctx.getAgentId(),
                        ctx.getIterationCount(), ctx.getMaxIterations(),
                        response.content(), details, ctx.getCachedSkillNames()));
            } catch (Exception e) {
                log.warn("Failed to publish iteration event: {}", e.getMessage());
            }

            // If no tool_calls and just text content, the agent is responding with chat
            // Continue the loop only if we got tool calls (agent wants to do more)
            // or if the finish reason is not "stop"
            if (!response.hasToolCalls()) {
                log.info("No tool calls in response — agent returned final answer. Ending loop.");
                return false;
            }

            // Record tool results as trajectory entries for the LLM feedback loop
            int toolResultBaseTurn = baseTurn + 1;
            recordToolResults(ctx, actions, results, toolResultBaseTurn);
            log.debug("Iteration {} complete: {} actions processed", iteration, actions.size());
            return true;

        } catch (BudgetExceededException e) {
            throw e; // let the loop handler deal with it
        } catch (Exception e) {
            log.error("Iteration {} failed: runId={}", iteration, ctx.getRunId(), e);
            ctx.addError("Iteration " + iteration + " failed: " + e.getMessage());
            throw e;
        }
    }

    // ---- Helper methods ----

    private List<LlmMessage> buildMessages(RunContext ctx) {
        List<LlmMessage> messages = new ArrayList<>();

        // System message: prefer agent.config.systemPrompt, fall back to agent.role
        StringBuilder systemPrompt = new StringBuilder();
        String systemPromptFromConfig = getSystemPromptFromConfig(ctx.getAgent());
        if (systemPromptFromConfig != null && !systemPromptFromConfig.isBlank()) {
            systemPrompt.append(systemPromptFromConfig).append("\n\n");
        } else if (ctx.getAgent().getRole() != null) {
            systemPrompt.append(ctx.getAgent().getRole()).append("\n\n");
        }

        // Inject approved knowledge context (was Aria-only, now all agents).
        // Format lives in one place: KnowledgeContextProvider.buildKnowledgeContextPrompt.
        try {
            String knowledgePrompt = knowledgeProvider.buildKnowledgeContextPrompt(5);
            if (!knowledgePrompt.isBlank()) {
                systemPrompt.append(knowledgePrompt).append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to inject knowledge context: {}", e.getMessage());
        }

        // Inject enabled SKILL-stage skills assigned to this agent (resolves #56 skills orphan).
        try {
            List<SkillContext> skills = skillProvider.getEnabledSkillsForAgent(ctx.getAgentId().toString());
            if (skills != null && !skills.isEmpty()) {
                systemPrompt.append("## Skills\n");
                for (SkillContext s : skills) {
                    systemPrompt.append("- **").append(s.name()).append("**: ")
                            .append(s.template()).append("\n");
                }
                systemPrompt.append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to inject skills for agent {}: {}", ctx.getAgentId(), e.getMessage());
        }

        if (!systemPrompt.isEmpty()) {
            messages.add(LlmMessage.system(systemPrompt.toString()));
        }

        // Load trajectory history for context
        List<SessionTrajectory> history = trajectoryRepository
                .findByRunIdOrderByTurnNumberAsc(ctx.getRunId());

        for (SessionTrajectory t : history) {
            if ("system".equals(t.getRole())) {
                messages.add(LlmMessage.system(t.getContent()));
            } else if ("user".equals(t.getRole())) {
                messages.add(LlmMessage.user(t.getContent()));
            } else if ("assistant".equals(t.getRole())) {
                // Reconstruct tool_calls from stored JSON for multi-iteration DeepSeek compatibility
                // (see recordTrajectory for the JSON storage format)
                List<LlmToolCall> tcs = parseTrajectoryToolCalls(t.getToolCalls());
                messages.add(tcs != null && !tcs.isEmpty()
                        ? LlmMessage.assistant(t.getContent(), tcs)
                        : LlmMessage.assistant(t.getContent()));
            } else if ("tool".equals(t.getRole())) {
                messages.add(LlmMessage.tool(t.getContent(), t.getToolCallId()));
            }
        }

        // If no history, add the prompt seed from the run
        if (history.isEmpty()) {
            Run run = runRepository.findById(ctx.getRunId()).orElse(null);
            if (run != null && run.getPromptSeed() != null) {
                messages.add(LlmMessage.user(run.getPromptSeed()));
            }
        }

        return messages;
    }

    private void recordPromptCall(RunContext ctx, LlmResponse response) {
        try {
            PromptCall promptCall = PromptCall.builder()
                    .runId(ctx.getRunId())
                    .agentId(ctx.getAgentId())
                    .provider(ctx.getAgent().getProvider())
                    .model(ctx.getAgent().getModel())
                    .inputTokens(response.inputTokens())
                    .outputTokens(response.outputTokens())
                    .latencyMs(0) // TODO: measure actual latency
                    .build();
            promptCallRepository.save(promptCall);
        } catch (Exception e) {
            log.warn("Failed to record prompt call: {}", e.getMessage());
        }
    }

    private void recordTrajectory(RunContext ctx, LlmResponse response, int turnNumber) {
        try {
            // Record only the assistant response — user messages are already persisted
            // by the inject endpoint or the initial promptSeed seeding in executeRunLoop().
            // Store tool_calls as JSON array of {id, name, arguments} so they can be
            // reconstructed in buildMessages() for multi-iteration DeepSeek compatibility.
            String toolCallsStr = response.hasToolCalls()
                    ? buildToolCallsJson(response.toolCalls())
                    : null;

            SessionTrajectory assistantTrajectory = SessionTrajectory.builder()
                    .runId(ctx.getRunId())
                    .turnNumber(turnNumber)
                    .role("assistant")
                    .content(response.content() != null ? response.content() : "")
                    .toolCalls(toolCallsStr)
                    .outputTokens(response.outputTokens())
                    .build();

            trajectoryRepository.save(assistantTrajectory);
        } catch (Exception e) {
            log.warn("Failed to record trajectory: {}", e.getMessage());
        }
    }

    /**
     * Serialize a list of LlmToolCall into a JSON array string.
     * Uses character-by-character escaping to handle all JSON control characters.
     */
    static String buildToolCallsJson(List<LlmToolCall> toolCalls) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var tc : toolCalls) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"id\":\"").append(escapeJson(tc.id())).append("\",");
            sb.append("\"name\":\"").append(escapeJson(tc.name())).append("\",");
            sb.append("\"arguments\":").append(quoteJsonString(
                    tc.arguments() != null ? tc.arguments() : "{}")).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    static String quoteJsonString(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    /**
     * Parse tool_calls JSON from a SessionTrajectory entry back into LlmToolCall objects.
     * Returns empty list if the JSON is null, blank, or unparseable.
     */
    static List<LlmToolCall> parseTrajectoryToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode arr = mapper.readTree(toolCallsJson);
            if (!arr.isArray()) return List.of();
            List<LlmToolCall> result = new ArrayList<>();
            for (JsonNode node : arr) {
                result.add(new LlmToolCall(
                        node.has("id") ? node.get("id").asText() : "",
                        node.has("name") ? node.get("name").asText() : "",
                        node.has("arguments") ? node.get("arguments").asText() : "{}"
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse trajectory tool_calls JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private void recordToolResults(RunContext ctx, List<Action> actions, List<ActionResult> results, int baseTurn) {
        try {
            List<SessionTrajectory> toolTrajectories = new ArrayList<>();
            for (int i = 0; i < actions.size(); i++) {
                String toolCallId = actions.get(i).toolCallId();
                if (toolCallId == null || toolCallId.isBlank()) {
                    log.warn("Skipping tool result with null/blank toolCallId: action={}", actions.get(i).name());
                    continue;
                }
                ActionResult result = results.get(i);
                String content = result.status() == ActionResult.Status.SUCCESS
                        ? (result.output() != null ? result.output() : "")
                        : "ERROR: " + (result.error() != null ? result.error() : "Unknown error");
                toolTrajectories.add(SessionTrajectory.builder()
                        .runId(ctx.getRunId())
                        .turnNumber(baseTurn + i)
                        .role("tool")
                        .content(content)
                        .toolCallId(toolCallId)
                        .build());
            }
            if (!toolTrajectories.isEmpty()) {
                trajectoryRepository.saveAll(toolTrajectories);
            }
        } catch (Exception e) {
            log.warn("Failed to record tool results: {}", e.getMessage());
        }
    }

    /**
     * Completes the run — updates DB entities and clears state.
     * Note: @Transactional omitted because this is a self-invocation from within the same class.
     * Each repository.save() call runs in its own transaction, which is acceptable here.
     */
    protected void completeRun(RunContext ctx, RunStatus finalStatus) {
        log.info("Completing run: runId={}, status={}, iterations={}, tokens={}",
                ctx.getRunId(), finalStatus, ctx.getIterationCount(), ctx.getTotalTokensUsed());

        // Tracks whether an external actor (e.g. RunService.cancelRun) already moved this run to a
        // terminal state AND published its RunCompletedEvent. In that case we must neither overwrite
        // the status nor publish a second (duplicate / contradictory) event.
        java.util.concurrent.atomic.AtomicBoolean alreadyTerminatedExternally = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Persist the Run entity FIRST — this is the authoritative status/output read by callers
        // (AriaService, dashboard). Doing it before session bookkeeping ensures a session-persistence
        // hiccup can never leave the run stuck in RUNNING.
        try {
            runRepository.findById(ctx.getRunId()).ifPresent(run -> {
                // Guard: do not overwrite a terminal state already set externally (e.g. CANCELLED by RunService)
                if (run.getStatus() == RunStatus.CANCELLED && finalStatus != RunStatus.CANCELLED) {
                    log.info("Run {} already CANCELLED externally, skipping overwrite with {}", ctx.getRunId(), finalStatus);
                    alreadyTerminatedExternally.set(true);
                    return;
                }
                // Guard: if the run was already CANCELLED (REST cancel path), RunService has already
                // published the CANCELLED event — persist nothing and publish nothing to avoid a duplicate.
                if (run.getStatus() == RunStatus.CANCELLED) {
                    log.info("Run {} already CANCELLED externally, skipping duplicate completion event", ctx.getRunId());
                    alreadyTerminatedExternally.set(true);
                    return;
                }
                run.setStatus(finalStatus);
                run.setIterationCount(ctx.getIterationCount());
                run.setTotalTokensUsed(ctx.getTotalTokensUsed());
                run.setCompletedAt(Instant.now());
                if (!ctx.getErrors().isEmpty()) {
                    run.setErrorMessage(String.join("; ", ctx.getErrors()));
                }
                // Store the final output (last assistant response)
                if (ctx.getLastAssistantResponse() != null) {
                    run.setFinalOutput(ctx.getLastAssistantResponse());
                }
                runRepository.save(run);
            });
        } catch (Exception e) {
            log.error("Error persisting final run state for {}: {}", ctx.getRunId(), e.getMessage(), e);
        }

        // Session bookkeeping — isolated so an optimistic-lock hiccup cannot abort run completion.
        try {
            SessionStatus sessionStatus = switch (finalStatus) {
                case COMPLETED -> SessionStatus.COMPLETED;
                case FAILED -> SessionStatus.FAILED;
                case CANCELLED -> SessionStatus.CANCELLED;
                default -> SessionStatus.FAILED;
            };
            sessionStateManager.updateSessionStatus(ctx.getRunId(), sessionStatus);
            sessionStateManager.clearSession(ctx.getRunId());
        } catch (Exception e) {
            log.warn("Session finalization failed for {} (non-fatal): {}", ctx.getRunId(), e.getMessage());
        }

        // Publish run completed event for live dashboard updates — skip if an external actor already did.
        if (!alreadyTerminatedExternally.get()) {
            try {
                eventPublisher.publishEvent(new RunCompletedEvent(
                        this, ctx.getRunId(), ctx.getAgentId(), finalStatus, ctx.getLastAssistantResponse()));
            } catch (Exception e) {
                log.warn("Failed to publish run completed event: {}", e.getMessage());
            }
        }

        // Cancel any pending approvals
        try {
            approvalGate.cancelAllPendingForRun(ctx.getRunId());
        } catch (Exception e) {
            log.warn("Failed to cancel pending approvals for {}: {}", ctx.getRunId(), e.getMessage());
        }

        activeContexts.remove(ctx.getRunId());
    }

    private void handleRunFailure(UUID runId, String errorMessage) {
        RunContext ctx = activeContexts.remove(runId);
        if (ctx != null) {
            ctx.addError(errorMessage);
            completeRun(ctx, RunStatus.FAILED);
        } else {
            // No context — try to update run directly
            runRepository.findById(runId).ifPresent(run -> {
                run.setStatus(RunStatus.FAILED);
                run.setErrorMessage(errorMessage);
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            });
        }
    }

    public static int parseMaxIterationsFromConfig(Agent agent, int runMaxIterations) {
        String config = agent.getConfig();
        if (config == null || config.isBlank()) {
            return runMaxIterations > 0 ? runMaxIterations : 50;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> configMap = mapper.readValue(config, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object maxToolCallRounds = configMap.get("maxToolCallRounds");
            if (maxToolCallRounds instanceof Number num) {
                int configValue = num.intValue();
                if (configValue <= 0) {
                    log.warn("Agent {} config.maxToolCallRounds <= 0, using run-level {}", agent.getId(), runMaxIterations);
                    return runMaxIterations > 0 ? runMaxIterations : 50;
                }
                // Run-level maxIterations is a hard cap over agent config
                if (runMaxIterations > 0) {
                    return Math.min(configValue, runMaxIterations);
                }
                return configValue;
            }
        } catch (Exception e) {
            log.warn("Failed to parse Agent.config for agent {}: {}", agent.getId(), e.getMessage());
        }
        return runMaxIterations > 0 ? runMaxIterations : 50;
    }

    private String getSystemPromptFromConfig(Agent agent) {
        String config = agent.getConfig();
        if (config == null || config.isBlank()) return null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> configMap = mapper.readValue(config,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object sp = configMap.get("systemPrompt");
            if (sp instanceof String s && !s.isBlank()) return s;
        } catch (Exception e) {
            log.debug("No systemPrompt in agent {} config", agent.getId());
        }
        return null;
    }

    private void updateRunStatus(Run run, RunStatus status) {
        run.setStatus(status);
        runRepository.save(run);
    }

    private void updateRunStatusDirect(UUID runId, RunStatus status) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            runRepository.save(run);
        });
    }

    // ---- SSE helpers —
    
    private static final ObjectMapper SSE_MAPPER = new ObjectMapper();
    
    /**
     * Builds the standard done event payload with runId, conversationId, and intent.
     */
    private Map<String, Object> donePayload(RunContext ctx, @Nullable String error) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", ctx.getRunId().toString());
        payload.put("conversationId", ctx.getConversationId() != null ? ctx.getConversationId() : "");
        payload.put("intent", ctx.getIntent() != null ? ctx.getIntent() : "");
        if (error != null) {
            payload.put("error", error);
        }
        return payload;
    }
    
    /**
     * Safely emit an SSE event, silently dropping if emitter is null or client disconnected.
     */
    private void tryEmit(@Nullable SseEmitter emitter, String eventName, Map<String, Object> payload) {
        if (emitter == null) return;
        try {
            String json = SSE_MAPPER.writeValueAsString(payload != null ? payload : Map.of());
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.debug("SSE client disconnected (event {}): {}", eventName, e.getMessage());
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed (event {}): {}", eventName, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to emit SSE event {}: {}", eventName, e.getMessage());
        }
    }

    private String truncateForSse(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    /**
     * Heuristic: does the user's prompt contain action verbs suggesting they expect tool execution?
     */
    private boolean looksLikeActionRequest(RunContext ctx) {
        Run run = runRepository.findById(ctx.getRunId()).orElse(null);
        if (run == null || run.getPromptSeed() == null) return false;
        return ACTION_PATTERN.matcher(run.getPromptSeed().toLowerCase()).find();
    }
}
