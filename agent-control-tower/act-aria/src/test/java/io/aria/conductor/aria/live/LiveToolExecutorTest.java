package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.dashboard.dto.DashboardSummary;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.controller.ApprovalController;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The tool set the live bridge may call (spec X7, X15, L4). Every name executes
 * through the owning service/repository directly — there is no MCP transport hop — and
 * returns one compact JSON value. The read tier answers immediately; the two write tools
 * answer {@code PendingApproval} and run nothing until the router's approved path. An
 * unknown name, a missing argument or a service refusal becomes
 * {@link LiveToolExecutor.ToolOutcome.Failure}; the executor never throws at the socket.
 * The output is capped so a turn's transcript cannot blow up, and no projection carries
 * an approval's argument/content text, an agent's config or a skill's template.
 */
@ExtendWith(MockitoExtension.class)
class LiveToolExecutorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T09:15:00Z");

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private RunService runService;
    @Mock
    private ApprovalQueryService approvalQueryService;
    @Mock
    private AgentService agentService;
    @Mock
    private WorkflowService workflowService;
    @Mock
    private RunRepository runRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ApprovalRepository approvalRepository;
    @Mock
    private SkillDefinitionRepository skillRepository;
    @Mock
    private LiveWriteService writes;

    private LiveToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LiveToolExecutor(runService, approvalQueryService, agentService, workflowService,
                runRepository, agentRepository, approvalRepository, skillRepository, writes,
                Clock.fixed(NOW, ZoneId.of("Asia/Hong_Kong")));
    }

    @Test
    void theFixedSetIsTheSevenReadsAndTheTwoGatedWrites() {
        assertThat(LiveToolExecutor.TOOL_NAMES).containsExactlyInAnyOrder(
                "get_time", "get_run", "get_latest_conversation", "list_approvals",
                "list_agents", "get_dashboard_summary", "get_workflow",
                "list_skills", "list_runs", "get_agent",
                "start_agent_run", "run_skill");
    }

    /** The operator's decision tools are never in the declared set (spec X15). */
    @Test
    void noOperatorDecisionOrOtherWriteToolIsDeclared() {
        assertThat(LiveToolExecutor.TOOL_NAMES).doesNotContain(
                "decide_approval", "approve", "finalize", "answer",
                "create_agent", "update_agent", "retire_agent",
                "create_llm_provider", "update_llm_provider", "delete_llm_provider",
                "remember_fact", "create_knowledge", "update_knowledge", "retire_knowledge",
                "create_kanban_item", "transition_kanban_item",
                "generate_report", "housekeeping_execute");
    }

    @Test
    void getTimeReturnsTheInjectedZoneAndTheSameUtcInstant() throws Exception {
        JsonNode value = value(executor.execute("get_time", json.readTree("{}")));

        assertThat(value.path("now").asText()).isEqualTo("2026-11-23T17:15:00+08:00");
        assertThat(value.path("zone").asText()).isEqualTo("Asia/Hong_Kong");
        assertThat(Instant.parse(value.path("utc").asText())).isEqualTo(NOW);
    }

    @Test
    void anUnknownNameIsAFailureAndNeverThrows() {
        LiveToolExecutor.ToolOutcome outcome = executor.execute("run_agent", json.createObjectNode());

        assertThat(outcome).isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        assertThat(((LiveToolExecutor.ToolOutcome.Failure) outcome).message())
                .contains("run_agent")
                .containsIgnoringCase("unknown");
        verifyNoInteractions(runService, approvalQueryService, agentService, workflowService,
                runRepository, agentRepository, approvalRepository);
    }

    @Test
    void aNullNameIsAFailure() {
        assertThat(executor.execute(null, null))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
    }

    @Test
    void getRunProjectsOneRunThroughRunService() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(runService.getRun(runId)).thenReturn(RunResponse.builder()
                .id(runId)
                .agentId(agentId)
                .status(RunStatus.COMPLETED)
                .iterationCount(3)
                .totalTokensUsed(1_234L)
                .finalOutput("done")
                .build());

        JsonNode value = value(executor.execute("get_run", json.readTree("{\"id\":\"" + runId + "\"}")));

        assertThat(value.path("id").asText()).isEqualTo(runId.toString());
        assertThat(value.path("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(value.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(value.path("iterationCount").asInt()).isEqualTo(3);
        assertThat(value.path("totalTokensUsed").asLong()).isEqualTo(1_234L);
        assertThat(value.path("finalOutput").asText()).isEqualTo("done");
    }

    @Test
    void getRunWithoutAnIdIsAFailureAndCallsNothing() {
        LiveToolExecutor.ToolOutcome outcome = executor.execute("get_run", json.createObjectNode());

        assertThat(outcome).isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        assertThat(((LiveToolExecutor.ToolOutcome.Failure) outcome).message()).contains("id");
        verifyNoInteractions(runService);
    }

    @Test
    void getRunWithAMalformedIdIsAFailure() {
        LiveToolExecutor.ToolOutcome outcome = executor.execute("get_run",
                json.createObjectNode().put("id", "not-a-uuid"));

        assertThat(outcome).isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        verifyNoInteractions(runService);
    }

    @Test
    void getLatestConversationReadsTheAriaConversationsNewestFirstFromTheRepository() throws Exception {
        Instant first = Instant.parse("2026-11-20T08:00:00Z");
        when(runRepository.findLatestConversationIds(eq(AriaConstants.ARIA_AGENT_ID), any()))
                .thenReturn(List.of("conv-9"));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-9")).thenReturn(List.of(
                Run.builder().id(UUID.randomUUID()).agentId(AriaConstants.ARIA_AGENT_ID)
                        .conversationId("conv-9").status(RunStatus.COMPLETED).createdAt(first).build(),
                Run.builder().id(UUID.randomUUID()).agentId(AriaConstants.ARIA_AGENT_ID)
                        .conversationId("conv-9").status(RunStatus.COMPLETED).createdAt(NOW).build()));

        JsonNode value = value(executor.execute("get_latest_conversation", null));

        assertThat(value.path("conversationId").asText()).isEqualTo("conv-9");
        assertThat(value.path("runCount").asInt()).isEqualTo(2);
        assertThat(Instant.parse(value.path("lastMessageAt").asText())).isEqualTo(NOW);
    }

    @Test
    void getLatestConversationIsNullWhenNoConversationExists() throws Exception {
        when(runRepository.findLatestConversationIds(eq(AriaConstants.ARIA_AGENT_ID), any()))
                .thenReturn(List.of());

        LiveToolExecutor.ToolOutcome outcome = executor.execute("get_latest_conversation", null);

        assertThat(((LiveToolExecutor.ToolOutcome.Value) outcome).json()).isEqualTo("null");
    }

    /**
     * Live rows are internal plumbing: the in-flight live run (created for this very
     * tool turn) and every earlier live turn must not be counted as the operator's
     * conversation, or the model would report a conversation that only talk-to-Aria
     * plumbing wrote.
     */
    @Test
    void getLatestConversationIgnoresLiveSeededRunsSoTheCountAndTimeAreTheOperators() throws Exception {
        Instant oldest = Instant.parse("2026-11-20T08:00:00Z");
        Instant realLatest = NOW.minusSeconds(60);
        UUID inFlight = UUID.randomUUID();
        when(runRepository.findLatestConversationIds(eq(AriaConstants.ARIA_AGENT_ID), any()))
                .thenReturn(List.of("conv-9", "conv-9", "conv-9", "conv-9"));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-9")).thenReturn(List.of(
                operatorRun("conv-9", oldest),
                liveRun(UUID.randomUUID(), "conv-9", NOW.minusSeconds(120)),
                operatorRun("conv-9", realLatest),
                liveRun(inFlight, "conv-9", NOW)));

        String raw = ((LiveToolExecutor.ToolOutcome.Value)
                executor.execute("get_latest_conversation", null)).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.path("conversationId").asText()).isEqualTo("conv-9");
        assertThat(value.path("runCount").asInt()).isEqualTo(2);
        assertThat(Instant.parse(value.path("lastMessageAt").asText())).isEqualTo(realLatest);
        assertThat(raw).doesNotContain(inFlight.toString());
        verify(runRepository, times(1)).findByConversationIdOrderByCreatedAtAsc("conv-9");
    }

    /** A conversation that only holds live rows describes no operator conversation. */
    @Test
    void getLatestConversationSkipsAConversationThatOnlyHoldsLiveRuns() throws Exception {
        Instant real = NOW.minusSeconds(3600);
        when(runRepository.findLatestConversationIds(eq(AriaConstants.ARIA_AGENT_ID), any()))
                .thenReturn(List.of("conv-live", "conv-live", "conv-real"));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-live"))
                .thenReturn(List.of(liveRun(UUID.randomUUID(), "conv-live", NOW)));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-real")).thenReturn(List.of(
                operatorRun("conv-real", real),
                liveRun(UUID.randomUUID(), "conv-real", real.plusSeconds(10))));

        JsonNode value = value(executor.execute("get_latest_conversation", null));

        assertThat(value.path("conversationId").asText()).isEqualTo("conv-real");
        assertThat(value.path("runCount").asInt()).isEqualTo(1);
        assertThat(Instant.parse(value.path("lastMessageAt").asText())).isEqualTo(real);
        verify(runRepository, times(1)).findByConversationIdOrderByCreatedAtAsc("conv-live");
    }

    @Test
    void listApprovalsProjectsTheGatesWithoutTheirArgumentsOrContent() throws Exception {
        UUID approvalId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(approvalQueryService.list(ApprovalStatus.PENDING)).thenReturn(List.of(
                new ApprovalController.ApprovalDetail(approvalId, runId, null, ApprovalStatus.PENDING,
                        "needs review", NOW, null, null, "TOOL_CALL", "# markdown content",
                        "MARKDOWN", null, "write_file", "{\"secret\":\"do-not-print\"}", "GATED",
                        null, null, null, null, null, null, "WORKER_WRITE")));

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("list_approvals",
                json.readTree("{\"status\":\"PENDING\"}"))).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.size()).isEqualTo(1);
        assertThat(value.get(0).path("id").asText()).isEqualTo(approvalId.toString());
        assertThat(value.get(0).path("runId").asText()).isEqualTo(runId.toString());
        assertThat(value.get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(value.get(0).path("toolName").asText()).isEqualTo("write_file");
        assertThat(raw).doesNotContain("do-not-print").doesNotContain("markdown content");
    }

    @Test
    void listApprovalsIsCappedAndRejectsAnUnknownStatus() throws Exception {
        List<ApprovalController.ApprovalDetail> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new ApprovalController.ApprovalDetail(UUID.randomUUID(), UUID.randomUUID(), null,
                    ApprovalStatus.PENDING, null, NOW, null, null, "TOOL_CALL", null, null, null,
                    "tool-" + i, null, "READ", null, null, null, null, null, null, "WORKER_READ"));
        }
        when(approvalQueryService.list(null)).thenReturn(many);

        JsonNode value = value(executor.execute("list_approvals", null));
        assertThat(value.size()).isEqualTo(LiveToolExecutor.MAX_APPROVALS);

        assertThat(executor.execute("list_approvals", json.readTree("{\"status\":\"WHATEVER\"}")))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
    }

    @Test
    void listAgentsProjectsTheActiveAgentsAndCapsTheList() throws Exception {
        List<AgentResponse> agents = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            agents.add(AgentResponse.builder()
                    .id(UUID.randomUUID())
                    .name("agent-" + i)
                    .role("worker")
                    .agentType(AgentType.ADK)
                    .config("{\"systemPrompt\":\"do-not-print\"}")
                    .healthStatus(HealthStatus.HEALTHY)
                    .build());
        }
        when(agentService.listAgents()).thenReturn(agents);

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("list_agents", null)).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.size()).isEqualTo(LiveToolExecutor.MAX_AGENTS);
        assertThat(value.get(0).path("name").asText()).isEqualTo("agent-0");
        assertThat(value.get(0).path("healthStatus").asText()).isEqualTo("HEALTHY");
        assertThat(raw).doesNotContain("do-not-print");
    }

    @Test
    void getDashboardSummaryReturnsTheSameShapeAsTheRestSummary() throws Exception {
        when(agentRepository.countByHealthStatus(HealthStatus.HEALTHY)).thenReturn(3L);
        when(agentRepository.countByHealthStatus(HealthStatus.DEGRADED)).thenReturn(1L);
        when(runRepository.countByStatus(RunStatus.RUNNING)).thenReturn(2L);
        Approval pending = new Approval();
        pending.setStatus(ApprovalStatus.PENDING);
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(pending));
        when(runRepository.sumReportedUsage()).thenReturn(9_000L);
        when(runRepository.countWithoutReportedUsage()).thenReturn(4L);

        JsonNode value = value(executor.execute("get_dashboard_summary", null));

        assertThat(value.path("healthyAgents").asLong()).isEqualTo(3L);
        assertThat(value.path("degradedAgents").asLong()).isEqualTo(1L);
        assertThat(value.path("runningRuns").asLong()).isEqualTo(2L);
        assertThat(value.path("pendingApprovals").asLong()).isEqualTo(1L);
        assertThat(value.path("totalTokensBurned").asLong()).isEqualTo(9_000L);
        assertThat(value.path("runsWithoutUsage").asLong()).isEqualTo(4L);
        assertThat(value.path("activeAgents").asLong()).isEqualTo(3L);
    }

    @Test
    void getWorkflowProjectsTheChainAndBoundsItsSteps() throws Exception {
        UUID chainId = UUID.randomUUID();
        List<WorkflowResponse.StepInfo> steps = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            steps.add(WorkflowResponse.StepInfo.builder()
                    .index(i)
                    .agentId(UUID.randomUUID())
                    .promptTemplate("template")
                    .status(WorkflowStep.Status.COMPLETED)
                    .runId(UUID.randomUUID())
                    .outputPreview("x".repeat(2_000))
                    .build());
        }
        when(workflowService.getWorkflow(chainId)).thenReturn(WorkflowResponse.builder()
                .id(chainId)
                .name("chain")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(2)
                .totalSteps(12)
                .steps(steps)
                .build());

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("get_workflow",
                json.readTree("{\"id\":\"" + chainId + "\"}"))).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.path("id").asText()).isEqualTo(chainId.toString());
        assertThat(value.path("status").asText()).isEqualTo("RUNNING");
        assertThat(value.path("totalSteps").asInt()).isEqualTo(12);
        assertThat(value.path("steps").size()).isEqualTo(LiveToolExecutor.MAX_STEPS);
        assertThat(raw.length()).isLessThanOrEqualTo(LiveToolExecutor.MAX_OUTPUT_CHARS);
    }

    @Test
    void anOversizedValueIsTruncatedToTheOutputCapAndStaysValidJson() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runService.getRun(runId)).thenReturn(RunResponse.builder()
                .id(runId)
                .agentId(UUID.randomUUID())
                .status(RunStatus.COMPLETED)
                .finalOutput("y".repeat(20_000))
                .build());

        LiveToolExecutor.ToolOutcome outcome = executor.execute("get_run",
                json.readTree("{\"id\":\"" + runId + "\"}"));
        String raw = ((LiveToolExecutor.ToolOutcome.Value) outcome).json();

        assertThat(outcome).isInstanceOf(LiveToolExecutor.ToolOutcome.Value.class);
        assertThat(raw.length()).isLessThanOrEqualTo(LiveToolExecutor.MAX_OUTPUT_CHARS);
        assertThat(json.readTree(raw).isObject()).isTrue();
    }

    @Test
    void everyValueFitsTheCapEvenWhenTheToolReturnsEverything() throws Exception {
        // The projections cap their lists and previews, so a hostile-sized page still
        // reaches the bridge as a compact value rather than a multi-kilobyte frame.
        List<AgentResponse> agents = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            agents.add(AgentResponse.builder().id(UUID.randomUUID()).name("n".repeat(400))
                    .healthStatus(HealthStatus.HEALTHY).build());
        }
        when(agentService.listAgents()).thenReturn(agents);

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("list_agents", null)).json();
        assertThat(raw.length()).isLessThanOrEqualTo(LiveToolExecutor.MAX_OUTPUT_CHARS);
        assertThat(json.readTree(raw).isArray()).isTrue();
    }

    @Test
    void theBoundedGuardTurnsAnOverlongValueIntoAMarkedPreview() throws Exception {
        String bounded = LiveToolExecutor.bounded("{\"a\":\"" + "z".repeat(5_000) + "\"}");

        assertThat(bounded.length()).isLessThanOrEqualTo(LiveToolExecutor.MAX_OUTPUT_CHARS);
        JsonNode value = json.readTree(bounded);
        assertThat(value.path("truncated").asBoolean()).isTrue();
        assertThat(value.path("chars").asInt()).isGreaterThan(LiveToolExecutor.MAX_OUTPUT_CHARS);
        assertThat(value.path("preview").asText()).isNotBlank();
    }

    // --- ops tier reads (spec X15) ---------------------------------------------------------

    @Test
    void listSkillsFiltersByStageAndEnabledAndNeverCarriesTheTemplate() throws Exception {
        List<SkillDefinition> skills = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            skills.add(SkillDefinition.builder().id("skill-" + i).name("skill-" + i)
                    .description("desc-" + i).template("DO-NOT-PRINT-" + i)
                    .stage("SKILL").enabled(true).tier("A").usageCount(i).build());
        }
        when(skillRepository.findByStageAndEnabledTrue("SKILL")).thenReturn(skills);

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("list_skills",
                json.readTree("{\"stage\":\"skill\",\"enabled\":true}"))).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.size()).isEqualTo(LiveToolExecutor.MAX_SKILLS);
        assertThat(value.get(0).path("id").asText()).isEqualTo("skill-0");
        assertThat(value.get(0).path("stage").asText()).isEqualTo("SKILL");
        assertThat(value.get(0).path("enabled").asBoolean()).isTrue();
        assertThat(value.get(0).path("usageCount").asInt()).isZero();
        assertThat(raw).doesNotContain("DO-NOT-PRINT");
        verifyNoInteractions(runService);
    }

    @Test
    void listSkillsRejectsAnUnknownStage() {
        assertThat(executor.execute("list_skills", json.createObjectNode().put("stage", "NOPE")))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        verifyNoInteractions(skillRepository);
    }

    @Test
    void listRunsFiltersByAgentAndStatusAndCapsTheList() throws Exception {
        UUID agentId = UUID.randomUUID();
        List<RunResponse> runs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            runs.add(RunResponse.builder().id(UUID.randomUUID()).agentId(agentId)
                    .status(RunStatus.COMPLETED).iterationCount(i).totalTokensUsed((long) i)
                    .promptSeed("SECRET-PROMPT").finalOutput("SECRET-OUTPUT").build());
        }
        when(runService.listRunsByAgentAndStatus(agentId, RunStatus.COMPLETED)).thenReturn(runs);

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("list_runs",
                json.readTree("{\"agentId\":\"" + agentId + "\",\"status\":\"completed\"}"))).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.size()).isEqualTo(LiveToolExecutor.MAX_RUNS);
        assertThat(value.get(0).path("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(value.get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(raw).doesNotContain("SECRET-PROMPT").doesNotContain("SECRET-OUTPUT");
    }

    @Test
    void listRunsWithNoFilterReadsTheWholeListAndRejectsABadStatus() throws Exception {
        when(runService.listRuns()).thenReturn(List.of());

        JsonNode value = value(executor.execute("list_runs", null));
        assertThat(value.isArray()).isTrue();
        assertThat(value.size()).isZero();

        assertThat(executor.execute("list_runs", json.readTree("{\"status\":\"WHATEVER\"}")))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
    }

    @Test
    void getAgentProjectsTheIdentityAndSkillsWithoutTheConfig() throws Exception {
        UUID agentId = UUID.randomUUID();
        List<String> skills = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            skills.add("skill-" + i);
        }
        when(agentService.getAgent(agentId)).thenReturn(AgentResponse.builder()
                .id(agentId).name("worker").role("developer").agentType(AgentType.ADK)
                .model("m").provider("p").healthStatus(HealthStatus.HEALTHY)
                .config("{\"systemPrompt\":\"DO-NOT-PRINT\"}")
                .workspacePath("/secret/path")
                .skills(skills).build());

        String raw = ((LiveToolExecutor.ToolOutcome.Value) executor.execute("get_agent",
                json.readTree("{\"id\":\"" + agentId + "\"}"))).json();
        JsonNode value = json.readTree(raw);

        assertThat(value.path("id").asText()).isEqualTo(agentId.toString());
        assertThat(value.path("name").asText()).isEqualTo("worker");
        assertThat(value.path("healthStatus").asText()).isEqualTo("HEALTHY");
        assertThat(value.path("skills").size()).isEqualTo(LiveToolExecutor.MAX_AGENT_SKILLS);
        assertThat(raw).doesNotContain("DO-NOT-PRINT").doesNotContain("/secret/path");
    }

    // --- ops tier writes (spec X15) --------------------------------------------------------

    @Test
    void startAgentRunReturnsPendingApprovalAndPerformsNothing() throws Exception {
        UUID agentId = UUID.randomUUID();
        JsonNode arguments = json.readTree("{\"agentId\":\"" + agentId
                + "\",\"prompt\":\"ship it\",\"maxIterations\":3}");

        LiveToolExecutor.ToolOutcome outcome = executor.execute("start_agent_run", arguments);

        assertThat(outcome).isInstanceOf(LiveToolExecutor.ToolOutcome.PendingApproval.class);
        LiveToolExecutor.ToolOutcome.PendingApproval pending =
                (LiveToolExecutor.ToolOutcome.PendingApproval) outcome;
        assertThat(pending.toolName()).isEqualTo("start_agent_run");
        assertThat(pending.riskTier()).isEqualTo(LiveToolExecutor.WRITE_RISK_TIER);
        assertThat(pending.arguments()).isEqualTo(arguments);
        verifyNoInteractions(writes);
    }

    @Test
    void runSkillReturnsPendingApprovalAndPerformsNothing() throws Exception {
        JsonNode arguments = json.readTree("{\"skillId\":\"skill-1\",\"prompt\":\"go\"}");

        LiveToolExecutor.ToolOutcome outcome = executor.execute("run_skill", arguments);

        assertThat(outcome).isInstanceOf(LiveToolExecutor.ToolOutcome.PendingApproval.class);
        assertThat(((LiveToolExecutor.ToolOutcome.PendingApproval) outcome).toolName())
                .isEqualTo("run_skill");
        verifyNoInteractions(writes, skillRepository);
    }

    @Test
    void aWriteWithMissingArgumentsIsAFailureAndRaisesNoAsk() throws Exception {
        assertThat(executor.execute("start_agent_run", json.createObjectNode()))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        assertThat(executor.execute("run_skill", json.createObjectNode()))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        assertThat(executor.execute("start_agent_run", json.readTree("{\"agentId\":\"" + UUID.randomUUID()
                + "\",\"prompt\":\"p\",\"maxIterations\":0}")))
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Failure.class);
        verifyNoInteractions(writes);
    }

    @Test
    void theApprovedStartAgentRunReachesTheWriteServiceOnce() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(writes.startAgentRun(agentId, "ship it", 3)).thenReturn(RunResponse.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING).maxIterations(3).build());

        ToolOutcomeView value = approved("start_agent_run",
                "{\"agentId\":\"" + agentId + "\",\"prompt\":\"ship it\",\"maxIterations\":3}");

        assertThat(value.json().path("runId").asText()).isEqualTo(runId.toString());
        assertThat(value.json().path("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(value.json().path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void theApprovedRunSkillReachesTheWriteServiceOnce() throws Exception {
        UUID runId = UUID.randomUUID();
        when(writes.runSkill("skill-1", null)).thenReturn(RunResponse.builder()
                .id(runId).agentId(AriaConstants.ARIA_AGENT_ID).status(RunStatus.RUNNING).build());

        ToolOutcomeView value = approved("run_skill", "{\"skillId\":\"skill-1\"}");

        assertThat(value.json().path("runId").asText()).isEqualTo(runId.toString());
        assertThat(value.json().path("skillId").asText()).isEqualTo("skill-1");
        assertThat(value.json().path("status").asText()).isEqualTo("RUNNING");
    }

    /** A read is not a gated write: the approved path still answers it as a read. */
    @Test
    void theApprovedPathStillServesARead() throws Exception {
        ToolOutcomeView value = approved("get_time", "{}");

        assertThat(value.json().path("zone").asText()).isEqualTo("Asia/Hong_Kong");
    }

    private ToolOutcomeView approved(String name, String arguments) throws Exception {
        LiveToolExecutor.ToolOutcome outcome = executor.executeApproved(name, json.readTree(arguments));
        assertThat(outcome).as("approved %s answers a value: %s", name, outcome)
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Value.class);
        return new ToolOutcomeView(json.readTree(((LiveToolExecutor.ToolOutcome.Value) outcome).json()));
    }

    private record ToolOutcomeView(JsonNode json) {
    }

    private JsonNode value(LiveToolExecutor.ToolOutcome outcome) throws Exception {
        assertThat(outcome).as("a read-only tool returns a value, never a failure: %s", outcome)
                .isInstanceOf(LiveToolExecutor.ToolOutcome.Value.class);
        return json.readTree(((LiveToolExecutor.ToolOutcome.Value) outcome).json());
    }

    private static Run operatorRun(String conversationId, Instant createdAt) {
        return Run.builder().id(UUID.randomUUID()).agentId(AriaConstants.ARIA_AGENT_ID)
                .conversationId(conversationId).promptSeed("what is on my board?")
                .status(RunStatus.COMPLETED).createdAt(createdAt).build();
    }

    private static Run liveRun(UUID id, String conversationId, Instant createdAt) {
        return Run.builder().id(id).agentId(AriaConstants.ARIA_AGENT_ID)
                .conversationId(conversationId)
                .promptSeed(LiveRunService.LIVE_SEED_PREFIX + "tool: get_run")
                .status(RunStatus.RUNNING).createdAt(createdAt).build();
    }
}
