#!/usr/bin/env node
/**
 * Run-level deterministic harness E2E for the ar_render SSE frame.
 *
 * The harness's mock opencode peer is told (scenario `ar-render`) to invoke the
 * real ar_render tool once, through the run-scoped streamable MCP endpoint, while
 * a real Aria run is in flight. This script:
 *   1. selects the `ar-render` scenario for the Aria agent,
 *   2. starts one chat/stream turn and reads its SSE frames,
 *   3. asserts the frame, its payload, and its ordering before message/done.
 *
 * The peer refuses to complete the turn unless the tool ack reports
 * delivered:true, so a frame that never reached the run's stream fails the turn
 * and this script. No real model is involved.
 *
 * Env: ARIA_E2E_BASE_URL (default http://127.0.0.1:8080),
 *      ARIA_OPERATOR_BEARER_TOKEN (optional; loopback is the operator),
 *      ARIA_AR_RENDER_E2E_TIMEOUT_MS (default 120000).
 */

import { randomUUID } from 'node:crypto';

const BASE = (process.env.ARIA_E2E_BASE_URL ?? 'http://127.0.0.1:8080').replace(/\/+$/, '');
const TOKEN = process.env.ARIA_OPERATOR_BEARER_TOKEN ?? '';
const TIMEOUT_MS = Number(process.env.ARIA_AR_RENDER_E2E_TIMEOUT_MS ?? 120_000);
const ARIA_AGENT_ID = '00000000-0000-0000-0000-000000000001';
const CONVERSATION_ID = randomUUID();
const EXPECTED = { primitive: 'track', trackId: 7, text: 'TV remote' };

function fail(message) {
  console.error(`AR_RENDER E2E FAILED: ${message}`);
  process.exit(1);
}

function authHeaders() {
  return TOKEN ? { authorization: `Bearer ${TOKEN}` } : {};
}

async function selectScenario() {
  const response = await fetch(`${BASE}/api/v1/maintenance/core-e2e/scenario`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ agentId: ARIA_AGENT_ID, scenario: 'ar-render' }),
  });
  if (!response.ok) {
    fail(`scenario selection answered ${response.status}: ${await response.text()}`);
  }
  const body = await response.json().catch(() => ({}));
  if (body.scenario !== 'ar-render') {
    fail(`scenario selection did not confirm ar-render: ${JSON.stringify(body)}`);
  }
}

function parseFrames(text) {
  const frames = [];
  for (const block of text.split('\n\n')) {
    if (!block.trim()) continue;
    let name = 'message';
    const data = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        name = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).trimStart());
      }
    }
    frames.push({ name, data: data.join('\n') });
  }
  return frames;
}

async function streamTurn() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(`${BASE}/api/v1/aria/chat/stream`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        accept: 'text/event-stream',
        ...authHeaders(),
      },
      body: JSON.stringify({
        conversationId: CONVERSATION_ID,
        message: 'Call ar_render for the tracked remote, then reply',
        history: [],
      }),
      signal: controller.signal,
    });
    if (response.status !== 200) {
      fail(`POST /chat/stream answered ${response.status}: ${await response.text()}`);
    }
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('text/event-stream')) {
      fail(`POST /chat/stream answered 200 with content-type '${contentType}'`);
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

async function main() {
  await selectScenario();
  const stream = await streamTurn();
  const frames = parseFrames(stream);

  const error = frames.find((frame) => frame.name === 'error');
  if (error) {
    fail(`the turn failed: ${error.data}`);
  }

  const renderIndex = frames.findIndex((frame) => frame.name === 'ar_render');
  const messageIndex = frames.findIndex((frame) => frame.name === 'message');
  const doneIndex = frames.findIndex((frame) => frame.name === 'done');
  if (renderIndex < 0) {
    fail(`no ar_render frame. Frames: ${frames.map((frame) => frame.name).join(', ')}`);
  }
  if (messageIndex < 0 || doneIndex < 0) {
    fail(`missing message/done. Frames: ${frames.map((frame) => frame.name).join(', ')}`);
  }
  if (!(renderIndex < messageIndex && messageIndex < doneIndex)) {
    fail(`ordering is ${renderIndex}/${messageIndex}/${doneIndex}; expected ar_render < message < done`);
  }

  let payload;
  try {
    payload = JSON.parse(frames[renderIndex].data);
  } catch (ignored) {
    fail(`the ar_render frame is not JSON: ${frames[renderIndex].data}`);
  }
  if (typeof payload.runId !== 'string' || !payload.runId) {
    fail(`the frame carries no runId: ${frames[renderIndex].data}`);
  }
  if (typeof payload.renderId !== 'string' || !payload.renderId) {
    fail(`the frame carries no renderId: ${frames[renderIndex].data}`);
  }
  const render = payload.render ?? {};
  if (render.primitive !== EXPECTED.primitive) {
    fail(`render.primitive is '${render.primitive}', expected '${EXPECTED.primitive}'`);
  }
  if (render.target?.trackId !== EXPECTED.trackId) {
    fail(`render.target.trackId is '${render.target?.trackId}', expected ${EXPECTED.trackId}`);
  }
  if (render.text !== EXPECTED.text) {
    fail(`render.text is '${render.text}', expected '${EXPECTED.text}'`);
  }

  console.log(
    'AR_RENDER E2E PASSED: one ar_render frame (track, trackId 7, "TV remote") ' +
      `before message and done; runId=${payload.runId}.`,
  );
}

main().catch((error) => fail(error?.stack ?? String(error)));
