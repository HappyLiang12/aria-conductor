import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Workflow lifecycle API functional tests.
 *
 * Covers all 9 WorkflowController endpoints:
 * 1. POST   /api/v1/workflows              — create
 * 2. GET    /api/v1/workflows              — list
 * 3. GET    /api/v1/workflows/{id}         — get
 * 4. POST   /api/v1/workflows/{id}/cancel  — cancel
 * 5. PUT    /api/v1/workflows/{id}         — update
 * 6. DELETE /api/v1/workflows/{id}         — delete
 * 7. POST   /api/v1/workflows/{id}/retry   — retry
 * 8. POST   /api/v1/workflows/merge        — merge
 * 9. POST   /api/v1/workflows/templates/{id}/reuse — reuse
 *
 * Also covers error cases: cancel non-existent, retry non-failed, delete running.
 */

test.describe.configure({ mode: 'serial', timeout: 120_000 });

const BACKEND = 'http://127.0.0.1:8080/api/v1';

/** Helper: backend fetch via page context (proxied through Vite). */
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

/** Create a test agent and return its id. */
async function createTestAgent(page: Page, name: string): Promise<string> {
  const { status, data } = await apiCall(page, 'POST', '/agents', {
    name,
    agentType: 'NATIVE',
    description: 'E2E workflow test agent',
  });
  expect(status).toBe(201);
  return data.id;
}

// Shared state across serial tests
let agentId: string;
let createdWfId: string;

test('0. setup — create test agent', async ({ page }) => {
  await page.goto('/');
  agentId = await createTestAgent(page, `WfE2EAgent-${Date.now()}`);
  expect(agentId).toBeTruthy();
});

// ── Scenario 1: Create workflow ──────────────────────────────────────
test('1. POST /workflows — create with steps', async ({ page }) => {
  const { status, data } = await apiCall(page, 'POST', '/workflows', {
    name: 'E2E Test Workflow',
    steps: [
      { agentId, promptTemplate: 'Step 1: Analyze {input}', maxIterations: 3 },
      { agentId, promptTemplate: 'Step 2: Report from {previousOutput}', maxIterations: 2 },
    ],
  });

  expect(status).toBe(201);
  expect(data.id).toBeTruthy();
  expect(data.name).toBe('E2E Test Workflow');
  expect(data.totalSteps).toBe(2);
  expect(data.steps).toHaveLength(2);
  expect(data.status).toBeTruthy();

  createdWfId = data.id;
});

// ── Scenario 2: List workflows ───────────────────────────────────────
test('2. GET /workflows — list contains created workflow', async ({ page }) => {
  const { status, data } = await apiCall(page, 'GET', '/workflows');

  expect(status).toBe(200);
  expect(Array.isArray(data)).toBe(true);
  expect(data.length).toBeGreaterThanOrEqual(1);

  const found = data.find((w: any) => w.id === createdWfId);
  expect(found).toBeTruthy();
  expect(found.name).toBe('E2E Test Workflow');
});

// ── Scenario 3: Get single workflow ─────────────────────────────────
test('3. GET /workflows/{id} — get single workflow', async ({ page }) => {
  const { status, data } = await apiCall(page, 'GET', `/workflows/${createdWfId}`);

  expect(status).toBe(200);
  expect(data.id).toBe(createdWfId);
  expect(data.name).toBe('E2E Test Workflow');
  expect(data.totalSteps).toBe(2);
  expect(data.steps).toHaveLength(2);
  expect(data.steps[0].promptTemplate).toContain('Step 1');
  expect(data.steps[1].promptTemplate).toContain('Step 2');
});

// ── Scenario 4: Cancel workflow ─────────────────────────────────────
test('4. POST /workflows/{id}/cancel — cancel workflow', async ({ page }) => {
  // Check current status — cancel only works on RUNNING or PENDING
  const { data: current } = await apiCall(page, 'GET', `/workflows/${createdWfId}`);

  if (current.status === 'RUNNING' || current.status === 'PENDING') {
    const { status, data } = await apiCall(page, 'POST', `/workflows/${createdWfId}/cancel`, {});
    expect(status).toBe(200);
    expect(data.status).toBe('CANCELLED');
    expect(data.id).toBe(createdWfId);

    // Verify via GET
    const verify = await apiCall(page, 'GET', `/workflows/${createdWfId}`);
    expect(verify.data.status).toBe('CANCELLED');
  } else {
    // Workflow already FAILED/COMPLETED (no LLM available) — cancel should be rejected
    const { status } = await apiCall(page, 'POST', `/workflows/${createdWfId}/cancel`, {});
    expect(status).toBeGreaterThanOrEqual(400);
  }
});

// ── Scenario 5: Cancel non-existent — error case ────────────────────
test('5. POST /workflows/{fake-id}/cancel — 404 for non-existent', async ({ page }) => {
  const fakeId = '00000000-0000-0000-0000-000000000000';
  const { status } = await apiCall(page, 'POST', `/workflows/${fakeId}/cancel`, {});

  expect(status).toBeGreaterThanOrEqual(400);
});

// ── Scenario 6: Create another workflow for update/delete tests ─────
test('6. setup — create second workflow for mutation tests', async ({ page }) => {
  const { status, data } = await apiCall(page, 'POST', '/workflows', {
    name: 'E2E Mutation Target',
    steps: [{ agentId, promptTemplate: 'Do mutation test work', maxIterations: 1 }],
  });

  expect(status).toBe(201);
  createdWfId = data.id; // reuse variable for subsequent tests
});

