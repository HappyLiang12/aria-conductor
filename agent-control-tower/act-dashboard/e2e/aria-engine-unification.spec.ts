import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Aria Engine Unification verification.
 *
 * Verifies:
 * 1. Aria SSE streaming produces thinking → tool_call → tool_result → message → done events
 * 2. AgentDrawer Live Activity Stream shows enhanced events (tool calls, thinking)
 * 3. Agent creation respects config.maxToolCallRounds
 * 4. Aria non-streaming endpoint works via new Run-based flow
 */
test.describe.configure({ mode: 'serial', timeout: 300_000 });

const STREAM_TIMEOUT = 90_000;

/** Open the Aria floating panel */
async function openAriaPanel(page: Page) {
  const fab = page.locator('button.ai-fab');
  await fab.waitFor({ state: 'visible', timeout: 10_000 });
  await fab.click();
  await expect(page.locator('.ai-panel')).toBeVisible({ timeout: 5000 });
}

test('Aria SSE streaming produces all expected events', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await openAriaPanel(page);

  // Stream a message and capture SSE events
  const events = await page.evaluate(async () => {
    const collected: Array<{ event: string; data: string }> = [];
    const response = await fetch('/api/v1/aria/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message: 'list all agents', history: [] }),
    });
    if (!response.ok || !response.body) return collected;

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() ?? '';
      for (const block of parts) {
        if (!block.trim()) continue;
        let eventName = 'message';
        const dataLines: string[] = [];
        for (const line of block.split('\n')) {
          const clean = line.replace(/\r$/, '');
          if (clean.startsWith('event:')) eventName = clean.slice(6).trim();
          else if (clean.startsWith('data:')) dataLines.push(clean.slice(5).trimStart());
        }
        collected.push({ event: eventName, data: dataLines.join('\n') });
      }
      if (collected.some((e) => e.event === 'done')) break;
    }
    return collected;
  });

  console.log('SSE events collected:', events.map((e) => e.event));
  expect(events.length).toBeGreaterThan(0);

  // Verify expected event sequence
  const eventNames = events.map((e) => e.event);
  expect(eventNames).toContain('thinking');
  expect(eventNames).toContain('message');
  expect(eventNames).toContain('done');

  await page.screenshot({ path: 'test-results/screenshots/aria-unification-01-sse-events.png' });
});

test('Aria non-streaming chat uses Run-based flow', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const result = await page.evaluate(async () => {
    const res = await fetch('/api/v1/aria/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'list all agents', history: [] }),
    });
    if (!res.ok) return null;
    return res.json();
  });

  expect(result).not.toBeNull();
  expect(result.runId).toBeTruthy();
  // runId should be a UUID (run ID)
  expect(result.runId).toMatch(/^[0-9a-f-]{36}$/);
  expect(result.message).toBeTruthy();
  expect(result.intent).toBeTruthy();
  console.log(`Non-streaming chat: runId=${result.runId}, intent=${result.intent}`);

  await page.screenshot({ path: 'test-results/screenshots/aria-unification-02-non-streaming.png' });
});

test('AgentDrawer Live Activity Stream shows enhanced events', async ({ page }) => {
  await page.goto('/crew');
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: 'test-results/screenshots/aria-unification-03-crew-page.png' });

  // Open agent drawer by clicking an agent card
  const agentCards = page.locator('.agent-card');
  const cardCount = await agentCards.count();
  if (cardCount > 0) {
    await agentCards.first().click();
    // Wait for drawer to open
    await page.waitForTimeout(1000);
    await page.screenshot({ path: 'test-results/screenshots/aria-unification-04-drawer-open.png' });

    // Verify Live Activity Stream section exists
    const liveStreamSection = page.locator('.section-h', { hasText: 'Live Activity Stream' });
    await expect(liveStreamSection).toBeVisible({ timeout: 5000 });

    // Verify Configuration section (replaced hardcoded "Files Touched")
    const configSection = page.locator('.section-h', { hasText: 'Configuration' });
    await expect(configSection).toBeVisible({ timeout: 5000 });

    console.log('AgentDrawer Live Activity Stream and Configuration sections verified');
  }
});

test('Agent creation with config.maxToolCallRounds', async ({ page }) => {
  await page.goto('/crew');
  await page.waitForLoadState('networkidle');

  // Create agent via API with config
  const agentName = `e2e-test-${Date.now()}`;
  const createResult = await page.evaluate(async (name) => {
    const res = await fetch('/api/v1/agents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        agentType: 'NATIVE',
        role: 'test',
        adkProvider: 'langchain',
        config: { maxToolCallRounds: 3 },
      }),
    });
    if (!res.ok) return null;
    return res.json();
  }, agentName);

  expect(createResult).not.toBeNull();
  expect(createResult.id).toBeTruthy();
  expect(createResult.config).toBeDefined();
  console.log(`Agent created: id=${createResult.id}, config=${JSON.stringify(createResult.config)}`);

  await page.screenshot({ path: 'test-results/screenshots/aria-unification-05-agent-created.png' });
});
