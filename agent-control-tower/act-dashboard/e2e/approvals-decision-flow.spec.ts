import { test, expect } from '@playwright/test';
import { apiCall } from './fixtures';

/**
 * Approvals decision-flow E2E.
 *
 * The ApprovalsPage and its /approvals route are DELETED — there is no redirect
 * to test, and the sidebar has no Approvals entry. The approvals SURFACE is the
 * kanban Review column with per-card asks (GET /approvals?kanbanItemId=). The
 * full decision flow (ask surfacing on a Review card, decision zone, quick
 * decisions) is covered live by kanban-hitl.spec.ts, so this spec stays a
 * focused smoke: the Review surface renders and the surviving approvals API
 * stays guarded.
 */
test.describe('Approvals decision flow', () => {
  test('the approvals surface is the kanban Review column (sidebar entry retired)', async ({ page, request }) => {
    // The approvals REST API remains the per-card ask source for the board.
    const { status, data } = await apiCall(request, 'GET', '/approvals');
    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    // The Review column is the single HITL surface.
    await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible();
    // The sidebar has no Approvals entry anymore — the Review column is it.
    await expect(page.locator('.rail-btn[data-view="approvals"]')).toHaveCount(0);
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
