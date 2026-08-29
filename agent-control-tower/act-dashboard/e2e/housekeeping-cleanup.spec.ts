import { test, expect } from '@playwright/test';
import { apiCall, seedAgent, seedKanbanItem, uniqueName } from './fixtures';

/**
 * Housekeeping e2e (no-LLM gate track): scan renders counts, kanban quick-clear
 * removes finished cards through the batch endpoint, and crew bulk-retire
 * retires leftover e2e agents. All destructive paths go through confirm modals.
 */
test.describe('Housekeeping cleanup', () => {
  test('Ops panel scans and shows category counts', async ({ page, request }) => {
    // Guarantee at least one finished kanban card so the scan has something to show.
    const item = await seedKanbanItem(request, { title: uniqueName('e2e-hk-scan') });
    await apiCall(request, 'POST', `/kanban/items/${item.id}/transition`, { status: 'IN_PROGRESS' });
    await apiCall(request, 'POST', `/kanban/items/${item.id}/transition`, { status: 'DONE' });

    await page.goto('/ops');
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: /scan leftovers/i }).click();

    // The kanban category row shows a non-zero count after the scan.
    const kanbanRow = page.locator('label').filter({ hasText: /finished kanban cards/i });
    await expect(kanbanRow).toBeVisible({ timeout: 15_000 });
    await expect(kanbanRow.locator('span').first()).not.toHaveText('0');
    // stuck + approvals stay unchecked by default
    await expect(
      page.getByLabel(/stuck \/ paused runs/i),
    ).not.toBeChecked();
  });

  test('kanban quick-clear removes finished cards via confirm modal', async ({ page, request }) => {
    const done = await seedKanbanItem(request, { title: uniqueName('e2e-hk-clear') });
    await apiCall(request, 'POST', `/kanban/items/${done.id}/transition`, { status: 'IN_PROGRESS' });
    await apiCall(request, 'POST', `/kanban/items/${done.id}/transition`, { status: 'DONE' });

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`[data-card="${done.id}"]`)).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: /clear done & cancelled \(\d+\)/i }).click();
    // confirm modal gates the batch
    await page.getByRole('button', { name: /approve & execute/i }).click();

    await expect(page.locator(`[data-card="${done.id}"]`)).toBeHidden({ timeout: 20_000 });
    const { status } = await apiCall(request, 'GET', `/kanban/items/${done.id}`);
    expect(status).toBe(404);
  });

  test('Select Leftovers button shows count, matches sibling style, always gives feedback', async ({ page }) => {
    await page.goto('/crew');
    await page.waitForLoadState('networkidle');

    const sel = page.getByRole('button', { name: /select leftovers \(\d+\)/i });
    const add = page.getByRole('button', { name: /add agent/i });
    await expect(sel).toBeVisible();

    // Same .btn base as the sibling action button (radius/padding/font).
    const styleOf = (loc: typeof sel) =>
      loc.evaluate((el) => {
        const s = getComputedStyle(el);
        return [s.borderRadius, s.padding, s.fontSize];
      });
    expect(await styleOf(sel)).toEqual(await styleOf(add));

    // Clicking always produces visible feedback: bulk bar or the no-leftovers note.
    await sel.click();
    await expect(
      page
        .getByRole('button', { name: /retire selected/i })
        .or(page.getByText(/no leftover agents found/i)),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('crew bulk-retire retires leftover e2e agents', async ({ page, request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-hk-retire'));

    await page.goto('/crew');
    await page.waitForLoadState('networkidle');
    const card = page
      .locator('.crew-card')
      .filter({ hasText: agent.name });
    await expect(card).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: /select leftovers/i }).click();
    await page.getByRole('button', { name: /retire selected/i }).click();

    await expect(card).toBeHidden({ timeout: 20_000 });
    const { data } = await apiCall(request, 'GET', `/agents/${agent.id}`);
    expect(data.healthStatus).toBe('RETIRED');
  });
});
