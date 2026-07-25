import { test, expect } from '@playwright/test';
import { apiCall } from './fixtures';

/**
 * Reports workspace E2E.
 *
 * ADAPTATION NOTE: report generation (POST /reports) calls the LLM
 * (ReportService.generate → generateHtmlViaLlm), so with no key on the
 * verification stack this spec asserts the workspace shell, the live
 * archive/empty state, and the generate-dialog validation — never a fake
 * "generated" report.
 */
test.describe('Reports workspace', () => {
  test('renders workspace header and generate affordance', async ({ page }) => {
    await page.goto('/reports');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('h1').filter({ hasText: 'Agent Report Workspace' })).toBeVisible();
    await expect(page.getByRole('button', { name: '+ Generate Report' }).first()).toBeVisible();
  });

  test('archive state mirrors the live API (empty state when silent)', async ({ page, request }) => {
    const { status, data } = await apiCall(request, 'GET', '/reports');
    expect(status).toBe(200);

    await page.goto('/reports');
    await page.waitForLoadState('networkidle');
    if ((data ?? []).length === 0) {
      await expect(page.locator('h2').filter({ hasText: 'Reports' })).toContainText('no dossiers yet');
      await expect(page.getByText('The archive is silent.')).toBeVisible();
    } else {
      await expect(page.locator('.report-tab')).toHaveCount(data.length);
    }
  });

  test('generate dialog validates required fields (generation itself needs an LLM)', async ({ page }) => {
    await page.goto('/reports');
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: '+ Generate Report' }).first().click();

    const dialog = page.locator('.mini-dialog.open');
    await expect(dialog.locator('h3')).toHaveText('Generate New Report');

    // Negative: both Title and Topic/Prompt are required.
    await dialog.getByRole('button', { name: /Generate$/ }).click();
    await expect(dialog.getByText('Title and topic/prompt are required')).toBeVisible();

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.locator('.mini-dialog.open')).toHaveCount(0);
  });
});
