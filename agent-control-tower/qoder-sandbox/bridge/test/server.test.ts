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
 * All credentials here are synthetic, never a real PAT: `test-bridge-token` for the bridge
 * bearer, `test-worker-token` for the session's MCP header value, `test-runtime-pat` for the
 * PAT the bridge forwards to the CLI child (deliberately a DIFFERENT value, mirroring
 * production where the MCP header carries the run-scoped worker token while
 * `QODER_PERSONAL_ACCESS_TOKEN` carries the qoder PAT) and `test-token-1` for the probe.
 * The suite asserts that none ever appears in an HTTP body, an SSE frame or a log line.
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
  DEFAULT_PERMISSION_DEADLINE_MS,
  MAX_BODY_BYTES,
  MAX_TIMER_DELAY_MS,
  PINNED_CLI_VERSION,
  clampTimerDelay,
  createBridgeServer,
  type BridgeAcpClient,
  type BridgeServer,
} from '../src/server.js';
import { EVENT_RING_CAPACITY, SessionEventStream } from '../src/sse.js';
import { DEFAULT_BRIDGE_PORT, readBridgeConfig } from '../src/main.js';

const FIXTURE = fileURLToPath(new URL('./fixtures/fake-qodercli.mjs', import.meta.url));
// Synthetic placeholders only: the bridge token and the worker token a session's
// mcpServers headers carry in production.
const BRIDGE_TOKEN = 'test-bridge-token';
const PAT = 'test-worker-token';
/**
 * F7: the PAT the bridge forwards to every CLI child. Production keeps the two values
 * separate — the session's MCP `Authorization` header carries the run-scoped worker token
 * (`QoderAdkProvider.java:291,895`) while the sandbox env's `QODER_PERSONAL_ACCESS_TOKEN`,
 * inherited by the bridge and allowlisted for the child (`src/env.ts:16-22`), carries the
 * qoder PAT. The fixture used to reuse one value for both, which is exactly why the
 * missing-PAT redaction was invisible.
 */
const RUNTIME_PAT = 'test-runtime-pat';
const AUTHORIZATION = `Bearer ${BRIDGE_TOKEN}`;
/**
 * The child environment the default `AcpClient` factory forwards (`resolveSpawnPlan`'s
 * `options.env ?? process.env`, allowlist `src/env.ts:16-22`). The harness passes it as
 * `acpClientOptions.env` so the suite is hermetic: the bridge derives the PAT it must
 * redact from that same source, and without it a test machine exporting a real
 * `QODER_PERSONAL_ACCESS_TOKEN` would leak into every redaction set.
 */
const CHILD_ENV = {
  PATH: process.env.PATH,
  HOME: process.env.HOME ?? 'C:/b3b-home',
  TERM: 'xterm',
  QODER_PERSONAL_ACCESS_TOKEN: RUNTIME_PAT,
};
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
  /** F4: every permission decision the bridge delivered, in order (requestId, approved). */
  readonly decisions: Array<[string, boolean]> = [];
  private handler: EventHandler | null = null;

  constructor(
    private readonly script: (client: ScriptedClient) => void = () => undefined,
    /** F7: when set, `createSession` fails with this message (create-failure redaction). */
    private readonly createFailure: string | null = null,
  ) {}

  onEvent(handler: EventHandler): () => void {
    this.handler = handler;
    return () => {
      this.handler = null;
    };
  }

  async createSession(): Promise<{ sessionId: string }> {
    this.script(this);
    if (this.createFailure !== null) {
      throw new Error(this.createFailure);
    }
    return { sessionId: 'acp-scripted' };
  }

  prompt(): void {
    this.calls.push('prompt');
  }

  decide(requestId: string, approved: boolean): PermissionDecision {
    this.calls.push('decide');
    this.decisions.push([requestId, approved]);
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
    env: { ...CHILD_ENV },
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
    acpClientOptions: { env: { ...CHILD_ENV } },
    createAcpClient: () => fixtureClient(scenario),
    ...overrides,
  });
  createdServers.push(bridge);
  await bridge.listen(0, '127.0.0.1');
  return bridge;
}

