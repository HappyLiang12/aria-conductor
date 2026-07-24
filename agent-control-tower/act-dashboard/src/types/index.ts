// === Enums ===
export type AgentType = 'NATIVE' | 'ADK';
export type AgentHealthStatus = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'RETIRED';
export type RunStatus = 'PENDING' | 'INITIALIZING' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'DENIED' | 'EXPIRED';
export type KnowledgeType = 'SKILL' | 'SCRIPT' | 'PROMPT' | 'TOOL' | 'TEMPLATE' | 'GUIDELINE' | 'WORKFLOW';
export type KnowledgeStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETIRED';
export type ToolCallStatus = 'PENDING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'DENIED';
export type WorkflowStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

// === Entities ===
export interface Agent {
  id: string;
  name: string;
  description: string;
  agentType: AgentType;
  role: string;
  model: string;
  provider: string;
  adkProvider?: string;
  config?: Record<string, unknown>;
  healthStatus: AgentHealthStatus;
  createdAt: string;
  skills?: string[];
  tools?: string[];
}

export interface Run {
  id: string;
  agentId: string;
  status: RunStatus;
  promptSeed: string;
  maxIterations: number;
  totalTokensUsed: number;
  iterationCount: number;
  errorMessage: string | null;
  finalOutput: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface Approval {
  id: string;
  runId: string;
  toolCallId: string;
  status: ApprovalStatus;
  reason: string;
  requestedAt: string;
  decidedAt: string | null;
  expiresAt: string;
  toolName?: string;
  riskTier?: string;
}

// === Harness Profiles (customisable agent-loop tuning) ===
export interface HarnessProfileSteering {
  shellExecToGitPack: boolean;
}

export interface HarnessProfileSelfVerify {
  enabled: boolean;
  escalateTiers: string[];
  maxResponseTokens: number;
  promptOverride: string | null;
}

export interface HarnessProfile {
  name: string;
  toolDenylist: string[];
  steering: HarnessProfileSteering;
  selfVerify: HarnessProfileSelfVerify;
  maxToolCallRounds: number;
  maxToolOutputChars: number;
}

export interface WorkspaceDiff {
  runId: string;
  hasWorkspace: boolean;
  summary: string;
  diff: string;
  truncated: boolean;
}

export interface KnowledgeItem {
  id: string;
  name: string;
  type: KnowledgeType;
  description: string;
  currentVersion: number;
  status: KnowledgeStatus;
  sensitivity: string;
  createdAt: string;
}

export interface ToolCall {
  id: string;
  runId: string;
  toolName: string;
  arguments: string;
  result: string | null;
  status: ToolCallStatus;
  latencyMs: number;
  createdAt: string;
}

export interface SessionTrajectory {
  id: string;
  runId: string;
  turnNumber: number;
  role: string;
  content: string;
  toolCalls: string | null;
  toolCallId?: string;
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
  createdAt: string;
}

export interface KnowledgeVersion {
  id: string;
  knowledgeItemId: string;
  version: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  content: string | null;
  createdAt: string;
  approvedAt: string | null;
}

export interface AriaMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
}

// === API DTOs ===
export interface CreateAgentRequest {
  name: string;
  description?: string;
  agentType: AgentType;
  role?: string;
  model?: string;
  provider?: string;
  adkProvider?: string;
  config?: Record<string, unknown>;
}

export interface CreateRunRequest {
  agentId: string;
  promptSeed: string;
  maxIterations?: number;
}

export interface DashboardSummary {
  activeAgents: number;
  runningRuns: number;
  pendingApprovals: number;
  totalTokensBurned: number;
}

export interface AgentTelemetry {
  agentId: string;
  totalTokensToday: number;
  callCountToday: number;
}

export interface ActivityEvent {
  eventType: string;
  resourceType: string;
  resourceId: string;
  action: string;
  timestamp: string;
  conversationId?: string;
  details?: string;
}

export interface ApprovalDecision {
  approved: boolean;
  reason?: string;
}

export interface CreateKnowledgeRequest {
  name: string;
  type: KnowledgeType;
  description: string;
  content?: string;
  sensitivity?: string;
}

export interface KnowledgeReviewRequest {
  decision: 'APPROVED' | 'REJECTED';
  reason?: string;
}

// === Workflows ===
export interface WorkflowStepInfo {
  index: number;
  agentId: string;
  promptTemplate: string;
  status: WorkflowStepStatus;
  runId: string | null;
  outputPreview: string | null;
}

export interface WorkflowChain {
  id: string;
  name: string;
  status: WorkflowStatus;
  currentStepIndex: number;
  totalSteps: number;
  steps: WorkflowStepInfo[];
  createdAt: string;
  completedAt: string | null;
  isTemplate?: boolean;
  knowledgeItemId?: string | null;
  description?: string | null;
}

// === DoD / Evidence ===
export type DoDOverallStatus = 'IN_PROGRESS' | 'PASSED' | 'FAILED';
export type DoDStageRollupStatus = 'PENDING' | 'PASSED' | 'FAILED' | 'SKIPPED';
export type EvidenceType = 'LOG' | 'ARTIFACT' | 'TEST_RESULT' | 'SCREENSHOT' | 'COMMENT';

export interface DoDStageReview {
  id: string;
  dodId: string;
  stage: string;
  reviewerId: string;
  reviewerName: string | null;
  passed: boolean;
  evidence: string | null;
  comment: string | null;
  reviewedAt: string;
}

export interface DoDStageStatus {
  stage: string;
  required: boolean;
  status: DoDStageRollupStatus;
  reviewCount: number;
  lastReviewedAt: string | null;
}

