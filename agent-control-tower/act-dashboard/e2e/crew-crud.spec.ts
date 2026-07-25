import { test, expect } from '@playwright/test';
import { apiCall, seedAgent } from './fixtures';

/**
 * Crew page E2E — replaces the planned agents-crud spec (the standalone
 * /agents route was removed; agents live on /crew).
 */
test.describe('Crew page', () => {
  test('renders roster, cost banner and hiring catalog', async ({ page }) => {
    await page.goto('/crew');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('h1').filter({ hasText: 'Crew' })).toBeVisible();
    await expect(page.locator('h2').filter({ hasText: 'Roster Cost' })).toBeVisible();
    await expect(page.locator('h2').filter({ hasText: 'Active Agents' })).toBeVisible();
    await expect(page.locator('h2').filter({ hasText: 'Hire from Catalog' })).toBeVisible();
    await expect(page.locator('[data-testid="agent-catalog"]')).toBeVisible();
  });

  test('API-seeded agent appears in the active roster', async ({ page, request }) => {
    const agent = await seedAgent(request);
    await page.goto('/crew');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.crew-grid').getByText(agent.name).first()).toBeVisible({ timeout: 15_000 });
  });

  test('hire-from-catalog deploys a template agent into the roster', async ({ page, request }) => {
    const before = await apiCall(request, 'GET', '/agents');
    expect(before.status).toBe(200);

    await page.goto('/crew');
    await page.waitForLoadState('networkidle');
    const firstTile = page.locator('[data-testid="agent-catalog"] .tmpl').first();
    await expect(firstTile).toBeVisible();
    await firstTile.getByRole('button', { name: '+ Deploy' }).click();

    // Deployment goes through POST /agents/from-template/{id} → roster grows.
    await expect
      .poll(async () => (await apiCall(request, 'GET', '/agents')).data.length, { timeout: 20_000 })
      .toBeGreaterThan(before.data.length);

    const after = await apiCall(request, 'GET', '/agents');
    const beforeIds = new Set(before.data.map((a: { id: string }) => a.id));
    const created = after.data.find((a: { id: string }) => !beforeIds.has(a.id));
    expect(created).toBeTruthy();
    await expect(page.locator('.crew-grid').getByText(created.name).first()).toBeVisible({ timeout: 15_000 });
  });

  test('negative: blank agent name is rejected by the API', async ({ request }) => {
    const { status } = await apiCall(request, 'POST', '/agents', {
      name: '',
      agentType: 'NATIVE',
    });
    expect(status).toBeGreaterThanOrEqual(400);
  });
});