async function startScriptedBridge(
  create: () => BridgeAcpClient,
  overrides: { permissionDeadlineMs?: number } = {},
): Promise<BridgeServer> {
  const bridge = createBridgeServer({
    token: BRIDGE_TOKEN,
    log: line => logLines.push(line),
    acpClientOptions: { env: { ...CHILD_ENV } },
    createAcpClient: create,
    ...overrides,
  });
  createdServers.push(bridge);
  await bridge.listen(0, '127.0.0.1');
  return bridge;
}

/**
 * G7: start a bridge the way the sandbox entry point does — `readBridgeConfig` decides the token,
 * the port and the host's approval window (`APPROVAL_TIMEOUT_MS`), and the server is built from
 * exactly that configuration. A test that passes its window here exercises the production path
 * (environment → config → per-ask deadline), not just the `createBridgeServer` option.
 */
async function startConfiguredBridge(
  create: () => BridgeAcpClient,
  env: NodeJS.ProcessEnv,
): Promise<BridgeServer> {
  const config = readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, ...env });
  const bridge = createBridgeServer({
    token: config.token,
    permissionDeadlineMs: config.permissionDeadlineMs,
    log: line => logLines.push(line),
    acpClientOptions: { env: { ...CHILD_ENV } },
    createAcpClient: create,
  });
  createdServers.push(bridge);
  await bridge.listen(0, '127.0.0.1');
  return bridge;
}

