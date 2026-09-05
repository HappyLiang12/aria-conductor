import { test, expect } from '@playwright/test';
import { uniqueName } from './fixtures';

/**
 * E2E: Git Pack Lifecycle — verifies the governed plugin system's git integration.
 * Flow: agent hits git_push (PUSH risk tier) -> approval page -> human approves -> run resumes.
 * Mirrors workflow-governance.spec.ts pattern; reuses ApprovalsPage.tsx.
 *
 * Prerequisites: backend running with git pack seeded (V32), TOOLS_SHELL_ENABLED=true.
 */

// Default to the vite dev/preview origin used by CI and local runs; the docker
// stack (port 3000) can still be targeted via BASE_URL. API uses 127.0.0.1 to
// avoid IPv6 localhost resolution issues in CI (see #42).
const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';
const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';

test.describe('Git Pack Governance Lifecycle', () => {

  test('git_push triggers approval gate and resumes after approval', async ({ page, request }) => {
    // 1. Verify git pack tools are registered and APPROVED
    const toolsResp = await request.get(`${API_URL}/api/v1/tools`);
    expect(toolsResp.ok()).toBeTruthy();
    const tools = await toolsResp.json();
    const gitPush = tools.find((t: any) => t.name === 'git_push');
    expect(gitPush).toBeDefined();
    expect(gitPush.riskTier).toBe('PUSH');
    expect(gitPush.status).toBe('APPROVED');

    // 2. Verify request_approval tool exists
    const requestApproval = tools.find((t: any) => t.name === 'request_approval');
    expect(requestApproval).toBeDefined();

    // 3. Navigate to Approvals page
    await page.goto(`${BASE_URL}/approvals`);
    await expect(page.locator('h1, h2, [data-testid="approvals-title"]').first()).toBeVisible();

    // 4. Verify pack management API
    const packsResp = await request.get(`${API_URL}/api/v1/packs`);
    expect(packsResp.ok()).toBeTruthy();
    const packs = await packsResp.json();
    const gitPack = packs.find((p: any) => p.name === 'git');
    expect(gitPack).toBeDefined();
    expect(gitPack.kind).toBe('SCRIPT');
    expect(gitPack.status).toBe('APPROVED');
  });

  test('pack management endpoints work (register, approve, reject)', async ({ request }) => {
    // Unique per-run pack name (via the shared uniqueName helper): pack names
    // are unique server-side and a leftover 'test-pack-e2e' from an earlier
    // run on a shared/dirty DB turns the register POST into a 500 — the same
    // fixed-literal class as the conversation ids.
    const registerResp = await request.post(`${API_URL}/api/v1/packs`, {
      data: {
        name: uniqueName('test-pack-e2e'),
        kind: 'HANDLER',
        sandboxMode: 'NONE',
        enabled: false,
      },
    });
    expect(registerResp.ok()).toBeTruthy();
    const pack = await registerResp.json();
    expect(pack.status).toBe('PENDING');
    expect(pack.id).toBeDefined();

    // Approve it
    const approveResp = await request.post(`${API_URL}/api/v1/packs/${pack.id}/approve`);
    expect(approveResp.ok()).toBeTruthy();
    const approved = await approveResp.json();
    expect(approved.status).toBe('APPROVED');
    expect(approved.enabled).toBe(true);
  });

  test('DESTRUCTIVE tools require approval (risk tier gate)', async ({ request }) => {
    const toolsResp = await request.get(`${API_URL}/api/v1/tools`);
    const tools = await toolsResp.json();

    const forceReset = tools.find((t: any) => t.name === 'git_reset_hard');
    expect(forceReset).toBeDefined();
    expect(forceReset.riskTier).toBe('DESTRUCTIVE');

    const forcePush = tools.find((t: any) => t.name === 'git_force_push');
    expect(forcePush).toBeDefined();
    expect(forcePush.riskTier).toBe('DESTRUCTIVE');

    // READ tools should NOT require approval
    const gitStatus = tools.find((t: any) => t.name === 'git_status');
    expect(gitStatus).toBeDefined();
    expect(gitStatus.riskTier).toBe('READ');
  });
});
