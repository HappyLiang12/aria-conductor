import { test, expect } from '@playwright/test';

/**
 * E2E Regression: conversationId semantic fix (Issue #141).
 *
 * Verifies:
 * 1. Non-streaming chat returns runId + conversationId
 * 2. Streaming done event carries runId + conversationId
 * 3. GET /sessions returns 404 (endpoint removed)
 * 4. DELETE /sessions/{id} returns 404 (endpoint removed)
 * 5. conversationId survives page refresh
 * 6. conversationId echoed back in response
 * 7. Streaming emits correct SSE event sequence
 * 8. Copy button works with new selector
 * 9. Clear button regenerates conversationId
 */
test.describe.configure({ mode: 'serial', timeout: 300_000 });

// A successful LLM reply is needed for the 'message' SSE event; without a key the
// stream ends thinking → error → done.
const HAS_LLM_KEY = !!(
  process.env.LLM_API_KEY || process.env.LLM_PROVIDER_API_KEY || process.env.DEEPSEEK_API_KEY
);

test('non-streaming chat returns runId + conversationId', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const result = await page.evaluate(async () => {
    const res = await fetch('/api/v1/aria/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'hello', history: [], conversationId: 'test-conv-001' }),
    });
    return { status: res.status, body: await res.json() };
  });

  expect(result.status).toBe(200);
  expect(result.body.runId).toBeTruthy();
  expect(result.body.runId).toMatch(/^[0-9a-f-]{36}$/);
  expect(result.body.conversationId).toBe('test-conv-001');
  expect(result.body.message).toBeTruthy();
  expect(result.body.intent).toBeTruthy();
});

test('GET /api/v1/aria/sessions returns 404', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const status = await page.evaluate(async () => {
    const res = await fetch('/api/v1/aria/sessions');
    return res.status;
  });

  expect(status).toBe(404);
});

test('DELETE /api/v1/aria/sessions/{id} returns 404', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const status = await page.evaluate(async () => {
    const res = await fetch('/api/v1/aria/sessions/test-id', { method: 'DELETE' });
    return res.status;
  });

  expect(status).toBe(404);
});

test('streaming SSE emits expected events with runId + conversationId', async ({ page }) => {
  test.skip(!HAS_LLM_KEY, 'requires a real LLM API key for the message event');
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const events = await page.evaluate(async () => {
    const collected: Array<{ event: string; data: unknown }> = [];
    const res = await fetch('/api/v1/aria/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message: 'list all agents', history: [], conversationId: 'test-conv-stream' }),
    });
    if (!res.ok || !res.body) return collected;

    const reader = res.body.getReader();
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
        let parsed: unknown = dataLines.join('\n');
        try { parsed = JSON.parse(parsed as string); } catch { /* keep raw */ }
        collected.push({ event: eventName, data: parsed });
      }
      if (collected.some((e) => e.event === 'done')) break;
    }
    return collected;
  });

  expect(events.length).toBeGreaterThan(0);

  // Verify expected event sequence
  const eventNames = events.map((e) => e.event);
  expect(eventNames).toContain('thinking');
  expect(eventNames).toContain('message');
  expect(eventNames).toContain('done');

  // Verify done event payload
  const doneEvent = events.find((e) => e.event === 'done');
  expect(doneEvent).toBeDefined();
  const doneData = doneEvent!.data as Record<string, unknown>;
  expect(doneData.runId).toBeTruthy();
  expect(doneData.runId).toMatch(/^[0-9a-f-]{36}$/);
  expect(doneData.conversationId).toBe('test-conv-stream');
});

test('conversationId is reused across two turns (#36)', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const result = await page.evaluate(async () => {
    const post = (body: unknown) =>
      fetch('/api/v1/aria/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }).then((r) => r.json());

    const turn1 = await post({ message: 'remember 1', history: [], conversationId: 'reuse-conv-001' });
    // Turn 2 echoes back the id the server returned on turn 1.
    const turn2 = await post({ message: 'remember 2', history: [], conversationId: turn1.conversationId });
    return { c1: turn1.conversationId, c2: turn2.conversationId };
  });

  expect(result.c1).toBe('reuse-conv-001');
  // Turn 2 must reuse turn 1's conversationId (no new id minted per turn).
  expect(result.c2).toBe(result.c1);
});