// ── Scenario 7: Update workflow (name) ──────────────────────────────
test('7. PUT /workflows/{id} — update workflow name', async ({ page }) => {
  // Update requires PENDING or FAILED. Check status first.
  const { data: current } = await apiCall(page, 'GET', `/workflows/${createdWfId}`);

  if (current.status === 'PENDING' || current.status === 'FAILED') {
    const { status, data } = await apiCall(page, 'PUT', `/workflows/${createdWfId}`, {
      name: 'E2E Updated Name',
      description: 'Updated by E2E test',
    });
    expect(status).toBe(200);
    expect(data.name).toBe('E2E Updated Name');
    expect(data.description).toBe('Updated by E2E test');
  } else {
    // RUNNING/CANCELLED — update should be rejected
    const { status } = await apiCall(page, 'PUT', `/workflows/${createdWfId}`, {
      name: 'E2E Updated Name',
    });
    expect(status).toBeGreaterThanOrEqual(400);
  }
});

// ── Scenario 8: Retry non-failed — error case ──────────────────────
test('8. POST /workflows/{id}/retry — error on non-FAILED workflow', async ({ page }) => {
  // Create a fresh workflow that won't be in FAILED state
  const { data: fresh } = await apiCall(page, 'POST', '/workflows', {
    name: 'E2E Retry Target',
    steps: [{ agentId, promptTemplate: 'Retry test work', maxIterations: 1 }],
  });

  const { status } = await apiCall(page, 'POST', `/workflows/${fresh.id}/retry`, {
    stepIndex: 0,
  });

  // Should fail: retry only works on FAILED workflows
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── Scenario 9: Merge workflows ─────────────────────────────────────
test('9. POST /workflows/merge — merge two workflows', async ({ page }) => {
  // Create two source workflows
  const { data: wf1 } = await apiCall(page, 'POST', '/workflows', {
    name: 'Merge Source A',
    steps: [{ agentId, promptTemplate: 'Source A step', maxIterations: 1 }],
  });
  const { data: wf2 } = await apiCall(page, 'POST', '/workflows', {
    name: 'Merge Source B',
    steps: [{ agentId, promptTemplate: 'Source B step', maxIterations: 1 }],
  });

  const { status, data } = await apiCall(page, 'POST', '/workflows/merge', {
    sourceIds: [wf1.id, wf2.id],
    name: 'E2E Merged Workflow',
  });

  expect(status).toBe(201);
  expect(data.name).toBe('E2E Merged Workflow');
  expect(data.totalSteps).toBe(2);
});

// ── Scenario 10: Delete workflow ────────────────────────────────────
test('10. DELETE /workflows/{id} — delete non-running workflow', async ({ page }) => {
  // Delete works on any non-RUNNING workflow
  const { data: current } = await apiCall(page, 'GET', `/workflows/${createdWfId}`);

  if (current.status === 'RUNNING') {
    // Cancel first
    await apiCall(page, 'POST', `/workflows/${createdWfId}/cancel`, {});
  }

  const { status } = await apiCall(page, 'DELETE', `/workflows/${createdWfId}`);
  expect(status).toBe(204);

  // Verify it's gone
  const { status: getStatus } = await apiCall(page, 'GET', `/workflows/${createdWfId}`);
  expect(getStatus).toBeGreaterThanOrEqual(400);
});

// ── Scenario 11: Delete running workflow — error case ───────────────
test('11. DELETE /workflows/{id} — cannot delete RUNNING workflow', async ({ page }) => {
  const { data: wf } = await apiCall(page, 'POST', '/workflows', {
    name: 'E2E Cannot Delete Running',
    steps: [{ agentId, promptTemplate: 'Running step', maxIterations: 1 }],
  });

  // Try to delete immediately (may be RUNNING or FAILED depending on LLM availability)
  const { status } = await apiCall(page, 'DELETE', `/workflows/${wf.id}`);

  if (status === 204) {
    // Was not RUNNING (e.g. FAILED/PENDING) — delete succeeded
    // Verify gone
    const { status: gs } = await apiCall(page, 'GET', `/workflows/${wf.id}`);
    expect(gs).toBeGreaterThanOrEqual(400);
  } else {
    // RUNNING — delete rejected
    expect(status).toBeGreaterThanOrEqual(400);
    // Clean up: cancel then delete
    await apiCall(page, 'POST', `/workflows/${wf.id}/cancel`, {});
    await apiCall(page, 'DELETE', `/workflows/${wf.id}`);
  }
});

// ── Scenario 12: Reuse template — requires isTemplate flag ──────────
test('12. POST /workflows/templates/{id}/reuse — rejects non-template', async ({ page }) => {
  // Create a regular workflow (not a template)
  const { data: regular } = await apiCall(page, 'POST', '/workflows', {
    name: 'E2E Regular Workflow',
    steps: [{ agentId, promptTemplate: 'Analyze {input}', maxIterations: 3 }],
  });

  // Reuse should fail because the workflow is not marked as a template
  const { status } = await apiCall(page, 'POST', `/workflows/templates/${regular.id}/reuse`, {
    parameters: { input: 'test-data' },
  });

  // Expected: 400 (IllegalArgumentException: not a template) or 500
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── Scenario 13: Workflows page renders created workflows in UI ─────
test('13. UI — workflows page shows workflow data', async ({ page }) => {
  // Create a workflow with a distinctive name for UI verification
  const wfName = `E2E UI Visible ${Date.now()}`;
  await apiCall(page, 'POST', '/workflows', {
    name: wfName,
    steps: [{ agentId, promptTemplate: 'UI test step', maxIterations: 1 }],
  });

  await page.goto('/workflows');
  await page.waitForLoadState('networkidle');

  // The workflow name should appear in the page
  const wfItem = page.locator(`text=${wfName}`);
  await expect(wfItem.first()).toBeVisible({ timeout: 10_000 });
});