export interface DoDStatusResponse {
  id: string;
  taskId: string;
  taskType: string | null;
  currentStage: string;
  overallStatus: DoDOverallStatus;
  createdAt: string;
  updatedAt: string;
  stages: DoDStageStatus[];
  reviews: DoDStageReview[];
  evidenceCount: number;
}

export interface DoDRecord {
  id: string;
  taskId: string;
  taskType: string | null;
  currentStage: string;
  overallStatus: DoDOverallStatus;
  createdAt: string;
  updatedAt: string;
}

export interface EvidenceItem {
  id: string;
  dodId: string;
  taskId: string;
  type: EvidenceType;
  title: string | null;
  content: string | null;
  artifactPath: string | null;
  sourceRunId: string | null;
  createdAt: string;
}

export interface InitDoDRequest {
  taskId: string;
  taskType?: string;
}

export interface SubmitReviewRequest {
  taskId: string;
  reviewerId: string;
  reviewerName?: string;
  passed: boolean;
  evidence?: string;
  comment?: string;
}

export interface CreateEvidenceRequest {
  type: EvidenceType;
  title?: string;
  content?: string;
  artifactPath?: string;
  sourceRunId?: string;
}

// === Kanban ===
export type KanbanStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED' | 'CANCELLED';
export type KanbanPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface KanbanItem {
  id: string;
  title: string;
  description: string | null;
  status: KanbanStatus;
  priority: KanbanPriority;
  assignee: string | null;
  labels: string | null;
  linkedRunId: string | null;
  linkedAgentId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateKanbanItemRequest {
  title: string;
  description?: string;
  priority?: KanbanPriority;
  assignee?: string;
  labels?: string;
  linkedRunId?: string;
  linkedAgentId?: string;
}

export interface UpdateKanbanItemRequest {
  title?: string;
  description?: string;
  priority?: KanbanPriority;
  assignee?: string;
  labels?: string;
}

export interface TransitionKanbanRequest {
  status: KanbanStatus;
  comment?: string;
}

// === Report Artifacts (Generative UI) ===
export type ReportStatus = 'GENERATED' | 'AMENDED' | 'ARCHIVED' | 'INCOMPLETE' | 'MISSING';

export interface ReportArtifact {
  id: string;
  title: string;
  sourceRunId: string | null;
  owner: string | null;
  sensitivity: string | null;
  dataScope: string | null;
  htmlPath: string | null;
  htmlUrl: string;
  version: number;
  status: ReportStatus;
  createdAt: string;
  amendedAt: string | null;
  amendmentHistory: string | null;
}

export interface AmendmentEvent {
  version: number;
  instruction: string;
  at: string;
}

export interface GenerateReportRequest {
  title: string;
  description: string;
  dataScope?: string;
  owner?: string;
  sourceRunId?: string;
  sensitivity?: string;
}

export interface AmendReportRequest {
  instruction: string;
}

// === WebSocket Events ===
export interface WsEvent {
  type: string;
  payload: Record<string, unknown>;
  timestamp: string;
}

// === Aria Notifications (Issue #58) ===
export type NotificationType =
  | 'run.completed'
  | 'run.failed'
  | 'approval.requested'
  | 'knowledge.submitted'
  | 'report.generated'
  | 'reminder'
  | 'monitor'
  | 'brief';

export interface Notification {
  id: string;
  type: NotificationType;
  title: string;
  body: string | null;
  resourceType: string | null;
  resourceId: string | null;
  jobId: string | null;
  isRead: boolean;
  createdAt: string;
}

export interface NotificationCount {
  unreadCount: number;
}

export type ScheduleType = 'ONE_SHOT' | 'RECURRING';

export type JobCategory = 'REMINDER' | 'MONITOR' | 'BRIEF';

export type JobStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED';

export interface ScheduledJob {
  id: string;
  scheduleType: ScheduleType;
  category: JobCategory;
  title: string;
  scheduleExpression: string;
  notificationTitle: string;
  notificationBody: string | null;
  nextFireAt: string | null;
  lastFiredAt: string | null;
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateScheduledJobRequest {
  scheduleType: ScheduleType;
  category: JobCategory;
  title: string;
  scheduleExpression: string;
  notificationTitle: string;
  notificationBody?: string;
}

// === LLM Provider ===
export type LlmProviderType = 'OPENAI' | 'AZURE' | 'ANTHROPIC' | 'LOCAL';

export interface LlmProvider {
  id: string;
  name: string;
  type: LlmProviderType;
  baseUrl: string;
  apiKeyMasked: string;
  defaultModel: string;
  defaultMaxTokens: number;
  active: boolean;
  createdAt: string;
  updatedAt: string | null;
}

export interface CreateLlmProviderRequest {
  name: string;
  type: LlmProviderType;
  baseUrl: string;
  apiKey: string;
  defaultModel: string;
  maxTokens: number;
}

export interface LlmProviderTestResult {
  success: boolean;
  message: string;
}

// === Agent Template Presets ===
export interface AgentTemplate {
  id: string;
  label: string;
  agentType: AgentType;
  role: string;
  model: string;
  provider: string;
  adkProvider?: string;
  description: string;
}

// === Tool & Skill Definitions ===
export interface ToolDefinition {
  id: string; name: string; displayName?: string; description: string;
  tier: 'TIER_1' | 'TIER_2' | 'TIER_3'; category: 'GENERAL' | 'PLATFORM' | 'ADVANCED';
  handlerClass?: string; scriptType?: 'PYTHON' | 'SHELL'; script?: string;
  parameters: string; sandboxMode: 'NONE' | 'PROCESS' | 'DOCKER'; enabled: boolean;
  version: number; createdAt: string;
}

export interface AgentToolAssignment { agentId: string; toolIds: string[]; skillIds: string[]; }

export interface SkillDefinitionUI { id: string; name: string; description: string; tier: string; toolDependencies: string[]; enabled: boolean; }
