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
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.RunAdmissionQueue;
import io.aria.conductor.execution.runtime.RunRuntimeRegistry;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmToolCall;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.tool.AgentSkillResolver;
import io.aria.conductor.execution.tool.WorkspaceManager;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Run engine — starts a run on its run-owned core (through the coordinator and
 * launcher) and keeps the engine-level business transitions: run status,
 * trajectory, SSE delivery and run-end approval finalization.
 *
 * <p>Uses virtual threads so blocking on the core does not pin OS threads.
 */
@Slf4j
@Component
public class AgentLoopEngine {


    private final RunRepository runRepository;
    private final AgentRepository agentRepository;
    private final SessionStateManager sessionStateManager;
    private final ApprovalGate approvalGate;
    private final PermissionCoordinator permissionCoordinator;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkflowService workflowService;
    private final WorkflowChainRepository workflowChainRepository;
    private final AgentSkillResolver agentSkillResolver;
    private final KnowledgeContextProvider knowledgeProvider;
    private final WorkspaceManager workspaceManager;
    private final ApprovalRepository approvalRepository;
    /**
     * The run coordinator and launcher of the cutover wiring: every run is
     * executed by a run-owned core, so both are required in production. Null
     * only in unit tests that exercise the engine without the core wiring.
     */
    private final ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    private final ObjectProvider<CoreRunLauncher> coreRunLauncherProvider;
    /**
     * The run admission gate of the cutover wiring: a launcher-owned run must
     * hold an admission slot before it leaves PENDING, so over-limit runs park
     * in the queue (card in TODO) until a slot frees.
     */
    private final RunAdmissionQueue admission;
    private final DoDService dodService;

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, RunContext> activeContexts = new ConcurrentHashMap<>();

    public AgentLoopEngine(RunRepository runRepository,
                           AgentRepository agentRepository,
                           SessionStateManager sessionStateManager,
                           ApprovalGate approvalGate,
                           PermissionCoordinator permissionCoordinator,
                           SessionTrajectoryRepository trajectoryRepository,
                           ApplicationEventPublisher eventPublisher,
                           WorkflowService workflowService,
                           WorkflowChainRepository workflowChainRepository,
                           AgentSkillResolver agentSkillResolver,
                           KnowledgeContextProvider knowledgeProvider,
                           WorkspaceManager workspaceManager,
                           ApprovalRepository approvalRepository,
                           ObjectProvider<CoreExecutionService> coreExecutionServiceProvider,
                           DoDService dodService,
                           ObjectProvider<CoreRunLauncher> coreRunLauncherProvider,
                           RunAdmissionQueue admission) {
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.sessionStateManager = sessionStateManager;
        this.approvalGate = approvalGate;
        this.permissionCoordinator = permissionCoordinator;
        this.trajectoryRepository = trajectoryRepository;
        this.eventPublisher = eventPublisher;
        this.workflowService = workflowService;
        this.workflowChainRepository = workflowChainRepository;
        this.agentSkillResolver = agentSkillResolver;
        this.knowledgeProvider = knowledgeProvider;
        this.workspaceManager = workspaceManager;
        this.approvalRepository = approvalRepository;
        this.coreExecutionServiceProvider = coreExecutionServiceProvider;
        this.coreRunLauncherProvider = coreRunLauncherProvider;
        this.admission = admission;
        this.dodService = dodService;
    }

    /**
     * Start a run — loads the agent, creates the session and dispatches the
     * run-owned core execution on a virtual thread.
     */
    public void startRun(UUID runId) {
        startRunInternal(runId, null, List.of());
    }

