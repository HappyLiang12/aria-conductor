import { test, expect } from '@playwright/test';

/**
 * E2E: Customisable Harness Governance (deterministic — no real LLM, no real GitHub).
 *
 * Guards the mechanics behind the reusable harness profiles that let a weak model stay usable:
 *  - Reusable harness profiles are seeded and exposed (default + weak-model-safe) with the
 *    expected tuning (shell_exec denylist, shell→git steering, self-verify HITL escalation tiers).
 *  - An agent that references weak-model-safe (explicitly or via worker-role auto-adopt) has
 *    shell_exec removed from its effective tool set while retaining the governed git pack.
 *  - git pack tools carry the governance risk tiers that drive the push/PR diff-preview gate.
 *  - The Approvals API exposes enriched fields (toolName/arguments/riskTier) and the UI renders.
 *  - The workspace-diff endpoint (powering the push/PR code-diff preview) responds.
 *
 * The LLM self-verification ESCALATE→HITL routing (#user-ask) is covered by Java unit/integration
 * tests (AiVerificationAgentTest, FullPipelineIntegrationTest); the HITL resume-bypass guard (#28)
 * is covered by RunToolHandlerTest. The full natural LLM-driven flow is verified out-of-band.
 *
 * Prerequisites: backend running with the h2 profile (V37 seeds), frontend dev server up.
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';
const API_URL = process.env.API_URL || 'http://localhost:8080';

test.describe('Customisable Harness Governance', () => {

  test('harness profiles are seeded and exposed (default + weak-model-safe)', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/harness-profiles`);
    expect(resp.ok()).toBeTruthy();
    const profiles = await resp.json();
    const names: string[] = profiles.map((p: any) => p.name);
    expect(names).toContain('default');
    expect(names).toContain('weak-model-safe');

    const weakResp = await request.get(`${API_URL}/api/v1/harness-profiles/weak-model-safe`);
    expect(weakResp.ok()).toBeTruthy();
    const weak = await weakResp.json();
    expect(weak.toolDenylist).toContain('shell_exec');
    expect(weak.steering.shellExecToGitPack).toBe(true);
    expect(Array.isArray(weak.selfVerify.escalateTiers)).toBeTruthy();
    expect(weak.selfVerify.escalateTiers.length).toBeGreaterThan(0);

    // The default profile must be a pure no-op (preserves pre-profile behaviour).
    const defResp = await request.get(`${API_URL}/api/v1/harness-profiles/default`);
    const def = await defResp.json();
    expect(def.toolDenylist).toEqual([]);
    expect(def.steering.shellExecToGitPack).toBe(false);
    expect(def.selfVerify.escalateTiers).toEqual([]);
  });

  test('an agent on weak-model-safe has shell_exec removed from its tool set', async ({ request }) => {
    const createResp = await request.post(`${API_URL}/api/v1/agents`, {
      data: {
        name: `e2e-weak-safe-${Date.now()}`,
        role: 'dev',
        agentType: 'NATIVE',
        config: { harnessProfile: 'weak-model-safe' },
      },
    });
    expect(createResp.ok()).toBeTruthy();
    const agent = await createResp.json();

    const toolsResp = await request.get(`${API_URL}/api/v1/agents/${agent.id}/tools`);
    expect(toolsResp.ok()).toBeTruthy();
    const names: string[] = (await toolsResp.json()).map((t: any) => t.name);

    expect(names, 'weak-model-safe must de-default shell_exec').not.toContain('shell_exec');
    // The governed git pack must remain available so the worker can still do real dev work.
    expect(names).toContain('git_clone');
    expect(names).toContain('git_push');
  });

  test('a free-text dev worker auto-adopts weak-model-safe (no shell_exec, keeps git pack)', async ({ request }) => {
    // No explicit config → worker/dev roles auto-adopt weak-model-safe in AgentService.createAgent.
    const createResp = await request.post(`${API_URL}/api/v1/agents`, {
      data: { name: `e2e-dev-auto-${Date.now()}`, role: 'Developer who fixes bugs', agentType: 'NATIVE' },
    });
    expect(createResp.ok()).toBeTruthy();
    const agent = await createResp.json();

    const toolsResp = await request.get(`${API_URL}/api/v1/agents/${agent.id}/tools`);
    expect(toolsResp.ok()).toBeTruthy();
    const names: string[] = (await toolsResp.json()).map((t: any) => t.name);

    expect(names, 'auto-adopted weak-model-safe must de-default shell_exec').not.toContain('shell_exec');
    expect(names).toContain('git_clone');
    expect(names).toContain('write_file');
  });

  test('git pack tools carry the risk tiers that gate the diff preview (#push)', async ({ request }) => {
    const resp = await request.get(`${API_URL}/api/v1/tools`);
    expect(resp.ok()).toBeTruthy();
    const tools = await resp.json();
    const byName = (n: string) => tools.find((t: any) => t.name === n);
    expect(byName('git_push')?.riskTier).toBe('PUSH');
    expect(byName('git_create_pr')?.riskTier).toBe('PUSH');
    expect(byName('git_clone')?.riskTier).toBe('WRITE_LOCAL');
  });

  test('approvals API exposes enriched fields and the UI renders', async ({ request, page }) => {
    const resp = await request.get(`${API_URL}/api/v1/approvals`);
    expect(resp.ok()).toBeTruthy();
    const approvals = await resp.json();
    expect(Array.isArray(approvals)).toBeTruthy();
    for (const a of approvals) {
      expect(a).toHaveProperty('riskTier');
      expect(a).toHaveProperty('toolName');
      expect(a).toHaveProperty('arguments');
    }

    await page.goto(`${BASE_URL}/approvals`);
    await expect(page.locator('h1, h2').first()).toBeVisible();
    const hardcodedMedium = page.locator('.risk-badge', { hasText: 'Medium' });
    expect(await hardcodedMedium.count()).toBe(0);
  });

  test('workspace-diff endpoint responds (powers the push/PR code-diff preview)', async ({ request }) => {
    // Arbitrary run id with no workspace → 200 with hasWorkspace=false (deterministic, no git repo).
    const runId = '00000000-0000-0000-0000-0000000000ff';
    const resp = await request.get(`${API_URL}/api/v1/runs/${runId}/workspace-diff`);
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body).toHaveProperty('hasWorkspace');
    expect(body.hasWorkspace).toBe(false);
    expect(body).toHaveProperty('diff');
  });
});
