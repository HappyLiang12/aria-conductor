/**
 * B3b vitest suite: the bridge HTTP/SSE control surface (frozen contract C0.2 of
 * docs/superpowers/plans/2026-09-17-qoder-cli-provider.md, plan Task B3b).
 *
 * Every request in this suite goes over a real listening socket (`port 0`, never a fixed
 * port) against the real HTTP server; most cases run the real `AcpClient` against the
 * committed fake CLI (`test/fixtures/fake-qodercli.mjs`), so the HTTP layer is exercised
 * over the real ACP client. Two cases deliberately inject `createAcpClient`: the REPLAY_GAP
 * / ring-buffer case needs more events than the fixture emits, and the secret-redaction
 * case needs CLI text that carries the token (the fixture never echoes it). The deadline
 * case stays on the real client and only injects the deadline length.
 *
 * All credentials here are synthetic (`test-bridge-token`, `test-worker-token`,
 * `test-token-1`), never a real PAT. The suite asserts that none ever appears in an HTTP
 * body, an SSE frame or a log line.
 *
 * C2 ruling R1 adds two suites: the bounded `permission_request.rawInput` evidence and
 * `POST /probe`. Both also inject a scripted client (no CLI spawns); the probe cases run
 * against a local `http` server on an ephemeral 127.0.0.1 port and never touch a real host.
 */
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EventEmitter } from 'node:events';
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { afterEach, describe, expect, it } from 'vitest';

import { AcpClient, type AcpClientOptions, type AcpEvent, type PermissionDecision } from '../src/acp-client.js';
import {
  MAX_BODY_BYTES,
  PINNED_CLI_VERSION,
  createBridgeServer,
  type BridgeAcpClient,
  type BridgeServer,
} from '../src/server.js';
import { EVENT_RING_CAPACITY, SessionEventStream } from '../src/sse.js';
import { DEFAULT_BRIDGE_PORT, readBridgeConfig } from '../src/main.js';

const FIXTURE = fileURLToPath(new URL('./fixtures/fake-qodercli.mjs', import.meta.url));
// Synthetic placeholders only: the bridge token and the PAT-shaped value a session's
// mcpServers headers would carry in production.
const BRIDGE_TOKEN = 'test-bridge-token';
const PAT = 'test-worker-token';
const AUTHORIZATION = `Bearer ${BRIDGE_TOKEN}`;
const MODEL = 'efficient';
const PROMPT = 'Reply with exactly: ok';
const OPTION_MENU = [
  { optionId: 'zz_always', kind: 'allow_always', name: 'Allow for this session' },
  { optionId: 'zz_once', kind: 'allow_once', name: 'Allow' },
  { optionId: 'zz_reject', kind: 'reject_once', name: 'Reject' },
];

const sleep = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms));

// ---------------------------------------------------------------------------------------
// SSE transport reader (data: framing, per C0.2)
// ---------------------------------------------------------------------------------------

class SseStream {
  readonly frames: Array<Record<string, unknown>> = [];
  raw = '';
  ended = false;

  private readonly waiters: Array<{
    match: (frame: Record<string, unknown>) => boolean;
    resolve: (frame: Record<string, unknown>) => void;
    timer: NodeJS.Timeout;
  }> = [];
  private readonly endWaiters: Array<{ resolve: () => void; timer: NodeJS.Timeout }> = [];
  private readonly reader: ReadableStreamDefaultReader<Uint8Array> | null;

  constructor(
    readonly status: number,
    readonly contentType: string | null,
    body: ReadableStream<Uint8Array> | null,
  ) {
    if (status === 200 && body !== null) {
      this.reader = body.getReader();
      void this.pump();
    } else {
      this.reader = null;
      this.ended = true;
    }
  }

  private async pump(): Promise<void> {
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      for (;;) {
        const { value, done } = await this.reader!.read();
        if (done) {
          break;
        }
        const text = decoder.decode(value, { stream: true });
        this.raw += text;
        buffer += text;
        let index: number;
        while ((index = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, index);
          buffer = buffer.slice(index + 2);
          if (!block.startsWith('data: ')) {
            continue;
          }
          const frame = JSON.parse(block.slice('data: '.length)) as Record<string, unknown>;
          this.frames.push(frame);
          for (const waiter of [...this.waiters]) {
            if (waiter.match(frame)) {
              this.waiters.splice(this.waiters.indexOf(waiter), 1);
              clearTimeout(waiter.timer);
              waiter.resolve(frame);
            }
          }
        }
      }
    } catch {
      /* the reader was cancelled at teardown */
    }
    this.ended = true;
    for (const waiter of this.endWaiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.resolve();
    }
  }

  private seen(): string {
    return this.frames.map(frame => String(frame.type)).join(', ') || '<none>';
  }

  /** First frame of `type` (also matches already-received frames), optionally filtered. */
  waitFor(
    type: string,
    extra?: (frame: Record<string, unknown>) => boolean,
    timeoutMs = 10_000,
  ): Promise<Record<string, unknown>> {
    const match = (frame: Record<string, unknown>): boolean =>
      frame.type === type && (extra === undefined || extra(frame));
    const existing = this.frames.find(match);
    if (existing !== undefined) {
      return Promise.resolve(existing);
    }
    if (this.ended) {
      return Promise.reject(new Error(`stream ended before '${type}' arrived (saw: ${this.seen()})`));
    }
    return new Promise<Record<string, unknown>>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.splice(
          this.waiters.findIndex(waiter => waiter.timer === timer),
          1,
        );
        reject(new Error(`timed out after ${timeoutMs} ms waiting for '${type}' (saw: ${this.seen()})`));
      }, timeoutMs);
      this.waiters.push({ match, resolve, timer });
    });
  }

  /** Frames of `type`, in arrival order. */
  of(type: string): Array<Record<string, unknown>> {
    return this.frames.filter(frame => frame.type === type);
  }

  waitForEnd(timeoutMs = 10_000): Promise<void> {
    if (this.ended) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`stream did not end within ${timeoutMs} ms (saw: ${this.seen()})`)),
        timeoutMs,
      );
      this.endWaiters.push({ resolve, timer });
    });
  }

  async close(): Promise<void> {
    try {
      await this.reader?.cancel();
    } catch {
      /* already closed */
    }
  }
}

