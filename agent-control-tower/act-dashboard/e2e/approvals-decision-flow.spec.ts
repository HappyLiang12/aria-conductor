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

    const pendingTab = page.locator('.tab-btn').filter({ hasText: 'Pending' });
    await expect(pendingTab).toBeVisible({ timeout: 15_000 });

    // Count-agnostic mirror check: the approvals queue is SHARED (live chains
    // and other specs decide gates concurrently, and /approvals returns every
    // status on a dirty DB), so no fixed total can be asserted. Re-read the
    // API and the rendered tab count together until they agree — the tab must
    // reflect the live PENDING queue at some moment within the window.
    await expect
      .poll(async () => {
        const { data: now } = await apiCall(request, 'GET', '/approvals');
        const apiPending = (Array.isArray(now) ? now : []).filter((a) => a?.status === 'PENDING').length;
        const shown = Number(((await pendingTab.textContent()) ?? '').match(/Pending \((\d+)\)/)?.[1] ?? -1);
        return shown === apiPending;
      }, { timeout: 20_000 })
      .toBe(true);
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
