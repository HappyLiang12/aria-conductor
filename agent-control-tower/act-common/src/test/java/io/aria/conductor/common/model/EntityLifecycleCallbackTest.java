package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct tests of the JPA {@code @PrePersist}/{@code @PreUpdate} callbacks. These callbacks carry
 * real defaulting logic (id generation, timestamps, status/enum defaults) — they are invoked
 * directly (the test lives in the same package to reach the protected/package-private methods)
 * and assert both that defaults are applied when unset AND that caller-supplied values are
 * preserved.
 */
class EntityLifecycleCallbackTest {

    private static final Instant FIXED = Instant.parse("2020-01-01T00:00:00Z");
    private static final UUID FIXED_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // ---- Agent ----
    @Test
    void agent_onCreate_appliesDefaults() {
        Agent agent = new Agent();
        agent.onCreate();
        assertThat(agent.getId()).isNotNull();
        assertThat(agent.getCreatedAt()).isNotNull();
        assertThat(agent.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void agent_onCreate_preservesExistingValues() {
        Agent agent = new Agent();
        agent.setId(FIXED_UUID);
        agent.setCreatedAt(FIXED);
        agent.setHealthStatus(HealthStatus.UNHEALTHY);
        agent.onCreate();
        assertThat(agent.getId()).isEqualTo(FIXED_UUID);
        assertThat(agent.getCreatedAt()).isEqualTo(FIXED);
        assertThat(agent.getHealthStatus()).isEqualTo(HealthStatus.UNHEALTHY);
    }

    @Test
    void agent_onUpdate_setsUpdatedAt() {
        Agent agent = new Agent();
        agent.onUpdate();
        assertThat(agent.getUpdatedAt()).isNotNull();
    }

    // ---- Run ----
    @Test
    void run_onCreate_appliesDefaults() {
        Run run = new Run();
        run.onCreate();
        assertThat(run.getId()).isNotNull();
        assertThat(run.getCreatedAt()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void run_onCreate_preservesExistingStatus() {
        Run run = new Run();
        run.setStatus(RunStatus.RUNNING);
        run.onCreate();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void run_onUpdate_setsUpdatedAt() {
        Run run = new Run();
        run.onUpdate();
        assertThat(run.getUpdatedAt()).isNotNull();
    }

    // ---- ToolCall ----
    @Test
    void toolCall_onCreate_appliesDefaults() {
        ToolCall call = new ToolCall();
        call.onCreate();
        assertThat(call.getId()).isNotNull();
        assertThat(call.getCreatedAt()).isNotNull();
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.PENDING);
    }

    // ---- ToolPack ----
    @Test
    void toolPack_onCreate_appliesDefaults() {
        ToolPack pack = new ToolPack();
        pack.setStatus(null);
        pack.setSandboxMode(null);
        pack.onCreate();
        assertThat(pack.getCreatedAt()).isNotNull();
        assertThat(pack.getUpdatedAt()).isNotNull();
        assertThat(pack.getStatus()).isEqualTo(VersionStatus.PENDING);
        assertThat(pack.getSandboxMode()).isEqualTo("NONE");
    }

    @Test
    void toolPack_onCreate_preservesSuppliedSandboxMode() {
        ToolPack pack = new ToolPack();
        pack.setSandboxMode("DOCKER");
        pack.setStatus(VersionStatus.APPROVED);
        pack.onCreate();
        assertThat(pack.getSandboxMode()).isEqualTo("DOCKER");
        assertThat(pack.getStatus()).isEqualTo(VersionStatus.APPROVED);
    }

    @Test
    void toolPack_onUpdate_setsUpdatedAt() {
        ToolPack pack = new ToolPack();
        pack.onUpdate();
        assertThat(pack.getUpdatedAt()).isNotNull();
    }

    // ---- ToolDefinition ----
    @Test
    void toolDefinition_onCreate_setsBothTimestamps() {
        ToolDefinition def = new ToolDefinition();
        def.onCreate();
        assertThat(def.getCreatedAt()).isNotNull();
        assertThat(def.getUpdatedAt()).isNotNull();
        assertThat(def.getCreatedAt()).isEqualTo(def.getUpdatedAt());
    }

    @Test
    void toolDefinition_onCreate_preservesExistingCreatedAt() {
        ToolDefinition def = new ToolDefinition();
        def.setCreatedAt(FIXED);
        def.onCreate();
        assertThat(def.getCreatedAt()).isEqualTo(FIXED);
    }

    @Test
    void toolDefinition_onUpdate_setsUpdatedAt() {
        ToolDefinition def = new ToolDefinition();
        def.onUpdate();
        assertThat(def.getUpdatedAt()).isNotNull();
    }

    // ---- WorkflowChain ----
    @Test
    void workflowChain_onCreate_appliesDefaults() {
        WorkflowChain chain = new WorkflowChain();
        chain.onCreate();
        assertThat(chain.getId()).isNotNull();
        assertThat(chain.getCreatedAt()).isNotNull();
        assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.PENDING);
    }

    @Test
    void workflowChain_onUpdate_setsUpdatedAt() {
        WorkflowChain chain = new WorkflowChain();
        chain.onUpdate();
        assertThat(chain.getUpdatedAt()).isNotNull();
    }

    // ---- PromptCall ----
    @Test
    void promptCall_onCreate_setsCreatedAtWhenNull() {
        PromptCall call = new PromptCall();
        call.onCreate();
        assertThat(call.getCreatedAt()).isNotNull();
    }

    @Test
    void promptCall_onCreate_preservesExistingCreatedAt() {
        PromptCall call = new PromptCall();
        call.setCreatedAt(FIXED);
        call.onCreate();
        assertThat(call.getCreatedAt()).isEqualTo(FIXED);
    }

    // ---- SessionTrajectory ----
    @Test
    void sessionTrajectory_onCreate_appliesDefaults() {
        SessionTrajectory trajectory = new SessionTrajectory();
        trajectory.onCreate();
        assertThat(trajectory.getId()).isNotNull();
        assertThat(trajectory.getCreatedAt()).isNotNull();
    }

    // ---- PackCredential ----
    @Test
    void packCredential_onCreate_setsUpdatedAt() {
        PackCredential cred = new PackCredential();
        cred.onCreate();
        assertThat(cred.getUpdatedAt()).isNotNull();
    }

    @Test
    void packCredential_onUpdate_setsUpdatedAt() {
        PackCredential cred = new PackCredential();
        cred.onUpdate();
        assertThat(cred.getUpdatedAt()).isNotNull();
    }

    // ---- AgentSession ----
    @Test
    void agentSession_onCreate_appliesDefaults() {
        AgentSession session = new AgentSession();
        session.onCreate();
        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    // ---- SystemConfig ----
    @Test
    void systemConfig_onUpdate_setsUpdatedAt() {
        SystemConfig config = new SystemConfig();
        config.onUpdate();
        assertThat(config.getUpdatedAt()).isNotNull();
    }

    // ---- AuditEvent ----
    @Test
    void auditEvent_onCreate_setsCreatedAtWhenNull() {
        AuditEvent event = new AuditEvent();
        event.onCreate();
        assertThat(event.getCreatedAt()).isNotNull();
    }

    // ---- KnowledgeItem ----
    @Test
    void knowledgeItem_onCreate_appliesAllDefaults() {
        KnowledgeItem item = new KnowledgeItem();
        item.onCreate();
        assertThat(item.getId()).isNotNull();
        assertThat(item.getCreatedAt()).isNotNull();
        assertThat(item.getStatus()).isEqualTo(KnowledgeStatus.DRAFT);
        assertThat(item.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        assertThat(item.getEscalationCount()).isZero();
    }

    @Test
    void knowledgeItem_onCreate_preservesSuppliedSensitivityAndCount() {
        KnowledgeItem item = new KnowledgeItem();
        item.setSensitivity(Sensitivity.RESTRICTED);
        item.setEscalationCount(3);
        item.onCreate();
        assertThat(item.getSensitivity()).isEqualTo(Sensitivity.RESTRICTED);
        assertThat(item.getEscalationCount()).isEqualTo(3);
    }

    // ---- KnowledgeVersion ----
    @Test
    void knowledgeVersion_onCreate_appliesDefaults() {
        KnowledgeVersion version = new KnowledgeVersion();
        version.onCreate();
        assertThat(version.getId()).isNotNull();
        assertThat(version.getCreatedAt()).isNotNull();
        assertThat(version.getStatus()).isEqualTo(VersionStatus.PENDING);
    }

    // ---- LlmProvider ----
    @Test
    void llmProvider_onCreate_setsIdAndCreatedAt() {
        LlmProvider provider = new LlmProvider();
        provider.onCreate();
        assertThat(provider.getId()).isNotNull();
        assertThat(provider.getCreatedAt()).isNotNull();
    }

    @Test
    void llmProvider_onUpdate_setsUpdatedAt() {
        LlmProvider provider = new LlmProvider();
        provider.onUpdate();
        assertThat(provider.getUpdatedAt()).isNotNull();
    }

    // ---- Approval ----
    @Test
    void approval_onCreate_appliesDefaults() {
        Approval approval = new Approval();
        approval.onCreate();
        assertThat(approval.getId()).isNotNull();
        assertThat(approval.getRequestedAt()).isNotNull();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }
}
