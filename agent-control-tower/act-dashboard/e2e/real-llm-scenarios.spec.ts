import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

/**
 * Real-LLM E2E scenarios (run against a live stack: backend 8080, OpenSandbox 8090,
 * real DeepSeek LLM). Covers:
 *   rl-01  Providers page renders both provider rows (opencode + langchain ADK).
 *   rl-02  Crew view: create an OpenCode agent (adkProvider=opencode) → write the
 *          workspace opencode.json (DeepSeek, {env:DEEPSEEK_API_KEY}) → start a run
 *          → approve the task-level approval gate (default-on for opencode) → poll
 *          until COMPLETED → verify the final output is displayed in the UI.
 *
 * Prerequisites (started outside this spec):
 *   - backend (SPRING_PROFILES_ACTIVE=h2, DEEPSEEK_API_KEY set, OpenSandbox reachable)
 *   - frontend dev server on 5173
 *   - opensandbox-server on 8090 with `host_ip = "127.0.0.1"` (docker-compose)
 *
 * Screenshots: e2e/screenshots/rl-XX-*.png
 */

const RUN_TIMEOUT = 240_000; // 4 min for a real LLM run
const POLL_INTERVAL = 5_000;

// Requires a live stack (backend + OpenSandbox) and a real LLM key; skipped in CI.
const DEEPSEEK_API_KEY = process.env.DEEPSEEK_API_KEY;
const LIVE_STACK_ENABLED = !!DEEPSEEK_API_KEY && !!process.env.OPENSANDBOX_API_KEY;
test.skip(!LIVE_STACK_ENABLED, 'requires DEEPSEEK_API_KEY + OPENSANDBOX_API_KEY and a live stack (backend 8080, opensandbox 8090)');

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const WORKSPACE_BASE = path.resolve(SPEC_DIR, '../../act-app/data/workspaces');

async function waitForBackend(
  page: Page,
  url: string,
  predicate: (json: any) => boolean,
  timeout: number = RUN_TIMEOUT,
  intervalMs: number = POLL_INTERVAL,
): Promise<any> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const result = await page.evaluate(
        async ({ u }) => {
          const r = await fetch(u);
          if (!r.ok) return null;
          return r.json();
        },
        { u: url },
      );
      if (result && predicate(result)) return result;
    } catch {
      /* ignore */
    }
    await page.waitForTimeout(intervalMs);
  }
  throw new Error(`waitForBackend timed out for ${url}`);
}

async function navigateTo(page: Page, view: string) {
  await page.locator(`.rail-btn[data-view="${view}"]`).click();
  await page.waitForLoadState('networkidle');
}

test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min total

// ─────────────────────────────────────────────────────────────────────
test('rl-01 Providers page renders opencode + langchain ADK', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });

  await navigateTo(page, 'providers');

  const providerTable = page.locator('.data-table').first();
  await expect(providerTable).toBeVisible({ timeout: 15_000 });
  await expect(providerTable).toContainText('OpenCode');
  await expect(providerTable).toContainText('LangChain ADK');
  await expect(providerTable.locator('tr', { hasText: 'langchain' }).first()).toContainText('Default');
  await expect(providerTable.locator('tr', { hasText: 'opencode' }).first()).not.toContainText('Default');

  await page.screenshot({ path: 'e2e/screenshots/rl-01-providers-page.png' });
  console.log('✅ rl-01 Providers page renders both providers');
});

