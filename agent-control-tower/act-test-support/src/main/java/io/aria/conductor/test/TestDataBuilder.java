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

    public static WorkflowChainBuilder aWorkflowChain() {
        return new WorkflowChainBuilder();
    }

    public static WorkflowStepBuilder aWorkflowStep() {
        return new WorkflowStepBuilder();
    }

    public static ToolPackBuilder aToolPack() {
        return new ToolPackBuilder();
    }

    public static ToolDefinitionBuilder aToolDefinition() {
        return new ToolDefinitionBuilder();
    }

    public static ToolCallBuilder aToolCall() {
        return new ToolCallBuilder();
    }

    public static AgentSessionBuilder anAgentSession() {
        return new AgentSessionBuilder();
    }

    public static LlmProviderBuilder anLlmProvider() {
        return new LlmProviderBuilder();
    }

    public static PackCredentialBuilder aPackCredential() {
        return new PackCredentialBuilder();
    }

    public static ScheduledJobBuilder aScheduledJob() {
        return new ScheduledJobBuilder();
    }

    public static SystemConfigBuilder aSystemConfig() {
        return new SystemConfigBuilder();
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
        private Approval.ApprovalType approvalType = Approval.ApprovalType.TOOL_CALL;
        private ApprovalStatus status = ApprovalStatus.PENDING;
        private String reason = "Test approval fixture";
        private Instant requestedAt = Instant.now();
        private Instant decidedAt;
        private Instant expiresAt;

        public ApprovalBuilder withId(UUID id) { this.id = id; return this; }
        public ApprovalBuilder withRunId(UUID runId) { this.runId = runId; return this; }
        public ApprovalBuilder withToolCallId(UUID toolCallId) { this.toolCallId = toolCallId; return this; }
        public ApprovalBuilder withApprovalType(Approval.ApprovalType approvalType) {
            this.approvalType = approvalType; return this;
        }
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
                    .approvalType(approvalType)
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

    // ---------------------------------------------------------------------
    // WorkflowChain
    // ---------------------------------------------------------------------
    public static final class WorkflowChainBuilder {
        private UUID id = UUID.randomUUID();
        private String name = "test-workflow-" + shortId();
        private WorkflowChain.Status status = WorkflowChain.Status.PENDING;
        private int currentStepIndex = 0;
        private String stepsJson = "[]";
        private Instant createdAt = Instant.now();
        private Instant updatedAt;
        private Instant completedAt;
        private boolean isTemplate = false;
        private String templateParams;
        private UUID sourceKnowledgeItemId;
        private UUID knowledgeItemId;
        private String description = "Test workflow fixture";

        public WorkflowChainBuilder withId(UUID id) { this.id = id; return this; }
        public WorkflowChainBuilder withName(String name) { this.name = name; return this; }
        public WorkflowChainBuilder withStatus(WorkflowChain.Status status) { this.status = status; return this; }
        public WorkflowChainBuilder withCurrentStepIndex(int currentStepIndex) { this.currentStepIndex = currentStepIndex; return this; }
        public WorkflowChainBuilder withStepsJson(String stepsJson) { this.stepsJson = stepsJson; return this; }
        public WorkflowChainBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public WorkflowChainBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public WorkflowChainBuilder withCompletedAt(Instant completedAt) { this.completedAt = completedAt; return this; }
        public WorkflowChainBuilder asTemplate(boolean isTemplate) { this.isTemplate = isTemplate; return this; }
        public WorkflowChainBuilder withTemplateParams(String templateParams) { this.templateParams = templateParams; return this; }
        public WorkflowChainBuilder withSourceKnowledgeItemId(UUID sourceKnowledgeItemId) { this.sourceKnowledgeItemId = sourceKnowledgeItemId; return this; }
        public WorkflowChainBuilder withKnowledgeItemId(UUID knowledgeItemId) { this.knowledgeItemId = knowledgeItemId; return this; }
        public WorkflowChainBuilder withDescription(String description) { this.description = description; return this; }

        public WorkflowChain build() {
            return WorkflowChain.builder()
                    .id(id)
                    .name(name)
                    .status(status)
                    .currentStepIndex(currentStepIndex)
                    .stepsJson(stepsJson)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .completedAt(completedAt)
                    .isTemplate(isTemplate)
                    .templateParams(templateParams)
                    .sourceKnowledgeItemId(sourceKnowledgeItemId)
                    .knowledgeItemId(knowledgeItemId)
                    .description(description)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // WorkflowStep
    // ---------------------------------------------------------------------
    public static final class WorkflowStepBuilder {
        private UUID agentId = UUID.randomUUID();
        private String promptTemplate = "Do the task: {previousOutput}";
        private int maxIterations = 3;
        private UUID runId;
        private WorkflowStep.Status status = WorkflowStep.Status.PENDING;
        private String output;

        public WorkflowStepBuilder withAgentId(UUID agentId) { this.agentId = agentId; return this; }
        public WorkflowStepBuilder withPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; return this; }
        public WorkflowStepBuilder withMaxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public WorkflowStepBuilder withRunId(UUID runId) { this.runId = runId; return this; }
        public WorkflowStepBuilder withStatus(WorkflowStep.Status status) { this.status = status; return this; }
        public WorkflowStepBuilder withOutput(String output) { this.output = output; return this; }

        public WorkflowStep build() {
            return WorkflowStep.builder()
                    .agentId(agentId)
                    .promptTemplate(promptTemplate)
                    .maxIterations(maxIterations)
                    .runId(runId)
                    .status(status)
                    .output(output)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // ToolPack
    // ---------------------------------------------------------------------
    public static final class ToolPackBuilder {
        private String id = UUID.randomUUID().toString();
        private String name = "test-pack-" + shortId();
        private PackKind kind = PackKind.HANDLER;
        private VersionStatus status = VersionStatus.APPROVED;
        private String sandboxMode = "NONE";
        private String config = "{}";
        private boolean enabled = true;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;

        public ToolPackBuilder withId(String id) { this.id = id; return this; }
        public ToolPackBuilder withName(String name) { this.name = name; return this; }
        public ToolPackBuilder withKind(PackKind kind) { this.kind = kind; return this; }
        public ToolPackBuilder withStatus(VersionStatus status) { this.status = status; return this; }
        public ToolPackBuilder withSandboxMode(String sandboxMode) { this.sandboxMode = sandboxMode; return this; }
        public ToolPackBuilder withConfig(String config) { this.config = config; return this; }
        public ToolPackBuilder withEnabled(boolean enabled) { this.enabled = enabled; return this; }
        public ToolPackBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public ToolPackBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public ToolPack build() {
            return ToolPack.builder()
                    .id(id)
                    .name(name)
                    .kind(kind)
                    .status(status)
                    .sandboxMode(sandboxMode)
                    .config(config)
                    .enabled(enabled)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // ToolDefinition
    // ---------------------------------------------------------------------
    public static final class ToolDefinitionBuilder {
        private String id = UUID.randomUUID().toString();
        private String name = "test-tool-" + shortId();
        private String displayName = "Test Tool";
        private String description = "Test tool fixture";
        private String tier = "TIER_1";
        private String category = "GENERAL";
        private String handlerClass;
        private String scriptType;
        private String script;
        private String parameters = "{\"type\":\"object\",\"properties\":{}}";
        private String sandboxMode = "NONE";
        private String sandboxConfig;
        private int timeoutMs = 30_000;
        private String knowledgeItemId;
        private String packId;
        private PackKind kind = PackKind.HANDLER;
        private RiskTier riskTier = RiskTier.READ;
        private VersionStatus status = VersionStatus.APPROVED;
        private boolean enabled = true;
        private int version = 1;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;
        private String createdBy = "test";

        public ToolDefinitionBuilder withId(String id) { this.id = id; return this; }
        public ToolDefinitionBuilder withName(String name) { this.name = name; return this; }
        public ToolDefinitionBuilder withDisplayName(String displayName) { this.displayName = displayName; return this; }
        public ToolDefinitionBuilder withDescription(String description) { this.description = description; return this; }
        public ToolDefinitionBuilder withTier(String tier) { this.tier = tier; return this; }
        public ToolDefinitionBuilder withCategory(String category) { this.category = category; return this; }
        public ToolDefinitionBuilder withHandlerClass(String handlerClass) { this.handlerClass = handlerClass; return this; }
        public ToolDefinitionBuilder withScriptType(String scriptType) { this.scriptType = scriptType; return this; }
        public ToolDefinitionBuilder withScript(String script) { this.script = script; return this; }
        public ToolDefinitionBuilder withParameters(String parameters) { this.parameters = parameters; return this; }
        public ToolDefinitionBuilder withSandboxMode(String sandboxMode) { this.sandboxMode = sandboxMode; return this; }
        public ToolDefinitionBuilder withSandboxConfig(String sandboxConfig) { this.sandboxConfig = sandboxConfig; return this; }
        public ToolDefinitionBuilder withTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public ToolDefinitionBuilder withKnowledgeItemId(String knowledgeItemId) { this.knowledgeItemId = knowledgeItemId; return this; }
        public ToolDefinitionBuilder withPackId(String packId) { this.packId = packId; return this; }
        public ToolDefinitionBuilder withKind(PackKind kind) { this.kind = kind; return this; }
        public ToolDefinitionBuilder withRiskTier(RiskTier riskTier) { this.riskTier = riskTier; return this; }
        public ToolDefinitionBuilder withStatus(VersionStatus status) { this.status = status; return this; }
        public ToolDefinitionBuilder withEnabled(boolean enabled) { this.enabled = enabled; return this; }
        public ToolDefinitionBuilder withVersion(int version) { this.version = version; return this; }
        public ToolDefinitionBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public ToolDefinitionBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public ToolDefinitionBuilder withCreatedBy(String createdBy) { this.createdBy = createdBy; return this; }

        public ToolDefinition build() {
            return ToolDefinition.builder()
                    .id(id)
                    .name(name)
                    .displayName(displayName)
                    .description(description)
                    .tier(tier)
                    .category(category)
                    .handlerClass(handlerClass)
                    .scriptType(scriptType)
                    .script(script)
                    .parameters(parameters)
                    .sandboxMode(sandboxMode)
                    .sandboxConfig(sandboxConfig)
                    .timeoutMs(timeoutMs)
                    .knowledgeItemId(knowledgeItemId)
                    .packId(packId)
                    .kind(kind)
                    .riskTier(riskTier)
                    .status(status)
                    .enabled(enabled)
                    .version(version)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .createdBy(createdBy)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // ToolCall
    // ---------------------------------------------------------------------
    public static final class ToolCallBuilder {
        private UUID id = UUID.randomUUID();
        private UUID runId = UUID.randomUUID();
        private String toolName = "test-tool";
        private String arguments = "{}";
        private String result;
        private ToolCallStatus status = ToolCallStatus.PENDING;
        private int latencyMs = 0;
        private Instant createdAt = Instant.now();

        public ToolCallBuilder withId(UUID id) { this.id = id; return this; }
        public ToolCallBuilder withRunId(UUID runId) { this.runId = runId; return this; }
        public ToolCallBuilder withToolName(String toolName) { this.toolName = toolName; return this; }
        public ToolCallBuilder withArguments(String arguments) { this.arguments = arguments; return this; }
        public ToolCallBuilder withResult(String result) { this.result = result; return this; }
        public ToolCallBuilder withStatus(ToolCallStatus status) { this.status = status; return this; }
        public ToolCallBuilder withLatencyMs(int latencyMs) { this.latencyMs = latencyMs; return this; }
        public ToolCallBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public ToolCall build() {
            return ToolCall.builder()
                    .id(id)
                    .runId(runId)
                    .toolName(toolName)
                    .arguments(arguments)
                    .result(result)
                    .status(status)
                    .latencyMs(latencyMs)
                    .createdAt(createdAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // AgentSession
    // ---------------------------------------------------------------------
    public static final class AgentSessionBuilder {
        private UUID runId = UUID.randomUUID();
        private UUID agentId = UUID.randomUUID();
        private SessionStatus status = SessionStatus.ACTIVE;
        private String memory = "{}";
        private String context = "[]";
        private int turnCount = 0;
        private long totalInputTokens = 0;
        private long totalOutputTokens = 0;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;

        public AgentSessionBuilder withRunId(UUID runId) { this.runId = runId; return this; }
        public AgentSessionBuilder withAgentId(UUID agentId) { this.agentId = agentId; return this; }
        public AgentSessionBuilder withStatus(SessionStatus status) { this.status = status; return this; }
        public AgentSessionBuilder withMemory(String memory) { this.memory = memory; return this; }
        public AgentSessionBuilder withContext(String context) { this.context = context; return this; }
        public AgentSessionBuilder withTurnCount(int turnCount) { this.turnCount = turnCount; return this; }
        public AgentSessionBuilder withTotalInputTokens(long totalInputTokens) { this.totalInputTokens = totalInputTokens; return this; }
        public AgentSessionBuilder withTotalOutputTokens(long totalOutputTokens) { this.totalOutputTokens = totalOutputTokens; return this; }
        public AgentSessionBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public AgentSessionBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public AgentSession build() {
            return AgentSession.builder()
                    .runId(runId)
                    .agentId(agentId)
                    .status(status)
                    .memory(memory)
                    .context(context)
                    .turnCount(turnCount)
                    .totalInputTokens(totalInputTokens)
                    .totalOutputTokens(totalOutputTokens)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // LlmProvider
    // ---------------------------------------------------------------------
    public static final class LlmProviderBuilder {
        private UUID id = UUID.randomUUID();
        private String name = "test-provider-" + shortId();
        private LlmProviderType type = LlmProviderType.OPENAI;
        private String baseUrl = "http://localhost:9999/v1";
        private String apiKey = "sk-test-key";
        private String defaultModel = "gpt-4o-mini";
        private int defaultMaxTokens = 4096;
        private boolean active = false;
        private Instant createdAt = Instant.now();
        private Instant updatedAt;

        public LlmProviderBuilder withId(UUID id) { this.id = id; return this; }
        public LlmProviderBuilder withName(String name) { this.name = name; return this; }
        public LlmProviderBuilder withType(LlmProviderType type) { this.type = type; return this; }
        public LlmProviderBuilder withBaseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public LlmProviderBuilder withApiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public LlmProviderBuilder withDefaultModel(String defaultModel) { this.defaultModel = defaultModel; return this; }
        public LlmProviderBuilder withDefaultMaxTokens(int defaultMaxTokens) { this.defaultMaxTokens = defaultMaxTokens; return this; }
        public LlmProviderBuilder withActive(boolean active) { this.active = active; return this; }
        public LlmProviderBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public LlmProviderBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public LlmProvider build() {
            return LlmProvider.builder()
                    .id(id)
                    .name(name)
                    .type(type)
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .defaultModel(defaultModel)
                    .defaultMaxTokens(defaultMaxTokens)
                    .active(active)
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // PackCredential
    // ---------------------------------------------------------------------
    public static final class PackCredentialBuilder {
        private String id = UUID.randomUUID().toString();
        private String packId = UUID.randomUUID().toString();
        private String agentId;
        private String credKey = "API_TOKEN";
        private String encValue = "enc:placeholder";
        private Instant updatedAt = Instant.now();

        public PackCredentialBuilder withId(String id) { this.id = id; return this; }
        public PackCredentialBuilder withPackId(String packId) { this.packId = packId; return this; }
        public PackCredentialBuilder withAgentId(String agentId) { this.agentId = agentId; return this; }
        public PackCredentialBuilder withCredKey(String credKey) { this.credKey = credKey; return this; }
        public PackCredentialBuilder withEncValue(String encValue) { this.encValue = encValue; return this; }
        public PackCredentialBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public PackCredential build() {
            return PackCredential.builder()
                    .id(id)
                    .packId(packId)
                    .agentId(agentId)
                    .credKey(credKey)
                    .encValue(encValue)
                    .updatedAt(updatedAt)
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // ScheduledJob (mutable DTO — builder assembles via setters)
    // ---------------------------------------------------------------------
    public static final class ScheduledJobBuilder {
        private String id = UUID.randomUUID().toString();
        private String userId = "test-user";
        private String scheduleType = "ONE_SHOT";
        private String category = "REMINDER";
        private String title = "Test job " + shortId();
        private String scheduleExpression = "2030-01-01T00:00:00Z";
        private Instant nextFireAt = Instant.now().plusSeconds(3600);
        private Instant lastFiredAt;
        private String status = "ACTIVE";
        private String notificationTitle = "Test notification";
        private String notificationBody = "Test body";
        private Instant createdAt = Instant.now();
        private Instant updatedAt;

        public ScheduledJobBuilder withId(String id) { this.id = id; return this; }
        public ScheduledJobBuilder withUserId(String userId) { this.userId = userId; return this; }
        public ScheduledJobBuilder withScheduleType(String scheduleType) { this.scheduleType = scheduleType; return this; }
        public ScheduledJobBuilder withCategory(String category) { this.category = category; return this; }
        public ScheduledJobBuilder withTitle(String title) { this.title = title; return this; }
        public ScheduledJobBuilder withScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; return this; }
        public ScheduledJobBuilder withNextFireAt(Instant nextFireAt) { this.nextFireAt = nextFireAt; return this; }
        public ScheduledJobBuilder withLastFiredAt(Instant lastFiredAt) { this.lastFiredAt = lastFiredAt; return this; }
        public ScheduledJobBuilder withStatus(String status) { this.status = status; return this; }
        public ScheduledJobBuilder withNotificationTitle(String notificationTitle) { this.notificationTitle = notificationTitle; return this; }
        public ScheduledJobBuilder withNotificationBody(String notificationBody) { this.notificationBody = notificationBody; return this; }
        public ScheduledJobBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public ScheduledJobBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public ScheduledJob build() {
            ScheduledJob job = new ScheduledJob();
            job.setId(id);
            job.setUserId(userId);
            job.setScheduleType(scheduleType);
            job.setCategory(category);
            job.setTitle(title);
            job.setScheduleExpression(scheduleExpression);
            job.setNextFireAt(nextFireAt);
            job.setLastFiredAt(lastFiredAt);
            job.setStatus(status);
            job.setNotificationTitle(notificationTitle);
            job.setNotificationBody(notificationBody);
            job.setCreatedAt(createdAt);
            job.setUpdatedAt(updatedAt);
            return job;
        }
    }

    // ---------------------------------------------------------------------
    // SystemConfig
    // ---------------------------------------------------------------------
    public static final class SystemConfigBuilder {
        private String configKey = "test.config." + shortId();
        private String configValue = "value";
        private String description = "Test config fixture";
        private Instant updatedAt = Instant.now();

        public SystemConfigBuilder withConfigKey(String configKey) { this.configKey = configKey; return this; }
        public SystemConfigBuilder withConfigValue(String configValue) { this.configValue = configValue; return this; }
        public SystemConfigBuilder withDescription(String description) { this.description = description; return this; }
        public SystemConfigBuilder withUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public SystemConfig build() {
            return SystemConfig.builder()
                    .configKey(configKey)
                    .configValue(configValue)
                    .description(description)
                    .updatedAt(updatedAt)
                    .build();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
