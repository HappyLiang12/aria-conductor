import { test, expect } from '@playwright/test';

test.describe('Notification Toast', () => {
  test('toast appears on aria.notification WebSocket event', async ({ page }) => {
    await page.goto('/');
    // Simulate a WebSocket message by evaluating script
    await page.evaluate(() => {
      window.dispatchEvent(new MessageEvent('message', {
        data: JSON.stringify({ type: 'aria.notification', payload: { id: 'n1', title: 'Test notification', type: 'run.completed' }, timestamp: new Date().toISOString() })
      }));
    });
    // Toast should appear
    await expect(page.locator('.toast-item')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('.toast-item')).toContainText('Test notification');
  });
});
