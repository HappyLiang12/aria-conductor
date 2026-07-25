import { test, expect } from '@playwright/test';
import { apiCall, seedKanbanItem, uniqueName } from './fixtures';

/**
 * Kanban board E2E.
 *
 * ADAPTATION NOTE: KanbanPage.tsx exists in src/pages but is not wired into
 * the router (App.tsx has no /kanban route). The shipped Kanban surface is the
 * KanbanBoard panel on the Overview page ('/'), so this spec targets that.
 */
test.describe('Kanban board (Overview governed flow)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  const boardPanel = (page: import('@playwright/test').Page) =>
    page.locator('section.panel').filter({ hasText: 'Kanban Board' });

  test('board renders all governed-flow columns', async ({ page }) => {
    // Panels nest (outer wrapper is also section.panel), so assert the h2 at
    // page level and the columns via their unique data-col attributes.
    await expect(page.locator('h2').filter({ hasText: 'Kanban Board' }).first()).toBeVisible();
    for (const col of ['backlog', 'todo', 'in_progress', 'review', 'qa_gate', 'done', 'archived']) {
      await expect(page.locator(`.col-k[data-col="${col}"]`)).toBeVisible();
    }
    await expect(page.locator('.col-k[data-col="todo"] header')).toContainText('Todo');
    await expect(page.locator('.col-k[data-col="qa_gate"] header')).toContainText('QA Gate');
  });

  test('API-seeded item appears in the Todo column', async ({ page, request }) => {
    const item = await seedKanbanItem(request, { title: uniqueName('e2e-kanban-seeded') });
    await page.reload();
    await page.waitForLoadState('networkidle');
    const card = page.locator(`[data-col="todo"] [data-card="${item.id}"]`);
    await expect(card).toBeVisible();
    await expect(card.locator('.t')).toHaveText(item.title);
  });

  test('create item via UI modal (with empty-title validation)', async ({ page }) => {
    const title = uniqueName('e2e-kanban-ui');
    await boardPanel(page).getByRole('button', { name: '+ New Item' }).click();
    await expect(page.locator('.modal-dialog h3')).toHaveText('New Kanban Item');

    // Negative: empty title is rejected client-side.
    await page.locator('.modal-dialog').getByRole('button', { name: 'Create', exact: true }).click();
    await expect(page.locator('.kanban-form-error')).toHaveText('Title is required');

    await page.locator('.kanban-form-row').filter({ hasText: 'Title' }).locator('input').fill(title);
    await page.locator('.modal-dialog').getByRole('button', { name: 'Create', exact: true }).click();
    await expect(page.locator('.modal-dialog')).toBeHidden({ timeout: 15_000 });
    await expect(
      page.locator('[data-col="todo"] .card .t').filter({ hasText: title }),
    ).toBeVisible({ timeout: 20_000 });
  });

  test('status transition moves the card across columns', async ({ page, request }) => {
    const item = await seedKanbanItem(request, { title: uniqueName('e2e-kanban-move') });
    const { status } = await apiCall(request, 'POST', `/kanban/items/${item.id}/transition`, {
      status: 'IN_PROGRESS',
      comment: 'e2e transition',
    });
    expect(status).toBe(200);

    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`[data-col="in_progress"] [data-card="${item.id}"]`)).toBeVisible();

    // Governance negative: IN_PROGRESS → TODO is not an allowed transition.
    const invalid = await apiCall(request, 'POST', `/kanban/items/${item.id}/transition`, {
      status: 'TODO',
    });
    expect(invalid.status).toBeGreaterThanOrEqual(400);
  });
});
