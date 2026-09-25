import type { APIRequestContext } from '@playwright/test';

/**
 * Phase E shared fixtures (Task 17: governed cores + deterministic harness).
 *
 * ALL seeding goes through the REST API via Playwright's APIRequestContext —
 * never through the UI. API_URL parameterizes the backend for isolated stacks
 * (worktrees / the core E2E harness), matching the existing workflow specs.
 *
 * Operator authority (Tasks 4/12): `/approvals/{id}/decide`, the credentials
 * surface and the harness scenario control are operator-only. The backend
 * accepts the environment-supplied synthetic operator credential
 * (`ARIA_OPERATOR_BEARER_TOKEN`) directly as a bearer header
 * (`Authorization: Bearer <credential>`, ActorAuthenticationFilter step 1 --
 * the header-authenticated operator branch, no CSRF surface). These fixtures
 * therefore authenticate with that header. The browser client is a different
 * flow: `src/api/operatorSession.ts` exchanges the credential once for an
 * HttpOnly session cookie plus `X-CSRF-Token`. Helpers that need operator
 * authority fail loudly when the credential is absent instead of silently
 * issuing an unauthenticated call.
 */
export const BACKEND = `${process.env.API_URL || 'http://localhost:8080'}/api/v1`;

/** The synthetic operator credential; empty when the environment did not supply one. */
export const OPERATOR_BEARER_TOKEN = (process.env.ARIA_OPERATOR_BEARER_TOKEN ?? '').trim();

/** The operator session header contract: `Authorization: Bearer <credential>`. */
export function operatorHeaders(): Record<string, string> {
  if (OPERATOR_BEARER_TOKEN === '') {
    throw new Error(
      'ARIA_OPERATOR_BEARER_TOKEN is not set: this call needs operator authority '
        + '(the environment-supplied synthetic operator credential the core E2E harness requires)',
    );
  }
  return { Authorization: `Bearer ${OPERATOR_BEARER_TOKEN}` };
}

export interface ApiResult<T = any> {
  status: number;
  data: T;
}

export function apiCall(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: object,
): Promise<ApiResult> {
  return apiFetch(request, method, path, body);
}

/** `apiCall` carrying the operator session headers (operator-only routes). */
export function operatorApiCall(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: object,
): Promise<ApiResult> {
  return apiFetch(request, method, path, body, operatorHeaders());
}