// ---------------------------------------------------------------------------------------
// Scripted client (injected only where the committed fixture cannot produce the input)
// ---------------------------------------------------------------------------------------

type EventHandler = (event: AcpEvent) => void;

class ScriptedClient implements BridgeAcpClient {
  readonly calls: string[] = [];
  private handler: EventHandler | null = null;

  constructor(private readonly script: (client: ScriptedClient) => void = () => undefined) {}

  onEvent(handler: EventHandler): () => void {
    this.handler = handler;
    return () => {
      this.handler = null;
    };
  }

  async createSession(): Promise<{ sessionId: string }> {
    this.script(this);
    return { sessionId: 'acp-scripted' };
  }

  prompt(): void {
    this.calls.push('prompt');
  }

  decide(): PermissionDecision {
    this.calls.push('decide');
    return { outcome: 'selected', optionId: 'scripted' };
  }

  cancel(): void {
    this.calls.push('cancel');
  }

  terminate(): void {
    this.calls.push('terminate');
  }

  close(): void {
    this.calls.push('close');
  }

  emit(event: AcpEvent): void {
    this.handler?.(event);
  }
}

// ---------------------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------------------

const createdServers: BridgeServer[] = [];
const createdClients: AcpClient[] = [];
const createdStreams: SseStream[] = [];
const createdDirs: string[] = [];
const createdTargets: ProbeTarget[] = [];
const logLines: string[] = [];

function fixtureClient(scenario: string, overrides: Partial<AcpClientOptions> = {}): AcpClient {
  const dir = mkdtempSync(join(tmpdir(), 'b3b-'));
  createdDirs.push(dir);
  const client = new AcpClient({
    command: process.execPath,
    args: [FIXTURE, scenario],
    cwd: dir,
    env: {
      PATH: process.env.PATH,
      HOME: process.env.HOME ?? 'C:/b3b-home',
      TERM: 'xterm',
      QODER_PERSONAL_ACCESS_TOKEN: PAT,
    },
    killGraceMs: 500,
    ...overrides,
  });
  createdClients.push(client);
  return client;
}

async function startBridge(
  scenario: string,
  overrides: { permissionDeadlineMs?: number } = {},
): Promise<BridgeServer> {
  const bridge = createBridgeServer({
    token: BRIDGE_TOKEN,
    log: line => logLines.push(line),
    createAcpClient: () => fixtureClient(scenario),
    ...overrides,
  });
  createdServers.push(bridge);
  await bridge.listen(0, '127.0.0.1');
  return bridge;
}

async function startScriptedBridge(create: () => BridgeAcpClient): Promise<BridgeServer> {
  const bridge = createBridgeServer({ token: BRIDGE_TOKEN, log: line => logLines.push(line), createAcpClient: create });
  createdServers.push(bridge);
  await bridge.listen(0, '127.0.0.1');
  return bridge;
}

afterEach(async () => {
  for (const target of createdTargets.splice(0)) {
    await target.close();
  }
  for (const stream of createdStreams.splice(0)) {
    await stream.close();
  }
  for (const bridge of createdServers.splice(0)) {
    try {
      await bridge.close();
    } catch {
      /* already closed */
    }
  }
  for (const client of createdClients.splice(0)) {
    try {
      client.close();
    } catch {
      /* already dead */
    }
  }
  await sleep(100);
  for (const dir of createdDirs.splice(0)) {
    try {
      rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
    } catch {
      /* a just-killed child may still hold its cwd on Windows */
    }
  }
});

function base(bridge: BridgeServer): string {
  return `http://127.0.0.1:${bridge.port}`;
}

interface CreateSessionOverrides {
  cwd?: string;
  model?: string;
  deadlineSeconds?: number;
  mcpServers?: unknown;
}

function createSessionBody(overrides: CreateSessionOverrides = {}): Record<string, unknown> {
  return {
    runId: 'run-1',
    agentId: 'agent-1',
    cwd: '/workspace',
    model: MODEL,
    deadlineSeconds: 600,
    mcpServers: [
      {
        name: 'aria-stub',
        url: 'http://127.0.0.1:9/mcp',
        headers: [{ name: 'Authorization', value: `Bearer ${PAT}` }],
      },
    ],
    ...overrides,
  };
}

async function createSession(
  bridge: BridgeServer,
  overrides: CreateSessionOverrides = {},
): Promise<{ status: number; body: Record<string, unknown> | null }> {
  const response = await post(bridge, '/sessions', createSessionBody(overrides));
  return { status: response.status, body: (await response.json().catch(() => null)) as Record<string, unknown> | null };
}

async function createSessionId(bridge: BridgeServer, overrides: CreateSessionOverrides = {}): Promise<string> {
  const created = await createSession(bridge, overrides);
  expect(created.status).toBe(201);
  return String(created.body?.bridgeSessionId);
}

