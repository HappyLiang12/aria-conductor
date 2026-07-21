import { test, expect } from '@playwright/test';

test.describe('Notification Toast', () => {
  test('toast appears on aria.notification WebSocket event', async ({ page }) => {
    // Intercept the app's WebSocket and emulate the minimal STOMP handshake the
    // client performs (see hooks/useWebSocket.ts): CONNECT -> CONNECTED, then on
    // SUBSCRIBE push a real aria.notification MESSAGE frame. This drives the toast
    // through the true production path (useWebSocket -> shared context -> Toast),
    // instead of a window 'message' event that nothing listens to.
    await page.routeWebSocket(/\/ws\/events/, (ws) => {
      ws.onMessage((message) => {
        const frame = typeof message === 'string' ? message : message.toString();
        if (frame.startsWith('CONNECT')) {
          ws.send('CONNECTED\nversion:1.2\n\n\0');
        } else if (frame.startsWith('SUBSCRIBE')) {
          const body = JSON.stringify({
            type: 'aria.notification',
            payload: { id: 'n1', title: 'Test notification', type: 'run.completed' },
            timestamp: new Date().toISOString(),
          });
          ws.send(
            `MESSAGE\ndestination:/topic/events\nsubscription:sub-0\ncontent-type:application/json\n\n${body}\0`
          );
        }
      });
    });

    await page.goto('/');
    // Toast should appear from the real WebSocket event path
    await expect(page.locator('.toast-item')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.toast-item')).toContainText('Test notification');
  });
});
