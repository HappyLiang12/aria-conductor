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