async function post(bridge: BridgeServer, path: string, body: unknown, authorization = AUTHORIZATION): Promise<Response> {
  return fetch(`${base(bridge)}${path}`, {
    method: 'POST',
    headers: { authorization, 'content-type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

function openEvents(bridge: BridgeServer, sessionId: string, after?: string | number, authorization = AUTHORIZATION): Promise<SseStream> {
  const query = after === undefined ? '' : `?after=${encodeURIComponent(String(after))}`;
  return openEventsUrl(`${base(bridge)}/sessions/${encodeURIComponent(sessionId)}/events${query}`, authorization);
}

async function openEventsUrl(url: string, authorization = AUTHORIZATION): Promise<SseStream> {
  const response = await fetch(url, { headers: { authorization } });
  const stream = new SseStream(response.status, response.headers.get('content-type'), response.body);
  createdStreams.push(stream);
  return stream;
}

// ---------------------------------------------------------------------------------------
// Local probe target (POST /probe tests stay hermetic: never a real host)
// ---------------------------------------------------------------------------------------

interface ProbeTarget {
  readonly port: number;
  close(): Promise<void>;
}

async function startTarget(handler: (req: IncomingMessage, res: ServerResponse) => void): Promise<ProbeTarget> {
  const server: Server = createHttpServer(handler);
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve());
  });
  const address = server.address();
  if (address === null || typeof address !== 'object') {
    throw new Error('probe target did not bind a port');
  }
  let closed = false;
  const target: ProbeTarget = {
    port: address.port,
    async close(): Promise<void> {
      if (closed) {
        return;
      }
      closed = true;
      // A never-answering handler leaves a socket attached; close() alone would wait for it.
      server.closeAllConnections();
      await new Promise<void>(resolve => server.close(() => resolve()));
    },
  };
  createdTargets.push(target);
  return target;
}

// ---------------------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------------------

describe('health and authorization (C0.2 header row 1)', () => {
  it('GET /health answers without a token, never spawns a CLI and stays exact', async () => {
    let spawns = 0;
    const bridge = await startScriptedBridge(() => {
      spawns += 1;
      return new ScriptedClient();
    });

    const response = await fetch(`${base(bridge)}/health`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 'ok', cliVersion: PINNED_CLI_VERSION });
    expect(PINNED_CLI_VERSION).toBe('1.1.41');
    expect(spawns).toBe(0);
  });

  it('requires the bearer on every other route', async () => {
    const bridge = await startBridge('happy');

    const anonymous = await fetch(`${base(bridge)}/sessions/unknown/events`);
    expect(anonymous.status).toBe(401);
    expect(await anonymous.json()).toEqual({ error: 'UNAUTHORIZED' });

    const wrong = await post(bridge, '/sessions', createSessionBody(), 'Bearer test-bridge-token-wrong');
    expect(wrong.status).toBe(401);
    expect(await wrong.json()).toEqual({ error: 'UNAUTHORIZED' });

    // A body without the `Bearer ` scheme is not accepted either.
    const scheme = await post(bridge, '/sessions', createSessionBody(), BRIDGE_TOKEN);
    expect(scheme.status).toBe(401);
  });
});

