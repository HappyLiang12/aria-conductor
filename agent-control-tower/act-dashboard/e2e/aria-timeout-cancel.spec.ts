import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Aria SSE timeout & cancel button (Issue #12).
 *
 * Verifies:
 * 1. Cancel button appears while Aria is processing.
 * 2. Clicking Cancel aborts the request, shows "cancelled" message, and
 *    allows sending a new message without page refresh.
 * 3. A stream that closes without a `done` event surfaces an error message
 *    and a Retry button (silent stream-end detection fix).
 */
test.describe.configure({ mode: 'serial', timeout: 60_000 });

const SCREENSHOT_DIR = 'e2e/screenshots/timeout-cancel';

/** Open the Aria floating panel */
async function openAriaPanel(page: Page) {
  const fab = page.locator('button.ai-fab');
  await fab.waitFor({ state: 'visible', timeout: 10_000 });
  await fab.click();
  await expect(page.locator('.ai-panel')).toBeVisible({ timeout: 5000 });
}

/** Type and send a message in the Aria panel */
async function typeAndSend(page: Page, message: string) {
  const textarea = page.locator('.ai-panel textarea');
  await textarea.waitFor({ state: 'visible', timeout: 5000 });
  await textarea.fill(message);
  await textarea.press('Enter');
}

// ─────────────────────────────────────────────────────────────────────
test('Cancel button appears while Aria is busy', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await openAriaPanel(page);

  // Send a message that will take some time to process.
  await typeAndSend(page, 'What is the status of all agents?');

  // The cancel button should appear while Aria is processing.
  const cancelBtn = page.locator('.ai-cancel-btn');
  await expect(cancelBtn).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/01-cancel-button-visible.png` });

  console.log('✓ Cancel button rendered while busy');
});

// ─────────────────────────────────────────────────────────────────────
test('Clicking Cancel aborts request and allows new message', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  // Override fetch AFTER navigation so the override survives.
  await page.evaluate(() => {
    const originalFetch = window.fetch;
    (window as any).__originalFetch = originalFetch;
    window.fetch = async (url: any, init?: any) => {
      if (typeof url === 'string' && url.includes('/api/v1/aria/chat/stream')) {
        const encoder = new TextEncoder();
        const stream = new ReadableStream({
          start(controller) {
            controller.enqueue(encoder.encode('event: thinking\ndata: {"status":"processing"}\n\n'));
            // Never close — the stream hangs indefinitely.
          },
        });
        return new Response(stream, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        });
      }
      return originalFetch(url, init);
    };
  });

  await openAriaPanel(page);

  // Send a message.
  await typeAndSend(page, 'Brief me on overnight runs');

  // The cancel button should appear since the stream only sent `thinking` (no `done`).
  const cancelBtn = page.locator('.ai-cancel-btn');
  await expect(cancelBtn).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/02-cancel-clicked.png` });

  // Click Cancel.
  await cancelBtn.click();

  // The cancel button should disappear (busy cleared).
  await expect(cancelBtn).toBeHidden({ timeout: 5000 });

  // A "Request cancelled." message should appear.
  await expect(page.getByText('Request cancelled.')).toBeVisible({ timeout: 5000 });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/03-cancelled-message.png` });

  // The Send button should be back and the textarea enabled.
  const sendBtn = page.locator('.ai-action-btn');
  await expect(sendBtn).toBeVisible({ timeout: 3000 });

  const textarea = page.locator('.ai-panel textarea');
  await expect(textarea).toBeEnabled({ timeout: 3000 });

  // Restore original fetch before sending a new message.
  await page.evaluate(() => {
    if ((window as any).__originalFetch) {
      window.fetch = (window as any).__originalFetch;
    }
  });

  // User can type and send a new message without refreshing.
  await textarea.fill('What is the dashboard summary?');
  await expect(sendBtn).toBeEnabled({ timeout: 3000 });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/04-can-send-again.png` });

  console.log('✓ Cancel aborts request, user can send new message');
});

// ─────────────────────────────────────────────────────────────────────
test('Error message on silent stream close', async ({ page }) => {
  // Intercept the SSE stream endpoint and return an empty stream (no done/error events).
  await page.route('**/api/v1/aria/chat/stream', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: '',  // Empty body — stream closes immediately without any events.
    });
  });

  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await openAriaPanel(page);

  // Send a message — the intercepted stream will close silently.
  await typeAndSend(page, 'Test silent close');

  // The error message "Connection closed unexpectedly" should appear.
  await expect(page.getByText(/Connection closed unexpectedly/)).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/05-silent-close-error.png` });

  // The Retry button should be shown alongside the error message.
  const retryBtn = page.locator('.ai-retry');
  await expect(retryBtn).toBeVisible({ timeout: 5000 });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/06-retry-button-shown.png` });

  // Busy state should be cleared (no spinner stuck).
  const cancelBtn = page.locator('.ai-cancel-btn');
  await expect(cancelBtn).toBeHidden({ timeout: 3000 });

  // Unroute to avoid interfering with other tests.
  await page.unroute('**/api/v1/aria/chat/stream');

  console.log('✓ Silent stream close shows error message and Retry button');
});