/** Poll `predicate` until it holds, for timer-driven behaviour whose firing is the assertion. */
async function waitUntil(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate() && Date.now() < deadline) {
    await sleep(20);
  }
  expect(predicate(), 'condition was not met within the bounded wait').toBe(true);
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
    // deadlineSeconds=1 wins over the 30 min default: a permission may not outlive its run.
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

  it('expires a decision at the pending deadline, releases the waiting CLI and never delivers it', async () => {
    const bridge = await startBridge('happy', { permissionDeadlineMs: 150 });
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await post(bridge, `/sessions/${sessionId}/prompt`, { text: PROMPT });
    const permission = await stream.waitFor('permission_request');
    const expiresAt = Date.parse(String(permission.expiresAt));
    expect(expiresAt).toBeLessThanOrEqual(Date.now() + 150);

    // F4 residual (design line 248): with the host gone nothing else rejects the waiting CLI,
    // so at the local deadline the bridge delivers the reject itself through the same private
    // path a host decision uses and the blocked turn is released instead of sitting until the
    // sandbox TTL. A4 §2 proves the exercised CLI semantics of a reject_once selection: the
    // tool call fails and the turn completes with stopReason end_turn
    // (e2e/qoder/slice-a/04-permissions.md:205-217) — the fixture models exactly that, so the
    // terminal frame arriving here is the reject's observable effect (the pre-fix bridge
    // stayed silent and the turn never ended).
    const completed = await stream.waitFor('completed');
    expect(completed.stopReason).toBe('end_turn');

    const path = `/sessions/${sessionId}/permissions/${String(permission.requestId)}`;
    const expired = await post(bridge, path, { approved: true });
    expect(expired.status).toBe(200);
    expect(await expired.json()).toEqual({ outcome: 'expired' });

    // Fail closed stays intact: the late decision is never delivered and never re-decided.
    const late = await post(bridge, path, { approved: false });
    expect(await late.json()).toEqual({ outcome: 'expired' });
  });

  it('sends exactly one reject when the host never decides (no duplicate decide)', async () => {
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p1',
        toolCallId: 'call_1',
        toolName: 'Write',
        title: null,
        options: [
          { optionId: 'o1', kind: 'allow_once', name: 'Allow' },
          { optionId: 'o2', kind: 'reject_once', name: 'Reject' },
        ],
        params: { toolCall: { rawInput: { file_path: '/workspace/x.txt' } } },
      });
    });
    const bridge = await startScriptedBridge(() => client, { permissionDeadlineMs: 60 });
    const sessionId = await createSessionId(bridge);

    await waitUntil(() => client.decisions.length > 0);
    // Exactly one reply at the deadline, and it is the reject flow's (approved=false).
    expect(client.decisions).toEqual([['p1', false]]);

    // A decision that arrives after the deadline is refused without a second reply.
    const lateApprove = await post(bridge, `/sessions/${sessionId}/permissions/p1`, { approved: true });
    expect(await lateApprove.json()).toEqual({ outcome: 'expired' });
    const lateDeny = await post(bridge, `/sessions/${sessionId}/permissions/p1`, { approved: false });
    expect(await lateDeny.json()).toEqual({ outcome: 'expired' });
    expect(client.decisions).toEqual([['p1', false]]);
  });

  it('does not let the stale timer of a replaced record expire a newer ask that reuses the id', async () => {
    const ask: AcpEvent = {
      type: 'permission_request',
      requestId: 'dup',
      toolCallId: 'call_1',
      toolName: 'Write',
      title: null,
      options: [{ optionId: 'o1', kind: 'allow_once', name: 'Allow' }],
      params: { toolCall: { rawInput: { file_path: '/workspace/x.txt' } } },
    };
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit(ask);
    });
    const bridge = await startScriptedBridge(() => client, { permissionDeadlineMs: 600 });
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await stream.waitFor('permission_request');

    // The CLI reuses the id while the first ask is still pending: the pending map now holds a
    // NEW record (a distinct object for the same key) whose own deadline is 600 ms after this
    // second ask.
    await sleep(300);
    client.emit(ask);
    await waitUntil(() => stream.of('permission_request').length === 2);

    // Wait past the FIRST record's deadline (600 ms after the first ask) while the replacement's
    // deadline is still ahead. A timer that resolved by requestId alone would have marked the
    // new record expired and rejected it early; the record that is actually pending must still
    // be decidable by the host.
    await sleep(450);

    const decided = await post(bridge, `/sessions/${sessionId}/permissions/dup`, { approved: true });
    expect(await decided.json()).toEqual({ outcome: 'delivered' });
    expect(client.decisions).toEqual([['dup', true]]);
  });

  it('clamps the local deadline delay to the largest timeout Node accepts (2^31 - 1 ms)', () => {
    // Node stores a delay as a 32-bit signed integer; a larger value overflows and the timer
    // fires almost immediately, which would turn a huge permissionDeadlineMs into an instant
    // reject. Asserted on the exported clamp rather than by arming a real multi-week timeout.
    expect(MAX_TIMER_DELAY_MS).toBe(2 ** 31 - 1);
    expect(clampTimerDelay(2 ** 31)).toBe(MAX_TIMER_DELAY_MS);
    expect(clampTimerDelay(Number.MAX_SAFE_INTEGER)).toBe(MAX_TIMER_DELAY_MS);
    expect(clampTimerDelay(150)).toBe(150);
    expect(clampTimerDelay(-1)).toBe(0);
  });

  it('clears the local deadline timer when the host decides in time', async () => {
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p1',
        toolCallId: 'call_1',
        toolName: 'Write',
        title: null,
        options: [{ optionId: 'o1', kind: 'allow_once', name: 'Allow' }],
        params: { toolCall: { rawInput: { file_path: '/workspace/x.txt' } } },
      });
    });
    const bridge = await startScriptedBridge(() => client, { permissionDeadlineMs: 150 });
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);
    await stream.waitFor('permission_request');

    const decided = await post(bridge, `/sessions/${sessionId}/permissions/p1`, { approved: true });
    expect(await decided.json()).toEqual({ outcome: 'delivered' });

    // Waiting past the deadline proves the resolution cleared the timer: a stale timer would
    // have marked the delivered record expired and sent a second reply.
    await sleep(300);
    expect(client.decisions).toEqual([['p1', true]]);
  });

  it('clears the local deadline timer when the session is cancelled', async () => {
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p1',
        toolCallId: 'call_1',
        toolName: 'Write',
        title: null,
        options: [{ optionId: 'o1', kind: 'allow_once', name: 'Allow' }],
        params: { toolCall: { rawInput: { file_path: '/workspace/x.txt' } } },
      });
    });
    const bridge = await startScriptedBridge(() => client, { permissionDeadlineMs: 100 });
    const sessionId = await createSessionId(bridge);

    const cancelled = await post(bridge, `/sessions/${sessionId}/cancel`, {});
    expect(await cancelled.json()).toEqual({ terminated: true });

    // A cancelled session has no waiting CLI left to reject: no decide may follow the cancel.
    await sleep(300);
    expect(client.decisions).toEqual([]);
  });

  it('rejects a malformed decision body', async () => {
    const bridge = await startBridge('happy');
    const sessionId = await createSessionId(bridge);
    const response = await post(bridge, `/sessions/${sessionId}/permissions/whatever`, { approved: 'yes' });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: 'INVALID_REQUEST' });
  });
});

