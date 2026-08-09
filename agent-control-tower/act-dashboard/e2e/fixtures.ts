import type { APIRequestContext } from '@playwright/test';

/**
 * Phase E shared fixtures.
 *
 * ALL seeding goes through the REST API via Playwright's APIRequestContext —
 * never through the UI. API_URL parameterizes the backend for isolated stacks
 * (worktrees / CI compose), matching the existing workflow-lifecycle specs.
 */
export const BACKEND = `${process.env.API_URL || 'http://localhost:8080'}/api/v1`;

export interface ApiResult<T = any> {
  status: number;
  data: T;
}

export async function apiCall(
  request: APIRequestContext,
  method: string,
  path: string,
  body?: object,
): Promise<ApiResult> {
  const resp = await request.fetch(`${BACKEND}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    data: body ? JSON.stringify(body) : undefined,
  });
  const data = await resp.json().catch(() => null);
  return { status: resp.status(), data };
}

export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
}

/** POST /agents — NATIVE agents are created HEALTHY, so they are immediately runnable. */
export async function seedAgent(request: APIRequestContext, name?: string) {
  const { status, data } = await apiCall(request, 'POST', '/agents', {
    name: name ?? uniqueName('e2e-agent'),
    agentType: 'NATIVE',
    description: 'Seeded by Phase E e2e fixtures',
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
  });
  if (status !== 201) {
    throw new Error(`seedKanbanItem failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

/** POST /runs — without an LLM key the run fails fast; callers assert reachable states only. */
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

/** POST /agents — ADK agent with an explicit provider (opencode | langchain). */
export async function seedAdkAgent(
  request: APIRequestContext,
  opts: { name?: string; adkProvider?: string; model?: string; config?: string } = {},
) {
  const { status, data } = await apiCall(request, 'POST', '/agents', {
    name: opts.name ?? uniqueName('e2e-adk-agent'),
    agentType: 'ADK',
    role: 'dev',
    model: opts.model ?? 'deepseek-chat',
    adkProvider: opts.adkProvider ?? 'opencode',
    ...(opts.config ? { config: opts.config } : {}),
  });
  if (status !== 201) {
    throw new Error(`seedAdkAgent failed: HTTP ${status} ${JSON.stringify(data)}`);
  }
  return data;
}

/**
 * Approve the task-level approval gate for a run (default-on since 632d3de):
 * waits for the PENDING approval tied to the run, then decides approved.
 * Throws when the decision is rejected so callers cannot silently pass a
 * broken approvals API.
 */
export async function approveRunApproval(
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
  const pending = approvals.find((a) => a.status === 'PENDING' && a.runId === runId);
  const decided = await apiCall(request, 'POST', `/approvals/${pending.id}/decide`, {
    approved: true,
    reason: 'API E2E auto-approval (task-level gate)',
  });
  if (decided.status !== 200) {
    throw new Error(`approval decide failed: HTTP ${decided.status} ${JSON.stringify(decided.data)}`);
  }
  return decided;
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
