import { test, expect } from '@playwright/test';
import { apiCall, seedAgent, seedRun, uniqueName } from './fixtures';

/**
 * Journey: create agent → run → approve → report.
 *
 * The non-LLM portions (agent creation, run start, page reflections, approval
 * queue consistency) run ungated. The report step calls the model
 * (ReportService.generate), so only that segment is LLM-gated — no assertion
 * theater on unreachable states.
 */
const HAS_LLM_KEY = !!(
  process.env.LLM_API_KEY ||
  process.env.LLM_PROVIDER_API_KEY ||
  process.env.DEEPSEEK_API_KEY
);

test.describe.configure({ mode: 'serial' });

let agentId = '';
let agentName = '';
let runId = '';

test('1. agent created via API appears on the crew roster', async ({ page, request }) => {
  const agent = await seedAgent(request);
  agentId = agent.id;
  agentName = agent.name;

  await page.goto('/crew');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.crew-grid').getByText(agentName).first()).toBeVisible({ timeout: 15_000 });
});

test('2. run starts and reaches a real state (fails fast without a key)', async ({ page, request }) => {
  const run = await seedRun(request, agentId, uniqueName('e2e-journey-run'));
  runId = run.id;
  expect(runId).toBeTruthy();

  // Leaves PENDING for a reachable state; FAILED is the expected no-key outcome.
  await expect
    .poll(async () => (await apiCall(request, 'GET', `/runs/${runId}`)).data?.status, { timeout: 60_000 })
    .toMatch(/RUNNING|FAILED|COMPLETED|CANCELLED/);

  await page.goto('/runs');
  await page.waitForLoadState('networkidle');
  const row = page.locator('.data-table tbody tr').filter({ hasText: agentName }).first();
  await expect(row).toBeVisible({ timeout: 20_000 });
  await expect(row.locator('td').nth(2)).toContainText(/RUNNING|FAILED|COMPLETED|CANCELLED/);
});

test('3. approvals surface stays consistent with the API', async ({ page, request }) => {
  // Tool-call approvals only fire during real LLM tool use; assert consistency,
  // not the (unreachable) approval itself.
  const { status } = await apiCall(request, 'GET', '/approvals');
  expect(status).toBe(200);

  await page.goto('/approvals');
  await page.waitForLoadState('networkidle');

  const pendingTab = page.locator('.tab-btn').filter({ hasText: 'Pending' });
  await expect(pendingTab).toBeVisible({ timeout: 15_000 });

  // Count-agnostic mirror check: the approvals queue is SHARED (live chains
  // decide gates concurrently, and /approvals returns every status on a dirty
  // DB), so no fixed total can be asserted. Re-read the API and the rendered
  // tab count together until they agree — the tab must reflect the live
  // PENDING queue at some moment within the window.
  await expect
    .poll(async () => {
      const { data: now } = await apiCall(request, 'GET', '/approvals');
      const apiPending = (Array.isArray(now) ? now : []).filter((a) => a?.status === 'PENDING').length;
      const shown = Number(((await pendingTab.textContent()) ?? '').match(/Pending \((\d+)\)/)?.[1] ?? -1);
      return shown === apiPending;
    }, { timeout: 20_000 })
    .toBe(true);
});

test.describe('LLM-gated tail', () => {
  test.describe.configure({ timeout: 300_000 });

  test('4. run completes and a generated report appears in the workspace', async ({ page, request }) => {
    test.skip(!HAS_LLM_KEY, 'requires a real LLM API key (LLM_API_KEY / LLM_PROVIDER_API_KEY / DEEPSEEK_API_KEY)');

    await expect
      .poll(async () => (await apiCall(request, 'GET', `/runs/${runId}`)).data?.status, { timeout: 240_000 })
      .toBe('COMPLETED');

    const title = uniqueName('e2e-journey-report');
    const gen = await apiCall(request, 'POST', '/reports', {
      title,
      description: 'One-paragraph summary of the latest agent run.',
      sourceRunId: runId,
    });
    expect(gen.status).toBeLessThan(300);

    await page.goto('/reports');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.report-tab').filter({ hasText: title }).first()).toBeVisible({
      timeout: 30_000,
    });
  });
});
