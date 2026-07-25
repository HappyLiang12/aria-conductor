import { test, expect } from '@playwright/test';
import { apiCall, seedAgent } from './fixtures';

/**
 * Overview dashboard E2E: executive summary + core panels render, rail nav
 * drives the SPA routes, and an API-seeded agent is reflected in the counts.
 */
test.describe('Overview dashboard', () => {
  test('renders executive summary stats and core panels', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    const exec = page.locator('#panel-exec');
    await expect(exec.locator('h2')).toContainText('Executive Summary');
    for (const label of ['Active Agents', 'Pending Approvals', 'Total Runs']) {
      await expect(exec.locator('.stat').filter({ hasText: label })).toBeVisible();
    }
    // Panels nest, so the section.panel+hasText filter also matches the outer
    // wrapper; assert the board heading at page level instead.
    await expect(page.locator('h2').filter({ hasText: 'Kanban Board' }).first()).toBeVisible();
  });

  test('rail navigation drives the SPA routes', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.locator('.rail-btn[data-view="runs"]').click();
    await expect(page).toHaveURL(/\/runs$/);
    await expect(page.locator('.page-header h2')).toHaveText('Runs');

    await page.locator('.rail-btn[data-view="overview"]').click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.locator('#panel-exec')).toBeVisible();
  });

  test('seeded agent is reflected in Active Agents count after reload', async ({ page, request }) => {
    await seedAgent(request);
    const summary = await apiCall(request, 'GET', '/dashboard/summary');
    expect(summary.status).toBe(200);
    expect(summary.data.activeAgents).toBeGreaterThanOrEqual(1);

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    const cell = page.locator('#panel-exec .stat').filter({ hasText: 'Active Agents' });
    await expect(cell.locator('.v')).toHaveText(String(summary.data.activeAgents));
  });
});
