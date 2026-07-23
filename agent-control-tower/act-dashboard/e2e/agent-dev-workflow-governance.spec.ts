import { test, expect } from '@playwright/test';

/**
 * E2E: Agent Dev-Workflow Governance (deterministic — no real LLM, no real GitHub).
 *
 * Guards the mechanics behind the governed "agent develops a fix and opens a PR" workflow:
 *  - #25 Aria is scoped to orchestration tools only (no git/file/shell/http) and a free-text
 *        "dev" worker resolves to the dev tool template (git pack + file tools) via keyword mapping.
 *  - #26/#23 git pack tools are registered with the correct governance risk tiers and the
 *        per-run workspace contract is in place.
 *  - #24 the Approvals API returns enriched fields (toolName/arguments/riskTier) and the
 *        Approvals UI renders (no hardcoded "Medium" risk badge).
 *
 * The full natural LLM-driven flow (clone→edit→commit→push→PR with UI-approved gates) is verified
 * manually; the circuit-breaker per-iteration latency semantics (#22) are covered by unit tests
 * (CircuitBreakerTest). Mirrors the git-pack-governance.spec.ts pattern; reuses ApprovalsPage.tsx.
 *
 * Prerequisites: backend running with the h2 profile (V33+ seeds), frontend dev server up.
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';
const API_URL = process.env.API_URL || 'http://localhost:8080';
const ARIA_AGENT_ID = '00000000-0000-0000-0000-000000000001';

test.describe('Agent Dev-Workflow Governance', () => {

  test('Aria is scoped to orchestration tools only (#25)', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/agents/${ARIA_AGENT_ID}/tools`);
    expect(resp.ok()).toBeTruthy();
    const tools = await resp.json();
    const names: string[] = tools.map((t: any) => t.name);

    // Orchestration tools present
    for (const expected of ['create_agent', 'run_agent', 'decide_approval', 'web_fetch']) {
      expect(names, `Aria should have orchestration tool '${expected}'`).toContain(expected);
    }
    // Development / destructive tools must NOT be granted to the orchestrator
    for (const forbidden of ['git_push', 'git_clone', 'git_create_pr', 'shell_exec', 'write_file', 'read_file', 'http_request']) {
      expect(names, `Aria must NOT have '${forbidden}'`).not.toContain(forbidden);
    }
  });

  test('a free-text dev worker resolves to the dev tool template (#25 delegation)', async ({ request }) => {
    const createResp = await request.post(`${API_URL}/api/v1/agents`, {
      data: { name: `e2e-dev-worker-${Date.now()}`, role: 'Developer who fixes bugs', agentType: 'NATIVE' },
    });
    expect(createResp.ok()).toBeTruthy();
    const agent = await createResp.json();

    const toolsResp = await request.get(`${API_URL}/api/v1/agents/${agent.id}/tools`);
    expect(toolsResp.ok()).toBeTruthy();
    const tools = await toolsResp.json();
    const names: string[] = tools.map((t: any) => t.name);

    // The free-text role must map to the 'dev' template (git pack + file tools), not bare WORKER.
    expect(names, 'dev worker should get git_clone').toContain('git_clone');
    expect(names, 'dev worker should get git_push').toContain('git_push');
    expect(names, 'dev worker should get write_file').toContain('write_file');
  });

  test('git pack tools carry the correct governance risk tiers (#26/#23)', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/tools`);
    expect(resp.ok()).toBeTruthy();
    const tools = await resp.json();
    const byName = (n: string) => tools.find((t: any) => t.name === n);

    expect(byName('git_clone')?.riskTier).toBe('WRITE_LOCAL');
    expect(byName('git_push')?.riskTier).toBe('PUSH');
    expect(byName('git_create_pr')?.riskTier).toBe('PUSH');
    expect(byName('git_reset_hard')?.riskTier).toBe('DESTRUCTIVE');
    expect(byName('git_status')?.riskTier).toBe('READ');
    // #24/V36: write_file is WRITE_LOCAL (was READ)
    expect(byName('write_file')?.riskTier).toBe('WRITE_LOCAL');
  });

  test('approvals API exposes enriched fields and the UI renders (#24)', async ({ request, page }) => {
    const resp = await request.get(`${API_URL}/api/v1/approvals`);
    expect(resp.ok()).toBeTruthy();
    const approvals = await resp.json();
    expect(Array.isArray(approvals)).toBeTruthy();
    // If any pending approval exists, it must carry the enriched governance fields.
    for (const a of approvals) {
      expect(a).toHaveProperty('riskTier');
      expect(a).toHaveProperty('toolName');
      expect(a).toHaveProperty('arguments');
    }

    await page.goto(`${BASE_URL}/approvals`);
    await expect(page.locator('h1, h2').first()).toBeVisible();
    // The risk badge is data-driven now — it must never be a hardcoded "Medium" for every card.
    const hardcodedMedium = page.locator('.risk-badge', { hasText: 'Medium' });
    expect(await hardcodedMedium.count()).toBe(0);
  });
});