describe('session lifecycle over the C0.2 contract', () => {
  it('creates a session, streams the run and completes a prompt (real ACP client)', async () => {
    const bridge = await startBridge('happy');

    const created = await createSession(bridge);
    expect(created.status).toBe(201);
    const sessionId = String(created.body?.bridgeSessionId);
    expect(Object.keys(created.body ?? {})).toEqual(['bridgeSessionId']);
    expect(sessionId.length).toBeGreaterThan(10);

    const stream = await openEvents(bridge, sessionId);
    expect(stream.status).toBe(200);
    expect(stream.contentType).toContain('text/event-stream');

    const started = await stream.waitFor('session_started');
    expect(started).toEqual({ sequence: 1, type: 'session_started', model: MODEL });
    // C0.2 frames `data: {"sequence":N,"type":...}`: sequence first, then type, then payload.
    expect(Object.keys(started)).toEqual(['sequence', 'type', 'model']);

    const prompted = await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    expect(prompted.status).toBe(202);
    expect(await prompted.json()).toEqual({ accepted: true });

    const permission = await stream.waitFor('permission_request');
    // The fixture's permission carries a fixed rawInput, so the R1 evidence is asserted
    // verbatim: serialized as a string, not truncated, and the legacy preview/digest still
    // describe the whole input (the amendment is additive).
    const fixtureRawInput = JSON.stringify({ file_path: '/workspace/hello.txt', content: 'hi' });
    expect(permission).toEqual({
      sequence: 3,
      type: 'permission_request',
      requestId: '4',
      toolCallId: 'call_1',
      toolName: 'Write',
      title: null,
      redactedPreview: fixtureRawInput,
      inputDigest: createHash('sha256').update(fixtureRawInput).digest('hex'),
      rawInput: fixtureRawInput,
      rawInputTruncated: false,
      options: OPTION_MENU,
      expiresAt: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/),
    });
    expect(Date.parse(String(permission.expiresAt))).toBeGreaterThan(Date.now());

    const decided = await post(bridge, `/sessions/${sessionId}/permissions/${String(permission.requestId)}`, {
      approved: true,
      reason: 'operator approved',
    });
    expect(decided.status).toBe(200);
    expect(await decided.json()).toEqual({ outcome: 'delivered' });

    await stream.waitFor('completed');

    expect(stream.frames.map(frame => frame.type)).toEqual([
      'session_started',
      'tool_call',
      'permission_request',
      'tool_call_update',
      'agent_message',
      'usage',
      'completed',
    ]);
    expect(stream.frames.map(frame => frame.sequence)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(stream.frames[1]).toEqual({
      sequence: 2,
      type: 'tool_call',
      toolCallId: 'call_1',
      toolName: 'Write',
      kind: 'edit',
      status: 'pending',
    });
    expect(stream.frames[3]).toEqual({ sequence: 4, type: 'tool_call_update', toolCallId: 'call_1', status: 'completed' });
    expect(stream.frames[4]).toEqual({ sequence: 5, type: 'agent_message', text: 'hi' });
    // A5: the CLI reports zero counters (unavailable accounting, recorded as reported) and
    // never a credit field over ACP, so `credits` stays null rather than a fabricated 0.
    expect(stream.frames[5]).toEqual({ sequence: 6, type: 'usage', credits: null, inputTokens: 0, outputTokens: 0 });
    expect(stream.frames[6]).toEqual({ sequence: 7, type: 'completed', stopReason: 'end_turn' });
  });

  it('caps the permission deadline at the session run deadline', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge, { deadlineSeconds: 1 });
    const stream = await openEvents(bridge, sessionId);
    await stream.waitFor('session_started');
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await stream.waitFor('permission_request');
    // deadlineSeconds=1 wins over the 15 min default: a permission may not outlive its run.
    expect(Date.parse(String(permission.expiresAt)) - Date.now()).toBeLessThanOrEqual(1_500);
  });

  it('maps a CLI handshake failure to a 502 without registering a session', async () => {
    const bridge = await startBridge('handshake-error');
    const created = await createSession(bridge);
    expect(created.status).toBe(502);
    expect(created.body?.error).toBe('SESSION_CREATE_FAILED');
    expect(String(created.body?.reason)).toContain('Authentication required');
    expect(created.body).not.toHaveProperty('bridgeSessionId');

    const missing = await fetch(`${base(bridge)}/sessions/does-not-exist/events`, {
      headers: { authorization: AUTHORIZATION },
    });
    expect(missing.status).toBe(404);
    expect(await missing.json()).toEqual({ error: 'NOT_FOUND' });
  });

  it('maps a create-time governance stop to a 502 GOVERNANCE_STOP without registering a session', async () => {
    const bridge = await startBridge('session-new-escalation');
    // The fixture reports a non-governed mode from `session/new` (A2 config-isolation
    // drift, F2), so the client stops the run before `session/set_model` and the bridge
    // must surface it as the explicit governance code, not a generic create failure.
    const created = await createSession(bridge);
    expect(created.status).toBe(502);
    expect(created.body?.error).toBe('GOVERNANCE_STOP');
    expect(String(created.body?.reason)).toContain('mode escalation observed');
    expect(created.body).not.toHaveProperty('bridgeSessionId');
  });

  it('reports a prompt-time governance stop exactly once (no doubled failed frame)', async () => {
    const bridge = await startBridge('mode-escalation');
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await stream.waitFor('session_started');

    const prompted = await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    expect(prompted.status).toBe(202);
    await stream.waitForEnd(10_000);

    // The client first emits `governance_error` (one `failed` frame) and then rejects the
    // pending turn with the same decision as `prompt_error`/GOVERNANCE_STOP, which the
    // bridge suppresses (src/server.ts:437-441). The governance `failed` ends the session,
    // so the live reader stops at that frame; the retained tail is replayed to prove the
    // echo was never appended as a second failure.
    const replay = await openEvents(bridge, sessionId, 0);
    expect(replay.status).toBe(200);
    await replay.waitForEnd(10_000);
    expect(replay.frames.map(frame => frame.type)).toEqual(['session_started', 'mode_changed', 'failed']);
    expect(replay.frames[2]).toEqual({
      sequence: 3,
      type: 'failed',
      reason: expect.stringContaining('mode escalation observed'),
      code: 'MODE_ESCALATION',
    });
  });

  it('rejects an unknown model with an explicit provider error (no silent fallback)', async () => {
    const bridge = await startBridge('unknown-model');
    const created = await createSession(bridge, { model: 'efficient' });
    // The fixture's menu omits `efficient` in this scenario, so the client refuses before
    // any session/set_model; the bridge surfaces the provider error, never a fallback.
    expect(created.status).toBe(400);
    expect(created.body).toEqual({ error: 'UNKNOWN_MODEL' });
  });

  it('rejects a malformed create request and an empty prompt', async () => {
    const bridge = await startBridge('happy');
    let spawns = 0;
    const scripted = await startScriptedBridge(() => {
      spawns += 1;
      return new ScriptedClient();
    });

    const noModel = await post(scripted, '/sessions', { runId: 'r', agentId: 'a', cwd: '/workspace' });
    expect(noModel.status).toBe(400);
    const badServers = await post(scripted, '/sessions', createSessionBody({ mcpServers: [{ name: 'x' }] }));
    expect(badServers.status).toBe(400);
    expect(spawns).toBe(0);

    const sessionId = await createSessionId(bridge);
    const empty = await post(bridge, `/sessions/${sessionId}/prompt`, { text: '' });
    expect(empty.status).toBe(400);
    expect(await empty.json()).toEqual({ error: 'INVALID_REQUEST' });
  });
});

