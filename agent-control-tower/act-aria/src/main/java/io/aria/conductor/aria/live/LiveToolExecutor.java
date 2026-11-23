package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.dashboard.dto.DashboardSummary;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.controller.ApprovalController;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The read-only tool set a live turn may call (spec X7, L4), executed through the
 * owning services directly. The MCP HTTP transport is deliberately not in this path:
 * a voice turn's tool call has a sub-second budget and no second credential hop.
 *
 * <p>The set is fixed and read-only, so a normal call returns a value and never parks;
 * {@link ToolOutcome.PendingApproval} exists for the gated-tool seam of Task 4 and is
 * not produced by any name here. An unknown name, a malformed argument or a service
 * refusal is a {@link ToolOutcome.Failure} — the socket layer never sees an exception
 * from a tool.
 *
 * <p>Every value is compact, projected JSON capped at {@link #MAX_OUTPUT_CHARS}: the
 * result is fed back into the model's transcript, so a full agent config or a 20 kB
 * run output would both leak and blow the turn's budget. Approvals lose their
 * {@code arguments}/{@code content} text (the review UI keeps it; the voice transcript
 * does not need it), and lists carry a bounded number of entries.
 *
 * <p>Privacy: a tool argument or result is never logged. A failure logs the tool name
 * and the exception class only.
 */
@Slf4j
@Service
public class LiveToolExecutor {

    /** The fixed, reviewed read-only set the bridge may declare (spec X7). */
    public static final Set<String> TOOL_NAMES = Set.of(
            "get_time", "get_run", "get_latest_conversation", "list_approvals",
            "list_agents", "get_dashboard_summary", "get_workflow");

    /** One tool result must stay small enough for a voice turn's transcript. */
    public static final int MAX_OUTPUT_CHARS = 2 * 1024;

    static final int MAX_AGENTS = 10;
    static final int MAX_APPROVALS = 5;
    static final int MAX_STEPS = 5;
    static final int PREVIEW_CHARS = 400;
    /** Step output previews are the bulk of a chain; shorter than a run's own preview. */
    static final int STEP_PREVIEW_CHARS = 160;
    /** How many distinct conversations the latest-conversation scan may open. */
    static final int MAX_LATEST_CONVERSATIONS = 5;
    /** The id page read once; live rows repeat ids, so the window is wider than the scan. */
    static final int LATEST_CONVERSATION_PAGE = 20;

    /** The preview kept when a value still exceeds {@link #MAX_OUTPUT_CHARS}. */
    private static final int TRUNCATED_PREVIEW_CHARS = 512;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RunService runService;
    private final ApprovalQueryService approvalQueryService;
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final RunRepository runRepository;
    private final AgentRepository agentRepository;
    private final ApprovalRepository approvalRepository;
    private final Clock clock;

    /** Production wiring: the wall clock is the only unmanaged dependency. */
    @Autowired
    public LiveToolExecutor(RunService runService,
                            ApprovalQueryService approvalQueryService,
                            AgentService agentService,
                            WorkflowService workflowService,
                            RunRepository runRepository,
                            AgentRepository agentRepository,
                            ApprovalRepository approvalRepository) {
        this(runService, approvalQueryService, agentService, workflowService,
                runRepository, agentRepository, approvalRepository, Clock.systemUTC());
    }

    LiveToolExecutor(RunService runService,
                     ApprovalQueryService approvalQueryService,
                     AgentService agentService,
                     WorkflowService workflowService,
                     RunRepository runRepository,
                     AgentRepository agentRepository,
                     ApprovalRepository approvalRepository,
                     Clock clock) {
        this.runService = runService;
        this.approvalQueryService = approvalQueryService;
        this.agentService = agentService;
        this.workflowService = workflowService;
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.approvalRepository = approvalRepository;
        this.clock = clock;
    }

    /**
     * Executes one bridge {@code tool_call}. Never throws: an unknown name or a refused
     * call is returned as {@link ToolOutcome.Failure} so the turn can tell the model
     * what happened instead of tearing the socket down.
     *
     * @param name the declared function name
     * @param args the bridge's {@code arguments} object, or null when it sent none
     */
    public ToolOutcome execute(String name, JsonNode args) {
        if (name == null || name.isBlank()) {
            return new ToolOutcome.Failure("Unknown live tool: " + name);
        }
        try {
            ToolOutcome outcome = switch (name) {
                case "get_time" -> value(timeView());
                case "get_run" -> value(runView(requireUuid(args, "id", "runId")));
                case "get_latest_conversation" -> value(latestConversationView());
                case "list_approvals" -> value(approvalsView(approvalStatus(args)));
                case "list_agents" -> value(agentsView());
                case "get_dashboard_summary" -> value(dashboardView());
                case "get_workflow" -> value(workflowView(requireUuid(args, "id", "chainId")));
                default -> new ToolOutcome.Failure("Unknown live tool: " + name);
            };
            if (outcome instanceof ToolOutcome.Value result) {
                log.debug("live tool {} -> {} chars", name, result.json().length());
            }
            return outcome;
        } catch (ResourceNotFoundException e) {
            return new ToolOutcome.Failure(reasonOr(e.getMessage(), name));
        } catch (IllegalArgumentException e) {
            return new ToolOutcome.Failure(reasonOr(e.getMessage(), name));
        } catch (RuntimeException e) {
            log.warn("live tool {} failed: {}", name, e.getClass().getSimpleName());
            return new ToolOutcome.Failure("Tool " + name + " failed");
        }
    }

    private static String reasonOr(String message, String toolName) {
        return message == null || message.isBlank() ? "Tool " + toolName + " was refused" : message;
    }

    private Map<String, Object> timeView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("now", Instant.now(clock).toString());
        return view;
    }

    private Map<String, Object> runView(UUID runId) {
        RunResponse run = runService.getRun(runId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("agentId", run.getAgentId());
        view.put("status", run.getStatus());
        view.put("iterationCount", run.getIterationCount());
        view.put("totalTokensUsed", run.getTotalTokensUsed());
        view.put("errorMessage", preview(run.getErrorMessage()));
        view.put("finalOutput", preview(run.getFinalOutput()));
        return view;
    }

    /**
     * The newest conversation Aria itself ran in. Scoped to the Aria agent id so a
     * socket can never adopt some other agent's run as "the latest conversation"; the
     * database picks the ids newest-first, so no full run table is read to compare.
     *
     * <p>Live rows are internal plumbing, not the operator's chat: the in-flight live
     * run (which carries {@link LiveRunService#LIVE_SEED_PREFIX}) and every earlier live
     * turn are filtered out, so {@code lastMessageAt}/{@code runCount} describe what the
     * operator actually said. A conversation that only holds live rows is skipped
     * rather than reported as empty; the scan is bounded to a few conversations.
     */
    private Map<String, Object> latestConversationView() {
        List<String> candidates = runRepository.findLatestConversationIds(
                AriaConstants.ARIA_AGENT_ID, PageRequest.of(0, LATEST_CONVERSATION_PAGE));
        Set<String> examined = new LinkedHashSet<>();
        for (String conversationId : candidates) {
            if (conversationId == null || !examined.add(conversationId)) {
                continue;
            }
            if (examined.size() > MAX_LATEST_CONVERSATIONS) {
                break;
            }
            List<Run> operatorRuns = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                    .stream().filter(LiveToolExecutor::isOperatorRun).toList();
            if (operatorRuns.isEmpty()) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("conversationId", conversationId);
            view.put("lastMessageAt", operatorRuns.get(operatorRuns.size() - 1).getCreatedAt());
            view.put("runCount", operatorRuns.size());
            return view;
        }
        return null;
    }

    /** A live turn's row; never part of the operator's conversation history. */
    private static boolean isOperatorRun(Run run) {
        String seed = run.getPromptSeed();
        return seed == null || !seed.startsWith(LiveRunService.LIVE_SEED_PREFIX);
    }

    private List<Map<String, Object>> approvalsView(ApprovalStatus status) {
        List<ApprovalController.ApprovalDetail> details = approvalQueryService.list(status);
        List<Map<String, Object>> view = new ArrayList<>();
        for (ApprovalController.ApprovalDetail detail : details.subList(0, Math.min(details.size(), MAX_APPROVALS))) {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("id", detail.id());
            gate.put("runId", detail.runId());
            gate.put("status", detail.status());
            gate.put("toolName", detail.toolName());
            gate.put("riskTier", detail.riskTier());
            gate.put("requestedAt", detail.requestedAt());
            view.add(gate);
        }
        return view;
    }

    private List<Map<String, Object>> agentsView() {
        List<AgentResponse> agents = agentService.listAgents();
        List<Map<String, Object>> view = new ArrayList<>();
        for (AgentResponse agent : agents.subList(0, Math.min(agents.size(), MAX_AGENTS))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", agent.getId());
            entry.put("name", agent.getName());
            entry.put("role", agent.getRole());
            entry.put("agentType", agent.getAgentType());
            entry.put("healthStatus", agent.getHealthStatus());
            view.add(entry);
        }
        return view;
    }

    /**
     * The same aggregate reads as {@code DashboardController} (there is no dashboard
     * service to call): reported token burn comes from the runs' counters, never the
     * prompt-call table. {@code runningRuns} counts RUNNING only, matching the MCP tool
     * rather than the REST summary (which also counts a parked WAITING_INPUT run).
     */
    private DashboardSummary dashboardView() {
        long healthyAgents = agentRepository.countByHealthStatus(HealthStatus.HEALTHY);
        long degradedAgents = agentRepository.countByHealthStatus(HealthStatus.DEGRADED);
        long runningRuns = runRepository.countByStatus(RunStatus.RUNNING);
        long pendingApprovals = approvalRepository.findByStatus(ApprovalStatus.PENDING).size();
        return new DashboardSummary(healthyAgents, healthyAgents, degradedAgents,
                runningRuns, pendingApprovals, runRepository.sumReportedUsage(),
                runRepository.countWithoutReportedUsage());
    }

    private Map<String, Object> workflowView(UUID chainId) {
        WorkflowResponse chain = workflowService.getWorkflow(chainId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", chain.getId());
        view.put("name", chain.getName());
        view.put("status", chain.getStatus());
        view.put("currentStepIndex", chain.getCurrentStepIndex());
        view.put("totalSteps", chain.getTotalSteps());
        List<WorkflowResponse.StepInfo> steps = chain.getSteps() == null ? List.of() : chain.getSteps();
        List<Map<String, Object>> stepViews = new ArrayList<>();
        for (WorkflowResponse.StepInfo step : steps.subList(0, Math.min(steps.size(), MAX_STEPS))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", step.getIndex());
            entry.put("agentId", step.getAgentId());
            entry.put("status", step.getStatus());
            entry.put("runId", step.getRunId());
            entry.put("outputPreview", preview(step.getOutputPreview(), STEP_PREVIEW_CHARS));
            stepViews.add(entry);
        }
        view.put("steps", stepViews);
        return view;
    }

    /**
     * Serializes a projection and keeps it under {@link #MAX_OUTPUT_CHARS}. A list is
     * shrunk from the tail (its entries are ordered, so the head is the useful part)
     * before the marked-preview fallback; an object that is still too large becomes the
     * marker, because truncating raw JSON would hand the model an unparseable value.
     */
    private static ToolOutcome value(Object data) {
        String json = write(data);
        if (data instanceof List<?> list) {
            int keep = list.size();
            while (json.length() > MAX_OUTPUT_CHARS && keep > 1) {
                keep = Math.max(1, keep - Math.max(1, keep / 4));
                json = write(list.subList(0, keep));
            }
        }
        return new ToolOutcome.Value(bounded(json));
    }

    /**
     * The output cap's last line of defence: a projection that still serializes past
     * {@link #MAX_OUTPUT_CHARS} becomes a marked preview object. Truncating raw JSON
     * would hand the model an unparseable value, so the marker is the whole value.
     */
    static String bounded(String json) {
        if (json == null || json.length() <= MAX_OUTPUT_CHARS) {
            return json;
        }
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("truncated", true);
        marker.put("chars", json.length());
        marker.put("preview", json.substring(0, TRUNCATED_PREVIEW_CHARS));
        return write(marker);
    }

    private static String write(Object data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("live tool output is not serializable", e);
        }
    }

    private static String preview(String text) {
        return preview(text, PREVIEW_CHARS);
    }

    private static String preview(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + " [truncated]";
    }

    private static UUID requireUuid(JsonNode args, String... fields) {
        if (args != null && args.isObject()) {
            for (String field : fields) {
                JsonNode node = args.get(field);
                if (node != null && !node.isNull() && !node.asText().isBlank()) {
                    try {
                        return UUID.fromString(node.asText().trim());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid " + field + ": expected a UUID");
                    }
                }
            }
        }
        throw new IllegalArgumentException("Missing required argument '" + fields[0] + "'");
    }

    private static ApprovalStatus approvalStatus(JsonNode args) {
        if (args == null || !args.isObject()) {
            return null;
        }
        JsonNode node = args.get("status");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        String raw = node.asText().trim().toUpperCase();
        try {
            return ApprovalStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid status '" + raw + "'. Valid: PENDING, APPROVED, DENIED, EXPIRED");
        }
    }

    /**
     * One tool call's outcome. Task 4 consumes {@link PendingApproval}: a gated tool
     * parks the live run and the handler reports {@code approval_required} with both ids.
     */
    public sealed interface ToolOutcome
            permits ToolOutcome.Value, ToolOutcome.Failure, ToolOutcome.PendingApproval {

        /** A compact JSON document (already bounded) to return to the model. */
        record Value(String json) implements ToolOutcome {
        }

        /** The call did not produce a value; the message says why (bounded, no stack). */
        record Failure(String message) implements ToolOutcome {
        }

        /**
         * A gated tool is waiting for the operator. {@code riskTier} is the catalog's
         * reviewed classification, carried so the phone can render the ask.
         */
        record PendingApproval(UUID runId, UUID approvalId, String toolName, String riskTier)
                implements ToolOutcome {
        }
    }
}
