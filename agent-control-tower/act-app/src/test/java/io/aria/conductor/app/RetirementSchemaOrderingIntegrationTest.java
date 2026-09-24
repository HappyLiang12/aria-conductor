package io.aria.conductor.app;

import io.aria.conductor.ActApplication;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
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
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.approval.PermissionDeliveryState;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanPriority;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.maintenance.LegacyRetirementService;
import io.aria.conductor.execution.maintenance.RetirementManifest;
import io.aria.conductor.execution.maintenance.RetirementReceipt;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.RuntimeActivity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Task 14 verification addition (fix round 2): the preview-first retirement's
 * child-before-parent delete ordering against the schema the application is
 * actually migrated to.
 *
 * <p>The retirement IT in {@code act-execution} runs on a Hibernate-generated
 * schema with no foreign keys, so it can never observe a parent-first delete
 * order. This test drives the <em>production</em> service bean (real wiring,
 * real {@code TransactionTemplate}) inside the real {@code ActApplication}
 * context against the Flyway-migrated H2 schema (V1-V62), with every FK edge of
 * the fixture populated: {@code session_trajectory}, {@code tool_calls},
 * {@code approvals} and {@code prompt_calls} reference the runs, and the runs
 * plus the agent session reference the agent. The destructive-path proof deletes
 * a parent row directly through plain SQL first: the database refuses it while
 * the children exist, so the service's own successful parent deletion can only
 * pass because it removed the children before the parents.
 *
 * <p>Seeding and every assertion go through the production repositories and
 * plain SQL on the migrated schema; the only test double is the
 * {@link RuntimeActivity} quiescence view, which the production context does not
 * have until Task 18 wires the run coordinator (the service fails closed without
 * one). The double reports a fully quiescent installation -- no active run, no
 * unstopped writer -- and stubs no business behaviour.
 */