describe('SSE replay (C0.2 events row)', () => {
  it('replays from `after` and then continues live without duplicates', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge);

    const first = await openEvents(bridge, sessionId, 0);
    await first.waitFor('session_started');
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await first.waitFor('permission_request');
    await post(bridge, `/sessions/${sessionId}/permissions/${String(permission.requestId)}`, { approved: true });
    const completed = await first.waitFor('completed');
    const resumeFrom = Number(completed.sequence);
    expect(first.of('completed')).toHaveLength(1);
    await first.close();

    // A reconnect with the last seen sequence replays nothing and then delivers the next
    // turn live (the second prompt runs on the same still-alive CLI session).
    const resumed = await openEvents(bridge, sessionId, resumeFrom);
    await sleep(150);
    expect(resumed.frames).toEqual([]);

    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const nextToolCall = await resumed.waitFor('tool_call');
    expect(nextToolCall.sequence).toBe(resumeFrom + 1);
    const nextPermission = await resumed.waitFor('permission_request');
    await post(bridge, `/sessions/${sessionId}/permissions/${String(nextPermission.requestId)}`, { approved: false });
    await resumed.waitFor('completed');
    expect(resumed.frames.every(frame => Number(frame.sequence) > resumeFrom)).toBe(true);
    expect(resumed.of('session_started')).toHaveLength(0);
  });

  it('returns REPLAY_GAP when `after` is older than the retained window', async () => {
    const bridge = await startScriptedBridge(() => {
      return new ScriptedClient(client => {
        for (let i = 1; i <= EVENT_RING_CAPACITY + 100; i++) {
          client.emit({
            type: 'session_update',
            update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: `msg-${i}` } },
          });
        }
      });
    });
    const sessionId = await createSessionId(bridge);
    const last = EVENT_RING_CAPACITY + 100;
    const floor = last - EVENT_RING_CAPACITY + 1;

    // Boundary: `after == floor - 1` replays the whole retained window.
    const boundary = await openEvents(bridge, sessionId, floor - 1);
    expect(boundary.status).toBe(200);
    for (let turn = 0; turn < 200 && boundary.frames.length < EVENT_RING_CAPACITY; turn++) {
      await new Promise(resolve => setImmediate(resolve));
    }
    expect(boundary.frames).toHaveLength(EVENT_RING_CAPACITY);
    expect(boundary.frames[0]?.sequence).toBe(floor);
    expect(boundary.frames[EVENT_RING_CAPACITY - 1]?.sequence).toBe(last);
    await boundary.close();

    const gap = await openEventsUrl(`${base(bridge)}/sessions/${sessionId}/events?after=${floor - 2}`);
    expect(gap.status).toBe(409);
    expect(gap.contentType).toContain('application/json');
  });

  it('replays the whole retained buffer for a missing or blank `after`', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge);

    const missing = await openEventsUrl(`${base(bridge)}/sessions/${sessionId}/events`);
    expect(missing.status).toBe(200);
    const blank = await openEventsUrl(`${base(bridge)}/sessions/${sessionId}/events?after=`);
    expect(blank.status).toBe(200);
    // Creation already retained one event, so both resume forms must replay it from
    // sequence 1 instead of starting at the tail (P1: the earlier scripted client emitted
    // nothing, so this case only proved the framing, never the replay).
    await missing.waitFor('session_started');
    await blank.waitFor('session_started');
    const retained = [{ sequence: 1, type: 'session_started', model: MODEL }];
    expect(missing.frames).toEqual(retained);
    expect(blank.frames).toEqual(retained);
    // The replay does not detach the reader: the session is alive, so the stream stays open.
    expect(missing.ended).toBe(false);

    const invalid = await openEventsUrl(`${base(bridge)}/sessions/${sessionId}/events?after=abc`);
    expect(invalid.status).toBe(400);
  });
});

describe('permission decisions (C0.2 permissions row)', () => {
  it('delivers a decision once, reports the same repeat as already_resolved and a conflict as 409', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await stream.waitFor('session_started');
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await stream.waitFor('permission_request');
    const path = `/sessions/${sessionId}/permissions/${String(permission.requestId)}`;

    const delivered = await post(bridge, path, { approved: true });
    expect(delivered.status).toBe(200);
    expect(await delivered.json()).toEqual({ outcome: 'delivered' });

    const repeat = await post(bridge, path, { approved: true });
    expect(repeat.status).toBe(200);
    expect(await repeat.json()).toEqual({ outcome: 'already_resolved' });

    const conflict = await post(bridge, path, { approved: false });
    expect(conflict.status).toBe(409);
    expect(await conflict.json()).toEqual({ error: 'ALREADY_RESOLVED' });
    await stream.waitFor('completed');
  });

  it('reports an unknown requestId as unknown and an unknown session as 404', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge);

    const unknown = await post(bridge, `/sessions/${sessionId}/permissions/never-seen`, { approved: true });
    expect(unknown.status).toBe(200);
    expect(await unknown.json()).toEqual({ outcome: 'unknown' });

    for (const [path, body] of [
      ['/sessions/00000000-0000-4000-8000-000000000000/prompt', { text: PROMPT }],
      ['/sessions/00000000-0000-4000-8000-000000000000/permissions/x', { approved: true }],
      ['/sessions/00000000-0000-4000-8000-000000000000/cancel', {}],
    ] as Array<[string, unknown]>) {
      const response = await post(bridge, path, body);
      expect(response.status).toBe(404);
      expect(await response.json()).toEqual({ error: 'NOT_FOUND' });
    }
  });

  it('fails closed with UNSUPPORTED_OPTIONS and keeps the request pending for a later denial', async () => {
    const bridge = await startBridge('allow-always-only');
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await stream.waitFor('permission_request');
    expect(permission.options).toEqual([{ optionId: 'zz_always', kind: 'allow_always', name: 'Allow for this session' }]);
    const path = `/sessions/${sessionId}/permissions/${String(permission.requestId)}`;

    const approved = await post(bridge, path, { approved: true });
    expect(approved.status).toBe(422);
    expect(await approved.json()).toEqual({ error: 'UNSUPPORTED_OPTIONS' });

    // Nothing was delivered; the request is still pending, so a denial must still resolve it.
    const denied = await post(bridge, path, { approved: false });
    expect(denied.status).toBe(200);
    expect(await denied.json()).toEqual({ outcome: 'delivered' });
  });

  it('expires a decision after the pending deadline and never delivers it', async () => {
    const bridge = await startBridge('happy', { permissionDeadlineMs: 150 });
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await stream.waitFor('permission_request');
    const expiresAt = Date.parse(String(permission.expiresAt));
    expect(expiresAt).toBeLessThanOrEqual(Date.now() + 150);
    await sleep(Math.max(0, expiresAt - Date.now()) + 60);

    const path = `/sessions/${sessionId}/permissions/${String(permission.requestId)}`;
    const expired = await post(bridge, path, { approved: true });
    expect(expired.status).toBe(200);
    expect(await expired.json()).toEqual({ outcome: 'expired' });

    // Fail closed: the decision never reached the CLI, so the turn cannot complete; and the
    // request stays expired for every later decision (a denial cannot rescue it either).
    await sleep(400);
    expect(stream.of('completed')).toHaveLength(0);
    const late = await post(bridge, path, { approved: false });
    expect(await late.json()).toEqual({ outcome: 'expired' });
  });

  it('rejects a malformed decision body', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge);
    const response = await post(bridge, `/sessions/${sessionId}/permissions/whatever`, { approved: 'yes' });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: 'INVALID_REQUEST' });
  });
});

