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
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.dashboard.dto.DashboardSummary;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.controller.ApprovalController;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tool set a live turn may call (spec X7, X15, L4), executed through the owning
 * services directly. The MCP HTTP transport is deliberately not in this path: a voice
 * turn's tool call has a sub-second budget and no second credential hop.
 *
 * <p>Two tiers. The read tools return a value immediately and never park. The two write
 * tools ({@code start_agent_run}, {@code run_skill}) are gated: {@link #execute} validates
 * their arguments and answers {@link ToolOutcome.PendingApproval} carrying the name and
 * arguments; the socket layer creates the approval row, parks the turn's live run and
 * reports {@code approval_required}. Only the operator's decision reaches
 * {@link #executeApproved}, which performs the write exactly once and never asks again.
 * An unknown name, a malformed argument or a service refusal is a
 * {@link ToolOutcome.Failure} — the socket layer never sees an exception from a tool.
 *
 * <p>Every value is compact, projected JSON capped at {@link #MAX_OUTPUT_CHARS}: the
 * result is fed back into the model's transcript, so a full agent config or a 20 kB
 * run output would both leak and blow the turn's budget. Approvals lose their
 * {@code arguments}/{@code content} text (the review UI keeps it; the voice transcript
 * does not need it), a list carries a bounded number of entries, and an agent's config,
 * workspace path, a skill's template and a run's prompt never travel.
 *
 * <p>Privacy: a tool argument or result is never logged. A failure logs the tool name
 * and the exception class only.
 */
@Slf4j
@Service
public class LiveToolExecutor {

    /** The fixed, reviewed set the bridge may declare (spec X7/X15). */
    public static final Set<String> TOOL_NAMES = Set.of(
            "get_time", "get_run", "get_latest_conversation", "list_approvals",
            "list_agents", "get_dashboard_summary", "get_workflow",
            "list_skills", "list_runs", "get_agent",
            "start_agent_run", "run_skill");

    /**
     * The approval-gated tier. Every name here answers {@link ToolOutcome.PendingApproval}
     * from {@link #execute}; only {@link #executeApproved} performs the write.
     */
    public static final Set<String> WRITE_TOOL_NAMES = Set.of("start_agent_run", "run_skill");

    /** The live writes are executed by the platform with the operator's authority. */
    static final String WRITE_RISK_TIER = RiskTier.WRITE_LOCAL.name();

    /** One tool result must stay small enough for a voice turn's transcript. */
    public static final int MAX_OUTPUT_CHARS = 2 * 1024;

    static final int MAX_AGENTS = 10;
    static final int MAX_APPROVALS = 5;
    static final int MAX_STEPS = 5;
    static final int MAX_RUNS = 10;
    static final int MAX_SKILLS = 10;
    static final int MAX_AGENT_SKILLS = 10;
    /** The maturity stages a skill may be filtered by (mirrors {@code SkillTools}). */
    static final Set<String> SKILL_STAGES = Set.of("SKILL", "SCRIPT", "WORKFLOW");
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
    private final SkillDefinitionRepository skillRepository;
    private final LiveWriteService writes;
    private final Clock clock;

    /** Production wiring: the wall clock is the only unmanaged dependency. */
    @Autowired
    public LiveToolExecutor(RunService runService,
                            ApprovalQueryService approvalQueryService,
                            AgentService agentService,
                            WorkflowService workflowService,
                            RunRepository runRepository,
                            AgentRepository agentRepository,
                            ApprovalRepository approvalRepository,
                            SkillDefinitionRepository skillRepository,
                            LiveWriteService writes) {
        this(runService, approvalQueryService, agentService, workflowService,
                runRepository, agentRepository, approvalRepository, skillRepository, writes,
                Clock.systemDefaultZone());
    }

    LiveToolExecutor(RunService runService,
                     ApprovalQueryService approvalQueryService,
                     AgentService agentService,
                     WorkflowService workflowService,
                     RunRepository runRepository,
                     AgentRepository agentRepository,
                     ApprovalRepository approvalRepository,
                     SkillDefinitionRepository skillRepository,
                     LiveWriteService writes,
                     Clock clock) {
        this.runService = runService;
        this.approvalQueryService = approvalQueryService;
        this.agentService = agentService;
        this.workflowService = workflowService;
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.approvalRepository = approvalRepository;
        this.skillRepository = skillRepository;
        this.writes = writes;
        this.clock = clock;
    }

    /**
     * Executes one bridge {@code tool_call}. Never throws: an unknown name or a refused
     * call is returned as {@link ToolOutcome.Failure} so the turn can tell the model
     * what happened instead of tearing the socket down. A write tool returns
     * {@link ToolOutcome.PendingApproval} and performs nothing.
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
                case "list_skills" -> value(skillsView(skillStage(args), optionalBoolean(args, "enabled")));
                case "list_runs" -> value(runsView(optionalUuid(args, "agentId"), optionalRunStatus(args)));
                case "get_agent" -> value(agentView(requireUuid(args, "id", "agentId")));
                case "start_agent_run", "run_skill" -> gated(name, args);
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

    /**
     * Executes a call the operator already approved (spec X15). A write runs here, once,
     * with no second ask; any other name keeps {@link #execute}'s behaviour. The router
     * is the only caller: the approval decision is the warrant, so this path must never
     * be reachable from the bridge's own {@code tool_call}.
     */
    public ToolOutcome executeApproved(String name, JsonNode args) {
        if (!WRITE_TOOL_NAMES.contains(name)) {
            return execute(name, args);
        }
        try {
            ToolOutcome outcome = switch (name) {
                case "start_agent_run" -> startAgentRun(args);
                case "run_skill" -> runSkill(args);
                default -> new ToolOutcome.Failure("Unknown live tool: " + name);
            };
            if (outcome instanceof ToolOutcome.Value result) {
                log.debug("live tool {} -> {} chars (approved)", name, result.json().length());
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

    /**
     * The gated tier's immediate answer: validate the arguments so an ask is only
     * raised for a call that can run, then carry the name and arguments to the socket
     * layer, which owns the approval row and the park.
     */
    private static ToolOutcome gated(String name, JsonNode args) {
        switch (name) {
            case "start_agent_run" -> {
                requireUuid(args, "agentId");
                requireText(args, "prompt");
                optionalPositiveInt(args, "maxIterations");
            }
            case "run_skill" -> {
                requireText(args, "skillId");
                optionalText(args, "prompt");
            }
            default -> {
                return new ToolOutcome.Failure("Unknown live tool: " + name);
            }
        }
        return new ToolOutcome.PendingApproval(null, null, name, WRITE_RISK_TIER, args);
    }

    // --- approved writes ------------------------------------------------------------------

    private ToolOutcome startAgentRun(JsonNode args) {
        UUID agentId = requireUuid(args, "agentId");
        String prompt = requireText(args, "prompt");
        Integer maxIterations = optionalPositiveInt(args, "maxIterations");
        RunResponse run = writes.startAgentRun(agentId, prompt, maxIterations);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.getId());
        view.put("agentId", run.getAgentId());
        view.put("status", run.getStatus());
        view.put("maxIterations", run.getMaxIterations());
        return valueForRun(view, run.getId(), agentName(agentId));
    }

    private ToolOutcome runSkill(JsonNode args) {
        String skillId = requireText(args, "skillId");
        String prompt = optionalText(args, "prompt");
        RunResponse run = writes.runSkill(skillId, prompt);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.getId());
        view.put("skillId", skillId);
        view.put("status", run.getStatus());
        return valueForRun(view, run.getId(), agentName(run.getAgentId()));
    }

    /** The child run a dispatched write just started, typed for the router's registry. */
    @SuppressWarnings("unchecked")
    private ToolOutcome valueForRun(Object view, UUID childRunId, String agentName) {
        // The approved path returns a map, not the raw projection: keep the JSON rules and
        // attach the run so the router can route its lifecycle without parsing text.
        Map<String, Object> map = view instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null;
        String json = map == null ? write(view) : write(map);
        return new ToolOutcome.Value(bounded(json), childRunId, agentName);
    }

    private String agentName(UUID agentId) {
        return agentId == null ? "" : agentRepository.findById(agentId)
                .map(Agent::getName).orElse("");
    }

    private static String reasonOr(String message, String toolName) {
        return message == null || message.isBlank() ? "Tool " + toolName + " was refused" : message;
    }

    private Map<String, Object> timeView() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("now", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")));
        view.put("zone", now.getZone().getId());
        view.put("utc", now.toInstant().toString());
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
     * The same projection as {@code get_agent} in the MCP catalog: identity, the core,
     * health and the assigned skills. The agent's config and workspace paths stay on the
     * server — they are host detail a voice transcript has no use for.
     */
    private Map<String, Object> agentView(UUID agentId) {
        AgentResponse agent = agentService.getAgent(agentId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", agent.getId());
        view.put("name", agent.getName());
        view.put("role", agent.getRole());
        view.put("agentType", agent.getAgentType());
        view.put("model", agent.getModel());
        view.put("provider", agent.getProvider());
        view.put("healthStatus", agent.getHealthStatus());
        List<String> skills = agent.getSkills() == null ? List.of() : agent.getSkills();
        view.put("skills", skills.subList(0, Math.min(skills.size(), MAX_AGENT_SKILLS)));
        return view;
    }

    /**
     * The skill catalogue as the voice turn needs it: which skills exist, their stage and
     * whether they are enabled. The template, trigger conditions and examples are the
     * injectable content and are deliberately absent — a list read must not become a
     * second copy of the prompt material.
     */
    private List<Map<String, Object>> skillsView(String stage, Boolean enabled) {
        List<SkillDefinition> skills;
        if (stage != null) {
            if (Boolean.TRUE.equals(enabled)) {
                skills = skillRepository.findByStageAndEnabledTrue(stage);
            } else if (Boolean.FALSE.equals(enabled)) {
                skills = skillRepository.findByStageAndEnabledFalse(stage);
            } else {
                skills = skillRepository.findByStage(stage);
            }
        } else if (Boolean.TRUE.equals(enabled)) {
            skills = skillRepository.findByEnabledTrue();
        } else if (Boolean.FALSE.equals(enabled)) {
            skills = skillRepository.findByEnabledFalse();
        } else {
            skills = skillRepository.findAll();
        }
        List<Map<String, Object>> view = new ArrayList<>();
        for (SkillDefinition skill : skills.subList(0, Math.min(skills.size(), MAX_SKILLS))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", skill.getId());
            entry.put("name", skill.getName());
            entry.put("description", preview(skill.getDescription(), STEP_PREVIEW_CHARS));
            entry.put("stage", skill.getStage());
            entry.put("enabled", skill.isEnabled());
            entry.put("tier", skill.getTier());
            entry.put("usageCount", skill.getUsageCount());
            view.add(entry);
        }
        return view;
    }

    /**
     * Runs the operator can see, filtered by agent and/or status and capped like every
     * other list. A run's own prompt, output and error text never travel: the voice turn
     * reports status, not content.
     */
    private List<Map<String, Object>> runsView(UUID agentId, RunStatus status) {
        List<RunResponse> runs;
        if (agentId != null && status != null) {
            runs = runService.listRunsByAgentAndStatus(agentId, status);
        } else if (agentId != null) {
            runs = runService.listRunsByAgent(agentId);
        } else if (status != null) {
            runs = runService.listRunsByStatus(status);
        } else {
            runs = runService.listRuns();
        }
        // Newest first, so the cap keeps the runs an operator is most likely to mean.
        List<RunResponse> newestFirst = runs.stream()
                .sorted(Comparator.comparing(RunResponse::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Map<String, Object>> view = new ArrayList<>();
        for (RunResponse run : newestFirst.subList(0, Math.min(newestFirst.size(), MAX_RUNS))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", run.getId());
            entry.put("agentId", run.getAgentId());
            entry.put("status", run.getStatus());
            entry.put("iterationCount", run.getIterationCount());
            entry.put("totalTokensUsed", run.getTotalTokensUsed());
            entry.put("createdAt", run.getCreatedAt());
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

    /** An optional UUID argument; a malformed present value is still refused. */
    private static UUID optionalUuid(JsonNode args, String field) {
        String raw = optionalText(args, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + field + ": expected a UUID");
        }
    }

    private static String requireText(JsonNode args, String field) {
        String value = optionalText(args, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument '" + field + "'");
        }
        return value;
    }

    private static String optionalText(JsonNode args, String field) {
        if (args == null || !args.isObject()) {
            return null;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isBlank() ? null : value;
    }

    /** A boolean argument that may also arrive as the strings {@code true}/{@code false}. */
    private static Boolean optionalBoolean(JsonNode args, String field) {
        if (args == null || !args.isObject()) {
            return null;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String raw = node.asText("").trim();
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Invalid " + field + ": expected true or false");
    }

    /** An optional positive iteration cap; a non-positive or non-numeric value is refused. */
    private static Integer optionalPositiveInt(JsonNode args, String field) {
        if (args == null || !args.isObject()) {
            return null;
        }
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid " + field + ": expected a positive integer");
        }
        int value = node.asInt();
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid " + field + ": expected a positive integer");
        }
        return value;
    }

    private static String skillStage(JsonNode args) {
        String raw = optionalText(args, "stage");
        if (raw == null) {
            return null;
        }
        String value = raw.toUpperCase(Locale.ROOT);
        if (!SKILL_STAGES.contains(value)) {
            throw new IllegalArgumentException(
                    "Invalid stage '" + raw + "'. Valid: SKILL, SCRIPT, WORKFLOW");
        }
        return value;
    }

    private static RunStatus optionalRunStatus(JsonNode args) {
        String raw = optionalText(args, "status");
        if (raw == null) {
            return null;
        }
        try {
            return RunStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder valid = new StringBuilder();
            for (RunStatus status : RunStatus.values()) {
                if (valid.length() > 0) {
                    valid.append(", ");
                }
                valid.append(status.name());
            }
            throw new IllegalArgumentException("Invalid status '" + raw + "'. Valid: " + valid);
        }
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
     * One tool call's outcome. A gated write parks the live run and the handler reports
     * {@code approval_required} with the approval it created.
     */
    public sealed interface ToolOutcome
            permits ToolOutcome.Value, ToolOutcome.Failure, ToolOutcome.PendingApproval {

        /**
         * A compact JSON document (already bounded) to return to the model. A dispatched
         * write also names the child run and its display name, so the live socket can route
         * that run's lifecycle frames back to the approving phone (Phase 4).
         */
        record Value(String json, UUID childRunId, String agentName) implements ToolOutcome {
            Value(String json) {
                this(json, null, null);
            }
        }

        /** The call did not produce a value; the message says why (bounded, no stack). */
        record Failure(String message) implements ToolOutcome {
        }

        /**
         * A gated tool is waiting for the operator. {@code riskTier} is the reviewed
         * classification, carried so the phone can render the ask; the handler binds the
         * name and arguments to the turn's live run and creates the approval row.
         */
        record PendingApproval(UUID runId, UUID approvalId, String toolName, String riskTier,
                               JsonNode arguments) implements ToolOutcome {

            /** A pending ask that already names its run and approval (the seam's older shape). */
            public PendingApproval(UUID runId, UUID approvalId, String toolName, String riskTier) {
                this(runId, approvalId, toolName, riskTier, null);
            }
        }
    }
}
