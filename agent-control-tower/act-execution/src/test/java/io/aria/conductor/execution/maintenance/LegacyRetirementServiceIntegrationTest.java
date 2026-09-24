package io.aria.conductor.execution.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.AgentSkill;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.model.AgentTool;
import io.aria.conductor.common.model.AgentToolId;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.AuditEvent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.approval.PermissionDeliveryState;
import io.aria.conductor.execution.controller.MaintenanceController;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.RuntimeActivity;
import io.aria.conductor.execution.security.ActorAuthenticationFilter;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Mixed-core preview/execute retirement against a disposable H2 database, plus
 * the explicit built-in setup that replaces the legacy repointing block.
 *
 * <p>Protected-data comparisons and target-absence checks read through native
 * SQL on purpose: a first-level-cache hit must never be able to satisfy either
 * side of the comparison in place of the database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegacyRetirementServiceIntegrationTest {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");

    private static final UUID BA_BUILTIN_ID = UUID.fromString("ba000000-0000-0000-0000-000000000001");
    private static final UUID DEV_BUILTIN_ID = UUID.fromString("de000000-0000-0000-0000-000000000002");
    private static final UUID QA_BUILTIN_ID = UUID.fromString("aa000000-0000-0000-0000-000000000003");

    @Autowired private AgentRepository agents;
    @Autowired private RunRepository runs;
    @Autowired private ApprovalRepository approvals;
    @Autowired private PromptCallRepository promptCalls;
    @Autowired private AcpPermissionRequestRepository permissionRequests;
    @Autowired private RunExecutionBindingRepository bindings;
    @Autowired private SessionTrajectoryRepository trajectories;
    @Autowired private ToolCallRepository toolCalls;
    @Autowired private AgentSessionRepository sessions;
    @Autowired private AgentToolRepository agentTools;
    @Autowired private AgentSkillRepository agentSkills;
    @Autowired private KanbanRepository kanban;
    @Autowired private WorkflowChainRepository chains;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private io.aria.conductor.common.repository.AuditEventBulkRepository auditBulk;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager platformTransactions;

    private final MutableClock clock = new MutableClock(T0);
    private final RuntimeView runtimeView = new RuntimeView();
    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
        @Override
        public void publishEvent(ApplicationEvent event) {
            publishedEvents.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            publishedEvents.add(event);
        }
    };

    private LegacyRetirementService service;
    private LegacySetupService setupService;
    private WorkflowService workflowService;
    private OperatorSessionService operatorSessions;
    private MaintenanceController controller;
    private ActorPrincipal operator;
    private ActorPrincipal worker;

    private UUID langChainAgentId;
    private UUID openCodeAgentId;
    private UUID langRun1;
    private UUID langRun2;
    private UUID openCodeRunId;
    private UUID langApproval1;
    private UUID langApproval2;
    private UUID openCodeApprovalId;
    private UUID langPermissionRequestId;
    private UUID langTrajectoryId;
    private UUID langToolCallId;
    private Long langRunOwnedPromptCallId;
    private Long langDirectPromptCallId;
    private String cardId;
    private UUID chainId;
    private WorkflowChain chain;
    private Long auditLangAgentId;
    private Long auditLangRunId;
    private Long auditLangActionId;
    private Long auditOpenCodeAgentId;
    private Long auditOpenCodeRunId;
    private Long auditConversationTrapId;

    private final Snapshot snapshot = new Snapshot();

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(chains, mock(RunService.class), new ObjectMapper(), publisher);
        service = serviceWith(runtimeView);
        setupService = new LegacySetupService(agents, clock);
        operatorSessions = new OperatorSessionService("operator-secret", Duration.ofHours(8),
                OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false, clock);
        controller = new MaintenanceController(service, setupService, operatorSessions);
        operator = ActorPrincipal.operator(null);
        // The controller resolves credential expiry with the real clock (the
        // mutable service clock is not the controller's), so the worker
        // credential must not expire while the test runs.
        worker = ActorPrincipal.worker(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1)));
    }

    /** The service with an explicit runtime view; the production wiring resolves it as a bean. */
    private LegacyRetirementService serviceWith(RuntimeActivity runtime) {
        return new LegacyRetirementService(agents, runs, approvals, promptCalls, permissionRequests,
                bindings, trajectories, toolCalls, sessions, agentTools, agentSkills, kanban, chains,
                auditEvents, auditBulk, workflowService, runtime, publisher,
                new TransactionTemplate(platformTransactions), clock, PREVIEW_TTL);
    }

    /* ------------------------------------------------------------------ */
    /* Step 1: mixed-core scoped deletion with exact before/after state     */
    /* ------------------------------------------------------------------ */

    @Test
    void previewAndExecuteDeleteExactlyTheLangChainScope() {
        seedMixedCoreFixture();

        var before = snapshot.protectedRecords();
        var preview = service.preview(operator);
        assertThat(preview.agentIds()).containsExactly(langChainAgentId);
        assertThat(preview.agentIds()).doesNotContain(openCodeAgentId);
        var receipt = service.execute(preview.previewId(), preview.digest(), operator);
        assertThat(receipt.deletedAgents()).isEqualTo(1);
        assertThat(receipt.deletedRuns()).isEqualTo(2);
        assertThat(snapshot.protectedRecords()).isEqualTo(before);
        assertThat(kanban.findById(cardId).orElseThrow().getLinkedAgentId()).isNull();
        assertThat(workflowService.stepAt(chain, 0).getAgentId()).isNull();
        assertThat(auditEventsFor(openCodeAgentId)).isNotEmpty();

        // The digest covers the exact frozen ID sets; the manifest carried them.
        assertThat(preview.digest()).matches("[0-9a-f]{64}");
        assertThat(preview.expiresAt()).isEqualTo(T0.plus(PREVIEW_TTL));
        assertThat(preview.runIds()).containsExactlyInAnyOrder(langRun1, langRun2);
        assertThat(preview.approvalIds()).containsExactlyInAnyOrder(langApproval1, langApproval2);
        assertThat(preview.auditEventIds()).containsExactlyInAnyOrder(
                auditLangAgentId, auditLangRunId, auditLangActionId);
        assertThat(preview.permissionRequestIds()).containsExactly(langPermissionRequestId);
        assertThat(preview.trajectoryIds()).containsExactly(langTrajectoryId);
        assertThat(preview.toolCallIds()).containsExactly(langToolCallId);
        assertThat(preview.promptCallIds()).containsExactlyInAnyOrder(
                langRunOwnedPromptCallId, langDirectPromptCallId);
        assertThat(preview.agentToolBindingIds()).containsExactlyInAnyOrder(
                langChainAgentId + ":tool-1", langChainAgentId + ":tool-2");
        assertThat(preview.agentSkillBindingIds()).containsExactly(langChainAgentId + ":skill-1");
        assertThat(preview.kanbanCardIds()).containsExactly(cardId);
        assertThat(preview.workflowChainIds()).containsExactly(chainId);

        // Every deleted/unlinked count is the exact fixture count.
        assertThat(receipt.deletedApprovals()).isEqualTo(2);
        assertThat(receipt.deletedPermissionRequests()).isEqualTo(1);
        assertThat(receipt.deletedTrajectories()).isEqualTo(1);
        assertThat(receipt.deletedToolCalls()).isEqualTo(1);
        assertThat(receipt.deletedRunPromptCalls()).isEqualTo(1);
        assertThat(receipt.deletedAgentPromptCalls()).isEqualTo(1);
        assertThat(receipt.deletedRunBindings()).isEqualTo(1);
        assertThat(receipt.deletedAgentSessions()).isEqualTo(1);
        assertThat(receipt.deletedAgentToolBindings()).isEqualTo(2);
        assertThat(receipt.deletedAgentSkillBindings()).isEqualTo(1);
        assertThat(receipt.deletedAuditEvents()).isEqualTo(3);
        assertThat(receipt.unlinkedKanbanCards()).isEqualTo(1);
        assertThat(receipt.unlinkedWorkflowSteps()).isEqualTo(1);
        assertThat(receipt.previewId()).isEqualTo(preview.previewId());
        assertThat(receipt.executedAt()).isEqualTo(T0);

        // The exact target UUIDs are gone; the other core is untouched.
        assertThat(nativeCount("runs", "id", langRun1)).isEqualTo(0L);
        assertThat(nativeCount("runs", "id", langRun2)).isEqualTo(0L);
        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(0L);
        assertThat(nativeCount("agents", "id", openCodeAgentId)).isEqualTo(1L);
        assertThat(nativeCount("runs", "id", openCodeRunId)).isEqualTo(1L);
        assertThat(nativeCount("approvals", "id", langApproval1)).isEqualTo(0L);
        assertThat(nativeCount("approvals", "id", langApproval2)).isEqualTo(0L);
        assertThat(nativeCount("approvals", "id", openCodeApprovalId)).isEqualTo(1L);
        assertThat(nativeCount("acp_permission_request", "id", langPermissionRequestId)).isEqualTo(0L);
        assertThat(nativeCount("session_trajectory", "id", langTrajectoryId)).isEqualTo(0L);
        assertThat(nativeCount("tool_calls", "id", langToolCallId)).isEqualTo(0L);
        assertThat(nativeCount("agent_sessions", "run_id", langRun1)).isEqualTo(0L);
        assertThat(nativeCount("run_execution_bindings", "run_id", langRun1)).isEqualTo(0L);
        assertThat(nativeCount("prompt_calls", "id", langRunOwnedPromptCallId)).isEqualTo(0L);
        assertThat(nativeCount("prompt_calls", "id", langDirectPromptCallId)).isEqualTo(0L);
        assertThat(nativeCount("agent_tools", "agent_id", langChainAgentId)).isEqualTo(0L);
        assertThat(nativeCount("agent_skills", "agent_id", langChainAgentId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", auditLangAgentId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", auditLangRunId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", auditLangActionId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", auditOpenCodeAgentId)).isEqualTo(1L);
        assertThat(nativeCount("audit_log", "id", auditOpenCodeRunId)).isEqualTo(1L);

        // No dangling links anywhere the deleted scope was referenced.
        assertThat(nativeString("kanban_items", "linked_agent_id", "id", cardId)).isNull();
        assertThat(nativeString("kanban_items", "linked_run_id", "id", cardId)).isNull();
        assertThat(workflowService.stepAt(reloadChain(), 0).getAgentId()).isNull();
        assertThat(workflowService.stepAt(reloadChain(), 0).getRunId()).isNull();
        assertThat(workflowService.stepAt(reloadChain(), 1).getAgentId()).isEqualTo(openCodeAgentId);
        assertThat(workflowService.stepAt(reloadChain(), 1).getOutput()).isEqualTo("qa-output");

        // The audit trail is deleted only by verified resource ID, never by conversation:
        // the surviving trap row shares the deleted run's conversation verbatim.
        assertThat(nativeCount("audit_log", "id", auditConversationTrapId)).isEqualTo(1L);
        assertThat(nativeColumns("audit_log", "resource_type, resource_id, conversation_id",
                "id", auditConversationTrapId.toString()))
                .isEqualTo("[Agent, " + openCodeAgentId + ", conv-legacy-1]");
        assertThat(auditCountByConversation("conv-legacy-1")).isEqualTo(1L);

        assertThat(publishedEvents).hasSize(1);
        AuditLogEvent audit = (AuditLogEvent) publishedEvents.get(0);
        assertThat(audit.getEventType()).isEqualTo("LEGACY_RETIREMENT_EXECUTED");
        assertThat(audit.getResourceType()).isEqualTo("Maintenance");
        assertThat(audit.getResourceId()).isEqualTo(preview.previewId().toString());
    }

    /* ------------------------------------------------------------------ */
    /* Step 4 matrix                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void executeRefusesADigestThatDoesNotMatchThePreview() {
        seedMixedCoreFixture();
        var preview = service.preview(operator);
        String tampered = "0".repeat(64);

        assertThatThrownBy(() -> service.execute(preview.previewId(), tampered, operator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retirement preview " + preview.previewId() + " digest mismatch");
        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(1L);
        assertThat(nativeCount("runs", "id", langRun1)).isEqualTo(1L);
    }

    @Test
    void executeRefusesAnExpiredPreview() {
        seedMixedCoreFixture();
        var preview = service.preview(operator);
        clock.advance(PREVIEW_TTL.plusSeconds(1));

        assertThatThrownBy(() -> service.execute(preview.previewId(), preview.digest(), operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement preview " + preview.previewId() + " expired at " + preview.expiresAt());
        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(1L);
    }

    @Test
    void executeRefusesWhenTheSelectionChangedAfterThePreview() {
        seedMixedCoreFixture();
        var preview = service.preview(operator);

        UUID lateLangChainAgent = UUID.randomUUID();
        agents.save(agent(lateLangChainAgent, "late-legacy-agent", "dev", "langchain"));

        assertThatThrownBy(() -> service.execute(preview.previewId(), preview.digest(), operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement preview " + preview.previewId() + " selection changed since preview");
        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(1L);
        assertThat(nativeCount("agents", "id", lateLangChainAgent)).isEqualTo(1L);
        assertThat(nativeCount("runs", "id", langRun2)).isEqualTo(1L);
    }

    @Test
    void previewRefusesWhenATargetRunIsStillActive() {
        seedMixedCoreFixture();
        Run active = runs.findById(langRun2).orElseThrow();
        active.setStatus(RunStatus.RUNNING);
        runs.save(active);

        assertThatThrownBy(() -> service.preview(operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement refused: target run(s) [" + langRun2 + "] are still active");
        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(1L);
    }

    @Test
    void previewAndExecuteRefuseWhileARuntimeIsStillOwned() {
        seedMixedCoreFixture();
        runtimeView.activate(langChainAgentId, langRun2);

        assertThatThrownBy(() -> service.preview(operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement refused: target run(s) [" + langRun2 + "] are still active");

        runtimeView.deactivate(langRun2);
        var preview = service.preview(operator);
        runtimeView.activate(langChainAgentId, langRun2);
        assertThatThrownBy(() -> service.execute(preview.previewId(), preview.digest(), operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement refused: target run(s) [" + langRun2 + "] are still active");
        assertThat(nativeCount("runs", "id", langRun2)).isEqualTo(1L);
    }

    @Test
    void previewRefusesWhenATargetRunHasAPendingApproval() {
        seedMixedCoreFixture();
        UUID pendingApprovalId = UUID.randomUUID();
        approvals.save(Approval.builder().id(pendingApprovalId).runId(langRun2)
                .status(ApprovalStatus.PENDING).reason("awaiting operator").requestedAt(T0).build());

        assertThatThrownBy(() -> service.preview(operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement refused: target run(s) [" + langRun2 + "] have pending approval(s)");
        assertThat(nativeCount("approvals", "id", pendingApprovalId)).isEqualTo(1L);
    }

    @Test
    void rerunWithNoTargetsIsANoOpAndAPreviewIsOneUse() {
        seedMixedCoreFixture();
        var preview = service.preview(operator);
        service.execute(preview.previewId(), preview.digest(), operator);

        var second = service.preview(operator);
        assertThat(second.agentIds()).isEmpty();
        assertThat(second.runIds()).isEmpty();
        var receipt = service.execute(second.previewId(), second.digest(), operator);
        assertThat(receipt.isNoOp()).isTrue();
        assertThat(receipt.deletedAgents()).isEqualTo(0);
        assertThat(receipt.deletedRuns()).isEqualTo(0);
        assertThat(receipt.deletedApprovals()).isEqualTo(0);
        assertThat(receipt.deletedAuditEvents()).isEqualTo(0);
        assertThat(receipt.unlinkedKanbanCards()).isEqualTo(0);
        assertThat(receipt.unlinkedWorkflowSteps()).isEqualTo(0);

        assertThat(nativeCount("agents", "id", openCodeAgentId)).isEqualTo(1L);
        assertThatThrownBy(() -> service.execute(preview.previewId(), preview.digest(), operator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown retirement preview: " + preview.previewId());
    }

    @Test
    void executeRefusesAnUnknownPreviewId() {
        seedMixedCoreFixture();
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.execute(unknown, "0".repeat(64), operator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown retirement preview: " + unknown);
    }

    /* ------------------------------------------------------------------ */
    /* Explicit setup (fresh-install bootstrap)                             */
    /* ------------------------------------------------------------------ */

    @Test
    void initializeMissingBuiltinsCreatesTheThreeRoleBuiltinsWithOpenCodeSandbox() {
        assertThat(agents.findAll()).isEmpty();

        LegacySetupService.SetupReceipt receipt = setupService.initializeMissingBuiltins(operator);

        assertThat(receipt.createdAgentIds()).containsExactlyInAnyOrder(
                BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID);
        assertThat(receipt.existingAgentIds()).isEmpty();
        assertThat(builtin(BA_BUILTIN_ID).getName()).isEqualTo("SDD BA Agent");
        assertThat(builtin(BA_BUILTIN_ID).getRole()).isEqualTo("ba");
        assertThat(builtin(DEV_BUILTIN_ID).getName()).isEqualTo("SDD DEV Agent");
        assertThat(builtin(DEV_BUILTIN_ID).getRole()).isEqualTo("dev");
        assertThat(builtin(QA_BUILTIN_ID).getName()).isEqualTo("SDD QA Agent");
        assertThat(builtin(QA_BUILTIN_ID).getRole()).isEqualTo("qa");
        for (UUID id : List.of(BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID)) {
            assertThat(builtin(id).getAdkProvider()).isEqualTo("opencode");
            assertThat(builtin(id).getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
            assertThat(builtin(id).getAgentType()).isEqualTo(AgentType.NATIVE);
            assertThat(builtin(id).getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(builtin(id).getModel()).isNull();
        }

        LegacySetupService.SetupReceipt second = setupService.initializeMissingBuiltins(operator);
        assertThat(second.createdAgentIds()).isEmpty();
        assertThat(second.existingAgentIds()).containsExactlyInAnyOrder(
                BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID);
    }

    @Test
    void initializeMissingBuiltinsNeverRepointsAnExistingBuiltin() {
        agents.save(agent(BA_BUILTIN_ID, "SDD BA Agent", "ba", "langchain"));

        LegacySetupService.SetupReceipt receipt = setupService.initializeMissingBuiltins(operator);

        assertThat(receipt.createdAgentIds()).containsExactlyInAnyOrder(DEV_BUILTIN_ID, QA_BUILTIN_ID);
        assertThat(receipt.existingAgentIds()).containsExactly(BA_BUILTIN_ID);
        assertThat(builtin(BA_BUILTIN_ID).getAdkProvider()).isEqualTo("langchain");
        assertThat(builtin(BA_BUILTIN_ID).getExecutionMode()).isNull();
    }

    /* ------------------------------------------------------------------ */
    /* Operator authority, endpoints and no automatic execution path        */
    /* ------------------------------------------------------------------ */

    @Test
    void servicesAndEndpointsRefuseEveryNonOperatorCaller() {
        assertThatThrownBy(() -> service.preview(worker))
                .isInstanceOf(SecurityException.class).hasMessage("Operator authority required");
        assertThatThrownBy(() -> service.execute(UUID.randomUUID(), "0".repeat(64), worker))
                .isInstanceOf(SecurityException.class).hasMessage("Operator authority required");
        assertThatThrownBy(() -> setupService.initializeMissingBuiltins(worker))
                .isInstanceOf(SecurityException.class).hasMessage("Operator authority required");

        assertStatus(controller.preview(anonymous("POST")), HttpStatus.UNAUTHORIZED);
        assertStatus(controller.execute(new MaintenanceController.RetirementExecuteRequest(
                UUID.randomUUID(), "0".repeat(64)), anonymous("POST")), HttpStatus.UNAUTHORIZED);
        assertStatus(controller.initializeBuiltins(anonymous("POST")), HttpStatus.UNAUTHORIZED);

        assertStatus(controller.preview(workerRequest()), HttpStatus.FORBIDDEN);
        assertStatus(controller.execute(new MaintenanceController.RetirementExecuteRequest(
                UUID.randomUUID(), "0".repeat(64)), workerRequest()), HttpStatus.FORBIDDEN);
        assertStatus(controller.initializeBuiltins(workerRequest()), HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredActorIdentityIsUnauthorizedNotForbidden() {
        ActorPrincipal expiredOperator = ActorPrincipal.operator(Instant.now().minusSeconds(1));
        MockHttpServletRequest expiredOperatorRequest = anonymous("POST");
        expiredOperatorRequest.setAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, expiredOperator);

        assertStatus(controller.preview(expiredOperatorRequest), HttpStatus.UNAUTHORIZED);
        assertStatus(controller.execute(new MaintenanceController.RetirementExecuteRequest(
                UUID.randomUUID(), "0".repeat(64)), expiredOperatorRequest), HttpStatus.UNAUTHORIZED);
        assertStatus(controller.initializeBuiltins(expiredOperatorRequest), HttpStatus.UNAUTHORIZED);

        ActorPrincipal expiredWorker = ActorPrincipal.worker(UUID.randomUUID(), Instant.now().minusSeconds(1));
        MockHttpServletRequest expiredWorkerRequest = anonymous("POST");
        expiredWorkerRequest.setAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, expiredWorker);
        assertStatus(controller.preview(expiredWorkerRequest), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void maintenanceEndpointsExecuteOnlyWithTheOperatorCredential() {
        seedMixedCoreFixture();

        ResponseEntity<Object> previewResponse = controller.preview(operatorRequest());
        assertStatus(previewResponse, HttpStatus.OK);
        RetirementManifest preview = (RetirementManifest) previewResponse.getBody();
        assertThat(preview.agentIds()).containsExactly(langChainAgentId);

        ResponseEntity<Object> executeResponse = controller.execute(
                new MaintenanceController.RetirementExecuteRequest(preview.previewId(), preview.digest()),
                operatorRequest());
        assertStatus(executeResponse, HttpStatus.OK);
        RetirementReceipt receipt = (RetirementReceipt) executeResponse.getBody();
        assertThat(receipt.deletedAgents()).isEqualTo(1);
        assertThat(receipt.deletedRuns()).isEqualTo(2);

        ResponseEntity<Object> setupResponse = controller.initializeBuiltins(operatorRequest());
        assertStatus(setupResponse, HttpStatus.OK);
        assertThat(((LegacySetupService.SetupReceipt) setupResponse.getBody()).createdAgentIds())
                .containsExactlyInAnyOrder(BA_BUILTIN_ID, DEV_BUILTIN_ID, QA_BUILTIN_ID);
    }

    @Test
    void retirementAndSetupHaveNoAutomaticExecutionPath() {
        for (Class<?> type : List.of(LegacyRetirementService.class, LegacySetupService.class,
                MaintenanceController.class)) {
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Scheduled.class))
                        .as("%s.%s must not be scheduled", type.getSimpleName(), method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(PostConstruct.class))
                        .as("%s.%s must not run at startup", type.getSimpleName(), method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(PreDestroy.class))
                        .as("%s.%s must not run at shutdown", type.getSimpleName(), method.getName()).isFalse();
                assertThat(method.isAnnotationPresent(EventListener.class))
                        .as("%s.%s must not react to events", type.getSimpleName(), method.getName()).isFalse();
            }
            assertThat(ApplicationRunner.class.isAssignableFrom(type)).isFalse();
            assertThat(CommandLineRunner.class.isAssignableFrom(type)).isFalse();
        }
        assertThatCode(() -> LegacyRetirementService.class.getMethod("preview", ActorPrincipal.class))
                .doesNotThrowAnyException();
        assertThatCode(() -> LegacyRetirementService.class.getMethod(
                "execute", UUID.class, String.class, ActorPrincipal.class)).doesNotThrowAnyException();
        assertThatCode(() -> LegacySetupService.class.getMethod(
                "initializeMissingBuiltins", ActorPrincipal.class)).doesNotThrowAnyException();
    }

    /* ------------------------------------------------------------------ */
    /* Fix round 1: fail-closed wiring, single-flight and narrowed audit    */
    /* ------------------------------------------------------------------ */

    @Test
    void missingRuntimeActivityFailsClosedOnBothEntryPoints() {
        seedMixedCoreFixture();
        LegacyRetirementService withoutView = serviceWith(null);

        assertThatThrownBy(() -> withoutView.preview(operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement refused: the RuntimeActivity run-quiescence view is not"
                        + " deployed; quiescence cannot be proven");

        // The refusal precedes every other check: even a manifest this instance
        // never produced cannot reach the deletion path without the view.
        RetirementManifest reviewed = service.preview(operator);
        assertThatThrownBy(() -> withoutView.execute(reviewed.previewId(), reviewed.digest(), operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement refused: the RuntimeActivity run-quiescence view is not"
                        + " deployed; quiescence cannot be proven");

        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(1L);
        assertThat(nativeCount("runs", "id", langRun1)).isEqualTo(1L);
    }

    @Test
    void auditDeletionLeavesRowsThatArrivedAfterTheFrozenAuditSet() {
        seedMixedCoreFixture();
        var preview = service.preview(operator);

        // The trap fires at the end of the execute-time derivation: the audit
        // rows are already read (and frozen), the deletion has not run yet. The
        // row is outside the frozen id set, so the narrowed delete must leave it.
        AtomicReference<Long> lateAuditRowId = new AtomicReference<>();
        runtimeView.onActiveRuns(() -> lateAuditRowId.set(auditEvents.save(audit(
                "AGENT_UPDATED", "Agent", langChainAgentId.toString(), "UPDATE", "conv-legacy-1")).getId()));

        RetirementReceipt receipt = service.execute(preview.previewId(), preview.digest(), operator);

        assertThat(lateAuditRowId.get()).isNotNull();
        assertThat(receipt.deletedAuditEvents()).isEqualTo(3);
        assertThat(nativeCount("audit_log", "id", auditLangAgentId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", auditLangRunId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", auditLangActionId)).isEqualTo(0L);
        assertThat(nativeCount("audit_log", "id", lateAuditRowId.get())).isEqualTo(1L);
        assertThat(nativeColumns("audit_log", "resource_type, resource_id, action",
                "id", lateAuditRowId.get().toString()))
                .isEqualTo("[Agent, " + langChainAgentId + ", UPDATE]");
    }

    @Test
    void aConcurrentPreviewAndExecuteRefuseWhileAPreviewIsInFlight() throws Exception {
        seedMixedCoreFixture();
        LatchRuntimeView blocking = new LatchRuntimeView();
        LegacyRetirementService inFlight = serviceWith(blocking);
        blocking.arm();

        AtomicReference<Throwable> refusedPreview = new AtomicReference<>();
        AtomicReference<Throwable> refusedExecute = new AtomicReference<>();
        AtomicReference<Throwable> attackerFailure = new AtomicReference<>();
        Thread attacker = new Thread(() -> {
            try {
                if (!blocking.awaitEntered()) {
                    attackerFailure.set(new AssertionError("retirement derivation never entered"));
                    return;
                }
                refusedPreview.set(catchThrowable(() -> inFlight.preview(operator)));
                refusedExecute.set(catchThrowable(() -> inFlight.execute(
                        UUID.randomUUID(), "0".repeat(64), operator)));
            } catch (Throwable t) {
                attackerFailure.set(t);
            } finally {
                blocking.release();
            }
        });
        attacker.start();

        RetirementManifest completed = inFlight.preview(operator);
        attacker.join(60_000);

        assertThat(attackerFailure.get()).isNull();
        assertThat(completed.digest()).matches("[0-9a-f]{64}");
        assertThat(refusedPreview.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement operation already in flight");
        assertThat(refusedExecute.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement operation already in flight");
        assertThat(nativeCount("agents", "id", langChainAgentId)).isEqualTo(1L);
    }

    @Test
    void aConcurrentPreviewRefusesWhileAnExecuteIsInFlight() throws Exception {
        seedMixedCoreFixture();
        LatchRuntimeView blocking = new LatchRuntimeView();
        LegacyRetirementService inFlight = serviceWith(blocking);
        RetirementManifest reviewed = inFlight.preview(operator);
        blocking.arm();

        AtomicReference<Throwable> refusedPreview = new AtomicReference<>();
        AtomicReference<Throwable> attackerFailure = new AtomicReference<>();
        Thread attacker = new Thread(() -> {
            try {
                if (!blocking.awaitEntered()) {
                    attackerFailure.set(new AssertionError("retirement derivation never entered"));
                    return;
                }
                refusedPreview.set(catchThrowable(() -> inFlight.preview(operator)));
            } catch (Throwable t) {
                attackerFailure.set(t);
            } finally {
                blocking.release();
            }
        });
        attacker.start();

        RetirementReceipt receipt = inFlight.execute(reviewed.previewId(), reviewed.digest(), operator);
        attacker.join(60_000);

        assertThat(attackerFailure.get()).isNull();
        assertThat(receipt.deletedAgents()).isEqualTo(1);
        assertThat(receipt.deletedRuns()).isEqualTo(2);
        assertThat(refusedPreview.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Retirement operation already in flight");
    }

    /* ------------------------------------------------------------------ */
    /* Fixture                                                             */
    /* ------------------------------------------------------------------ */

    private void seedMixedCoreFixture() {
        langChainAgentId = UUID.randomUUID();
        openCodeAgentId = UUID.randomUUID();
        agents.save(agent(langChainAgentId, "legacy-langchain-agent", "dev", "langchain"));
        agents.save(agent(openCodeAgentId, "current-opencode-agent", "qa", "opencode"));

        agentTools.save(AgentTool.builder()
                .id(new AgentToolId(langChainAgentId.toString(), "tool-1")).assignedBy("operator").build());
        agentTools.save(AgentTool.builder()
                .id(new AgentToolId(langChainAgentId.toString(), "tool-2")).assignedBy("operator").build());
        agentSkills.save(AgentSkill.builder()
                .id(new AgentSkillId(langChainAgentId.toString(), "skill-1")).build());
        agentTools.save(AgentTool.builder()
                .id(new AgentToolId(openCodeAgentId.toString(), "tool-1")).assignedBy("operator").build());
        agentSkills.save(AgentSkill.builder()
                .id(new AgentSkillId(openCodeAgentId.toString(), "skill-1")).build());

        langRun1 = UUID.randomUUID();
        langRun2 = UUID.randomUUID();
        openCodeRunId = UUID.randomUUID();
        runs.save(run(langRun1, langChainAgentId, RunStatus.COMPLETED, "legacy run one", "conv-legacy-1"));
        runs.save(run(langRun2, langChainAgentId, RunStatus.FAILED, "legacy run two", "conv-legacy-2"));
        runs.save(run(openCodeRunId, openCodeAgentId, RunStatus.COMPLETED, "opencode run", "conv-opencode"));

        langApproval1 = UUID.randomUUID();
        langApproval2 = UUID.randomUUID();
        openCodeApprovalId = UUID.randomUUID();
        approvals.save(approval(langApproval1, langRun1, ApprovalStatus.DENIED));
        approvals.save(approval(langApproval2, langRun2, ApprovalStatus.APPROVED));
        approvals.save(approval(openCodeApprovalId, openCodeRunId, ApprovalStatus.APPROVED));

        langPermissionRequestId = UUID.randomUUID();
        permissionRequests.save(AcpPermissionRequest.builder()
                .id(langPermissionRequestId).approvalId(langApproval1)
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(langRun1).sessionId("legacy-session").requestId("req-1")
                .toolName("write_file").target("NATIVE_TOOL").argumentsDigest("digest-1")
                .expiresAt(T0.plusSeconds(300))
                .deliveryState(PermissionDeliveryState.AWAITING_DECISION.name())
                .createdAt(T0).build());

        langTrajectoryId = UUID.randomUUID();
        trajectories.save(SessionTrajectory.builder().id(langTrajectoryId).runId(langRun1)
                .turnNumber(1).role("assistant").content("legacy turn").createdAt(T0).build());

        langToolCallId = UUID.randomUUID();
        toolCalls.save(ToolCall.builder().id(langToolCallId).runId(langRun1)
                .toolName("write_file").status(ToolCallStatus.COMPLETED).createdAt(T0).build());

        sessions.save(AgentSession.builder().runId(langRun1).agentId(langChainAgentId)
                .status(SessionStatus.COMPLETED).build());

        bindings.save(RunExecutionBinding.builder().runId(langRun1).agentId(langChainAgentId)
                .coreId("langchain").executionMode(ExecutionMode.HOST).settingsJson("{}").build());

        langRunOwnedPromptCallId = promptCalls.save(PromptCall.builder().runId(langRun1)
                .agentId(langChainAgentId).provider("deepseek").model("legacy").outcome("success")
                .createdAt(T0).build()).getId();
        langDirectPromptCallId = promptCalls.save(PromptCall.builder().runId(null)
                .agentId(langChainAgentId).provider("deepseek").model("legacy").outcome("success")
                .createdAt(T0).build()).getId();

        cardId = UUID.randomUUID().toString();
        kanban.save(KanbanItem.builder().id(cardId).title("shared card")
                .description("shared business record").status(KanbanStatus.TODO)
                .priority(KanbanPriority.HIGH).assignee("operator").labels("shared")
                .linkedAgentId(langChainAgentId.toString()).linkedRunId(langRun1.toString())
                .agentTemplateId("ba-agent").build());

        chainId = UUID.randomUUID();
        List<WorkflowStep> steps = List.of(
                WorkflowStep.builder().agentId(langChainAgentId).runId(langRun1)
                        .promptTemplate("legacy step").status(WorkflowStep.Status.COMPLETED)
                        .output("legacy-output").build(),
                WorkflowStep.builder().agentId(openCodeAgentId).kind(WorkflowStep.StepKind.QA)
                        .promptTemplate("current step").status(WorkflowStep.Status.PENDING)
                        .output("qa-output").build());
        chain = chains.save(WorkflowChain.builder().id(chainId).name("mixed chain")
                .status(WorkflowChain.Status.RUNNING).currentStepIndex(0)
                .stepsJson(workflowService.serializeSteps(steps)).build());

        auditLangAgentId = auditEvents.save(audit("AGENT_CREATED", "Agent",
                langChainAgentId.toString(), "CREATE", "conv-legacy-1")).getId();
        auditLangRunId = auditEvents.save(audit("RUN_STARTED", "Run",
                langRun1.toString(), "START", "conv-legacy-1")).getId();
        auditLangActionId = auditEvents.save(audit("ACTION_EXECUTED", "Action",
                langToolCallId.toString(), "write_file", "conv-legacy-1")).getId();
        auditOpenCodeAgentId = auditEvents.save(audit("AGENT_CREATED", "Agent",
                openCodeAgentId.toString(), "CREATE", "conv-opencode")).getId();
        auditOpenCodeRunId = auditEvents.save(audit("RUN_STARTED", "Run",
                openCodeRunId.toString(), "START", "conv-opencode")).getId();
        auditConversationTrapId = auditEvents.save(audit("AGENT_CREATED", "Agent",
                openCodeAgentId.toString(), "CREATE", "conv-legacy-1")).getId();
    }

    private Agent agent(UUID id, String name, String role, String provider) {
        return Agent.builder().id(id).name(name).role(role).agentType(AgentType.NATIVE)
                .adkProvider(provider)
                .executionMode("opencode".equals(provider) ? ExecutionMode.SANDBOX : null)
                .config("{}").healthStatus(HealthStatus.HEALTHY).pickupEnabled(true).build();
    }

    private Run run(UUID id, UUID agentId, RunStatus status, String prompt, String conversationId) {
        return Run.builder().id(id).agentId(agentId).status(status).promptSeed(prompt)
                .conversationId(conversationId).build();
    }

    private Approval approval(UUID id, UUID runId, ApprovalStatus status) {
        return Approval.builder().id(id).runId(runId).status(status).reason("fixture")
                .approvalType(Approval.ApprovalType.TOOL_CALL).askType(Approval.AskType.APPROVAL)
                .requestedAt(T0).build();
    }

    private AuditEvent audit(String eventType, String resourceType, String resourceId,
                             String action, String conversationId) {
        return AuditEvent.builder().eventType(eventType).resourceType(resourceType)
                .resourceId(resourceId).action(action).details("fixture")
                .conversationId(conversationId).build();
    }

    private Agent builtin(UUID id) {
        return agents.findById(id).orElseThrow();
    }

    private WorkflowChain reloadChain() {
        return chains.findById(chainId).orElseThrow();
    }

    private List<AuditEvent> auditEventsFor(UUID agentId) {
        return auditEvents.findByResourceTypeAndResourceId("Agent", agentId.toString());
    }

    /* ------------------------------------------------------------------ */
    /* Native-SQL reads: never satisfied by the first-level cache           */
    /* ------------------------------------------------------------------ */

    private long nativeCount(String table, String column, Object value) {
        entityManager.flush();
        Object result = entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = :v")
                .setParameter("v", String.valueOf(value)).getSingleResult();
        return ((Number) result).longValue();
    }

    private String nativeString(String table, String column, String idColumn, String idValue) {
        entityManager.flush();
        Object result = entityManager.createNativeQuery(
                        "SELECT CAST(" + column + " AS VARCHAR) FROM " + table
                                + " WHERE CAST(" + idColumn + " AS VARCHAR) = :id")
                .setParameter("id", idValue).getSingleResult();
        return result == null ? null : result.toString();
    }

    private long auditCountByConversation(String conversationId) {
        entityManager.flush();
        Object result = entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM audit_log WHERE conversation_id = :c")
                .setParameter("c", conversationId).getSingleResult();
        return ((Number) result).longValue();
    }

    private String nativeColumns(String table, String columns, String idColumn, String idValue) {
        entityManager.flush();
        Object result = entityManager.createNativeQuery(
                        "SELECT " + columns + " FROM " + table
                                + " WHERE CAST(" + idColumn + " AS VARCHAR) = :id")
                .setParameter("id", idValue).getSingleResult();
        if (result instanceof Object[] row) {
            return Arrays.toString(row);
        }
        return String.valueOf(result);
    }

    private Map<String, Object> protectedRecords() {
        Map<String, Object> records = new LinkedHashMap<>();
        records.put("agent:" + openCodeAgentId, nativeColumns("agents",
                "name, role, adk_provider, execution_mode, health_status, pickup_enabled",
                "id", openCodeAgentId.toString()));
        records.put("run:" + openCodeRunId, nativeColumns("runs",
                "status, prompt_seed, conversation_id", "id", openCodeRunId.toString()));
        records.put("approval:" + openCodeApprovalId, nativeCount("approvals", "id", openCodeApprovalId));
        records.put("agent-tools:" + openCodeAgentId,
                nativeCount("agent_tools", "agent_id", openCodeAgentId));
        records.put("agent-skills:" + openCodeAgentId,
                nativeCount("agent_skills", "agent_id", openCodeAgentId));
        records.put("card:" + cardId, nativeColumns("kanban_items",
                "title, description, status, priority, labels, assignee, agent_template_id",
                "id", cardId));
        List<WorkflowStep> steps = workflowService.deserializeSteps(nativeString(
                "workflow_chains", "steps_json", "id", chainId.toString()));
        WorkflowStep otherStep = steps.get(1);
        records.put("chain-other-step:" + chainId, otherStep.getAgentId() + "|"
                + otherStep.getPromptTemplate() + "|" + otherStep.getStatus() + "|"
                + otherStep.getOutput() + "|" + otherStep.getRunId());
        records.put("audit-Agent:" + openCodeAgentId,
                auditCountBy("Agent", openCodeAgentId.toString()));
        records.put("audit-Run:" + openCodeRunId, auditCountBy("Run", openCodeRunId.toString()));
        records.put("audit-Action:" + openCodeRunId,
                auditCountBy("Action", openCodeRunId.toString()));
        return records;
    }

    private long auditCountBy(String resourceType, String resourceId) {
        entityManager.flush();
        Object result = entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM audit_log WHERE resource_type = :t"
                                + " AND CAST(resource_id AS VARCHAR) = :r")
                .setParameter("t", resourceType).setParameter("r", resourceId).getSingleResult();
        return ((Number) result).longValue();
    }

    /* ------------------------------------------------------------------ */
    /* Controller request helpers                                           */
    /* ------------------------------------------------------------------ */

    private MockHttpServletRequest anonymous(String method) {
        return new MockHttpServletRequest(method, "/api/v1/maintenance/langchain/preview");
    }

    private MockHttpServletRequest operatorRequest() {
        MockHttpServletRequest request = anonymous("POST");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer operator-secret");
        return request;
    }

    private MockHttpServletRequest workerRequest() {
        MockHttpServletRequest request = anonymous("POST");
        request.setAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE, worker);
        return request;
    }

    private void assertStatus(ResponseEntity<?> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
    }

    /* ------------------------------------------------------------------ */
    /* Test doubles for the two collaborator seams the IT must control      */
    /* ------------------------------------------------------------------ */

    /** Order-preserving mutable clock so an expired preview is testable without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The quiescence view as the run coordinator would report it. */
    private static final class RuntimeView implements RuntimeActivity {

        private final Map<UUID, Set<UUID>> activeByAgent = new ConcurrentHashMap<>();
        /** One-shot hook: runs inside the next {@code activeRuns} call (derivation tail). */
        private final AtomicReference<Runnable> onActiveRuns = new AtomicReference<>();

        void activate(UUID agentId, UUID runId) {
            activeByAgent.computeIfAbsent(agentId, key -> ConcurrentHashMap.newKeySet()).add(runId);
        }

        void deactivate(UUID runId) {
            activeByAgent.values().forEach(set -> set.remove(runId));
        }

        void onActiveRuns(Runnable action) {
            onActiveRuns.set(action);
        }

        @Override
        public boolean writersStopped(UUID runId) {
            return activeByAgent.values().stream().noneMatch(set -> set.contains(runId));
        }

        @Override
        public Set<UUID> activeRuns(UUID agentId) {
            Runnable action = onActiveRuns.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return Set.copyOf(new LinkedHashSet<>(activeByAgent.getOrDefault(agentId, Set.of())));
        }
    }

    /**
     * Runtime view that parks a derivation on a latch, so the single-flight
     * refusal is observable from a second thread without any sleeping.
     */
    private static final class LatchRuntimeView implements RuntimeActivity {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean();

        void arm() {
            armed.set(true);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(60, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        @Override
        public boolean writersStopped(UUID runId) {
            return true;
        }

        @Override
        public Set<UUID> activeRuns(UUID agentId) {
            if (armed.get()) {
                entered.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("single-flight latch was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while parked in the runtime view", e);
                }
            }
            return Set.of();
        }
    }

    /** The frozen before/after view of everything retirement must not touch. */
    private final class Snapshot {

        Map<String, Object> protectedRecords() {
            return LegacyRetirementServiceIntegrationTest.this.protectedRecords();
        }
    }
}