describe('permission rawInput evidence (C2 ruling R1)', () => {
  /** A scripted ask with a known rawInput: no CLI spawn, no fixture drift. */
  async function scriptedPermission(rawInput: unknown): Promise<Record<string, unknown>> {
    const bridge = await startScriptedBridge(
      () =>
        new ScriptedClient(client => {
          client.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
          client.emit({
            type: 'permission_request',
            requestId: 'r1',
            toolCallId: 'call_1',
            toolName: 'Write',
            title: null,
            options: [{ optionId: 'o1', kind: 'allow_once', name: 'Allow' }],
            params: { toolCall: { rawInput } },
          });
        }),
    );
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId, 0);
    return stream.waitFor('permission_request');
  }

  it('carries the serialized rawInput and leaves the legacy preview/digest untouched', async () => {
    const rawInput = { file_path: '/workspace/probe.txt', content: 'hello' };
    const serialized = JSON.stringify(rawInput);

    const permission = await scriptedPermission(rawInput);

    expect(permission.rawInput).toBe(serialized);
    expect(permission.rawInputTruncated).toBe(false);
    // Additive amendment: the legacy fields still describe the whole input.
    expect(permission.redactedPreview).toBe(serialized);
    expect(permission.inputDigest).toBe(createHash('sha256').update(serialized).digest('hex'));
  });

  it('emits rawInput:null and rawInputTruncated:false when the tool call carries no input', async () => {
    const permission = await scriptedPermission(undefined);

    expect(permission.rawInput).toBeNull();
    expect(permission.rawInputTruncated).toBe(false);
    expect(permission.redactedPreview).toBeNull();
  });

  it('truncates an oversize rawInput to exactly 65536 characters and flags the truncation', async () => {
    const rawInput = { blob: 'x'.repeat(70_000) };
    const serialized = JSON.stringify(rawInput);
    expect(serialized.length).toBeGreaterThan(65_536);

    const permission = await scriptedPermission(rawInput);

    expect(typeof permission.rawInput).toBe('string');
    expect((permission.rawInput as string).length).toBe(65_536);
    expect(permission.rawInputTruncated).toBe(true);
    // Unaffected: the legacy fields are still derived from the whole input.
    expect(permission.redactedPreview).toBe(`${serialized.slice(0, 256)}...`);
    expect(permission.inputDigest).toBe(createHash('sha256').update(serialized).digest('hex'));
  });
});

describe('cancel (C0.2 cancel row)', () => {
  it('terminates the CLI, rejects pending requests, ends the stream and refuses later prompts', async () => {
    const bridge = await startBridge('cancel');
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId, 0);
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await stream.waitFor('permission_request');

    const cancelled = await post(bridge, `/sessions/${sessionId}/cancel`, {});
    expect(cancelled.status).toBe(202);
    expect(await cancelled.json()).toEqual({ terminated: true });

    // A pending permission is rejected, not delivered: no approval can be granted after cancel.
    const decision = await post(bridge, `/sessions/${sessionId}/permissions/${String(permission.requestId)}`, {
      approved: true,
    });
    expect(decision.status).toBe(200);
    expect(await decision.json()).toEqual({ outcome: 'already_resolved' });

    await stream.waitForEnd(10_000);
    // The turn either aborted first (completed/cancelled) or the process death won the race
    // (failed/PROCESS_EXITED); both are fail-closed outcomes and nothing else may be present.
    const terminal = stream.frames.filter(frame => frame.type === 'completed' || frame.type === 'failed');
    expect(terminal).toHaveLength(1);
    if (terminal[0]?.type === 'completed') {
      expect(terminal[0].stopReason).toBe('cancelled');
    } else {
      expect(terminal[0]?.code).toBe('PROCESS_EXITED');
    }
    expect(stream.of('permission_reply')).toHaveLength(0);

    const again = await post(bridge, `/sessions/${sessionId}/cancel`, {});
    expect(again.status).toBe(202);
    expect(await again.json()).toEqual({ terminated: false });

    const laterPrompt = await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    expect(laterPrompt.status).toBe(409);
    expect(await laterPrompt.json()).toEqual({ error: 'SESSION_ENDED' });

    // The ended session is retained: the tail can still be replayed for a late reader.
    const replay = await openEvents(bridge, sessionId, 0);
    expect(replay.status).toBe(200);
    await replay.waitForEnd(10_000);
    expect(replay.frames.length).toBeGreaterThanOrEqual(2);
    expect(replay.frames[0]?.type).toBe('session_started');
  });
});

describe('request bounds', () => {
  it('rejects a body larger than 1 MiB with 413 without spawning the CLI', async () => {
    let spawns = 0;
    const bridge = await startScriptedBridge(() => {
      spawns += 1;
      return new ScriptedClient();
    });
    const head = '{"runId":"r1","agentId":"a1","cwd":"/workspace","model":"efficient","padding":"';
    const oversized = `${head}${'x'.repeat(MAX_BODY_BYTES + 1_024 - head.length - 2)}"}`;
    expect(Buffer.byteLength(oversized)).toBe(MAX_BODY_BYTES + 1_024);

    const response = await post(bridge, '/sessions', oversized);
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: 'PAYLOAD_TOO_LARGE' });
    expect(spawns).toBe(0);
  });

  it('accepts a body of exactly 1 MiB (the cap is a limit, not an off-by-one)', async () => {
    const bridge = await startBridge('happy');
    const head = '{"runId":"r1","agentId":"a1","cwd":"/workspace","model":"efficient","padding":"';
    const atCap = `${head}${'x'.repeat(MAX_BODY_BYTES - head.length - 2)}"}`;
    expect(Buffer.byteLength(atCap)).toBe(MAX_BODY_BYTES);

    const response = await post(bridge, '/sessions', atCap);
    expect(response.status).toBe(201);
  });
});