describe('host approval window (G7 finding 2, G8 finding 3)', () => {
  /**
   * The G7 hole: the host expires an ask at `min(now + approvals.timeout-ms, runDeadline)` while
   * the bridge used to deny the CLI at its own 15-minute default — up to 15 minutes earlier than
   * the host, which was never told. An approval arriving in that window was recorded APPROVED,
   * minted a one-use grant, was answered `expired` and stayed retryable forever.
   *
   * G8 finding 3: the host anchors its expiry when it PERSISTS the ask, after the bridge handled
   * the frame, so the host's window alone would still let the bridge's local deadline precede the
   * host's expiry by the delivery-plus-commit transit. The provider therefore forwards the host's
   * window PADDED by its documented margin (`QoderAdkProvider.APPROVAL_WINDOW_SLACK_MS`), and the
   * session run deadline stays the outer bound: the nearer of the two wins, with the host always
   * the first to expire an ask it still holds.
   */
  const WINDOW_ENV = 'APPROVAL_TIMEOUT_MS';

  /** One scripted ask (both selectable kinds), emitted right after the session is created. */
  function askClient(): ScriptedClient {
    return new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p1',
        toolCallId: 'call_1',
        toolName: 'Write',
        title: null,
        options: [
          { optionId: 'o1', kind: 'allow_once', name: 'Allow' },
          { optionId: 'o2', kind: 'reject_once', name: 'Reject' },
        ],
        params: { toolCall: { rawInput: { file_path: '/workspace/x.txt' } } },
      });
    });
  }

  it('clamps every ask to the host window and releases the waiting CLI at that deadline', async () => {
    const window = 300;
    const client = askClient();
    const bridge = await startConfiguredBridge(() => client, { [WINDOW_ENV]: String(window) });

    const askedAt = Date.now();
    const created = await createSession(bridge, { deadlineSeconds: 600 });
    expect(created.status).toBe(201);
    const stream = await openEvents(bridge, String(created.body?.bridgeSessionId));
    const permission = await stream.waitFor('permission_request');
    // The published deadline is the host's window (600 s of session deadline are further away).
    expect(Date.parse(String(permission.expiresAt)) - askedAt).toBeLessThanOrEqual(window + 250);

    await waitUntil(() => client.decisions.length > 0);
    const releasedAt = Date.now();
    // The local reject at the deadline is the release the waiting CLI sees (F4 residual): it must
    // land at the host window, not at the bridge's old 15-minute default.
    expect(client.decisions).toEqual([['p1', false]]);
    expect(releasedAt - askedAt).toBeGreaterThanOrEqual(window - 50);
    expect(releasedAt - askedAt).toBeLessThan(window + 1_000);
  });

  it('keeps the session deadline as the outer bound when it is nearer than the window', async () => {
    const client = askClient();
    // A 5 s window over a 1 s session deadline: the session deadline must win.
    const bridge = await startConfiguredBridge(() => client, { [WINDOW_ENV]: '5000' });
    const created = await createSession(bridge, { deadlineSeconds: 1 });

    const stream = await openEvents(bridge, String(created.body?.bridgeSessionId));
    const permission = await stream.waitFor('permission_request');
    expect(Date.parse(String(permission.expiresAt)) - Date.now()).toBeLessThanOrEqual(1_500);
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

describe('runtime PAT redaction (F7)', () => {
  it('never republishes the runtime PAT the bridge forwards to the CLI child', async () => {
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'session_update',
        update: {
          sessionUpdate: 'agent_message_chunk',
          content: { type: 'text', text: `the agent echoed ${RUNTIME_PAT} verbatim` },
        },
      });
      scripted.emit({
        type: 'prompt_error',
        requestId: '1',
        code: 'RPC_ERROR',
        message: `the CLI rejected the run: token=${RUNTIME_PAT}`,
      });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p1',
        toolCallId: 'call_1',
        toolName: 'Write',
        title: `Write guarded by Authorization: Bearer ${RUNTIME_PAT}`,
        options: [{ optionId: 'o1', kind: 'allow_once', name: 'Allow' }],
        params: { toolCall: { rawInput: { authorization: `Bearer ${RUNTIME_PAT}` } } },
      });
    });
    const bridge = await startScriptedBridge(() => client);
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);

    const message = await stream.waitFor('agent_message');
    const failed = await stream.waitFor('failed');
    const permission = await stream.waitFor('permission_request');

    expect(message.text).toBe('the agent echoed [redacted] verbatim');
    expect(failed).toEqual({
      sequence: expect.any(Number),
      type: 'failed',
      reason: 'the CLI rejected the run: token=[redacted]',
      code: 'RPC_ERROR',
    });
    expect(permission.title).toBe('Write guarded by Authorization: Bearer [redacted]');
    const redactedInput = JSON.stringify({ authorization: 'Bearer [redacted]' });
    expect(permission.redactedPreview).toBe(redactedInput);
    expect(permission.rawInput).toBe(redactedInput);
    // The session's MCP header carries a DIFFERENT synthetic value, so a redaction set built
    // from the header values alone lets every one of these strings through verbatim.
    expect(RUNTIME_PAT).not.toBe(PAT);
    expect(stream.raw).not.toContain(RUNTIME_PAT);
    expect(logLines.join('\n')).not.toContain(RUNTIME_PAT);
  });

  it('holds back a runtime PAT split across two agent chunks so the frames cannot be rejoined', async () => {
    const half = RUNTIME_PAT.slice(0, Math.ceil(RUNTIME_PAT.length / 2));
    const rest = RUNTIME_PAT.slice(half.length);
    expect(half + rest).toBe(RUNTIME_PAT);
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: `leaked ${half}` } },
      });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: `${rest} tail` } },
      });
    });
    const bridge = await startScriptedBridge(() => client);
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId);

    await stream.waitFor('agent_message', frame => String(frame.text).includes('tail'));
    // The partial prefix never leaves the bridge on its own: the first frame stops before it
    // and the completing half is redacted together with the carried first half, so no reader
    // (including the host, which concatenates agent text into the run output) can rejoin it.
    expect(stream.of('agent_message').map(frame => frame.text)).toEqual(['leaked ', '[redacted] tail']);
    expect(stream.raw).not.toContain(RUNTIME_PAT);
    expect(stream.raw).not.toContain(half);
    expect(stream.raw).not.toContain(rest);
    expect(logLines.join('\n')).not.toContain(half);
  });

  it('redacts the runtime PAT in a create-failure reason (the host-facing error text)', async () => {
    const client = new ScriptedClient(() => undefined, `session/new failed: token=${RUNTIME_PAT} rejected`);
    const bridge = await startScriptedBridge(() => client);

    const created = await createSession(bridge);

    expect(created.status).toBe(502);
    expect(created.body?.error).toBe('SESSION_CREATE_FAILED');
    expect(created.body?.reason).toBe('session/new failed: token=[redacted] rejected');
    expect(JSON.stringify(created.body)).not.toContain(RUNTIME_PAT);
  });

  it('redacts the runtime PAT echoed into CLI-supplied scalar fields', async () => {
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'session_update',
        update: {
          sessionUpdate: 'tool_call',
          toolCallId: 'call_9',
          kind: `k-${RUNTIME_PAT}`,
          status: `s-${RUNTIME_PAT}`,
          _meta: { qoder: { toolName: 'Write' } },
        },
      });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'tool_call_update', toolCallId: 'call_9', status: `u-${RUNTIME_PAT}` },
      });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'current_mode_update', currentModeId: `m-${RUNTIME_PAT}` },
      });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p-9',
        toolCallId: 'call_9',
        toolName: 'Write',
        title: null,
        options: [{ optionId: `o-${RUNTIME_PAT}`, kind: 'allow_once', name: 'Allow' }],
        params: { toolCall: { rawInput: { file_path: '/workspace/x.txt' } } },
      });
      scripted.emit({ type: 'prompt_result', requestId: 'p-9', result: { stopReason: `r-${RUNTIME_PAT}` } });
    });
    const bridge = await startScriptedBridge(() => client);
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId, 0);
    const completed = await stream.waitFor('completed');

    // Enum-shaped values are still CLI-supplied strings, so the PAT must not ride through any of
    // them; the ids stay verbatim because the host correlates on them and echoes the requestId.
    expect(stream.of('tool_call')).toEqual([
      {
        sequence: expect.any(Number),
        type: 'tool_call',
        toolCallId: 'call_9',
        toolName: 'Write',
        kind: 'k-[redacted]',
        status: 's-[redacted]',
      },
    ]);
    expect(stream.of('tool_call_update')).toEqual([
      { sequence: expect.any(Number), type: 'tool_call_update', toolCallId: 'call_9', status: 'u-[redacted]' },
    ]);
    expect(stream.of('mode_changed')).toEqual([
      { sequence: expect.any(Number), type: 'mode_changed', currentModeId: 'm-[redacted]' },
    ]);
    const permission = stream.of('permission_request')[0];
    expect(permission?.requestId).toBe('p-9');
    expect(permission?.toolCallId).toBe('call_9');
    expect(permission?.options).toEqual([{ optionId: 'o-[redacted]', kind: 'allow_once', name: 'Allow' }]);
    expect(completed).toEqual({ sequence: expect.any(Number), type: 'completed', stopReason: 'r-[redacted]' });
    expect(stream.raw).not.toContain(RUNTIME_PAT);
    expect(logLines.join('\n')).not.toContain(RUNTIME_PAT);
  });

  it('redacts the flushed split-guard carry under a pathological secret set', async () => {
    // Pathological by construction: the bridge token is the word inside the redaction placeholder
    // and the MCP header secret starts with the placeholder's own tail, so the split guard holds
    // a carry that contains the complete token. Synthetic values only.
    const token = 'redacted';
    const headerSecret = 'redacted]tok-9x';
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: `x${token}` } },
      });
      scripted.emit({ type: 'prompt_result', requestId: '1', result: { stopReason: 'end_turn' } });
    });
    const bridge = createBridgeServer({
      token,
      log: line => logLines.push(line),
      acpClientOptions: { env: {} },
      createAcpClient: () => client,
    });
    createdServers.push(bridge);
    await bridge.listen(0, '127.0.0.1');
    const authorization = `Bearer ${token}`;
    const created = await post(
      bridge,
      '/sessions',
      createSessionBody({
        mcpServers: [
          {
            name: 'aria-stub',
            url: 'http://127.0.0.1:9/mcp',
            headers: [{ name: 'Authorization', value: `Bearer ${headerSecret}` }],
          },
        ],
      }),
      authorization,
    );
    expect(created.status).toBe(201);
    const sessionId = String(((await created.json()) as Record<string, unknown>).bridgeSessionId);
    const stream = await openEvents(bridge, sessionId, 0, authorization);
    await stream.waitFor('completed');

    // 'xredacted' redacts to 'x[redacted]'; the 9-character tail 'redacted]' is held (it is a
    // proper prefix of the header secret), so the turn-boundary flush must run the carry through
    // the redact pass before publishing it instead of republishing the raw hold.
    expect(stream.of('agent_message').map(frame => frame.text)).toEqual(['x[', '[redacted]]']);
  });

  it('never lets the runtime PAT reach any published frame, whichever CLI-supplied field carries it', async () => {
    // The F7 closure, deliberately NOT a per-field test: one session where the CLI echoes the
    // same runtime PAT into every class of outbound string — free text, enum-shaped fields, an
    // options entry (all three option fields), the preview/rawInput pair, an error reason and a
    // stop reason. A future CLI-supplied field republished without its redact pass turns this
    // red even though every per-field test above still passes.
    const client = new ScriptedClient(scripted => {
      scripted.emit({ type: 'session_created', sessionId: 'acp-1', currentModeId: 'default', availableModels: [] });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: `text ${RUNTIME_PAT}` } },
      });
      scripted.emit({
        type: 'session_update',
        update: {
          sessionUpdate: 'tool_call',
          toolCallId: 'call_9',
          kind: `kind-${RUNTIME_PAT}`,
          status: `status-${RUNTIME_PAT}`,
          _meta: { qoder: { toolName: `tool-${RUNTIME_PAT}` } },
        },
      });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'tool_call_update', toolCallId: 'call_9', status: `upd-${RUNTIME_PAT}` },
      });
      scripted.emit({
        type: 'session_update',
        update: { sessionUpdate: 'current_mode_update', currentModeId: `mode-${RUNTIME_PAT}` },
      });
      scripted.emit({
        type: 'permission_request',
        requestId: 'p-9',
        toolCallId: 'call_9',
        toolName: `ask-${RUNTIME_PAT}`,
        title: `title ${RUNTIME_PAT}`,
        options: [
          { optionId: `opt-${RUNTIME_PAT}`, kind: RUNTIME_PAT, name: `label ${RUNTIME_PAT}` },
          { optionId: 'opt-plain', kind: 'reject_once', name: 'Reject' },
        ],
        params: { toolCall: { rawInput: { authorization: `Bearer ${RUNTIME_PAT}` } } },
      });
      scripted.emit({ type: 'prompt_error', requestId: '1', code: 'RPC_ERROR', message: `reason ${RUNTIME_PAT}` });
      scripted.emit({ type: 'prompt_result', requestId: 'p-9', result: { stopReason: `stop-${RUNTIME_PAT}` } });
    });
    const bridge = await startScriptedBridge(() => client);
    const sessionId = await createSessionId(bridge);
    const stream = await openEvents(bridge, sessionId, 0);
    await stream.waitFor('completed');

    // Non-vacuity: every surface class must really have been published (a stream that emitted
    // nothing would satisfy the leak scan below without proving anything).
    const types = stream.frames.map(frame => frame.type);
    for (const surface of [
      'agent_message',
      'tool_call',
      'tool_call_update',
      'mode_changed',
      'permission_request',
      'failed',
      'completed',
    ]) {
      expect(types, `surface '${surface}' was never published`).toContain(surface);
    }

    // The closure scan: the PAT may not appear in ANY published frame, whatever field carried
    // it (this is the assertion that was red while `options[].kind` was the one unredacted field).
    const leaked = stream.frames
      .filter(frame => JSON.stringify(frame).includes(RUNTIME_PAT))
      .map(frame => frame.type);
    expect(leaked, 'the runtime PAT reached a published frame').toEqual([]);

    // The mandate, literally: nowhere in the concatenated SSE payloads.
    expect(stream.raw).not.toContain(RUNTIME_PAT);

    // Every carrier was not just dropped: exactly 15 carriers went in and 15 masks came out
    // (a silently dropped frame would fail this count too).
    expect(stream.raw.split('[redacted]').length - 1).toBe(15);
    // The redaction set is built from the runtime PAT and NOT from the MCP header value: the
    // two synthetic values differ, so a header-only set would have let every carrier through.
    expect(RUNTIME_PAT).not.toBe(PAT);
    expect(logLines.join('\n')).not.toContain(RUNTIME_PAT);
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
    expect(readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN })).toEqual({
      token: BRIDGE_TOKEN,
      port: 4097,
      permissionDeadlineMs: DEFAULT_PERMISSION_DEADLINE_MS,
    });
    expect(readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, PORT: '4123' })).toEqual({
      token: BRIDGE_TOKEN,
      port: 4123,
      permissionDeadlineMs: DEFAULT_PERMISSION_DEADLINE_MS,
    });
  });

  it('rejects a PORT that is not a usable port', () => {
    for (const port of ['0', '-1', '65536', 'http', '80.5']) {
      expect(() => readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, PORT: port })).toThrowError(/PORT/);
    }
  });

  it('reads the host approval window the provider passes, falling back only when it is absent', () => {
    // G7 finding 2: the window the host expires asks with travels in `APPROVAL_TIMEOUT_MS`
    // (`QoderAdkProvider` sets it from `approvals.timeout-ms`, padded by its documented margin,
    // at sandbox creation). G8 finding 4: the fallback is aligned with the host's documented
    // default (30 min = 1800000 ms) and applies ONLY to bridges started without a host (sandbox
    // smoke tests, the image boot check) — a host-started bridge always receives the env, so the
    // fallback can never disagree with a host that uses its default window.
    expect(DEFAULT_PERMISSION_DEADLINE_MS).toBe(30 * 60 * 1000);
    expect(readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, APPROVAL_TIMEOUT_MS: '250' }))
      .toEqual({ token: BRIDGE_TOKEN, port: 4097, permissionDeadlineMs: 250 });
    expect(readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, APPROVAL_TIMEOUT_MS: '  ' }).permissionDeadlineMs)
      .toBe(DEFAULT_PERMISSION_DEADLINE_MS);
  });

  it('refuses a malformed approval window instead of enforcing a silently different deadline', () => {
    for (const value of ['0', '-1', '2.5', 'soon', '1800000ms']) {
      expect(() => readBridgeConfig({ BRIDGE_TOKEN: BRIDGE_TOKEN, APPROVAL_TIMEOUT_MS: value }))
        .toThrowError(/APPROVAL_TIMEOUT_MS/);
    }
  });
});
