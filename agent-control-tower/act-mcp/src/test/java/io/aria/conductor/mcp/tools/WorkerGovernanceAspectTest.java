package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.approval.WorkerScope;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.aria.conductor.mcp.McpCallerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4 ruling 7/8: enforcement of the worker/operator boundary at the tool
 * invocation seam. Every denial is one typed runtime exception carrying a
 * stable code ({@code OPERATOR_ONLY}, {@code GRANT_REQUIRED},
 * {@code SCOPE_MISMATCH}, {@code UNKNOWN_TOOL}, {@code INVALID_IDENTITY}) and
 * the delegate is never invoked (the fixture records every delegation, so the
 * negative assertions are interaction proofs). Absent identity stays
 * operator-equivalent (legacy none-mode); operator calls are never
 * scope-checked.
 */
class WorkerGovernanceAspectTest {

    private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_AGENT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final WriteGrantService writeGrants = new WriteGrantService();
    private final ToolPolicyRegistry registry = new ToolPolicyRegistry();
    private final WorkerFixture fixture = new WorkerFixture();
    private final WorkerFixture proxy = proxied();

    @AfterEach
    void clearIdentity() {
        McpCallerContext.clear();
    }

    private WorkerFixture proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(fixture);
        factory.addAspect(new WorkerGovernanceAspect(registry, writeGrants));
        return factory.getProxy();
    }

    private static WorkerScope scope(UUID runId, UUID agentId) {
        return new WorkerScope(runId, agentId, Instant.parse("2099-01-01T00:00:00Z"));
    }

    private void worker() {
        McpCallerContext.set(McpCallerContext.Caller.worker(scope(RUN, AGENT)));
    }

    private static WorkerGovernanceDeniedException.Code codeOf(Throwable throwable) {
        return ((WorkerGovernanceDeniedException) throwable).getCode();
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Test
    void workerRead_isAllowed() {
        worker();

        assertThat(proxy.listKnowledge("SKILL", "APPROVED")).isEqualTo("ok");
        assertThat(fixture.invoked).containsExactly("list_knowledge");
    }

    @Test
    void workerWithoutScope_isDeniedAsInvalidIdentity() {
        // A transport bug that classifies a request as worker but loses the scope must not
        // silently degrade into operator access.
        McpCallerContext.set(new McpCallerContext.Caller(McpCallerContext.Kind.WORKER, null));

        assertThatThrownBy(() -> proxy.listKnowledge("SKILL", null))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.INVALID_IDENTITY);
        assertThat(fixture.invoked).isEmpty();
    }

    // ── writes and grants ───────────────────────────────────────────────────

    @Test
    void workerWrite_withoutGrant_isDeniedAndNeverInvoked() {
        worker();

        assertThatThrownBy(() -> proxy.generateReport("title-value", "description-value"))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .hasMessageContaining("GRANT_REQUIRED")
                .hasMessageContaining("generate_report")
                .hasMessageNotContaining("title-value")
                .hasMessageNotContaining("description-value");
        assertThat(fixture.invoked).isEmpty();
    }

    @Test
    void workerWrite_withMatchingGrant_runsOnce_andTheReplayIsDenied() {
        worker();
        writeGrants.grant(RUN, "generate_report",
                WriteGrantService.effectiveArgsDigest(args("title", "t", "description", "d")));

        assertThat(proxy.generateReport("t", "d")).isEqualTo("ok");
        assertThat(fixture.invoked).containsExactly("generate_report");

        assertThatThrownBy(() -> proxy.generateReport("t", "d"))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.GRANT_REQUIRED);
        assertThat(fixture.invoked).containsExactly("generate_report");
    }

    @Test
    void workerWrite_withGrantForDifferentArgs_isDenied_andConsumesNothing() {
        worker();
        String approved = WriteGrantService.effectiveArgsDigest(args("title", "t", "description", "d"));
        writeGrants.grant(RUN, "generate_report", approved);

        assertThatThrownBy(() -> proxy.generateReport("t", "changed"))
                .isInstanceOf(WorkerGovernanceDeniedException.class);
        // The mismatched attempt consumed nothing: the approved call still succeeds.
        assertThat(proxy.generateReport("t", "d")).isEqualTo("ok");
    }

    @Test
    void workerWrite_withGrantForAnotherRun_isDenied() {
        worker();
        writeGrants.grant(OTHER_RUN, "generate_report",
                WriteGrantService.effectiveArgsDigest(args("title", "t", "description", "d")));

        assertThatThrownBy(() -> proxy.generateReport("t", "d"))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.GRANT_REQUIRED);
    }

    @Test
    void workerWrite_grantComputedWithoutTheOmittedOptional_authorizesTheNullBinding() {
        worker();
        // C2/C3 contract: both sides digest the effective (top-level non-null) argument map,
        // so a grant computed from the approval request without the omitted optional key
        // authorizes the invocation that binds that parameter to null.
        writeGrants.grant(RUN, "update_kanban_item", WriteGrantService.effectiveArgsDigest(
                args("id", "item-1", "title", "new title")));

        assertThat(proxy.updateKanbanItem("item-1", "new title", null)).isEqualTo("ok");
    }

    @Test
    void workerWrite_grantThatPinnedTheOptionalValue_doesNotAuthorizeTheNullBinding() {
        worker();
        // Reverse direction of the same contract: an approval that pinned a value for the
        // optional parameter does not authorize the call that omits it, and consumes nothing.
        writeGrants.grant(RUN, "update_kanban_item", WriteGrantService.effectiveArgsDigest(
                args("id", "item-2", "title", "new title", "description", "pinned description")));

        assertThatThrownBy(() -> proxy.updateKanbanItem("item-2", "new title", null))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.GRANT_REQUIRED);
        assertThat(proxy.updateKanbanItem("item-2", "new title", "pinned description")).isEqualTo("ok");
    }

    // ── operator-only tools ─────────────────────────────────────────────────

    @Test
    void operatorOnlyTools_areDeniedForWorkers() {
        worker();

        assertThatThrownBy(() -> proxy.decideApproval("approval-1", "APPROVED"))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.OPERATOR_ONLY);
        assertThatThrownBy(() -> proxy.retireAgent(OTHER_AGENT))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.OPERATOR_ONLY);
        assertThatThrownBy(() -> proxy.runAgent(AGENT, "prompt"))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.OPERATOR_ONLY);
        assertThat(fixture.invoked).isEmpty();
    }

    @Test
    void unknownTool_isDeniedForWorkers() {
        worker();

        assertThatThrownBy(() -> proxy.notARealTool("x"))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.UNKNOWN_TOOL);
        assertThat(fixture.invoked).isEmpty();
    }

    // ── scope binding ───────────────────────────────────────────────────────

    @Test
    void runScopedTool_withForeignRunId_isDenied_andWithOwnRunIdIsAllowed() {
        worker();

        assertThatThrownBy(() -> proxy.getRun(OTHER_RUN))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.SCOPE_MISMATCH);
        assertThat(proxy.getRun(RUN)).isEqualTo("ok");
        // Absent/null scope parameter cannot be a cross-run read; the tool itself decides.
        assertThat(proxy.getRun(null)).isEqualTo("ok");
    }

    @Test
    void agentScopedTool_withForeignAgentId_isDenied_andWithOwnAgentIdIsAllowed() {
        worker();

        assertThatThrownBy(() -> proxy.getAgent(OTHER_AGENT))
                .isInstanceOf(WorkerGovernanceDeniedException.class)
                .extracting(WorkerGovernanceAspectTest::codeOf)
                .isEqualTo(WorkerGovernanceDeniedException.Code.SCOPE_MISMATCH);
        assertThat(proxy.getAgent(AGENT)).isEqualTo("ok");
    }

    // ── operator / absent identity ──────────────────────────────────────────

    @Test
    void operatorCaller_isNeverScopeChecked_andSkipsGrants() {
        McpCallerContext.set(McpCallerContext.Caller.operator());

        assertThat(proxy.getRun(OTHER_RUN)).isEqualTo("ok");
        assertThat(proxy.generateReport("t", "d")).isEqualTo("ok");
        assertThat(proxy.decideApproval("approval-1", "APPROVED")).isEqualTo("ok");
        assertThat(proxy.notARealTool("x")).isEqualTo("ok");
        assertThat(fixture.invoked)
                .containsExactly("get_run", "generate_report", "decide_approval", "not_a_real_tool");
    }

    @Test
    void absentIdentity_isTreatedAsOperator_legacyNoneMode() {
        // No holder value: in-process invocations keep full access by design; only a
        // present-but-invalid identity is rejected (by the transport filter).
        assertThat(proxy.decideApproval("approval-1", "APPROVED")).isEqualTo("ok");
        assertThat(proxy.generateReport("t", "d")).isEqualTo("ok");
    }

    /** Minimal named-argument map helper (nulls allowed, insertion ordered). */
    private static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Test fixture exposing the same tool names and parameter names as the real
     * tool beans; every delegation appends to {@link #invoked} so denials can
     * assert "the delegate never ran".
     */
    public static class WorkerFixture {

        public final List<String> invoked = new ArrayList<>();

        @Tool(name = "list_knowledge")
        public String listKnowledge(String type, String status) {
            invoked.add("list_knowledge");
            return "ok";
        }

        @Tool(name = "generate_report")
        public String generateReport(String title, String description) {
            invoked.add("generate_report");
            return "ok";
        }

        @Tool(name = "update_kanban_item")
        public String updateKanbanItem(String id, String title, String description) {
            invoked.add("update_kanban_item");
            return "ok";
        }

        @Tool(name = "get_run")
        public String getRun(UUID id) {
            invoked.add("get_run");
            return "ok";
        }

        @Tool(name = "get_agent")
        public String getAgent(UUID id) {
            invoked.add("get_agent");
            return "ok";
        }

        @Tool(name = "decide_approval")
        public String decideApproval(String id, String decision) {
            invoked.add("decide_approval");
            return "ok";
        }

        @Tool(name = "retire_agent")
        public String retireAgent(UUID id) {
            invoked.add("retire_agent");
            return "ok";
        }

        @Tool(name = "run_agent")
        public String runAgent(UUID agentId, String prompt) {
            invoked.add("run_agent");
            return "ok";
        }

        @Tool(name = "not_a_real_tool")
        public String notARealTool(String x) {
            invoked.add("not_a_real_tool");
            return "ok";
        }
    }
}
