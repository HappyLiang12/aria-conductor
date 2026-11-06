import { test, expect } from '@playwright/test';

/**
 * An unknown URL used to render a completely blank page, because App.tsx had
 * no catch-all route. It now renders a not-found surface with the navigation
 * intact.
 */
test('unknown route renders the not-found surface, not a blank page', async ({ page }) => {
  await page.goto('/definitely-not-a-route');
  await page.waitForLoadState('networkidle');

  await expect(page.getByTestId('not-found')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/page not found/i)).toBeVisible();
  // The rail must remain, so the operator can navigate out.
  await expect(page.locator('.rail')).toBeVisible();

  await page.getByRole('link', { name: /back to overview/i }).click();
  await expect(page.locator('.rail')).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});