async function apiFetch(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: object,
  extraHeaders: Record<string, string> = {},
): Promise<ApiResult> {
  const resp = await request.fetch(`${BACKEND}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...extraHeaders },
    data: body ? JSON.stringify(body) : undefined,
  });
  const data = await resp.json().catch(() => null);
  return { status: resp.status(), data };
}

export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
}

/**
 * The governed execution selection (Tasks 2/3/15). Fields are sent only when
 * provided, so every pre-existing caller keeps its exact request body.
 */
export interface ExecutionSelectionOpts {
  executionMode?: 'HOST' | 'SANDBOX';
  workspaceMode?: 'WORKTREE' | 'DIRECT';
  workspacePath?: string;
  workspaceBaseRef?: string;
}

function executionSelection(opts: ExecutionSelectionOpts) {
  return {
    ...(opts.executionMode ? { executionMode: opts.executionMode } : {}),
    ...(opts.workspaceMode ? { workspaceMode: opts.workspaceMode } : {}),
    ...(opts.workspacePath ? { workspacePath: opts.workspacePath } : {}),
    ...(opts.workspaceBaseRef ? { workspaceBaseRef: opts.workspaceBaseRef } : {}),
  };
}

/**
 * POST /agents — NATIVE agents are created HEALTHY, so they are immediately
 * runnable. Optional explicit core/mode/workspace selection is passed through
 * to the same fields the Crew create form submits.
 */
export async function seedAgent(
  request: APIRequestContext,
  name?: string,
  opts: ExecutionSelectionOpts = {},
) {
  const { status, data } = await apiCall(request, 'POST', '/agents', {
    name: name ?? uniqueName('e2e-agent'),
    agentType: 'NATIVE',
    description: 'Seeded by Phase E e2e fixtures',
    ...executionSelection(opts),
  });
  if (status !== 201) {
    throw new Error(`seedAgent failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

export interface SeedWorkflowOpts {
  name?: string;
  steps?: Array<{ agentId: string; promptTemplate: string; maxIterations?: number }>;
}

/** POST /workflows — creates a chain and starts executing it immediately. */
export async function seedWorkflow(
  request: APIRequestContext,
  agentId: string,
  opts: SeedWorkflowOpts = {},
) {
  const { status, data } = await apiCall(request, 'POST', '/workflows', {
    name: opts.name ?? uniqueName('e2e-wf'),
    steps: opts.steps ?? [{ agentId, promptTemplate: 'Say hello briefly', maxIterations: 1 }],
  });
  if (status !== 201 && status !== 200) {
    throw new Error(`seedWorkflow failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

export interface SeedKnowledgeOpts {
  name?: string;
  type?: 'SKILL' | 'SCRIPT' | 'PROMPT' | 'TOOL' | 'TEMPLATE' | 'GUIDELINE' | 'WORKFLOW';
  description?: string;
  content?: string;
  sensitivity?: string;
}

/** POST /knowledge — new items always land with status PENDING (review queue). */
export async function seedKnowledgeItem(
  request: APIRequestContext,
  opts: SeedKnowledgeOpts = {},
) {
  const { status, data } = await apiCall(request, 'POST', '/knowledge', {
    name: opts.name ?? uniqueName('e2e-knowledge'),
    type: opts.type ?? 'GUIDELINE',
    description: opts.description ?? 'Seeded by Phase E e2e fixtures',
    content: opts.content ?? 'Always verify behavior with a failing test first.',
    ...(opts.sensitivity ? { sensitivity: opts.sensitivity } : {}),
  });
  if (status !== 201) {
    throw new Error(`seedKnowledgeItem failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

export interface SeedKanbanOpts {
  title?: string;
  description?: string;
  priority?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  assignee?: string;
  labels?: string;
  /** Pins the dispatch agent: AgentPickerService matches this string against agent names. */
  agentTemplateId?: string;
  /** Landing column; TODO (default) or BACKLOG (queued, never auto-dispatches). */
  status?: 'TODO' | 'BACKLOG';
}

/** POST /kanban/items — new items land in TODO (rendered in the Todo column). */
export async function seedKanbanItem(
  request: APIRequestContext,
  opts: SeedKanbanOpts = {},
) {
  const { status, data } = await apiCall(request, 'POST', '/kanban/items', {
    title: opts.title ?? uniqueName('e2e-kanban'),
    description: opts.description ?? 'Seeded by Phase E e2e fixtures',
    priority: opts.priority ?? 'MEDIUM',
    ...(opts.assignee ? { assignee: opts.assignee } : {}),
    ...(opts.labels ? { labels: opts.labels } : {}),
    ...(opts.agentTemplateId ? { agentTemplateId: opts.agentTemplateId } : {}),
    ...(opts.status ? { status: opts.status } : {}),
  });
  if (status !== 201) {
    throw new Error(`seedKanbanItem failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

/**
 * POST /runs — starts a run for the agent's frozen execution binding. Under the
 * deterministic harness the run is driven by the recorded peer scenario(s)
 * selected with {@link setScenario}; a run without a selected scenario fails
 * loudly instead of reaching an external model. The run starts asynchronously
 * (AFTER_COMMIT listener), so callers must poll — never assume a terminal state
 * from this response.
 */
export async function seedRun(
  request: APIRequestContext,
  agentId: string,
  promptSeed?: string,
  maxIterations = 1,
) {
  const { status, data } = await apiCall(request, 'POST', '/runs', {
    agentId,
    promptSeed: promptSeed ?? uniqueName('e2e-run-seed'),
    maxIterations,
  });
  if (status !== 201 && status !== 200) {
    throw new Error(`seedRun failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

// ─────────────────────────────────────────────────────────────────────
// Concurrency & metrics helpers (added for the skill/knowledge/workflow
// concurrent multi-agent E2E effort). All additive — the exports above
// are unchanged so existing specs keep working verbatim.
// ─────────────────────────────────────────────────────────────────────

/** A single timed API result: adds elapsed wall-clock ms to ApiResult. */
export interface TimedResult<T = any> extends ApiResult<T> {
  ms: number;
  error?: string;
}

/** apiCall wrapper that records elapsed milliseconds and never throws. */
export async function timedApiCall(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: object,
): Promise<TimedResult> {
  const start = Date.now();
  try {
    const { status, data } = await apiCall(request, method, path, body);
    return { status, data, ms: Date.now() - start };
  } catch (e: any) {
    return { status: 0, data: null, ms: Date.now() - start, error: String(e?.message ?? e) };
  }
}

/**
 * Polls GET {path} until predicate(json) is true or the deadline elapses.
 * Mirrors the waitForBackend pattern from multi-agent-lifecycle.spec.ts so
 * both API and UI specs share one implementation. Runs are async (started
 * via an AFTER_COMMIT @Async listener), so callers must poll — never assume
 * a POST returns a terminal state synchronously.
 */
export async function pollUntil<T = any>(
  request: APIRequestContext,
  path: string,
  predicate: (data: T) => boolean,
  timeoutMs = 60_000,
  intervalMs = 2_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: any = null;
  while (Date.now() < deadline) {
    const { status, data } = await apiCall(request, 'GET', path);
    last = data;
    if (status >= 200 && status < 300 && predicate(data as T)) return data as T;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(
    `pollUntil timed out for ${path} after ${timeoutMs}ms; last=${JSON.stringify(last)?.slice(0, 300)}`,
  );
}

// ─────────────────────────────────────────────────────────────────────
// Task-level (ADK provider) helpers — used by the API fault-injection /
// concurrency / state-machine specs. All seeding goes through the REST API.
// ─────────────────────────────────────────────────────────────────────

export interface SeedAdkAgentOpts extends ExecutionSelectionOpts {
  name?: string;
  /** The governed core: the production catalog is exactly `qoder` | `opencode`. */
  adkProvider?: 'qoder' | 'opencode';
  model?: string;
  config?: string;
}

/**
 * POST /agents — ADK agent bound to an explicit core and, when provided, an
 * explicit placement/workspace selection. The selection fields are the same
 * ones the Crew create form submits (`CreateAgentRequest`); the production
 * admission resolves them at run start (Tasks 3/13).
 */
export async function seedAdkAgent(
  request: APIRequestContext,
  opts: SeedAdkAgentOpts = {},
) {
  const { status, data } = await apiCall(request, 'POST', '/agents', {
    name: opts.name ?? uniqueName('e2e-adk-agent'),
    agentType: 'ADK',
    role: 'dev',
    model: opts.model ?? 'deepseek-chat',
    adkProvider: opts.adkProvider ?? 'opencode',
    ...(opts.config ? { config: opts.config } : {}),
    ...executionSelection(opts),
  });
  if (status !== 201) {
    throw new Error(`seedAdkAgent failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

/**
 * Harness-only control route that selects the deterministic peer scenario for
 * an agent's runs (Tasks 7/16). It drives the committed mock peers, never the
 * database: no run, approval or binding row is written here.
 *
 * The core E2E harness serves this route under the `core-e2e` profile only and
 * requires operator authority (the synthetic operator credential). The recorded
 * scenario is the exact fixture the peer is launched with; a peer refuses to
 * start without both its control token and a declared scenario id.
 */
export const SCENARIO_CONTROL_PATH = '/maintenance/core-e2e/scenario';

export async function setScenario(request: APIRequestContext, agentId: string, scenario: string) {
  const { status, data } = await operatorApiCall(request, 'POST', SCENARIO_CONTROL_PATH, {
    agentId,
    scenario,
  });
  if (status !== 200) {
    throw new Error(
      `setScenario failed for agent ${agentId}: HTTP ${status} ${JSON.stringify(data)}`,
    );
  }
  if (data?.agentId !== agentId || data?.scenario !== scenario) {
    throw new Error(`setScenario recorded a different selection: ${JSON.stringify(data)}`);
  }
  return data;
}

/**
 * The run-scoped worker credential of a live run, read from the harness-only
 * operator-authenticated surface (`GET /maintenance/core-e2e/worker-token?runId=…`,
 * returning `{runId, token}`). The token is inherently unrecoverable elsewhere:
 * the run lifecycle hands it only to that run's processes (packages/mcp-server
 * documents it as `ACT_ACTOR_TOKEN`), so an E2E must ask the harness for the
 * exact value the peer received. Like {@link setScenario} this route drives no
 * database outcome — it echoes the credential the coordinator already minted.
 * The route is part of the T18 peer-launch wiring; until it is served the helper
 * fails loudly instead of fabricating a credential.
 */
export const WORKER_TOKEN_CONTROL_PATH = '/maintenance/core-e2e/worker-token';

export async function runWorkerToken(request: APIRequestContext, runId: string) {
  const { status, data } = await operatorApiCall(
    request,
    'GET',
    `${WORKER_TOKEN_CONTROL_PATH}?runId=${encodeURIComponent(runId)}`,
  );
  if (status !== 200 || data?.runId !== runId || typeof data?.token !== 'string' || data.token === '') {
    throw new Error(
      `runWorkerToken failed for run ${runId}: HTTP ${status} ${JSON.stringify(data)}`,
    );
  }
  return data.token as string;
}

/** The offered native permission options of an ask, parsed from its recorded option set. */
export function permissionOptions(approval: any): Array<{ optionId: string; choice: string }> {
  if (typeof approval?.optionsJson !== 'string' || approval.optionsJson.trim() === '') {
    throw new Error(
      `approval ${approval?.id} carries no offered permission options: ${JSON.stringify(approval)}`,
    );
  }
  return JSON.parse(approval.optionsJson);
}

/**
 * The PENDING asks of one run, captured by id. Approvals are operator-visible
 * reads; the decision itself requires operator authority (next helper).
 */
export async function pendingRunApprovals(
  request: APIRequestContext,
  runId: string,
  timeoutMs = 30_000,
) {
  const approvals = await pollUntil<any[]>(
    request,
    '/approvals',
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING' && a.runId === runId),
    timeoutMs,
    2_000,
  );
  return approvals.filter((a) => a.status === 'PENDING' && a.runId === runId);
}

/**
 * Decide an ask through the operator-only route (Task 12). The response is
 * verified field by field: a decision that was not processed is a failure, not
 * a pass. `approved: true` is the one-use native grant (ALLOW_ONCE); `false` is
 * the offered reject option.
 */
export async function decideApproval(
  request: APIRequestContext,
  approvalId: string,
  approved: boolean,
  reason: string,
) {
  const { status, data } = await operatorApiCall(request, 'POST', `/approvals/${approvalId}/decide`, {
    approved,
    reason,
  });
  if (
    status !== 200 ||
    data?.status !== 'processed' ||
    data?.approvalId !== approvalId ||
    data?.approved !== approved
  ) {
    throw new Error(
      `approval decide failed for ${approvalId}: HTTP ${status} ${JSON.stringify(data)}`,
    );
  }
  return data;
}

/**
 * Approve the run's pending ask as the operator (one-use grant). Throws when
 * the decision is refused so callers cannot silently pass a broken approvals
 * API. The ask itself is returned so callers can assert its captured id and
 * offered options.
 */
export async function approveRunApproval(
  request: APIRequestContext,
  runId: string,
  timeoutMs = 30_000,
) {
  const pending = await pendingRunApprovals(request, runId, timeoutMs);
  const ask = pending[0];
  await decideApproval(request, ask.id, true, 'API E2E operator approval (one-use grant)');
  return ask;
}

/** Wait until a run reaches a terminal state and return the run entity. */
export async function pollRunTerminal(
  request: APIRequestContext,
  runId: string,
  timeoutMs = 120_000,
) {
  const TERMINAL = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];
  return pollUntil<any>(
    request,
    `/runs/${runId}`,
    (run) => TERMINAL.includes(run.status),
    timeoutMs,
    2_000,
  );
}

/**
 * Runs {fn} over {items} with at most {concurrency} in flight, preserving
 * input order in the result. This is the real-LLM budget guard: it caps how
 * many expensive runs execute simultaneously.
 */
export async function runBounded<I, O>(
  items: I[],
  concurrency: number,
  fn: (item: I, index: number) => Promise<O>,
): Promise<O[]> {
  const results: O[] = new Array(items.length);
  let cursor = 0;
  const lanes = Math.max(1, Math.min(concurrency, items.length || 1));
  const workers = Array.from({ length: lanes }, async () => {
    while (true) {
      const i = cursor++;
      if (i >= items.length) break;
      results[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return results;
}

export interface Metrics {
  count: number;
  ok: number;
  errors: number;
  errorRate: number;
  p50: number;
  p95: number;
  p99: number;
  max: number;
  byStatus: Record<string, number>;
}

/** Aggregates timed results into latency percentiles + a status histogram. */
export function collectMetrics(results: TimedResult[]): Metrics {
  const lat = results.map((r) => r.ms).sort((a, b) => a - b);
  const pct = (p: number) =>
    lat.length ? lat[Math.min(lat.length - 1, Math.floor((p / 100) * lat.length))] : 0;
  const byStatus: Record<string, number> = {};
  let ok = 0;
  for (const r of results) {
    const key = String(r.status);
    byStatus[key] = (byStatus[key] ?? 0) + 1;
    if (r.status >= 200 && r.status < 300) ok++;
  }
  const errors = results.length - ok;
  return {
    count: results.length,
    ok,
    errors,
    errorRate: results.length ? errors / results.length : 0,
    p50: pct(50),
    p95: pct(95),
    p99: pct(99),
    max: lat.length ? lat[lat.length - 1] : 0,
    byStatus,
  };
}

// ── Thin REST wrappers over the governed endpoints ──────────────────────

/** POST /knowledge/{id}/review — decision is APPROVED | REJECTED. */
export function reviewKnowledge(
  request: APIRequestContext,
  id: string,
  decision: 'APPROVED' | 'REJECTED',
  reason = 'e2e concurrency review',
) {
  return apiCall(request, 'POST', `/knowledge/${id}/review`, { decision, reason });
}

/** POST /knowledge/{id}/promote — derives a new item of targetType. */
export function promoteKnowledge(
  request: APIRequestContext,
  id: string,
  targetType: 'SKILL' | 'SCRIPT' | 'PROMPT' | 'TOOL' | 'TEMPLATE' | 'GUIDELINE' | 'WORKFLOW',
  targetName?: string,
) {
  return apiCall(request, 'POST', `/knowledge/${id}/promote`, {
    targetType,
    ...(targetName ? { targetName } : {}),
  });
}

/** POST /skills/{id}/toggle — flips the enabled flag. */
export function toggleSkill(request: APIRequestContext, id: string) {
  return apiCall(request, 'POST', `/skills/${id}/toggle`);
}

/** POST /agents/{id}/skills — assign an existing skill to an agent. */
export function assignSkill(request: APIRequestContext, agentId: string, skillId: string) {
  return apiCall(request, 'POST', `/agents/${agentId}/skills`, { skillId });
}

/** POST /workflows/{id}/cancel. */
export function cancelWorkflow(request: APIRequestContext, id: string) {
  return apiCall(request, 'POST', `/workflows/${id}/cancel`);
}

/** POST /workflows/{id}/retry — retry a failed step by index. */
export function retryWorkflow(request: APIRequestContext, id: string, stepIndex: number) {
  return apiCall(request, 'POST', `/workflows/${id}/retry`, { stepIndex });
}

/** DELETE /workflows/{id}. */
export function deleteWorkflow(request: APIRequestContext, id: string) {
  return apiCall(request, 'DELETE', `/workflows/${id}`);
}

/** POST /workflows/merge — concatenate >=2 source chains into a new one. */
export function mergeWorkflows(request: APIRequestContext, sourceIds: string[], name: string) {
  return apiCall(request, 'POST', '/workflows/merge', { sourceIds, name });
}

/** POST /workflows/execute-yaml — run a workflow straight from YAML. */
export function executeYamlWorkflow(
  request: APIRequestContext,
  yamlContent: string,
  parameters?: Record<string, string>,
) {
  return apiCall(request, 'POST', '/workflows/execute-yaml', {
    yamlContent,
    ...(parameters ? { parameters } : {}),
  });
}

/**
 * POST /kanban/items/{id}/transition — the HITL-redesign orchestrator semantics:
 * TODO→IN_PROGRESS dispatches (two-phase pickup), IN_PROGRESS→TODO/BACKLOG pauses,
 * REVIEW→TODO requests changes, CANCELLED cancels, same status is a no-op.
 */
export function transitionKanban(
  request: APIRequestContext,
  id: string,
  status: string,
  extra: Record<string, unknown> = {},
) {
  return apiCall(request, 'POST', `/kanban/items/${id}/transition`, { status, ...extra });
}
