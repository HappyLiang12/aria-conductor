import { test, expect } from '@playwright/test';

test.describe('Scheduled Jobs Page', () => {
  test('navigates to scheduled jobs page', async ({ page }) => {
    await page.route('**/api/v1/aria/jobs**', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify([]) })
    );
    await page.goto('/scheduled-jobs');
    await expect(page.locator('.page-header h2')).toContainText('Scheduled Jobs');
  });

  test('shows empty state when no jobs', async ({ page }) => {
    await page.route('**/api/v1/aria/jobs**', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify([]) })
    );
    await page.goto('/scheduled-jobs');
    await expect(page.locator('.card p')).toContainText('No scheduled jobs found');
  });

  test('create job button opens modal', async ({ page }) => {
    await page.route('**/api/v1/aria/jobs**', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify([]) })
    );
    await page.goto('/scheduled-jobs');
    await page.locator('button:has-text("+ New Job")').click();
    await expect(page.locator('.modal-dialog')).toBeVisible();
    await expect(page.locator('.modal-dialog h3')).toContainText('New Job');
  });
});