// ─────────────────────────────────────────────────────────────────────
test('rl-02 OpenCode agent: create via UI → real-LLM run → result displayed', async ({ page }) => {
  const agentName = `RL OpenCode ${Date.now()}`;
  let agentId: string | null = null;
  const runId: string | null = null;

  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/rl-02-dashboard.png' });

  // ── Create an OpenCode agent via Crew view ──
  await navigateTo(page, 'crew');
  await page.getByRole('button', { name: '+ Add Agent' }).click();
  await expect(page.locator('.mini-dialog.open')).toBeVisible({ timeout: 5_000 });

  await page.locator('#add-agent-name').fill(agentName);
  const adkProviderSelect = page.locator('#add-agent-adk-provider');
  await expect(adkProviderSelect).toBeVisible({ timeout: 10_000 });
  const adkOptionTexts = await adkProviderSelect.locator('option').allTextContents();
  expect(
    adkOptionTexts.some((t) => t.includes('OpenCode')),
    `ADK Provider dropdown should contain an OpenCode option (got: ${adkOptionTexts.join(', ')})`,
  ).toBeTruthy();
  await adkProviderSelect.selectOption('opencode');
  await page.screenshot({ path: 'e2e/screenshots/rl-03-create-opencode-agent.png' });

  await page.getByRole('button', { name: 'Hire Agent' }).click();
  await expect(page.locator('.crew-card', { hasText: agentName })).toBeVisible({ timeout: 15_000 });

  // Resolve the persisted agent id via API
  const agentInfo = await page.evaluate(async (name) => {
    const r = await fetch('/api/v1/agents');
    if (!r.ok) return null;
    const agents = await r.json();
    return agents.find((a: any) => a.name === name) ?? null;
  }, agentName);
  expect(agentInfo, `agent '${agentName}' should be persisted via API`).toBeTruthy();
  agentId = agentInfo.id;
  expect(agentInfo.adkProvider, 'created agent should have adkProvider=opencode').toBe('opencode');

  // ── Fixture: write opencode.json into the agent workspace (uploaded on next prepare) ──
  const workspaceDir = path.join(WORKSPACE_BASE, agentId);
  fs.mkdirSync(workspaceDir, { recursive: true });
  fs.writeFileSync(
    path.join(workspaceDir, 'opencode.json'),
    JSON.stringify(
      {
        $schema: 'https://opencode.ai/config.json',
        model: 'deepseek/deepseek-chat',
        provider: {
          deepseek: {
            options: {
              apiKey: '{env:DEEPSEEK_API_KEY}',
              baseURL: 'https://api.deepseek.com/v1',
            },
          },
        },
      },
      null,
      2,
    ),
  );
  console.log(`opencode.json written to ${workspaceDir}`);

  await page.screenshot({ path: 'e2e/screenshots/rl-04-agent-created.png' });

  // ── Start a run from the Runs view ──
  await navigateTo(page, 'runs');
  await page.getByRole('button', { name: '+ Start Run' }).click();
  await expect(page.locator('.form-card')).toBeVisible({ timeout: 5_000 });

  const agentSelect = page.locator('.form-card select').first();
  const options = await agentSelect.locator('option').allTextContents();
  const targetOption = options.find((o) => o.includes(agentName));
  expect(targetOption, `run form should list '${agentName}' (got: ${options.join(', ')})`).toBeTruthy();
  await agentSelect.selectOption({ label: targetOption! });

  await page.locator('.form-card textarea').fill(
    'Reply with the current year and the capital of France. Do not use any tools.',
  );
  const maxIterInput = page.locator('.form-card input[type="number"]');
  await maxIterInput.fill('2');
  await page.screenshot({ path: 'e2e/screenshots/rl-05-create-run.png' });

  await page.locator('.form-card button[type="submit"]').click();
  await page.waitForTimeout(3000);
  await page.screenshot({ path: 'e2e/screenshots/rl-06-run-started.png' });

  // ── Approve the task-level approval gate (default-on for opencode since 632d3de) ──
  // The task-level path requests a PENDING approval via ApprovalGate and BLOCKS
  // until a human decides (approvals.timeout-ms, default 30 min); the run stays
  // non-terminal until approved. GET /api/v1/approvals returns PENDING approvals
  // only; POST /api/v1/approvals/{id}/decide { approved: true } grants one.
  // If the run already reached a terminal state (failed before the gate), skip
  // approval and let the terminal poll below assert that state.
  const TERMINAL_RUN_STATUSES = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];
  const alreadyTerminal = await page.evaluate(
    async ({ agentId }) => {
      const r = await fetch(`http://localhost:8080/api/v1/runs?agentId=${agentId}`);
      if (!r.ok) return false;
      const runs = await r.json();
      return Array.isArray(runs) && runs.some((x: any) => TERMINAL_RUN_STATUSES.includes(x.status));
    },
    { agentId },
  );

  if (!alreadyTerminal) {
    // Resolve this agent's run ids, then wait for a PENDING approval tied to one
    // of those runs.
    const agentRuns = await waitForBackend(
      page,
      `http://localhost:8080/api/v1/runs?agentId=${agentId}`,
      (runs: any[]) => Array.isArray(runs) && runs.length > 0,
      RUN_TIMEOUT,
    );
    const runIds = new Set(agentRuns.map((r: any) => r.id));
    const approvals = await waitForBackend(
      page,
      'http://localhost:8080/api/v1/approvals',
      (list: any[]) =>
        Array.isArray(list) && list.some((a: any) => a.status === 'PENDING' && runIds.has(a.runId)),
      RUN_TIMEOUT,
    );
    const approval = approvals.find((a: any) => a.status === 'PENDING' && runIds.has(a.runId));
    console.log(`Task-level approval ${approval.id} requested for run ${approval.runId}; approving…`);
    const decided = await page.evaluate(
      async ({ id }) => {
        const r = await fetch(`/api/v1/approvals/${id}/decide`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ approved: true, reason: 'E2E auto-approval (task-level gate)' }),
        });
        if (!r.ok) throw new Error(`approval decide failed: HTTP ${r.status}`);
        return r.json();
      },
      { id: approval.id },
    );
    console.log(`Approval ${approval.id} decided: ${JSON.stringify(decided)}`);
  }

  // ── Poll the backend for a terminal run state (real LLM + sandbox) ──
  const runs = await waitForBackend(
    page,
    `http://localhost:8080/api/v1/runs?agentId=${agentId}`,
    (list: any[]) =>
      Array.isArray(list) &&
      list.some((r: any) => r.status === 'COMPLETED' || r.status === 'FAILED'),
    RUN_TIMEOUT,
  );
  const run = runs.find((r: any) => r.status === 'COMPLETED' || r.status === 'FAILED');
  console.log(`Run ${run.id} finished with status: ${run.status}`);

  // Real-LLM expectation: the OpenCode path with a live sandbox + DeepSeek must COMPLETE.
  expect(
    run.status,
    `run should COMPLETE with the real sandbox+LLM (error: ${run.errorMessage ?? 'none'})`,
  ).toBe('COMPLETED');
  expect(run.finalOutput, 'finalOutput must be non-empty').toBeTruthy();
  expect(run.totalTokensUsed, 'real LLM must consume tokens').toBeGreaterThan(0);

  await page.screenshot({ path: 'e2e/screenshots/rl-07-run-completed.png' });

  // ── Verify the result is displayed in the UI (Runs view row) ──
  await page.waitForTimeout(3_000);
  const runRow = page.locator('.data-table tr', { hasText: run.id.slice(0, 8) }).first();
  await expect(runRow).toBeVisible({ timeout: 15_000 });
  await expect(runRow).toContainText('COMPLETED');
  await page.screenshot({ path: 'e2e/screenshots/rl-08-run-row-ui.png' });

  // ── Cleanup: retire the test agent ──
  if (agentId) {
    await page
      .evaluate(async (id) => {
        await fetch(`/api/v1/agents/${id}/retire`, { method: 'POST' });
      }, agentId)
      .catch(() => {});
  }

  console.log('✅ rl-02 OpenCode real-LLM run completed and displayed in UI');
});
