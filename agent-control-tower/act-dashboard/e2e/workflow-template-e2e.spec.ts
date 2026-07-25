import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Workflow template reuse functionality.
 *
 * Covers template workflow lifecycle:
 * - Non-template reuse → rejected
 * - Template reuse with parameters → parameter substitution
 * - Reuse creates new running workflow
 * - Non-existent template → 404
 *
 * Note: isTemplate flag cannot be set via API (no endpoint for it).
 * Tests focus on the rejection path and error handling.
 */

test.describe.configure({ mode: 'serial', timeout: 120_000 });

const BACKEND = `${process.env.API_URL || 'http://localhost:8080'}/api/v1`;

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
    name: `WfTplAgent-${Date.now()}`,
    agentType: 'NATIVE',
    description: 'Template E2E agent',
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

// ── 1. Non-template reuse → rejected ────────────────────────────────
test('1. reuse non-template workflow → rejected (400+)', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'Regular Workflow',
    steps: [{ agentId, promptTemplate: 'Analyze {input}', maxIterations: 3 }],
  });

  const { status } = await apiCall(page, 'POST', `/workflows/templates/${wf.id}/reuse`, {
    parameters: { input: 'test-data' },
  });

  // Should be rejected: "WorkflowChain X is not a template"
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 2. Reuse non-existent template → 404 ────────────────────────────
test('2. reuse non-existent template → 400+', async ({ page }) => {
  const fakeId = '00000000-0000-0000-0000-000000000000';
  const { status } = await apiCall(page, 'POST', `/workflows/templates/${fakeId}/reuse`, {
    parameters: { input: 'test' },
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 3. Workflow response includes isTemplate field ──────────────────
test('3. GET workflow → response includes isTemplate field', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'Template Check WF',
    steps: [{ agentId, promptTemplate: 'Check template field', maxIterations: 1 }],
  });

  const { data } = await apiCall(page, 'GET', `/workflows/${wf.id}`);

  // isTemplate should be present and false for regular workflows
  expect(data).toHaveProperty('template');
  // The field might be "template" or "isTemplate" depending on JSON serialization
  const isTemplate = data.template ?? data.isTemplate;
  expect(isTemplate).toBe(false);
});

// ── 4. Reuse with empty parameters ──────────────────────────────────
test('4. reuse non-template with empty params → still rejected', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'Empty Params WF',
    steps: [{ agentId, promptTemplate: 'No params test', maxIterations: 1 }],
  });

  const { status } = await apiCall(page, 'POST', `/workflows/templates/${wf.id}/reuse`, {
    parameters: {},
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 5. Reuse with null parameters ───────────────────────────────────
test('5. reuse non-template with null params → rejected', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'Null Params WF',
    steps: [{ agentId, promptTemplate: 'Null params test', maxIterations: 1 }],
  });

  const { status } = await apiCall(page, 'POST', `/workflows/templates/${wf.id}/reuse`, {});
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 6. List workflows shows template flag ───────────────────────────
test('6. list workflows → each item has template field', async ({ page }) => {
  const { data: workflows } = await apiCall(page, 'GET', '/workflows');

  expect(Array.isArray(workflows)).toBe(true);
  expect(workflows.length).toBeGreaterThan(0);

  // Every workflow should have the template/isTemplate field
  for (const wf of workflows) {
    const hasField = wf.template !== undefined || wf.isTemplate !== undefined;
    expect(hasField).toBeTruthy();
  }
});
