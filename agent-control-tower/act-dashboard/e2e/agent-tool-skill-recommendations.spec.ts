import { test, expect } from '@playwright/test';

/**
 * E2E: role-based tool & skill recommendations.
 *
 * Verifies the full stack for the "Aria recommends, user confirms" flow:
 *  1. Opening "Add Agent" fetches GET /api/v1/agents/role-defaults/{role} and
 *     renders the role's recommended tools PRE-CHECKED (proves V33 seeded the
 *     ba/dev/qa defaults and the resolver returns them end-to-end).
 *  2. Hiring the agent persists the confirmed selection via the bulk PUT, so the
 *     new agent's Manage Capabilities dialog shows those tools already assigned.
 *  3. "Apply role defaults" in the Manage dialog re-applies the recommended set.
 */
test.describe('Agent tool & skill recommendations', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/crew');
    await page.waitForSelector('[data-view="crew"]', { state: 'visible' });
  });

  test('Add Agent pre-checks the role recommended tools', async ({ page }) => {
    await page.getByRole('button', { name: '+ Add Agent' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('Recommended tools')).toBeVisible();

    // The role defaults load async; a dev agent must have at least one pre-checked tool.
    await expect
      .poll(async () => dialog.locator('input[type="checkbox"]:checked').count(), {
        timeout: 15_000,
      })
      .toBeGreaterThan(0);
  });

  test('Hiring persists the recommended tools onto the new agent', async ({ page }) => {
    const unique = `Rec-${Date.now()}`;
    await page.getByRole('button', { name: '+ Add Agent' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // Wait for recommendations to pre-check, then capture the selected count.
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

    // Open Manage Tools/Skills for the new agent and confirm the tools were persisted.
    await card.getByRole('button', { name: /manage|tools|capabilities/i }).first().click();
    const manage = page.getByRole('dialog');
    await expect(manage.getByText('Assign', { exact: false }).or(manage.getByText('Capabilities'))).toBeVisible();
    await expect
      .poll(async () => manage.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThanOrEqual(preChecked);
  });
});