    /**
     * Start a run with pre-loaded conversation history.
     * Used by AriaService to restore multi-turn context from prior runs.
     *
     * @param runId          the Run UUID (pre-saved with conversationId set)
     * @param initialContext the conversation turns that PRECEDE this run (the current
     *                       request is the run's own prompt seed); merged into every
     *                       message assembly, never persisted as this run's rows
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
     * @param initialContext  the conversation turns that PRECEDE this run (client
     *                        history, current request excluded); merged into every
     *                        message assembly, never persisted as this run's rows
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

        // Run-owned cores pass the admission gate while the run is still PENDING:
        // over-limit runs park here (card in TODO) until a slot frees; the slot
        // then starts the run and only now does the mirror card move IN_PROGRESS.
        CoreRunLauncher coreLauncher = runCoreLauncher();
        if (coreLauncher != null && coreLauncher.owns(agent)) {
            admission.acquire(run.getId(), run.getAgentId(), run.getCreatedAt());
            // The cancel can land before this waiter was even parked: settle()
            // no-ops, then admission grants the slot. Re-read after admission —
            // a run that left PENDING while queued must give its slot back and
            // never start (the stale INITIALIZING write would resurrect it).
            RunStatus afterAdmission = runRepository.findById(run.getId())
                    .map(Run::getStatus).orElse(null);
            if (afterAdmission != RunStatus.PENDING && afterAdmission != RunStatus.PAUSED) {
                admission.settle(run.getId());
                throw new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                        "Run " + run.getId() + " left PENDING while queued (" + afterAdmission + "); start aborted");
            }
            eventPublisher.publishEvent(new RunIterationEvent(this, run.getId(), run.getAgentId(),
                    1, run.getMaxIterations()));
        }

        // Update run status to INITIALIZING
        updateRunStatus(run, RunStatus.INITIALIZING);

        // Create/load session
        sessionStateManager.loadOrCreateSession(runId, agent.getId());

        RunContext ctx = new RunContext(runId, agent.getId(), agent, run.getConversationId());
        if (intent != null) {
            ctx.setIntent(intent);
        }
        activeContexts.put(runId, ctx);

        // Launch loop on virtual thread
        virtualThreadExecutor.execute(() -> {
            try {
                dispatchRun(ctx, run, emitter, initialContext);
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
     * Pause a running run. When the run's runtime is owned by the run
     * coordinator, the pause is requested and verified through it first: the run
     * is marked PAUSED only after the run-owned core confirmed the pause, and a
     * refusal surfaces the exact reason instead of a state the runtime never
     * reached.
     */
    public void pauseRun(UUID runId) {
        RunContext ctx = activeContexts.get(runId);
        if (ctx == null) {
            log.warn("Cannot pause run — no active context: runId={}", runId);
            return;
        }
        CoreExecutionService coordinator = runCoordinator();
        if (coordinator != null && coordinator.activeRuns(ctx.getAgentId()).contains(runId)) {
            ControlAck ack = awaitControl(coordinator.pause(runId), runId, "pause");
            if (ack.state() != ControlState.PAUSED || !ack.verified()) {
                throw new IllegalStateException("Run " + runId + " was not paused: "
                        + coordinator.pendingControl(runId)
                                .map(RunRuntimeRegistry.ControlRequest::failureReason)
                                .orElse("the run-owned core did not verify the pause"));
            }
        }
        updateRunStatusDirect(runId, RunStatus.PAUSED);
        sessionStateManager.updateSessionStatus(runId, SessionStatus.PAUSED);
        log.info("Run paused: runId={}", runId);
    }

    /**
     * Resume a paused run. For a coordinator-owned run the resume is verified on
     * the same run-owned handle/session, which also releases the manual hold on
     * permission delivery (a decision received while paused is delivered only
     * after that re-validation).
     */
    public void resumeRun(UUID runId) {
        RunContext ctx = activeContexts.get(runId);
        if (ctx == null) {
            log.warn("Cannot resume run — no active context: runId={}", runId);
            return;
        }
        // #28: never let a raw resume bypass a pending human approval gate. The legitimate unblock
        // path is ApprovalGate.decideApproval; a pending approval must be decided, not resumed past.
        if (hasPendingApproval(runId)) {
            log.warn("Refusing to resume run {} — a human approval is still pending; decide it instead", runId);
            throw new IllegalStateException(
                    "Run " + runId + " is waiting for human approval; decide the approval instead of resuming.");
        }
        CoreExecutionService coordinator = runCoordinator();
        if (coordinator != null && coordinator.activeRuns(ctx.getAgentId()).contains(runId)) {
            ControlAck ack = awaitControl(coordinator.resume(runId), runId, "resume");
            if (ack.state() != ControlState.RUNNING || !ack.verified()) {
                throw new IllegalStateException("Run " + runId + " was not resumed: "
                        + coordinator.pendingControl(runId)
                                .map(RunRuntimeRegistry.ControlRequest::failureReason)
                                .orElse("the run-owned core did not verify the resume"));
            }
        }
        updateRunStatusDirect(runId, RunStatus.RUNNING);
        sessionStateManager.updateSessionStatus(runId, SessionStatus.ACTIVE);
        log.info("Run resumed: runId={}", runId);
    }

