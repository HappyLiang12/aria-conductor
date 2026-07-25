import { test, expect } from '@playwright/test';
import { seedAgent } from './fixtures';

/**
 * Runs page E2E: render + form validation + a real run started via the UI.
 * NO LLM key on the verification stack — runs fail fast, so the spec asserts
 * reachable states (PENDING/RUNNING/FAILED…) and never model output.
 */
test.describe('Runs page', () => {
  test('page renders header, filters and start-run affordance', async ({ page }) => {
    await page.goto('/runs');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.page-header h2')).toHaveText('Runs');
    await expect(page.getByRole('button', { name: '+ Start Run' })).toBeVisible();
    await expect(page.locator('.filter-bar select').first()).toBeVisible();
  });

  test('start-run form rejects empty submission', async ({ page }) => {
    await page.goto('/runs');
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: '+ Start Run' }).click();
    await expect(page.locator('.form-card h3')).toHaveText('Start New Run');

    await page.getByRole('button', { name: 'Start Run', exact: true }).click();
    await expect(page.locator('.error-text').filter({ hasText: 'Agent selection is required' })).toBeVisible();
    await expect(page.locator('.error-text').filter({ hasText: 'Prompt seed is required' })).toBeVisible();
  });

  test('run started via UI form appears with a status badge', async ({ page, request }) => {
    const agent = await seedAgent(request); // NATIVE agents are HEALTHY → selectable
    await page.goto('/runs');
    await page.waitForLoadState('networkidle');

    await page.getByRole('button', { name: '+ Start Run' }).click();
    await page.locator('.form-card select').selectOption(agent.id);
    await page.locator('.form-card textarea').fill('E2E UI-started run — assert reachable state only');
    await page.getByRole('button', { name: 'Start Run', exact: true }).click();

    // Form closes on success and the run shows up in the table.
    await expect(page.locator('.form-card')).toHaveCount(0, { timeout: 20_000 });
    const row = page.locator('.data-table tbody tr').filter({ hasText: agent.name }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(row.locator('td').nth(2)).toContainText(
      /PENDING|INITIALIZING|RUNNING|PAUSED|COMPLETED|FAILED|CANCELLED/,
    );
  });
});