describe('POST /probe (C2 ruling R1)', () => {
  it('performs one MCP initialize POST and answers reachable for a 2xx JSON-RPC result', async () => {
    const seen: Array<{
      authorization: string | undefined;
      accept: string | undefined;
      contentType: string | undefined;
      body: string;
    }> = [];
    const target = await startTarget((req, res) => {
      let body = '';
      req.setEncoding('utf8');
      req.on('data', (chunk: string) => {
        body += chunk;
      });
      req.on('end', () => {
        seen.push({
          authorization: req.headers.authorization,
          accept: req.headers.accept,
          contentType: req.headers['content-type'],
          body,
        });
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { protocolVersion: '2024-11-05' } }));
      });
    });
    const bridge = await startScriptedBridge(() => new ScriptedClient());

    const response = await post(bridge, '/probe', {
      url: `http://127.0.0.1:${target.port}/mcp`,
      headers: [{ name: 'Authorization', value: 'Bearer test-token-1' }],
    });

    expect(response.status).toBe(200);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body).toEqual({ reachable: true, status: 200, detail: 'HTTP 200 (JSON-RPC result)' });
    // Exactly one outbound POST, carrying exactly the caller's credential and an MCP initialize.
    expect(seen).toHaveLength(1);
    expect(seen[0]?.authorization).toBe('Bearer test-token-1');
    // Streamable HTTP MCP rejects a request whose Accept excludes text/event-stream (2025-03-26).
    expect(seen[0]?.accept).toBe('application/json, text/event-stream');
    expect(seen[0]?.contentType).toBe('application/json');
    expect(JSON.parse(String(seen[0]?.body))).toMatchObject({
      method: 'initialize',
      params: { protocolVersion: expect.any(String) },
    });
    // The bridge answer never echoes the credential the probe carried.
    expect(JSON.stringify(body)).not.toContain('test-token-1');
  });

  it('treats any HTTP answer as liveness, 401 included', async () => {
    const target = await startTarget((_req, res) => {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end('{"error":"credential required"}');
    });
    const bridge = await startScriptedBridge(() => new ScriptedClient());

    const response = await post(bridge, '/probe', {
      url: `http://127.0.0.1:${target.port}/mcp`,
      headers: [{ name: 'Authorization', value: 'Bearer test-token-1' }],
    });

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ reachable: true, status: 401, detail: 'HTTP 401' });
  });

  it('reports a refused connection as unreachable with a null status', async () => {
    const target = await startTarget((_req, res) => res.end('{}'));
    const deadPort = target.port;
    await target.close();
    const bridge = await startScriptedBridge(() => new ScriptedClient());

    const response = await post(bridge, '/probe', {
      url: `http://127.0.0.1:${deadPort}/mcp`,
      headers: [{ name: 'Authorization', value: 'Bearer test-token-1' }],
      timeoutMs: 2_000,
    });

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ reachable: false, status: null, detail: 'connect ECONNREFUSED' });
  });

  it('gives up at timeoutMs, reports a null status and names the timeout', async () => {
    // Accepts the connection and never writes a byte back.
    const target = await startTarget(() => undefined);
    const bridge = await startScriptedBridge(() => new ScriptedClient());

    const response = await post(bridge, '/probe', {
      url: `http://127.0.0.1:${target.port}/mcp`,
      headers: [{ name: 'Authorization', value: 'Bearer test-token-1' }],
      timeoutMs: 250,
    });

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ reachable: false, status: null, detail: 'timeout after 250ms' });
    // R1 fixes the caller-less default at 3000 ms; asserted on the exported constant because
    // a real 3 s wait would only slow the suite down.
    const { DEFAULT_PROBE_TIMEOUT_MS } = await import('../src/server.js');
    expect(DEFAULT_PROBE_TIMEOUT_MS).toBe(3_000);
  });

  it('rejects a probe without the bearer before any outbound request', async () => {
    let outbound = 0;
    const target = await startTarget((_req, res) => {
      outbound += 1;
      res.end('{}');
    });
    const bridge = await startScriptedBridge(() => new ScriptedClient());
    const probeBody = { url: `http://127.0.0.1:${target.port}/mcp`, headers: [] };

    const anonymous = await fetch(`${base(bridge)}/probe`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(probeBody),
    });
    expect(anonymous.status).toBe(401);
    expect(await anonymous.json()).toEqual({ error: 'UNAUTHORIZED' });

    const wrong = await post(bridge, '/probe', probeBody, 'Bearer test-bridge-token-wrong');
    expect(wrong.status).toBe(401);
    expect(await wrong.json()).toEqual({ error: 'UNAUTHORIZED' });
    expect(outbound).toBe(0);
  });

  it('rejects a malformed probe body with the shared error style', async () => {
    const bridge = await startScriptedBridge(() => new ScriptedClient());
    const url = 'http://127.0.0.1:1/mcp';
    const malformed: unknown[] = [
      {},
      { url: '', headers: [] },
      { url: 'not-a-url', headers: [] },
      { url: 'ftp://127.0.0.1/mcp', headers: [] },
      { url, headers: 'Authorization: Bearer test-token-1' },
      { url, headers: [{ name: 'Authorization' }] },
      { url, headers: [{ name: 'Authorization', value: '' }] },
      { url, headers: [{ name: 'Bad Name', value: 'x' }] },
      { url, headers: [{ name: 'Authorization', value: 'x\r\ny' }] },
      { url, headers: [], timeoutMs: 0 },
      { url, headers: [], timeoutMs: -5 },
      { url, headers: [], timeoutMs: 'soon' },
    ];
    for (const body of malformed) {
      const response = await post(bridge, '/probe', body);
      expect(response.status, JSON.stringify(body)).toBe(400);
      expect(await response.json(), JSON.stringify(body)).toEqual({ error: 'INVALID_REQUEST' });
    }

    // The 1 MiB cap applies to /probe like to the other body-carrying routes.
    const oversize = await post(
      bridge,
      '/probe',
      `{"url":"${url}","headers":[],"padding":"${'x'.repeat(MAX_BODY_BYTES + 1_024)}"}`,
    );
    expect(oversize.status).toBe(413);
    expect(await oversize.json()).toEqual({ error: 'PAYLOAD_TOO_LARGE' });
  });
});

