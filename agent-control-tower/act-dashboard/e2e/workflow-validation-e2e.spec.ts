import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Workflow API input validation and boundary conditions.
 *
 * Covers all validation rules from WorkflowController + WorkflowService:
 * - @NotBlank name
 * - @NotEmpty steps
 * - @NotBlank promptTemplate per step
 * - agentId existence
 * - merge constraints (>=2 sourceIds, valid IDs, non-blank name)
 * - reuse non-existent template
 * - YAML empty content
 */

test.describe.configure({ mode: 'serial', timeout: 120_000 });

const BACKEND = `${process.env.API_URL || 'http://127.0.0.1:8080'}/api/v1`;

async function apiCall(
  page: Page,
  method: string,
  path: string,
  body?: object,
): Promise<{ status: number; data: any }> {
  // Node-side request via Playwright: browser fetch from about:blank pages is
  // blocked by CORS (Origin: null) in CI. page.request has no origin restrictions.
  const resp = await page.request.fetch(`${BACKEND}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    data: body ? JSON.stringify(body) : undefined,
  });
  const data = await resp.json().catch(() => null);
  return { status: resp.status(), data };
}

async function createAgent(page: Page): Promise<string> {
  const { status, data } = await apiCall(page, 'POST', '/agents', {
    name: `WfValAgent-${Date.now()}`,
    agentType: 'NATIVE',
    description: 'Validation E2E agent',
  });
  expect(status).toBe(201);
  return data.id;
}

let agentId: string;

test('0. setup — create test agent', async ({ page }) => {
  await page.goto('/');
  agentId = await createAgent(page);
  expect(agentId).toBeTruthy();
});

// ── 1. name is blank → error ───────────────────────────────────────
test('1. create with blank name → error (400+)', async ({ page }) => {
  const { status } = await apiCall(page, 'POST', '/workflows', {
    name: '',
    steps: [{ agentId, promptTemplate: 'Test prompt', maxIterations: 1 }],
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 2. steps is empty array → error ─────────────────────────────────
test('2. create with empty steps → error (400+)', async ({ page }) => {
  const { status } = await apiCall(page, 'POST', '/workflows', {
    name: 'Empty Steps Workflow',
    steps: [],
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 3. step missing agentId → 400 or 500 ───────────────────────────
test('3. create with step missing agentId → error', async ({ page }) => {
  const { status } = await apiCall(page, 'POST', '/workflows', {
    name: 'No AgentId Workflow',
    steps: [{ promptTemplate: 'Test prompt', maxIterations: 1 }],
  });
  // agentId is null → either validation error or NPE → 400/500
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 4. step missing promptTemplate → error ──────────────────────────
test('4. create with step missing promptTemplate → error (400+)', async ({ page }) => {
  const { status } = await apiCall(page, 'POST', '/workflows', {
    name: 'No Prompt Workflow',
    steps: [{ agentId, maxIterations: 1 }],
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 5. agentId does not exist → error ───────────────────────────────
test('5. create with non-existent agentId → error', async ({ page }) => {
  const fakeAgentId = '00000000-0000-0000-0000-000000000000';
  const { status } = await apiCall(page, 'POST', '/workflows', {
    name: 'Fake Agent Workflow',
    steps: [{ agentId: fakeAgentId, promptTemplate: 'Test prompt', maxIterations: 1 }],
  });
  // Should fail when trying to create a Run for a non-existent agent
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 6. merge with < 2 sourceIds → 400 ──────────────────────────────
test('6. merge with only 1 sourceId → 400+', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'Merge Single Source',
    steps: [{ agentId, promptTemplate: 'Single step', maxIterations: 1 }],
  });

  const { status } = await apiCall(page, 'POST', '/workflows/merge', {
    sourceIds: [wf.id],
    name: 'Merged Single',
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 7. merge with non-existent sourceId → 404 ──────────────────────
test('7. merge with non-existent sourceId → 400+', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'Merge Valid Source',
    steps: [{ agentId, promptTemplate: 'Valid step', maxIterations: 1 }],
  });
  const fakeId = '00000000-0000-0000-0000-000000000000';

  const { status } = await apiCall(page, 'POST', '/workflows/merge', {
    sourceIds: [wf.id, fakeId],
    name: 'Merged With Fake',
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 8. merge with blank name → 400 ─────────────────────────────────
test('8. merge with blank name → error (400+)', async ({ page }) => {
  const { data: wf1 } = await apiCall(page, 'POST', '/workflows', {
    name: 'Merge Source 1',
    steps: [{ agentId, promptTemplate: 'Step A', maxIterations: 1 }],
  });
  const { data: wf2 } = await apiCall(page, 'POST', '/workflows', {
    name: 'Merge Source 2',
    steps: [{ agentId, promptTemplate: 'Step B', maxIterations: 1 }],
  });

  const { status } = await apiCall(page, 'POST', '/workflows/merge', {
    sourceIds: [wf1.id, wf2.id],
    name: '',
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 9. reuse non-existent template → 404 ────────────────────────────
test('9. reuse non-existent template ID → 400+', async ({ page }) => {
  const fakeId = '00000000-0000-0000-0000-000000000000';
  const { status } = await apiCall(page, 'POST', `/workflows/templates/${fakeId}/reuse`, {
    parameters: { input: 'test' },
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 10. YAML execute with empty content → 400 ──────────────────────
test('10. execute-yaml with empty content → 400', async ({ page }) => {
  const { status, data } = await apiCall(page, 'POST', '/workflows/execute-yaml', {
    yamlContent: '',
  });
  expect(status).toBe(400);
  expect(data?.error).toBeTruthy();
});
