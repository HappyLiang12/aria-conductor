import { test, expect } from '@playwright/test';
import { apiCall, pollRunTerminal, seedAgent, seedKanbanItem, transitionKanban, uniqueName } from './fixtures';

/**
 * Housekeeping e2e (no-LLM gate track): scan renders counts, kanban quick-clear
 * removes finished cards through the batch endpoint, and crew bulk-retire
 * retires leftover e2e agents. All destructive paths go through confirm modals.
 *
 * ADAPTATION NOTE (kanban HITL redesign): the scan's kanban category counts
 * DONE + CANCELLED cards, and CANCELLED cards render nowhere on the board
 * (cancel is an action, not a column). TODO→IN_PROGRESS is now a two-phase
 * pickup dispatch that needs an eligible healthy agent — on a fresh CI DB the
 * un-pinned old seeding silently stayed TODO (lastError) and TODO→DONE is
 * illegal, so nothing ever reached the scan category. Seeding now either
 * cancels directly (scan test) or pins a fresh agent and waits for the linked
 * run to go terminal before DONE (quick-clear test — the DONE guard rejects
 * while the linked run is still active).
 */
test.describe('Housekeeping cleanup', () => {
  test('Ops panel scans and shows category counts', async ({ page, request }) => {
    // Guarantee at least one finished kanban card so the scan has something to
    // show: TODO → CANCELLED is legal without a run and counts in the scan's
    // kanban category (DONE + CANCELLED).
    const item = await seedKanbanItem(request, { title: uniqueName('e2e-hk-scan') });
    const cancelled = await transitionKanban(request, item.id, 'CANCELLED');
    expect(cancelled.status).toBe(200);

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
    // The card must be VISIBLE before the clear (cancelled cards never render),
    // so drive it to DONE: pin a fresh healthy agent for the pickup, then wait
    // for the linked run to go terminal before the DONE transition.
    const agent = await seedAgent(request, uniqueName('e2e-hk-clear-agent'));
    const done = await seedKanbanItem(request, {
      title: uniqueName('e2e-hk-clear'),
      agentTemplateId: agent.name,
    });
    const dispatched = await transitionKanban(request, done.id, 'IN_PROGRESS');
    expect(dispatched.status).toBe(200);
    // A pickup pre-validation failure returns 200 with the card still in TODO
    // (lastError set) — fail fast here instead of timing out on the run poll.
    expect(dispatched.data.status).toBe('IN_PROGRESS');
    expect(dispatched.data.linkedRunId).toBeTruthy();
    await pollRunTerminal(request, dispatched.data.linkedRunId, 60_000);
    const finished = await transitionKanban(request, done.id, 'DONE');
    expect(finished.status).toBe(200);

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
