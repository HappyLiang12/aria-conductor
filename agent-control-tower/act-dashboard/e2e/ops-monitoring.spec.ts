import { test, expect } from '@playwright/test';
import { apiCall, seedAgent, seedRun, uniqueName } from './fixtures';

/**
 * Ops command-surface E2E: all four panels render, the approvals panel
 * mirrors the live queue, and an API-seeded run shows up in Recent Runs.
 */
test.describe('Ops monitoring surface', () => {
  test('renders all four command-surface panels', async ({ page }) => {
    await page.goto('/ops');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('h1').filter({ hasText: 'Operations' })).toBeVisible();
    await expect(page.locator('#ops-approvals-h')).toContainText('Pending Approvals');
    await expect(page.locator('#ops-runs-h')).toContainText('Recent Runs');
    await expect(page.locator('#ops-activity-h')).toContainText('Activity Timeline');
    await expect(page.locator('#ops-brief-h')).toContainText('Briefing');
  });

  test('approvals panel mirrors the live queue (empty state when clear)', async ({ page, request }) => {
    const { status, data } = await apiCall(request, 'GET', '/approvals');
    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);

    await page.goto('/ops');
    await page.waitForLoadState('networkidle');

    const inboxZero = page.getByText('Inbox zero. All requests resolved.');
    const queueItems = page.locator('.qitem');

    // Count-agnostic mirror check: the approvals queue is SHARED (live chains
    // decide gates concurrently), so no fixed total can be asserted and the
    // panel may legitimately drift between the API snapshot above and its own
    // 8s refetch. Poll until the rendered panel state agrees with the live
    // PENDING count read at the same moment.
    await expect
      .poll(async () => {
        const { data: now } = await apiCall(request, 'GET', '/approvals');
        const apiPending = (Array.isArray(now) ? now : []).filter((a) => a?.status === 'PENDING').length;
        if (apiPending === 0) {
          return inboxZero.isVisible().catch(() => false);
        }
        return (await queueItems.count()) > 0;
      }, { timeout: 25_000 })
      .toBe(true);
  });

  test('API-seeded run appears in Recent Runs with agent and prompt', async ({ page, request }) => {
    const agent = await seedAgent(request);
    const marker = uniqueName('e2e-ops-run');
    const run = await seedRun(request, agent.id, marker);
    expect(run.id).toBeTruthy();

    await page.goto('/ops');
    await page.waitForLoadState('networkidle');
    const runsPanel = page.locator('section.panel').filter({ hasText: 'Recent Runs' });
    const row = runsPanel.locator('[role="row"]').filter({ hasText: agent.name }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(row).toContainText(marker.slice(0, 20));
  });
});
