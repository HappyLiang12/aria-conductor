import { test, expect, type Page } from '@playwright/test';

/**
 * The main Aria chat surface. Aria's core is pinned to opencode/SANDBOX, so a
 * stack without a container runtime (or an LLM key) cannot answer a real turn;
 * this spec drives the real browser against stubbed Aria endpoints and the SSE
 * contract the backend actually emits. Live-progress rendering from WS frames
 * is unit-tested (src/components/main-chat/__tests__/progress.test.ts).
 */

const CONVERSATION = {
  conversationId: 'conv-e2e',
  title: 'Deploy the crew',
  lastMessageAt: '2026-11-20T10:00:00Z',
  runCount: 2,
};

async function stubAriaSurface(page: Page) {
  let deleted = false;

  await page.route(/\/api\/v1\/aria\/conversations\/latest$/, (route) =>
    route.fulfill({ json: CONVERSATION }));

  await page.route(/\/api\/v1\/aria\/conversations\/(?!latest$)[^/?]+$/, async (route) => {
    if (route.request().method() === 'DELETE') {
      deleted = true;
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fulfill({
      json: [
        { role: 'user', content: 'Deploy the crew', timestamp: '2026-11-20T09:59:00Z', runId: 'run-e2e' },
        { role: 'assistant', content: 'Crew deployed.', timestamp: '2026-11-20T10:00:00Z', runId: 'run-e2e' },
      ],
    });
  });

  await page.route(/\/api\/v1\/aria\/conversations(\?.*)?$/, (route) => {
    if (route.request().method() !== 'GET') {
      return route.fulfill({ status: 405, body: '' });
    }
    return route.fulfill({ json: deleted ? [] : [CONVERSATION] });
  });

  await page.route(/\/api\/v1\/runs\/[^/]+\/progress(\?.*)?$/, (route) => route.fulfill({ json: [] }));
}

async function stubStream(page: Page) {
  await page.route('**/api/v1/aria/chat/stream', (route) => {
    const body = [
      'event: thinking',
      'data: {"status":"processing","runId":"run-e2e","mode":"core"}',
      '',
      'event: message',
      'data: {"content":"All good. The crew is healthy."}',
      '',
      'event: done',
      'data: {"runId":"run-e2e","conversationId":"conv-e2e","intent":"status"}',
      '',
      '',
    ].join('\n');
    return route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      body,
    });
  });
}

test.describe('Aria main chat', () => {
  test('opens from the rail, lists conversations and renders the latest timeline', async ({ page }) => {
    await stubAriaSurface(page);
    await page.goto('/');

    await page.getByRole('link', { name: 'Aria' }).click();
    await expect(page).toHaveURL(/\/aria(\?conversationId=conv-e2e)?$/);

    await expect(page.locator('.mc-header-title')).toHaveText('Deploy the crew');
    await expect(page.locator('.mc-conversation-list')).toContainText('Deploy the crew');
    await expect(page.locator('.mc-stack')).toContainText('Crew deployed.');
  });

  test('expands the floating panel into the main chat with its conversation', async ({ page }) => {
    await stubAriaSurface(page);
    await page.goto('/');
    await page.waitForSelector('.ai-fab', { state: 'visible' });
    await page.click('.ai-fab');
    await expect(page.locator('.ai-panel')).toBeVisible();
    await expect(page.locator('.ai-conversation-id')).not.toHaveText('...', { timeout: 15_000 });

    await page.getByRole('button', { name: 'Open Aria in the main chat' }).click();

    await expect(page).toHaveURL(/\/aria\?conversationId=conv-e2e$/);
    await expect(page.locator('.mc-header-title')).toHaveText('Deploy the crew');
  });

  test('sends a turn and renders the streamed final answer', async ({ page }) => {
    await stubAriaSurface(page);
    await stubStream(page);
    await page.goto('/aria');
    await expect(page.locator('.mc-stack')).toContainText('Crew deployed.');

    await page.fill('.mc-compose .ai-compose-input', 'Send me a status');
    await page.click('.mc-compose .ai-action-btn');

    await expect(page.locator('.mc-stack')).toContainText('Send me a status');
    await expect(page.locator('.mc-stack')).toContainText('All good. The crew is healthy.');
    await expect(page.locator('.mc-compose .ai-action-btn')).toBeVisible();
  });

  test('deletes a conversation and returns to an empty new chat', async ({ page }) => {
    await stubAriaSurface(page);
    await page.goto('/aria');
    await expect(page.locator('.mc-conversation-list')).toContainText('Deploy the crew');

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Delete conversation Deploy the crew' }).click();

    await expect(page.locator('.mc-conversation-list')).toHaveCount(0);
    await expect(page.locator('.mc-empty')).toContainText('Ask Aria anything.');
  });
});
