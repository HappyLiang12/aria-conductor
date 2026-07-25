import { test, expect } from '@playwright/test';
import { apiCall } from './fixtures';

/**
 * Approvals decision-flow E2E.
 *
 * ADAPTATION NOTE (documented per task brief): approvals are only created as a
 * side effect of governed tool calls during real agent runs. The REST surface
 * (ApprovalController) exposes GET /approvals, GET /{id} and POST /{id}/decide
 * — there is NO create/seed endpoint, and without an LLM key no tool call ever
 * fires. Per the honesty rule this spec asserts page structure, the live empty
 * states and the API guards instead of faking a decision flow. (The h2-only
 * DevSqlController backdoor was deliberately not used: schema-coupled SQL
 * seeding would rot silently.)
 */
test.describe('Approvals decision flow', () => {
  test('page renders header and queue tabs', async ({ page }) => {
    await page.goto('/approvals');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('h2').filter({ hasText: 'Approvals' }).first()).toBeVisible();
    await expect(page.locator('.tab-btn').filter({ hasText: 'Pending' })).toBeVisible();
    await expect(page.locator('.tab-btn').filter({ hasText: 'History' })).toBeVisible();
  });

  test('pending tab mirrors the live queue', async ({ page, request }) => {
    const { status, data } = await apiCall(request, 'GET', '/approvals');
    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);

    await page.goto('/approvals');
    await page.waitForLoadState('networkidle');
    if (data.length === 0) {
      await expect(page.locator('.empty-state').filter({ hasText: 'No pending approvals' })).toBeVisible();
    } else {
      await expect(page.locator('.tab-btn').filter({ hasText: `Pending (${data.length})` })).toBeVisible();
    }
  });

  test('history tab switches and shows resolved list or empty state', async ({ page }) => {
    await page.goto('/approvals');
    await page.waitForLoadState('networkidle');
    await page.locator('.tab-btn').filter({ hasText: 'History' }).click();

    // Pending empty state must be gone; history renders its own content.
    await expect(page.locator('.empty-state').filter({ hasText: 'No pending approvals' })).toHaveCount(0);
    const historyEmpty = page.locator('.empty-state').filter({ hasText: 'No resolved approvals yet.' });
    const anyContent = historyEmpty.or(page.locator('.empty-state, table, .approval-card').first());
    await expect(anyContent.first()).toBeVisible();
  });

  test('negative: deciding a non-existent approval is rejected', async ({ request }) => {
    const { status } = await apiCall(
      request,
      'POST',
      '/approvals/00000000-0000-0000-0000-000000000000/decide',
      { approved: true, reason: 'e2e negative' },
    );
    expect(status).toBeGreaterThanOrEqual(400);
  });
});