@SpringBootTest(classes = {
        ActApplication.class,
        NoopLlmTestConfig.class,
        RetirementSchemaOrderingIntegrationTest.QuiescentRuntimeActivityConfig.class
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "noop-llm"})
@Sql(scripts = "classpath:db/cleanup-all.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RetirementSchemaOrderingIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired private LegacyRetirementService retirement;
    @Autowired private AgentRepository agents;
    @Autowired private RunRepository runs;
    @Autowired private ApprovalRepository approvals;
    @Autowired private PromptCallRepository promptCalls;
    @Autowired private AcpPermissionRequestRepository permissionRequests;
    @Autowired private RunExecutionBindingRepository bindings;
    @Autowired private SessionTrajectoryRepository trajectories;
    @Autowired private ToolCallRepository toolCalls;
    @Autowired private AgentSessionRepository sessions;
    @Autowired private KanbanRepository kanban;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;

    /** The configuration-managed operator credential: no expiry, the only retirement authority. */
    private final ActorPrincipal operator = ActorPrincipal.operator(null);

    private UUID langChainAgentId;
    private UUID openCodeAgentId;
    private UUID langRun1;
    private UUID langRun2;
    private UUID openCodeRunId;
    private String legacyConversation;
    private UUID langApprovalId;
    private UUID openCodeApprovalId;
    private UUID langPermissionRequestId;
    private UUID langTrajectoryId;
    private UUID langToolCallId;
    private Long langRunPromptCallId;
    private Long langAgentPromptCallId;
    private String cardId;
    private Long auditLangAgentId;
    private Long auditLangRunId;
    private Long auditLangActionId;
    private Long auditOpenCodeAgentId;
    private Long auditOpenCodeRunId;
    private Long auditConversationTrapId;

    /**
     * The destructive path and the ordered deletion, executed once against the
     * migrated schema. Every FK-constrained child of the frozen scope is present
     * before the retirement runs, and the counts are asserted exactly.
     */
    @Test
    void executeAgainstTheMigratedSchemaDeletesChildrenBeforeParentsAndCommitsOnce() {
        seedLegacyAndCurrentCore();

        // 1. The destructive path, proven on the very schema the retirement runs
        //    against: a direct parent delete is refused while its FK-constrained
        //    children exist. Without that refusal the ordered deletion below
        //    would be unobservable on this database.
        assertParentDeleteRefusedByForeignKey("runs", langRun1);
        assertParentDeleteRefusedByForeignKey("agents", langChainAgentId);

        // The refused attempts changed nothing.
        assertThat(rowsIn("runs", "id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("agents", "id", langChainAgentId)).isEqualTo(1L);

        // 2. Every FK-constrained child of the frozen scope is present beforehand.
        assertThat(rowsIn("runs", "agent_id", langChainAgentId)).isEqualTo(2L);
        assertThat(rowsIn("session_trajectory", "run_id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("tool_calls", "run_id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("approvals", "run_id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("prompt_calls", "run_id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("agent_sessions", "run_id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("run_execution_bindings", "run_id", langRun1)).isEqualTo(1L);
        assertThat(rowsIn("acp_permission_request", "run_id", langRun1)).isEqualTo(1L);

        // 3. Preview freezes exactly the legacy scope on the migrated schema.
        RetirementManifest preview = retirement.preview(operator);
        assertThat(preview.agentIds()).containsExactly(langChainAgentId);
        assertThat(preview.runIds()).containsExactlyInAnyOrder(langRun1, langRun2);
        assertThat(preview.approvalIds()).containsExactly(langApprovalId);
        assertThat(preview.auditEventIds()).containsExactlyInAnyOrder(
                auditLangAgentId, auditLangRunId, auditLangActionId);
        assertThat(preview.permissionRequestIds()).containsExactly(langPermissionRequestId);
        assertThat(preview.trajectoryIds()).containsExactly(langTrajectoryId);
        assertThat(preview.toolCallIds()).containsExactly(langToolCallId);
        assertThat(preview.promptCallIds()).containsExactlyInAnyOrder(
                langRunPromptCallId, langAgentPromptCallId);
        assertThat(preview.agentSessionRunIds()).containsExactly(langRun1);
        assertThat(preview.kanbanCardIds()).containsExactly(cardId);
        assertThat(preview.workflowChainIds()).isEmpty();

        // 4. Execute once: the receipt counts are the exact fixture counts.
        RetirementReceipt receipt =
                retirement.execute(preview.previewId(), preview.digest(), operator);
        assertThat(receipt.previewId()).isEqualTo(preview.previewId());
        assertThat(receipt.deletedAgents()).isEqualTo(1);
        assertThat(receipt.deletedRuns()).isEqualTo(2);
        assertThat(receipt.deletedApprovals()).isEqualTo(1);
        assertThat(receipt.deletedAuditEvents()).isEqualTo(3);
        assertThat(receipt.deletedPermissionRequests()).isEqualTo(1);
        assertThat(receipt.deletedTrajectories()).isEqualTo(1);
        assertThat(receipt.deletedToolCalls()).isEqualTo(1);
        assertThat(receipt.deletedRunPromptCalls()).isEqualTo(1);
        assertThat(receipt.deletedAgentPromptCalls()).isEqualTo(1);
        assertThat(receipt.deletedRunBindings()).isEqualTo(1);
        assertThat(receipt.deletedAgentSessions()).isEqualTo(1);
        assertThat(receipt.deletedAgentToolBindings()).isEqualTo(0);
        assertThat(receipt.deletedAgentSkillBindings()).isEqualTo(0);
        assertThat(receipt.unlinkedKanbanCards()).isEqualTo(1);
        assertThat(receipt.unlinkedWorkflowSteps()).isEqualTo(0);
        assertThat(receipt.isNoOp()).isFalse();

        // 5. The committed state, read fresh: the whole legacy scope is gone --
        //    parents and every FK child -- with no partial deletion anywhere.
        assertThat(rowsIn("agents", "id", langChainAgentId)).isEqualTo(0L);
        assertThat(rowsIn("runs", "id", langRun1)).isEqualTo(0L);
        assertThat(rowsIn("runs", "id", langRun2)).isEqualTo(0L);
        assertThat(rowsIn("session_trajectory", "run_id", langRun1)).isEqualTo(0L);
        assertThat(rowsIn("tool_calls", "run_id", langRun1)).isEqualTo(0L);
        assertThat(rowsIn("approvals", "id", langApprovalId)).isEqualTo(0L);
        assertThat(rowsIn("prompt_calls", "id", langRunPromptCallId)).isEqualTo(0L);
        assertThat(rowsIn("prompt_calls", "id", langAgentPromptCallId)).isEqualTo(0L);
        assertThat(rowsIn("agent_sessions", "run_id", langRun1)).isEqualTo(0L);
        assertThat(rowsIn("run_execution_bindings", "run_id", langRun1)).isEqualTo(0L);
        assertThat(rowsIn("acp_permission_request", "id", langPermissionRequestId)).isEqualTo(0L);
        assertThat(rowsIn("audit_log", "id", auditLangAgentId)).isEqualTo(0L);
        assertThat(rowsIn("audit_log", "id", auditLangRunId)).isEqualTo(0L);
        assertThat(rowsIn("audit_log", "id", auditLangActionId)).isEqualTo(0L);

        // 6. The other core survives, byte-identical on its business fields.
        assertThat(rowsIn("agents", "id", openCodeAgentId)).isEqualTo(1L);
        assertThat(columnOf("agents", "name, role, adk_provider, execution_mode",
                "id", openCodeAgentId.toString()))
                .isEqualTo("current-opencode-agent, qa, opencode, SANDBOX");
        assertThat(rowsIn("runs", "id", openCodeRunId)).isEqualTo(1L);
        assertThat(rowsIn("approvals", "id", openCodeApprovalId)).isEqualTo(1L);
        assertThat(columnOf("audit_log", "resource_type, resource_id, action",
                "id", auditOpenCodeAgentId.toString()))
                .isEqualTo("Agent, " + openCodeAgentId + ", CREATE");
        assertThat(columnOf("audit_log", "resource_type, resource_id, action",
                "id", auditOpenCodeRunId.toString()))
                .isEqualTo("Run, " + openCodeRunId + ", START");
        // The trap row shares the deleted run's conversation verbatim and must
        // survive: audit deletion is by verified resource identity, never by
        // conversation.
        assertThat(columnOf("audit_log", "resource_type, resource_id, action, conversation_id",
                "id", auditConversationTrapId.toString()))
                .isEqualTo("Agent, " + openCodeAgentId + ", CREATE, " + legacyConversation);
        assertThat(rowsIn("audit_log", "conversation_id", legacyConversation)).isEqualTo(1L);

        // 7. The shared card keeps its business fields and loses both target links.
        assertThat(text("kanban_items", "linked_agent_id", "id", cardId)).isNull();
        assertThat(text("kanban_items", "linked_run_id", "id", cardId)).isNull();
        assertThat(text("kanban_items", "title", "id", cardId)).isEqualTo("shared retirement card");
        assertThat(text("kanban_items", "description", "id", cardId))
                .isEqualTo("shared business record, not retirement scope");
        assertThat(text("kanban_items", "status", "id", cardId)).isEqualTo("TODO");
        assertThat(text("kanban_items", "priority", "id", cardId)).isEqualTo("HIGH");
        assertThat(text("kanban_items", "assignee", "id", cardId)).isEqualTo("operator");
        assertThat(text("kanban_items", "labels", "id", cardId)).isEqualTo("shared");
        assertThat(text("kanban_items", "agent_template_id", "id", cardId)).isEqualTo("ba-agent");
    }

    /**
     * The ordering property the empty schema cannot show: after the one
     * executed retirement the selection is genuinely empty (no orphaned child
     * left behind, no audit row alive, no card still linked), so the next
     * preview/execute pair is a no-op -- and the consumed preview is one-use.
     */
    @Test
    void theRerunAgainstTheMigratedSchemaIsANoOpAndThePreviewIsOneUse() {
        seedLegacyAndCurrentCore();

        RetirementManifest first = retirement.preview(operator);
        RetirementReceipt executed =
                retirement.execute(first.previewId(), first.digest(), operator);
        assertThat(executed.deletedAgents()).isEqualTo(1);
        assertThat(executed.deletedRuns()).isEqualTo(2);

        RetirementManifest second = retirement.preview(operator);
        assertThat(second.agentIds()).isEmpty();
        assertThat(second.runIds()).isEmpty();
        assertThat(second.approvalIds()).isEmpty();
        assertThat(second.auditEventIds()).isEmpty();
        assertThat(second.permissionRequestIds()).isEmpty();
        assertThat(second.trajectoryIds()).isEmpty();
        assertThat(second.toolCallIds()).isEmpty();
        assertThat(second.promptCallIds()).isEmpty();
        assertThat(second.agentSessionRunIds()).isEmpty();
        assertThat(second.agentToolBindingIds()).isEmpty();
        assertThat(second.agentSkillBindingIds()).isEmpty();
        assertThat(second.kanbanCardIds()).isEmpty();
        assertThat(second.workflowChainIds()).isEmpty();

        RetirementReceipt noOp = retirement.execute(second.previewId(), second.digest(), operator);
        assertThat(noOp.isNoOp()).isTrue();
        assertThat(noOp.deletedAgents()).isEqualTo(0);
        assertThat(noOp.deletedRuns()).isEqualTo(0);
        assertThat(noOp.deletedApprovals()).isEqualTo(0);
        assertThat(noOp.deletedAuditEvents()).isEqualTo(0);
        assertThat(noOp.deletedPermissionRequests()).isEqualTo(0);
        assertThat(noOp.deletedTrajectories()).isEqualTo(0);
        assertThat(noOp.deletedToolCalls()).isEqualTo(0);
        assertThat(noOp.deletedRunPromptCalls()).isEqualTo(0);
        assertThat(noOp.deletedAgentPromptCalls()).isEqualTo(0);
        assertThat(noOp.deletedRunBindings()).isEqualTo(0);
        assertThat(noOp.deletedAgentSessions()).isEqualTo(0);
        assertThat(noOp.deletedAgentToolBindings()).isEqualTo(0);
        assertThat(noOp.deletedAgentSkillBindings()).isEqualTo(0);
        assertThat(noOp.unlinkedKanbanCards()).isEqualTo(0);
        assertThat(noOp.unlinkedWorkflowSteps()).isEqualTo(0);

        assertThat(rowsIn("agents", "id", langChainAgentId)).isEqualTo(0L);
        assertThat(rowsIn("runs", "agent_id", langChainAgentId)).isEqualTo(0L);
        assertThat(rowsIn("agents", "id", openCodeAgentId)).isEqualTo(1L);
        assertThat(rowsIn("runs", "id", openCodeRunId)).isEqualTo(1L);

        assertThatThrownBy(() -> retirement.execute(first.previewId(), first.digest(), operator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown retirement preview: " + first.previewId());
    }

    /* ------------------------------------------------------------------ */
    /* Fixture: one legacy core with every FK edge populated                */
    /* ------------------------------------------------------------------ */

    private void seedLegacyAndCurrentCore() {
        // Per-seed conversation: the trap row below shares it verbatim with the
        // deleted runs, so its survival is a per-fixture observation. The column
        // is VARCHAR(36), the width of one conversation UUID.
        legacyConversation = UUID.randomUUID().toString();
        langChainAgentId = UUID.randomUUID();
        openCodeAgentId = UUID.randomUUID();
        agents.save(agent(langChainAgentId, "legacy-langchain-agent", "dev", "langchain"));
        agents.save(agent(openCodeAgentId, "current-opencode-agent", "qa", "opencode"));

        // runs.agent_id -> agents.id (fk_runs_agent)
        langRun1 = UUID.randomUUID();
        langRun2 = UUID.randomUUID();
        openCodeRunId = UUID.randomUUID();
        runs.save(run(langRun1, langChainAgentId, RunStatus.COMPLETED, "legacy run one",
                legacyConversation));
        runs.save(run(langRun2, langChainAgentId, RunStatus.FAILED, "legacy run two",
                legacyConversation));
        runs.save(run(openCodeRunId, openCodeAgentId, RunStatus.COMPLETED, "current run",
                "conv-current"));

        // approvals.run_id -> runs.id (fk_approvals_run). Not PENDING: a pending
        // ask would (correctly) refuse the retirement before the deletion.
        langApprovalId = UUID.randomUUID();
        openCodeApprovalId = UUID.randomUUID();
        approvals.save(approval(langApprovalId, langRun1, ApprovalStatus.APPROVED));
        approvals.save(approval(openCodeApprovalId, openCodeRunId, ApprovalStatus.APPROVED));

        // session_trajectory.run_id -> runs.id (fk_trajectory_run)
        langTrajectoryId = UUID.randomUUID();
        trajectories.save(SessionTrajectory.builder().id(langTrajectoryId).runId(langRun1)
                .turnNumber(1).role("assistant").content("legacy turn").createdAt(T0).build());

        // tool_calls.run_id -> runs.id (fk_tool_calls_run)
        langToolCallId = UUID.randomUUID();
        toolCalls.save(ToolCall.builder().id(langToolCallId).runId(langRun1)
                .toolName("write_file").status(ToolCallStatus.COMPLETED).createdAt(T0).build());

        // agent_sessions.agent_id -> agents.id (fk_sessions_agent); run_id is the key
        sessions.save(AgentSession.builder().runId(langRun1).agentId(langChainAgentId)
                .status(SessionStatus.COMPLETED).build());

        // prompt_calls.run_id -> runs.id (fk_prompt_calls_run); V3 made run_id nullable
        langRunPromptCallId = promptCalls.save(PromptCall.builder().runId(langRun1)
                .agentId(langChainAgentId).provider("deepseek").model("legacy").outcome("success")
                .createdAt(T0).build()).getId();
        langAgentPromptCallId = promptCalls.save(PromptCall.builder().runId(null)
                .agentId(langChainAgentId).provider("deepseek").model("legacy").outcome("success")
                .createdAt(T0).build()).getId();

        // Task 2 / Task 12 children of the run (no FK in V59/V62 by design). The
        // @Version field stays null so Spring Data treats the row as new and
        // inserts it; Hibernate seeds the stored version itself.
        bindings.save(RunExecutionBinding.builder().runId(langRun1).agentId(langChainAgentId)
                .coreId("langchain").executionMode(ExecutionMode.HOST).settingsJson("{}")
                .createdAt(T0).build());
        langPermissionRequestId = UUID.randomUUID();
        permissionRequests.save(AcpPermissionRequest.builder()
                .id(langPermissionRequestId).approvalId(langApprovalId)
                .kind(AcpPermissionRequest.Kind.NATIVE_PERMISSION)
                .runId(langRun1).sessionId("legacy-session").requestId("req-1")
                .toolName("write_file").target("NATIVE_TOOL").argumentsDigest("digest-1")
                .expiresAt(T0.plusSeconds(300))
                .deliveryState(PermissionDeliveryState.AWAITING_DECISION.name())
                .createdAt(T0).build());

        // A shared board card that merely links into the frozen scope.
        cardId = UUID.randomUUID().toString();
        kanban.save(KanbanItem.builder().id(cardId).title("shared retirement card")
                .description("shared business record, not retirement scope").status(KanbanStatus.TODO)
                .priority(KanbanPriority.HIGH).assignee("operator").labels("shared")
                .linkedAgentId(langChainAgentId.toString()).linkedRunId(langRun1.toString())
                .agentTemplateId("ba-agent").build());

        // Audit rows of the frozen (type, id) sets, the other core's rows, and one
        // conversation trap row typed to the surviving agent.
        auditLangAgentId = auditEvents.save(audit("AGENT_CREATED", "Agent",
                langChainAgentId.toString(), "CREATE", legacyConversation)).getId();
        auditLangRunId = auditEvents.save(audit("RUN_STARTED", "Run",
                langRun1.toString(), "START", legacyConversation)).getId();
        auditLangActionId = auditEvents.save(audit("ACTION_EXECUTED", "Action",
                langToolCallId.toString(), "write_file", "conv-retire-action")).getId();
        auditOpenCodeAgentId = auditEvents.save(audit("AGENT_CREATED", "Agent",
                openCodeAgentId.toString(), "CREATE", "conv-current")).getId();
        auditOpenCodeRunId = auditEvents.save(audit("RUN_STARTED", "Run",
                openCodeRunId.toString(), "START", "conv-current")).getId();
        auditConversationTrapId = auditEvents.save(audit("AGENT_CREATED", "Agent",
                openCodeAgentId.toString(), "CREATE", legacyConversation)).getId();
    }

    private Agent agent(UUID id, String name, String role, String provider) {
        return Agent.builder().id(id).name(name).role(role).agentType(AgentType.NATIVE)
                .adkProvider(provider)
                .executionMode("opencode".equals(provider) ? ExecutionMode.SANDBOX : null)
                .config("{}").healthStatus(HealthStatus.HEALTHY).pickupEnabled(false).build();
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

    /* ------------------------------------------------------------------ */
    /* Plain-SQL reads on the migrated schema (never a first-level cache)   */
    /* ------------------------------------------------------------------ */

    /**
     * Deletes one parent row directly, by plain SQL, while its FK-constrained
     * children exist: the database must refuse with H2's referential-integrity
     * error and the row must survive. This is the destructive-path proof -- the
     * ordered deletion performed later by the service can only pass because the
     * children were removed first.
     */
    private void assertParentDeleteRefusedByForeignKey(String table, UUID id) {
        Throwable refused = catchThrowable(() -> jdbc.update(
                "DELETE FROM " + table + " WHERE CAST(id AS VARCHAR) = ?", id.toString()));

        assertThat(refused)
                .as("deleting %s %s before its children must be refused", table, id)
                .isInstanceOf(DataIntegrityViolationException.class);
        Throwable cause = ((DataIntegrityViolationException) refused).getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLIntegrityConstraintViolationException.class);
        // 23503 is H2's REFERENTIAL_INTEGRITY_VIOLATED_CHILD_EXISTS_1: an FK, not
        // some other integrity error (23502 null, 23505 unique) refused the row.
        assertThat(((SQLException) cause).getErrorCode()).isEqualTo(23503);
        assertThat(rowsIn(table, "id", id)).isEqualTo(1L);
    }

    private long rowsIn(String table, String column, Object value) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = ?",
                Long.class, String.valueOf(value));
        return count;
    }

    private String text(String table, String column, String idColumn, String idValue) {
        return jdbc.queryForObject("SELECT CAST(" + column + " AS VARCHAR) FROM " + table
                + " WHERE CAST(" + idColumn + " AS VARCHAR) = ?", String.class, idValue);
    }

    private String columnOf(String table, String columns, String idColumn, String idValue) {
        return jdbc.queryForObject("SELECT " + columns + " FROM " + table
                        + " WHERE CAST(" + idColumn + " AS VARCHAR) = ?",
                (resultSet, rowNumber) -> {
                    StringBuilder row = new StringBuilder();
                    for (int column = 1; column <= resultSet.getMetaData().getColumnCount(); column++) {
                        if (column > 1) {
                            row.append(", ");
                        }
                        row.append(resultSet.getString(column));
                    }
                    return row.toString();
                }, idValue);
    }

    /* ------------------------------------------------------------------ */
    /* The only test double: the Task 18 run-quiescence view               */
    /* ------------------------------------------------------------------ */

    /**
     * Supplies the {@link RuntimeActivity} bean the production context does not
     * declare until Task 18 wires the run coordinator. It reports a fully
     * quiescent installation (no active run, no unstopped writer) and stubs no
     * business behaviour: preview, digest verification, quiescence checks and
     * every deletion below run against the production repositories, the
     * production transaction template and the Flyway-migrated schema.
     */
    @TestConfiguration
    static class QuiescentRuntimeActivityConfig {

        @Bean
        RuntimeActivity runtimeActivity() {
            return new QuiescentRuntimeActivity();
        }
    }

    /** Nothing is running: no run of any agent is active and every writer has stopped. */
    static final class QuiescentRuntimeActivity implements RuntimeActivity {

        @Override
        public boolean writersStopped(UUID runId) {
            return true;
        }

        @Override
        public Set<UUID> activeRuns(UUID agentId) {
            return Set.of();
        }
    }
}
