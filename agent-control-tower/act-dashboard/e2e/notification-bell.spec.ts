import { test, expect } from '@playwright/test';

test.describe('Notification Bell', () => {
  test('bell icon is visible in TopBar', async ({ page }) => {
    await page.goto('/');
    const bell = page.locator('.notif-bell-btn');
    await expect(bell).toBeVisible();
  });

  test('bell shows no-bell emoji when no unread notifications', async ({ page }) => {
    await page.route('**/api/v1/aria/notifications/count', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ unreadCount: 0 }) })
    );
    await page.goto('/');
    await expect(page.locator('.notif-bell-btn')).toContainText('🔔');
  });

  test('bell shows badge when unread notifications exist', async ({ page }) => {
    await page.route('**/api/v1/aria/notifications/count', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ unreadCount: 3 }) })
    );
    await page.goto('/');
    await expect(page.locator('.notif-badge')).toContainText('3');
  });

  test('click bell opens dropdown', async ({ page }) => {
    await page.route('**/api/v1/aria/notifications?page=*', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0 }) })
    );
    await page.route('**/api/v1/aria/notifications/count', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ unreadCount: 0 }) })
    );
    await page.goto('/');
    await page.locator('.notif-bell-btn').click();
    await expect(page.locator('.notif-dropdown')).toBeVisible();
  });

  test('dropdown shows empty state when no notifications', async ({ page }) => {
    await page.route('**/api/v1/aria/notifications?page=*', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0 }) })
    );
    await page.route('**/api/v1/aria/notifications/count', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ unreadCount: 0 }) })
    );
    await page.goto('/');
    await page.locator('.notif-bell-btn').click();
    await expect(page.locator('.notif-empty')).toContainText('No notifications');
  });

  test('click outside closes dropdown', async ({ page }) => {
    await page.route('**/api/v1/aria/notifications?page=*', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0 }) })
    );
    await page.route('**/api/v1/aria/notifications/count', (route) =>
      route.fulfill({ status: 200, body: JSON.stringify({ unreadCount: 0 }) })
    );
    await page.goto('/');
    await page.locator('.notif-bell-btn').click();
    await expect(page.locator('.notif-dropdown')).toBeVisible();
    await page.locator('body').click({ position: { x: 0, y: 0 } });
    await expect(page.locator('.notif-dropdown')).not.toBeVisible();
  });
});