    /** The run coordinator of the cutover wiring, or null when none is deployed (legacy paths). */
    @Nullable
    private CoreExecutionService runCoordinator() {
        return coreExecutionServiceProvider == null ? null : coreExecutionServiceProvider.getIfAvailable();
    }

    /** The production core launcher, or null in unit tests without the cutover wiring. */
    @Nullable
    private CoreRunLauncher runCoreLauncher() {
        return coreRunLauncherProvider == null ? null : coreRunLauncherProvider.getIfAvailable();
    }


    /**
     * Drives one run on a registered production core through the cutover
     * launcher: freeze the immutable binding (first attempt) and execute the
     * run-owned attempt through the coordinator, then keep the engine's own
     * business transitions (tokens, trajectory, SSE, run status).
     */
    private void executeCoreRun(RunContext ctx, CoreRunLauncher launcher, @Nullable SseEmitter emitter) {
        Agent agent = ctx.getAgent();
        log.info("Coordinated core run: runId={}, agent={}", ctx.getRunId(), agent.getId());
        tryEmit(emitter, "thinking", Map.of("status", "processing", "mode", "core",
                "runId", ctx.getRunId().toString()));
        try {
            Run run = runRepository.findById(ctx.getRunId())
                    .orElseThrow(() -> new ResourceNotFoundException("Run", ctx.getRunId()));
            // A cancel can land after the dispatch gate while the runtime is still
            // unregistered (owns() == false), so the recorded CANCELLED would be
            // overwritten by a launch. Re-read the committed status here; a terminal
            // run is never launched.
            RunStatus committed = committedStatus(ctx.getRunId(), run);
            if (isTerminal(committed)) {
                log.info("Run {} is already terminal ({}); core launch skipped", ctx.getRunId(), committed);
                tryEmit(emitter, "done", donePayload(ctx, null));
                return;
            }
            CoreResult result = launcher.execute(run, agent, coreTask(ctx));
            recordUsage(ctx, result);
            ctx.incrementIteration();
            String finalOutput = result.finalOutput();
            if (finalOutput != null && !finalOutput.isBlank()) {
                ctx.setLastAssistantResponse(finalOutput);
                recordTaskTrajectory(ctx, finalOutput, tokenCount(result.usage() == null ? null
                        : result.usage().outputTokens()));
                tryEmit(emitter, "message", Map.of("content", finalOutput));
            }
            tryEmit(emitter, "done", donePayload(ctx, null));
            completeRun(ctx, result.cancelled() ? RunStatus.CANCELLED : RunStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Coordinated core run failed: runId={}", ctx.getRunId(), e);
            ctx.addError(e.getMessage());
            String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            tryEmit(emitter, "done", donePayload(ctx, errMsg));
            tryEmit(emitter, "error", Map.of("message", errMsg));
            completeRun(ctx, RunStatus.FAILED);
        }
    }

    private static void recordUsage(RunContext ctx, CoreResult result) {
        UsageSnapshot usage = result.usage();
        if (usage == null) {
            return;
        }
        ctx.addTokensUsed(tokenCount(usage.inputTokens()), tokenCount(usage.outputTokens()));
    }

    private static int tokenCount(Long value) {
        return value == null ? 0 : value.intValue();
    }

    /** The committed status (fresh SELECT), falling back to the caller's snapshot. */
    private RunStatus committedStatus(UUID runId, Run snapshot) {
        return runRepository.findStateById(runId)
                .map(RunRepository.RunStateView::getStatus)
                .orElse(snapshot.getStatus());
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED || status == RunStatus.ABORTED;
    }

    /** The accepted conversation as the core task: system material, history, current request. */
    private CoreTask coreTask(RunContext ctx) {
        StringBuilder system = new StringBuilder();
        List<LlmMessage> history = new ArrayList<>();
        String userPrompt = "";
        List<LlmMessage> messages = buildMessages(ctx);
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage message = messages.get(i);
            String role = message.role() == null ? "" : message.role();
            if ("system".equals(role)) {
                if (message.content() != null && !message.content().isBlank()) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(message.content());
                }
            } else if ("user".equals(role) && i == lastUserIndex(messages)) {
                userPrompt = message.content() == null ? "" : message.content();
            } else {
                history.add(message);
            }
        }
        return new CoreTask(system.toString(), List.copyOf(history), userPrompt);
    }

    private static int lastUserIndex(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    /** Waits (bounded) for a control acknowledgement; a missing one is a refused control, never a success. */
    private static ControlAck awaitControl(CompletionStage<ControlAck> stage, UUID runId, String action) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Run " + runId + " " + action + " was interrupted", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Run " + runId + " " + action
                    + " was not acknowledged within 30s; no state was claimed for it", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Run " + runId + " " + action + " failed: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e.getCause());
        }
    }

    /** True when the run has at least one PENDING run-gate approval (HITL gate not yet decided). */
    private boolean hasPendingApproval(UUID runId) {
        try {
            return approvalRepository.findByRunId(runId).stream()
                    .anyMatch(AgentLoopEngine::isBlockingGateApproval);
        } catch (Exception e) {
            log.debug("Could not check pending approvals for run {}: {}", runId, e.getMessage());
            return false;
        }
    }

    /**
     * A run gate is a PENDING approval that pauses the run loop. The kanban
     * REVIEW_REQUEST ask is a display-only HITL prompt created after run
     * completion — it must never block resuming.
     */
    static boolean isBlockingGateApproval(Approval a) {
        return a.getStatus() == ApprovalStatus.PENDING
                && a.getAskType() != Approval.AskType.REVIEW_REQUEST;
    }

    /**
     * Records the cancellation of a run whose operator cancel was verified and
     * persisted upstream: the run-owned core stop is verified by
     * {@code RunService.cancelRun} through {@code RunRuntimeControlPort} before
     * the {@code RunCompletedEvent} reaches this listener.
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
        updateRunStatusDirect(runId, RunStatus.CANCELLED);
        sessionStateManager.updateSessionStatus(runId, SessionStatus.CANCELLED);
        log.info("Run cancelled: runId={}", runId);
    }

    /** Check if a run has an active execution context (used by ZombieRunReaper). */
    public boolean hasActiveContext(UUID runId) {
        return activeContexts.containsKey(runId);
    }

    /**
     * Startup recovery: mark runs left in RUNNING or INITIALIZING by a previous backend
     * process (JVM crash/restart) as FAILED and publish {@link RunCompletedEvent} so
     * downstream listeners (workflow chainer, kanban, WS broadcast) reconcile instead of
     * leaving chains/boards stuck. Runs are saved individually so one failure cannot roll
     * back the recovery of the others.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        recoverOrphanedRuns();
    }

    /** Extracted so tests can invoke recovery without firing Spring lifecycle events. */
    void recoverOrphanedRuns() {
        List<Run> orphaned = runRepository.findByStatusIn(
                List.of(RunStatus.RUNNING, RunStatus.INITIALIZING));
        if (orphaned.isEmpty()) {
            return;
        }
        log.info("Recovering {} orphaned run(s) left by backend restart", orphaned.size());
        for (Run run : orphaned) {
            try {
                run.setStatus(RunStatus.FAILED);
                run.setErrorMessage("Run orphaned by backend restart");
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
                eventPublisher.publishEvent(new RunCompletedEvent(
                        this, run.getId(), run.getAgentId(), RunStatus.FAILED, null));
            } catch (Exception e) {
                log.error("Failed to recover orphaned run {}: {}", run.getId(), e.getMessage(), e);
            }
        }
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
                        .kind(s.getKind() != null ? s.getKind() : WorkflowStep.StepKind.GENERIC)
                        .build())
                .toList();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("yaml-workflow-" + Instant.now().toEpochMilli())
                .steps(stepDefs)
                .build();

        // Delegate to WorkflowService which handles chain creation + first step start.
        // WorkflowAutoChainer will advance subsequent steps when runs complete.
        WorkflowResponse response = workflowService.createAndStart(request);

        // SDD wiring for YAML-defined BA/DEV/QA chains (mirrors instantiateTemplate):
        // custom DoD stages. Per-run kanban items are created exclusively by
        // RunKanbanAutoCreator — no chain-level kanban item here.
        boolean isSdd = steps.stream().anyMatch(s ->
                s.getKind() == WorkflowStep.StepKind.BA
                || s.getKind() == WorkflowStep.StepKind.DEV
                || s.getKind() == WorkflowStep.StepKind.QA);
        if (isSdd) {
            dodService.init(response.getId().toString(), "SDD", List.of("dev", "qa"));
        }

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

            // SDD step kind (case-normalised; unknown/missing -> GENERIC).
            Object kindVal = raw.get("kind");
            step.setKind(parseStepKind(kindVal != null ? kindVal.toString() : null));

            step.setStatus(WorkflowStep.Status.PENDING);
            result.add(step);
        }
        return result;
    }

    /** Parse a step kind with case normalisation; null/unknown -> GENERIC. */
    private static WorkflowStep.StepKind parseStepKind(String raw) {
        if (raw == null || raw.isBlank()) return WorkflowStep.StepKind.GENERIC;
        try {
            return WorkflowStep.StepKind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown workflow step kind '{}', defaulting to GENERIC", raw);
            return WorkflowStep.StepKind.GENERIC;
        }
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

    // ---- Run dispatch ----

    private void dispatchRun(RunContext ctx, Run run, @Nullable SseEmitter emitter, List<LlmMessage> initialContext) {
        log.info("Dispatching run: runId={}, agentId={}", ctx.getRunId(), ctx.getAgentId());

        // SSE: notify client that processing has started. Include the runId so the client
        // can cancel this run server-side mid-stream (M1).
        tryEmit(emitter, "thinking", Map.of("status", "processing", "runId", ctx.getRunId().toString()));

        // A cancel that landed after admission must not be resurrected by the
        // dispatch write: only a non-terminal committed run may move to RUNNING.
        RunStatus committed = committedStatus(ctx.getRunId(), run);
        if (isTerminal(committed)) {
            log.info("Run {} is already terminal ({}); dispatch skipped", ctx.getRunId(), committed);
            return;
        }
        updateRunStatusDirect(ctx.getRunId(), RunStatus.RUNNING);

        // Provision the per-run scratch workspace that the diff preview reads.
        try {
            String wsDir = workspaceManager.getOrProvision(ctx.getRunId());
            log.info("Provisioned run workspace: {}", wsDir);
        } catch (Exception e) {
            log.error("Workspace provisioning failed for run {}: {}",
                    ctx.getRunId(), e.getMessage());
        }

        // Two different stores with two different jobs:
        // - the caller's prior turns are CONTEXT: held on the run context and merged
        //   into every message assembly (buildMessages), never written as this run's
        //   trajectory rows -- the conversation timeline aggregates rows across runs,
        //   so persisting them here would duplicate every earlier turn;
        // - this run's own turns are the TIMELINE: its request (the run's prompt seed)
        //   and its assistant turns are persisted, in that order.
        ctx.setPriorContext(initialContext);
        if (run.getPromptSeed() != null && !run.getPromptSeed().isBlank()) {
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

        // Runtime-coordination branch: a run whose runtime is owned by the run coordinator
        // is driven by its run-owned core session (prompt, events, permissions, finalization).
        // No second execution is started for that run.
        CoreExecutionService coordinator = runCoordinator();
        if (coordinator != null && coordinator.activeRuns(ctx.getAgentId()).contains(ctx.getRunId())) {
            log.info("Run {} is owned by its run-owned core session; no provider-level execution is started",
                    ctx.getRunId());
            return;
        }

        // Production cutover: a run whose (stored or defaulted) core is a registered
        // production core (opencode, qoder) is executed end to end by the run
        // coordinator through the core launcher (frozen binding -> backend -> core
        // session -> verified finalization) -- including a run whose remaining
        // selection admission refuses: executeCoreRun() fails it with the admission
        // message.
        CoreRunLauncher coreLauncher = runCoreLauncher();
        if (coreLauncher != null && coreLauncher.owns(ctx.getAgent())) {
            executeCoreRun(ctx, coreLauncher, emitter);
            return;
        }

        // No production core runtime is available for this agent. Never fall back to
        // a provider: the run-owned core is the only execution path.
        String coreId = ctx.getAgent() == null ? null : ctx.getAgent().getAdkProvider();
        String message = "Core '" + coreId + "' is served by its run-owned core runtime"
                + " (the core launcher/coordinator), which is not available in this wiring;"
                + " the run cannot execute";
        ctx.addError(message);
        tryEmit(emitter, "done", donePayload(ctx, message));
        tryEmit(emitter, "error", Map.of("message", message));
        completeRun(ctx, RunStatus.FAILED);
    }


    /**
     * Mirrors {@link #recordTrajectory} for the task path: persists the final task
     * output as an assistant trajectory entry (next turn after the existing max) so
     * the following session turn can see the previous conclusion. Written only on
     * success with non-blank output — failure/abort paths write nothing, matching
     * the turn-loop semantics of not auditing failed attempts.
     */
    private void recordTaskTrajectory(RunContext ctx, String finalOutput, int outputTokens) {
        try {
            int turnNumber = trajectoryRepository.findMaxTurnNumberByRunId(ctx.getRunId()) + 1;
            trajectoryRepository.save(SessionTrajectory.builder()
                    .runId(ctx.getRunId())
                    .turnNumber(turnNumber)
                    .role("assistant")
                    .content(finalOutput)
                    .outputTokens(outputTokens)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record task trajectory: {}", e.getMessage());
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
            List<SkillContext> skills = agentSkillResolver.resolveForAgent(ctx.getAgent());
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

        // The caller's prior turns sit between the system prompt and this run's own
        // rows: the run's request lands from its prompt seed, never from the caller's
        // context, so a request repeated verbatim across turns is still two messages.
        for (LlmMessage prior : ctx.getPriorContext()) {
            if (prior == null || prior.content() == null) continue;
            String role = prior.role() == null ? "" : prior.role();
            if ("system".equals(role)) {
                continue;
            } else if ("assistant".equals(role)) {
                messages.add(LlmMessage.assistant(prior.content()));
            } else if ("tool".equals(role)) {
                messages.add(LlmMessage.tool(prior.content(), prior.toolCallId()));
            } else {
                messages.add(LlmMessage.user(prior.content()));
            }
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
                // Task-level ABORTED runs terminate the session like a cancellation
                case ABORTED -> SessionStatus.CANCELLED;
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

        // Cancel any pending approvals. An ask whose own core window already
        // closed is expired by that timeout first, so the run ending cannot
        // rewrite a timed-out permission ask as a cancellation.
        try {
            CoreExecutionService coordinator = runCoordinator();
            if (coordinator != null) {
                coordinator.expirePendingAsksForRun(ctx.getRunId(), Instant.now());
            }
            approvalGate.cancelAllPendingForRun(ctx.getRunId());
            permissionCoordinator.cancelPendingForRun(ctx.getRunId());
        } catch (Exception e) {
            log.warn("Failed to cancel pending approvals for {}: {}", ctx.getRunId(), e.getMessage());
        }

        // Cleanup per-run workspace
        try {
            workspaceManager.cleanup(ctx.getRunId());
        } catch (Exception e) {
            log.warn("Workspace cleanup failed for {} (non-fatal): {}", ctx.getRunId(), e.getMessage());
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

}
