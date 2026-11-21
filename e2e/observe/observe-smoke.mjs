#!/usr/bin/env node
/**
 * Deterministic-harness E2E for POST /api/v1/aria/observe.
 *
 * Boots nothing itself: it expects the core E2E harness backend on
 * ARIA_E2E_BASE_URL (default http://127.0.0.1:8080), the way the CI smoke lane
 * starts it. It posts one observation with a real 1x1 JPEG, reads the SSE
 * response to its terminal frame, then reads the conversation timeline and
 * proves the persisted turn is the text seed with no image data.
 *
 * Covered here that unit tests cannot cover: the real HTTP route, operator
 * authority from loopback, the run created and executed on the mock opencode
 * peer, the live SSE frames, and the persisted timeline.
 *
 * NOT covered here: an ar_render tool call. The harness fixture completes
 * without invoking tools; that frame is proven by the controller fixture test
 * and the real MCP-session integration test (McpEndpointIntegrationTest).
 *
 * Env: ARIA_E2E_BASE_URL, ARIA_OBSERVE_E2E_TIMEOUT_MS (default 120000).
 */

import { randomUUID } from 'node:crypto';

const BASE = (process.env.ARIA_E2E_BASE_URL ?? 'http://127.0.0.1:8080').replace(/\/+$/, '');
const TIMEOUT_MS = Number(process.env.ARIA_OBSERVE_E2E_TIMEOUT_MS ?? 120_000);
const CONVERSATION_ID = randomUUID();

/** A real 1x1 JPEG (sips from a 1x1 PNG); the validator checks base64 and MIME. */
const JPEG_BASE64 =
  '/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAAaADAAQAAAABAAAAAQAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAAQABAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A+mKKKK/Kz/QA/9k=';

const OBSERVATION = {
  conversationId: CONVERSATION_ID,
  note: 'observe e2e',
  image: { mimeType: 'image/jpeg', dataBase64: JPEG_BASE64, width: 1, height: 1 },
  clientCapabilities: { kind: 'glasses', ar: true, vision: true, audio: false },
  observation: {
    timestamp: Date.now(),
    heading: 12.5,
    pitch: -4,
    roll: 0.5,
    objects: [
      {
        id: 'o1',
        trackId: 7,
        label: 'remote',
        confidence: 0.91,
        bbox: [0.42, 0.55, 0.12, 0.08],
        center: [0.48, 0.59],
        description: 'observe e2e remote',
      },
    ],
    ocrText: 'E2E',
    localSummary: '1 remote',
  },
};

function fail(message) {
  console.error(`OBSERVE E2E FAILED: ${message}`);
  process.exit(1);
}

function requireFrame(text, frame) {
  if (!text.includes(`event:${frame}`)) {
    fail(`SSE stream carries no '${frame}' frame. Body tail:\n${text.slice(-2000)}`);
  }
}

async function streamObservation() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(`${BASE}/api/v1/aria/observe`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(OBSERVATION),
      signal: controller.signal,
    });
    const contentType = response.headers.get('content-type') ?? '';
    if (response.status !== 200) {
      fail(`POST /observe answered ${response.status}: ${await response.text()}`);
    }
    if (!contentType.includes('text/event-stream')) {
      fail(`POST /observe answered 200 with content-type '${contentType}'`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let text = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        text += decoder.decode();
        break;
      }
      text += decoder.decode(value, { stream: true });
      if (text.includes('event:done') || text.includes('event:error')) {
        await reader.cancel();
        break;
      }
    }
    return text;
  } finally {
    clearTimeout(timer);
  }
}

async function readTimeline() {
  const response = await fetch(`${BASE}/api/v1/aria/conversations/${encodeURIComponent(CONVERSATION_ID)}`);
  if (response.status !== 200) {
    fail(`GET /conversations/${CONVERSATION_ID} answered ${response.status}: ${await response.text()}`);
  }
  return response.text();
}

async function expectLongConversationIdRejected() {
  const response = await fetch(`${BASE}/api/v1/aria/observe`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ ...OBSERVATION, conversationId: `observe-e2e-${randomUUID()}` }),
  });
  const text = await response.text();
  if (response.status !== 400) {
    fail(`a 48-character conversationId answered ${response.status} (expected 400): ${text}`);
  }
  if (!text.includes('conversationId')) {
    fail(`the 400 body does not name conversationId: ${text}`);
  }
}

async function main() {
  await expectLongConversationIdRejected();

  const stream = await streamObservation();

  if (stream.includes('event:error')) {
    fail(`the turn failed. Body tail:\n${stream.slice(-2000)}`);
  }
  requireFrame(stream, 'thinking');
  requireFrame(stream, 'message');
  requireFrame(stream, 'done');
  if (!stream.includes('"intent":"observe"')) {
    fail(`the done frame does not carry intent=observe:\n${stream.slice(-1200)}`);
  }
  if (!stream.includes(`"conversationId":"${CONVERSATION_ID}"`)) {
    fail(`the done frame does not carry conversationId=${CONVERSATION_ID}`);
  }
  if (stream.includes(JPEG_BASE64)) {
    fail('the SSE stream carries the image base64');
  }

  const timeline = await readTimeline();
  if (!timeline.includes('[glasses observation]')) {
    fail(`the persisted timeline has no observation seed:\n${timeline.slice(0, 2000)}`);
  }
  if (timeline.includes(JPEG_BASE64)) {
    fail('the persisted timeline carries the image base64');
  }
  if (!timeline.includes('trackId=7')) {
    fail('the persisted seed lost the observation objects');
  }

  console.log('OBSERVE E2E PASSED: thinking/message/done over SSE, intent=observe,');
  console.log('text-only persisted seed, no image data in the stream or the timeline.');
}

main().catch((error) => fail(error?.stack ?? String(error)));
