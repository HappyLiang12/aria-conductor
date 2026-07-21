import { test, expect } from '@playwright/test';

/**
 * E2E: role-based tool & skill recommendations.
 *
 * Verifies the full stack for the "recommend, user confirms" flow:
 *  1. Opening "Add Agent" fetches GET /api/v1/agents/role-defaults/{role} and
 *     renders the role's recommended tools PRE-CHECKED (proves the migration
 *     seeded ba/dev/qa defaults and the resolver returns them end-to-end).
 *  2. Hiring the agent persists the confirmed selection via the bulk PUT, so the
 *     new agent's Manage (Capabilities) dialog shows those tools already assigned.
 *
 * Both the Add-Agent and Manage dialogs use the `.mini-dialog` convention and are
 * both present in the DOM, so locators are scoped to the currently-open dialog
 * (`.mini-dialog.open`) to avoid role="dialog" ambiguity.
 */
test.describe('Agent tool & skill recommendations', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/crew');
    await page.waitForSelector('[data-view="crew"]', { state: 'visible' });
  });

  test('Add Agent pre-checks the role recommended tools', async ({ page }) => {
    await page.getByRole('button', { name: '+ Add Agent' }).click();

    const dialog = page.locator('.mini-dialog.open');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('Recommended tools')).toBeVisible();

    // Role defaults load async; a dev agent must have at least one pre-checked tool.
    await expect
      .poll(async () => dialog.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThan(0);
  });

  test('Hiring persists the recommended tools onto the new agent', async ({ page }) => {
    const unique = `Rec-${Date.now()}`;
    await page.getByRole('button', { name: '+ Add Agent' }).click();

    const dialog = page.locator('.mini-dialog.open');
    await expect(dialog).toBeVisible();
    await expect
      .poll(async () => dialog.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThan(0);
    const preChecked = await dialog.locator('input[type="checkbox"]:checked').count();

    await dialog.locator('#add-agent-name').fill(unique);
    await dialog.getByRole('button', { name: 'Hire Agent' }).click();

    // Dialog closes and the new agent appears on the roster.
    await expect(dialog).toBeHidden({ timeout: 15_000 });
    const card = page.locator('.crew-card', { hasText: unique });
    await expect(card).toBeVisible({ timeout: 15_000 });

    // Open the Manage (Capabilities) dialog and confirm the tools were persisted.
    await card.getByRole('button', { name: /tools/i }).first().click();
    const manage = page.locator('.mini-dialog.open');
    await expect(manage).toBeVisible();
    await expect(manage.getByText('Capabilities')).toBeVisible();
    await expect
      .poll(async () => manage.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThanOrEqual(preChecked);
  });

  test('Apply role defaults restores the recommended set in Manage', async ({ page }) => {
    const unique = `RecApply-${Date.now()}`;
    await page.getByRole('button', { name: '+ Add Agent' }).click();
    const addDialog = page.locator('.mini-dialog.open');
    await expect(addDialog).toBeVisible();
    await expect
      .poll(async () => addDialog.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThan(0);
    await addDialog.locator('#add-agent-name').fill(unique);
    await addDialog.getByRole('button', { name: 'Hire Agent' }).click();
    await expect(addDialog).toBeHidden({ timeout: 15_000 });

    const card = page.locator('.crew-card', { hasText: unique });
    await expect(card).toBeVisible({ timeout: 15_000 });
    await card.getByRole('button', { name: /tools/i }).first().click();

    const manage = page.locator('.mini-dialog.open');
    await expect(manage).toBeVisible();
    // The Skills section is rendered alongside Tools.
    await expect(manage.getByText('Skills', { exact: true })).toBeVisible();

    await expect
      .poll(async () => manage.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThan(0);
    const recommended = await manage.locator('input[type="checkbox"]:checked').count();

    // Uncheck the first tool, then re-apply role defaults to restore the recommended set.
    await manage.locator('input[type="checkbox"]:checked').first().uncheck();
    expect(await manage.locator('input[type="checkbox"]:checked').count()).toBe(recommended - 1);

    await manage.getByRole('button', { name: 'Apply role defaults' }).click();
    await expect
      .poll(async () => manage.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThanOrEqual(recommended);
  });
});