describe('secret handling (B3a re-review observation)', () => {
  it('never republishes the bridge token or MCP header values, and logs no payloads', async () => {
    const bridge = await startScriptedBridge(
      () =>
        new ScriptedClient(client => {
          client.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
          client.emit({
            type: 'permission_request',
            requestId: 'p1',
            toolCallId: 'call_9',
            toolName: 'Write',
            title: `Write guarded by Authorization: Bearer ${PAT}`,
            options: [{ optionId: 'o1', kind: 'allow_once', name: 'Allow' }],
            params: { toolCall: { rawInput: { path: '/workspace/x', authorization: `Bearer ${PAT}`, bridge: BRIDGE_TOKEN } } },
          });
          client.emit({
            type: 'prompt_error',
            requestId: '1',
            code: 'RPC_ERROR',
            message: `the CLI rejected Authorization: Bearer ${PAT}`,
          });
        }),
    );
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    const permission = await stream.waitFor('permission_request');
    const failed = await stream.waitFor('failed');

    expect(permission.title).toBe('Write guarded by Authorization: Bearer [redacted]');
    expect(permission.redactedPreview).toContain('[redacted]');
    expect(failed).toEqual({
      sequence: expect.any(Number),
      type: 'failed',
      reason: 'the CLI rejected Authorization: Bearer [redacted]',
      code: 'RPC_ERROR',
    });
    expect(stream.raw).not.toContain(PAT);
    expect(stream.raw).not.toContain(BRIDGE_TOKEN);
    expect(logLines.join('\n')).not.toContain(PAT);
    expect(logLines.join('\n')).not.toContain(BRIDGE_TOKEN);
    for (const line of logLines) {
      expect(line).toMatch(/^(GET|POST) \S+ -> \d{3}( session=\S+)?$/);
    }
  });
});

describe('bounded state', () => {
  it('retains at most 32 ended sessions and drops the oldest first', async () => {
    const bridge = await startScriptedBridge(
      () =>
        new ScriptedClient(client => {
          client.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
          // A CLI that dies right after creation: every session is ended and retained only
          // for late replay, which is exactly the state the cap bounds.
          client.emit({ type: 'child_exit', code: 0, signal: null });
        }),
    );
    const ids: string[] = [];
    for (let i = 0; i < 33; i++) {
      ids.push(await createSessionId(bridge));
    }

    const evicted = await fetch(`${base(bridge)}/sessions/${ids[0]}/events`, { headers: { authorization: AUTHORIZATION } });
    expect(evicted.status).toBe(404);
    const kept = await openEvents(bridge, ids[1] as string, 0);
    expect(kept.status).toBe(200);
    await kept.waitForEnd(2_000);
    expect(kept.frames.map(frame => frame.type)).toEqual(['session_started']);
  });
});

describe('SSE transport (unit)', () => {
  class FakeResponse extends EventEmitter {
    readonly chunks: string[] = [];
    ended = false;
    destroyed = false;
    writableEnded = false;
    writableLength = 0;

    write(chunk: string): boolean {
      this.chunks.push(chunk);
      return true;
    }

    end(): void {
      this.ended = true;
      this.writableEnded = true;
    }
  }

  it('assigns monotonic sequences, evicts beyond the ring capacity and reports the gap window', () => {
    const stream = new SessionEventStream();
    expect(stream.isReplayGap(0)).toBe(false);
    for (let i = 1; i <= EVENT_RING_CAPACITY + 5; i++) {
      stream.append('agent_message', { text: `msg-${i}` });
    }
    expect(stream.lastSequence).toBe(EVENT_RING_CAPACITY + 5);
    expect(stream.floorSequence).toBe(6);
    expect(stream.isReplayGap(4)).toBe(true);
    expect(stream.isReplayGap(5)).toBe(false);
    expect(stream.replayFromMissingAfter()).toBe(5);
  });

  it('replays from `after`, detaches on response close and ends every stream', () => {
    const stream = new SessionEventStream();
    stream.append('session_started', { model: MODEL });
    stream.append('agent_message', { text: 'one' });

    const response = new FakeResponse() as unknown as Parameters<SessionEventStream['attach']>[0];
    stream.attach(response, 1);
    expect(stream.listenerCount).toBe(1);
    expect((response as unknown as FakeResponse).chunks).toEqual([
      `data: ${JSON.stringify({ sequence: 2, type: 'agent_message', text: 'one' })}\n\n`,
    ]);

    (response as unknown as FakeResponse).emit('close');
    expect(stream.listenerCount).toBe(0);

    const second = new FakeResponse() as unknown as Parameters<SessionEventStream['attach']>[0];
    stream.attach(second, 0);
    expect((second as unknown as FakeResponse).chunks).toHaveLength(2);
    stream.endAll();
    expect((second as unknown as FakeResponse).ended).toBe(true);
    expect(stream.listenerCount).toBe(0);
  });
});

describe('main configuration (fail-closed start)', () => {
  it('refuses to start without BRIDGE_TOKEN and defaults the port to 4097', () => {
    expect(DEFAULT_BRIDGE_PORT).toBe(4097);
    expect(() => readBridgeConfig({})).toThrowError(/BRIDGE_TOKEN/);
    expect(() => readBridgeConfig({ BRIDGE_TOKEN: '' })).toThrowError(/BRIDGE_TOKEN/);
    expect(readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN })).toEqual({ token: BRIDGE_TOKEN, port: 4097 });
    expect(readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, PORT: '4123' })).toEqual({ token: BRIDGE_TOKEN, port: 4123 });
  });

  it('rejects a PORT that is not a usable port', () => {
    for (const port of ['0', '-1', '65536', 'http', '80.5']) {
      expect(() => readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, PORT: port })).toThrowError(/PORT/);
    }
  });
});
