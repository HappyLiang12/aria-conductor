package io.aria.conductor.test;

import io.aria.conductor.common.model.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Fluent builders for constructing entity fixtures in tests.
 * <p>
 * Always prefer these over raw constructors so tests stay declarative
 * and resilient to entity field additions.
 */
public final class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static AgentBuilder anAgent() {
        return new AgentBuilder();
    }

    public static RunBuilder aRun() {
        return new RunBuilder();
    }

    public static KnowledgeItemBuilder aKnowledgeItem() {
        return new KnowledgeItemBuilder();
    }

    public static ApprovalBuilder anApproval() {
        return new ApprovalBuilder();
    }

    public static PromptCallBuilder aPromptCall() {
        return new PromptCallBuilder();
    }

    // ---------------------------------------------------------------------
    // Agent
    // ---------------------------------------------------------------------
    public static final class AgentBuilder {
        private UUID id = UUID.randomUUID();
        private String name = "test-agent-" + shortId();
        private String description = "Test agent fixture";
        private AgentType agentType = AgentType.NATIVE;
        private String role = "tester";
        private String model = "gpt-4o-mini";
        private String provider = "openai";
        private String config = "{}";
        private HealthStatus healthStatus = HealthStatus.HEALTHY;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;
        private Instant retiredAt;

        public AgentBuilder withId(UUID id) { this.id = id; return this; }
        public AgentBuilder withName(String name) { this.name = name; return this; }
        public AgentBuilder withDescription(String description) { this.description = description; return this; }
        public AgentBuilder withAgentType(AgentType agentType) { this.agentType = agentType; return this; }
        public AgentBuilder withRole(String role) { this.role = role; return this; }
        public AgentBuilder withModel(String model) { this.model = model; return this; }
        public AgentBuilder withProvider(String provider) { this.provider = provider; return this; }
        public AgentBuilder withConfig(String config) { this.config = config; return this; }
        public AgentBuilder withHealthStatus(HealthStatus healthStatus) { this.healthStatus = healthStatus; return this; }
        public AgentBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public AgentBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public AgentBuilder withRetiredAt(Instant retiredAt) { this.retiredAt = retiredAt; return this; }

        public Agent build() {
            return Agent.builder()
                    .id(id)
                    .name(name)
                    .description(description)
                    .agentType(agentType)
                    .role(role)
                    .model(model)
                    .provider(provider)
                    .config(config)
                    .healthStatus(healthStatus)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .retiredAt(retiredAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // Run
    // ---------------------------------------------------------------------
    public static final class RunBuilder {
        private UUID id = UUID.randomUUID();
        private UUID agentId = UUID.randomUUID();
        private RunStatus status = RunStatus.PENDING;
        private String promptSeed = "Test prompt";
        private int maxIterations = 50;
        private long totalTokensUsed = 0L;
        private int iterationCount = 0;
        private String errorMessage;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;
        private Instant completedAt;

        public RunBuilder withId(UUID id) { this.id = id; return this; }
        public RunBuilder withAgentId(UUID agentId) { this.agentId = agentId; return this; }
        public RunBuilder withStatus(RunStatus status) { this.status = status; return this; }
        public RunBuilder withPromptSeed(String promptSeed) { this.promptSeed = promptSeed; return this; }
        public RunBuilder withMaxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public RunBuilder withTotalTokensUsed(long totalTokensUsed) { this.totalTokensUsed = totalTokensUsed; return this; }
        public RunBuilder withIterationCount(int iterationCount) { this.iterationCount = iterationCount; return this; }
        public RunBuilder withErrorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public RunBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public RunBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public RunBuilder withCompletedAt(Instant completedAt) { this.completedAt = completedAt; return this; }

        public Run build() {
            return Run.builder()
                    .id(id)
                    .agentId(agentId)
                    .status(status)
                    .promptSeed(promptSeed)
                    .maxIterations(maxIterations)
                    .totalTokensUsed(totalTokensUsed)
                    .iterationCount(iterationCount)
                    .errorMessage(errorMessage)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .completedAt(completedAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // KnowledgeItem
    // ---------------------------------------------------------------------
    public static final class KnowledgeItemBuilder {
        private UUID id = UUID.randomUUID();
        private String name = "test-knowledge-" + shortId();
        private KnowledgeType type = KnowledgeType.SKILL;
        private String description = "Test knowledge fixture";
        private String currentVersion = "1.0.0";
        private KnowledgeStatus status = KnowledgeStatus.DRAFT;
        private Sensitivity sensitivity = Sensitivity.INTERNAL;
        private String filePath;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;
        private Instant retiredAt;

        public KnowledgeItemBuilder withId(UUID id) { this.id = id; return this; }
        public KnowledgeItemBuilder withName(String name) { this.name = name; return this; }
        public KnowledgeItemBuilder withType(KnowledgeType type) { this.type = type; return this; }
        public KnowledgeItemBuilder withDescription(String description) { this.description = description; return this; }
        public KnowledgeItemBuilder withCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; return this; }
        public KnowledgeItemBuilder withStatus(KnowledgeStatus status) { this.status = status; return this; }
        public KnowledgeItemBuilder withSensitivity(Sensitivity sensitivity) { this.sensitivity = sensitivity; return this; }
        public KnowledgeItemBuilder withFilePath(String filePath) { this.filePath = filePath; return this; }
        public KnowledgeItemBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public KnowledgeItemBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public KnowledgeItemBuilder withRetiredAt(Instant retiredAt) { this.retiredAt = retiredAt; return this; }

        public KnowledgeItem build() {
            return KnowledgeItem.builder()
                    .id(id)
                    .name(name)
                    .type(type)
                    .description(description)
                    .currentVersion(currentVersion)
                    .status(status)
                    .sensitivity(sensitivity)
                    .filePath(filePath)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .retiredAt(retiredAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // Approval
    // ---------------------------------------------------------------------
    public static final class ApprovalBuilder {
        private UUID id = UUID.randomUUID();
        private UUID runId = UUID.randomUUID();
        private UUID toolCallId;
        private ApprovalStatus status = ApprovalStatus.PENDING;
        private String reason = "Test approval fixture";
        private Instant requestedAt = Instant.now();
        private Instant decidedAt;
        private Instant expiresAt;

        public ApprovalBuilder withId(UUID id) { this.id = id; return this; }
        public ApprovalBuilder withRunId(UUID runId) { this.runId = runId; return this; }
        public ApprovalBuilder withToolCallId(UUID toolCallId) { this.toolCallId = toolCallId; return this; }
        public ApprovalBuilder withStatus(ApprovalStatus status) { this.status = status; return this; }
        public ApprovalBuilder withReason(String reason) { this.reason = reason; return this; }
        public ApprovalBuilder withRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; return this; }
        public ApprovalBuilder withDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; return this; }
        public ApprovalBuilder withExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

        public Approval build() {
            return Approval.builder()
                    .id(id)
                    .runId(runId)
                    .toolCallId(toolCallId)
                    .status(status)
                    .reason(reason)
                    .requestedAt(requestedAt)
                    .decidedAt(decidedAt)
                    .expiresAt(expiresAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // PromptCall
    // ---------------------------------------------------------------------
    public static final class PromptCallBuilder {
        private Long id;
        private UUID runId = UUID.randomUUID();
        private UUID agentId = UUID.randomUUID();
        private String provider = "openai";
        private String model = "gpt-4o-mini";
        private int inputTokens = 100;
        private int outputTokens = 50;
        private int latencyMs = 250;
        private String outcome = "success";
        private String toolsUsed = "";
        private Instant createdAt = Instant.now();

        public PromptCallBuilder withId(Long id) { this.id = id; return this; }
        public PromptCallBuilder withRunId(UUID runId) { this.runId = runId; return this; }
        public PromptCallBuilder withAgentId(UUID agentId) { this.agentId = agentId; return this; }
        public PromptCallBuilder withProvider(String provider) { this.provider = provider; return this; }
        public PromptCallBuilder withModel(String model) { this.model = model; return this; }
        public PromptCallBuilder withInputTokens(int inputTokens) { this.inputTokens = inputTokens; return this; }
        public PromptCallBuilder withOutputTokens(int outputTokens) { this.outputTokens = outputTokens; return this; }
        public PromptCallBuilder withLatencyMs(int latencyMs) { this.latencyMs = latencyMs; return this; }
        public PromptCallBuilder withOutcome(String outcome) { this.outcome = outcome; return this; }
        public PromptCallBuilder withToolsUsed(String toolsUsed) { this.toolsUsed = toolsUsed; return this; }
        public PromptCallBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public PromptCall build() {
            return PromptCall.builder()
                    .id(id)
                    .runId(runId)
                    .agentId(agentId)
                    .provider(provider)
                    .model(model)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .latencyMs(latencyMs)
                    .outcome(outcome)
                    .toolsUsed(toolsUsed)
                    .createdAt(createdAt)
                    .build();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
