// End-to-end tests for the run-bound Qoder ACP bridge (Task 8, Steps 3-4).
//
// Every test in this file spawns the real bridge (`dist/main.js`) with the
// committed mock Qoder CLI peer (Task 7:
// agent-control-tower/act-app/src/test/resources/e2e/peers/mock-qoder.mjs) as
// its child process and asserts the exact recorded wire behaviour: the
// initialize/session/new/session/set_model order, the pinned model `efficient`,
// the exact permission option lists and response frames, the recorded rejection
// codes, the streamed update payloads, the cooperative cancel notification and
// the real child exit code.
//
// Cross-package coupling (recorded in the task report): this test spawns a peer
// that lives under agent-control-tower/act-app/src/test/resources/e2e/ and is
// owned by Task 7. The peer path is referenced by relative path from this file.
import { execFileSync, spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { request as httpRequest, type IncomingMessage } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeAll, expect, test } from 'vitest';

import { buildChildEnvironment, childSpawnOptions } from '../src/acp-client.js';
import { EventBuffer } from '../src/events.js';
import { createBridgeServer } from '../src/server.js';

const MOCK_CLI = fileURLToPath(
  new URL('../../../agent-control-tower/act-app/src/test/resources/e2e/peers/mock-qoder.mjs', import.meta.url),
);
const MAIN_JS = fileURLToPath(new URL('../dist/main.js', import.meta.url));
const TSC_JS = fileURLToPath(new URL('../node_modules/typescript/lib/tsc.js', import.meta.url));
const CONTROL_SECRET_HEADER = 'x-bridge-control-secret';
const REDACTED = '[REDACTED-SECRET]';
const ISO_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TOOL_CALL_ID = /^call_[0-9a-f]{24}$/;

interface BridgeEvent {
  seq: number;
  at: string;
  type: string;
  [key: string]: unknown;
}

interface ApiResult {
  status: number;
  json: Record<string, unknown>;
}

interface BridgeHandle {
  root: string;
  workspace: string;
  runId: string;
  secret: string;
  proc: ChildProcess;
  endpoint: string | null;
  stdoutLines: string[];
  stderrText: () => string;
  exit: Promise<{ code: number | null; signal: string | null }>;
  events: () => BridgeEvent[];
  ready(timeoutMs?: number): Promise<Record<string, unknown>>;
  readyOutcome(timeoutMs?: number): Promise<Record<string, unknown> | null>;
  waitForEvent(predicate: (event: BridgeEvent) => boolean, timeoutMs?: number): Promise<BridgeEvent>;
  api(method: string, path: string, body?: unknown, secretOverride?: string | null): Promise<ApiResult>;
  stop(): Promise<void>;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const roots: string[] = [];
const handles: BridgeHandle[] = [];

beforeAll(() => {
  execFileSync(process.execPath, [TSC_JS, '-p', fileURLToPath(new URL('../tsconfig.json', import.meta.url))], {
    stdio: 'pipe',
  });
}, 120000);

afterEach(async () => {
  for (const handle of handles.splice(0)) await handle.stop();
  for (const root of roots.splice(0)) {
    try {
      rmSync(root, { recursive: true, force: true, maxRetries: 5 });
    } catch {
      // Best effort: a fixture writer may still hold a handle for a moment.
    }
  }
});

/** Deterministic subset of an event/response object for exact deep comparison. */
function pick(source: Record<string, unknown>, keys: string[]): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const key of keys) result[key] = source[key];
  return result;
}

function killTree(pid: number | undefined): void {
  if (pid === undefined) return;
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { encoding: 'utf8', windowsHide: true });
    return;
  }
  try {
    process.kill(-pid, 'SIGKILL');
  } catch {
    try {
      process.kill(pid, 'SIGKILL');
    } catch {
      // already gone
    }
  }
}

function startBridge(config: {
  scenario: string;
  model?: string;
  extraArgs?: string[];
  credential?: string;
  worker?: { name?: string; url: string; token: string };
}): BridgeHandle {
  const root = mkdtempSync(join(tmpdir(), 'aria-bridge-'));
  roots.push(root);
  const workspace = join(root, 'ws');
  mkdirSync(workspace, { recursive: true });
  const runId = randomUUID();
  const secret = `control-secret-${randomBytes(12).toString('hex')}`;
  const secretFile = join(root, 'control.secret');
  writeFileSync(secretFile, `${secret}\n`, 'utf8');
  const peerToken = `peer-token-${randomBytes(10).toString('hex')}`;
  const args = [
    '--run-id',
    runId,
    '--workspace',
    workspace,
    '--model',
    config.model ?? 'efficient',
    '--cli',
    process.execPath,
    '--cli-arg',
    MOCK_CLI,
    '--cli-arg',
    '--scenario',
    '--cli-arg',
    config.scenario,
    '--cli-arg',
    '--workspace',
    '--cli-arg',
    workspace,
    '--control-secret-file',
    secretFile,
    '--child-env',
    `ARIA_PEER_CONTROL_TOKEN=${peerToken}`,
  ];
  if (config.credential !== undefined) {
    const credentialFile = join(root, 'credential.secret');
    writeFileSync(credentialFile, config.credential, 'utf8');
    args.push('--credential-env', 'QODER_PERSONAL_ACCESS_TOKEN', '--credential-file', credentialFile);
  }
  if (config.worker !== undefined) {
    const tokenFile = join(root, 'worker-token.secret');
    writeFileSync(tokenFile, config.worker.token, 'utf8');
    args.push(
      '--worker-mcp-name',
      config.worker.name ?? 'aria-worker',
      '--worker-mcp-url',
      config.worker.url,
      '--worker-mcp-token-file',
      tokenFile,
    );
  }
  for (const extra of config.extraArgs ?? []) args.push(extra);

  const proc = spawn(process.execPath, [MAIN_JS, ...args], {
    // The bridge process itself is given the secret in its environment on
    // purpose: it must only ever read it from --control-secret-file and must
    // never forward it to the child.
    env: { ...process.env, QODER_BRIDGE_CONTROL_SECRET: secret },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    detached: process.platform !== 'win32',
  });
  const stdoutLines: string[] = [];
  const stderrChunks: string[] = [];
  let stdoutBuffer = '';
  proc.stdout?.setEncoding('utf8');
  proc.stdout?.on('data', (chunk: string) => {
    stdoutBuffer += chunk;
    for (;;) {
      const at = stdoutBuffer.indexOf('\n');
      if (at < 0) break;
      stdoutLines.push(stdoutBuffer.slice(0, at));
      stdoutBuffer = stdoutBuffer.slice(at + 1);
    }
  });
  proc.stderr?.setEncoding('utf8');
  proc.stderr?.on('data', (chunk: string) => stderrChunks.push(chunk));
  const exit = new Promise<{ code: number | null; signal: string | null }>((resolveExit) => {
    proc.once('exit', (code, signal) => resolveExit({ code, signal }));
  });

  const collected: BridgeEvent[] = [];
  let endpoint: string | null = null;
  let lastSeq = 0;
  let polling = true;
  let pollLoop: Promise<void> | null = null;
  const stderrText = () => stderrChunks.join('');

  const handle: BridgeHandle = {
    root,
    workspace,
    runId,
    secret,
    proc,
    get endpoint() {
      return endpoint;
    },
    stdoutLines,
    stderrText,
    exit,
    events: () => collected,
    async ready(timeoutMs = 15000) {
      const outcome = await handle.readyOutcome(timeoutMs);
      if (outcome === null) {
        throw new Error(
          `Bridge did not become ready (exit=${JSON.stringify(proc.exitCode)}): stdout=${JSON.stringify(stdoutLines)} stderr=${JSON.stringify(stderrText())}`,
        );
      }
      return outcome;
    },
    async readyOutcome(timeoutMs = 15000) {
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        const line = stdoutLines.find((candidate) => candidate.includes('"bridge.ready"'));
        if (line !== undefined) {
          const parsed = JSON.parse(line) as Record<string, unknown>;
          endpoint = String(parsed.endpoint);
          lastSeq = 0;
          polling = true;
          pollLoop = (async () => {
            while (polling) {
              try {
                const response = await handle.api('GET', `/events?after=${lastSeq}`);
                const events = response.json.events;
                if (response.status === 200 && Array.isArray(events)) {
                  for (const event of events as BridgeEvent[]) {
                    collected.push(event);
                    lastSeq = event.seq;
                  }
                } else if (response.status === 409) {
                  // This consumer explicitly acknowledges a gap by resuming
                  // from the first retained event; the bridge never silently
                  // pretends the evicted events did not exist.
                  const retainedFrom = (response.json.error as Record<string, unknown>).retainedFrom;
                  if (typeof retainedFrom === 'number') lastSeq = retainedFrom - 1;
                }
              } catch {
                await sleep(20);
              }
              await sleep(15);
            }
          })();
          return parsed;
        }
        if (proc.exitCode !== null || Date.now() > deadline) return null;
        await sleep(20);
      }
    },
    async waitForEvent(predicate, timeoutMs = 15000) {
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        const found = collected.find(predicate);
        if (found !== undefined) return found;
        if (Date.now() > deadline) {
          throw new Error(
            `Timed out waiting for an event; observed=${JSON.stringify(collected.map((event) => event.type))} stderr=${JSON.stringify(stderrText())}`,
          );
        }
        await sleep(20);
      }
    },
    async api(method, path, body, secretOverride) {
      const headers: Record<string, string> = {};
      const effectiveSecret = secretOverride === undefined ? secret : secretOverride;
      if (effectiveSecret !== null) headers[CONTROL_SECRET_HEADER] = effectiveSecret;
      if (body !== undefined) headers['content-type'] = 'application/json';
      const response = await fetch(`${endpoint}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      return { status: response.status, json: JSON.parse(text) as Record<string, unknown> };
    },
    async stop() {
      polling = false;
      if (pollLoop) await pollLoop.catch(() => undefined);
      if (proc.exitCode === null && !proc.killed) {
        killTree(proc.pid);
        await Promise.race([exit, sleep(5000)]);
      }
    },
  };
  handles.push(handle);
  return handle;
}

const sha256 = (value: string) => createHash('sha256').update(value).digest('hex');

function sessionIdOf(ready: Record<string, unknown>): string {
  const sessionId = ready.sessionId;
  if (typeof sessionId !== 'string') throw new Error(`No sessionId in ready line: ${JSON.stringify(ready)}`);
  return sessionId;
}

function diagnosticsOf(bridge: BridgeHandle): Array<Record<string, unknown>> {
  return bridge
    .events()
    .filter((event) => event.type === 'child.diagnostic')
    .map((event) => JSON.parse(String(event.line)) as Record<string, unknown>);
}

function expectSequenced(events: BridgeEvent[]): void {
  expect(events.map((event) => event.seq)).toEqual(events.map((_, index) => index + 1));
  expect(events.every((event) => ISO_TIMESTAMP.test(String(event.at)))).toBe(true);
}

// ---------------------------------------------------------------------------- tests

test(
  'complete scenario: exact handshake order, pinned model, streaming and guarded routes',
  async () => {
    const bridge = startBridge({ scenario: 'complete' });
    const ready = await bridge.ready();
    const workspace = bridge.workspace;
    const sessionId = sessionIdOf(ready);

    // The readiness line is the only stdout line; child diagnostics live in events.
    expect(bridge.stdoutLines).toHaveLength(1);
    expect(Object.keys(ready).sort()).toEqual(['endpoint', 'model', 'pid', 'runId', 'sessionId', 'type']);
    expect(ready.type).toBe('bridge.ready');
    expect(ready.runId).toBe(bridge.runId);
    expect(ready.model).toBe('efficient');
    expect(ready.pid).toBe(bridge.proc.pid);
    expect(ready.sessionId).toBe(sessionId);
    expect(String(ready.endpoint)).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/);
    expect(bridge.endpoint).toBe(String(ready.endpoint));

    // /health is exempt from the control secret and leaks nothing.
    const health = await bridge.api('GET', '/health', undefined, null);
    expect(health.status).toBe(200);
    const { lastSeq, retainedFrom, childPid, ...healthRest } = health.json as Record<string, unknown> & {
      lastSeq: number;
      retainedFrom: number;
      childPid: number;
    };
    expect(healthRest).toEqual({
      status: 'ok',
      state: 'ready',
      runId: bridge.runId,
      pid: bridge.proc.pid,
      childAlive: true,
      sessionId,
      model: { requested: 'efficient', observed: null },
      core: { name: 'qoder-cli', title: 'Qoder CLI', version: '1.1.61' },
      protocolVersion: 1,
      promptInFlight: false,
      pendingPermissionRequests: [],
    });
    expect(typeof childPid).toBe('number');
    expect(retainedFrom).toBe(1);
    expect(lastSeq).toBe(bridge.events().at(-1)?.seq);
    expect(JSON.stringify(health.json)).not.toContain(bridge.secret);

    // Every other route requires the control secret.
    for (const [method, path] of [
      ['GET', '/session'],
      ['POST', '/prompt'],
      ['GET', '/events'],
      ['POST', '/permission-response'],
      ['POST', '/control'],
    ]) {
      const payload = method === 'POST' ? { text: 'x' } : undefined;
      const missing = await bridge.api(method, path, payload, null);
      expect(missing.status).toBe(401);
      expect(missing.json).toEqual({
        error: { code: 'E_UNAUTHORIZED', message: `Missing or invalid ${CONTROL_SECRET_HEADER} header` },
      });
      const wrong = await bridge.api(method, path, payload, 'wrong-secret-wrong');
      expect(wrong.status).toBe(401);
      expect(wrong.json).toEqual({
        error: { code: 'E_UNAUTHORIZED', message: `Missing or invalid ${CONTROL_SECRET_HEADER} header` },
      });
    }

    // /session confirms the launch-time binding and refuses anything else.
    const binding = await bridge.api('POST', '/session', { runId: bridge.runId, workspace, model: 'efficient' });
    expect(binding.status).toBe(200);
    expect(binding.json).toEqual({
      runId: bridge.runId,
      workspace,
      model: 'efficient',
      sessionId,
      state: 'ready',
      core: { name: 'qoder-cli', title: 'Qoder CLI', version: '1.1.61' },
      protocolVersion: 1,
      observedModel: null,
    });
    const override = await bridge.api('POST', '/session', {
      runId: bridge.runId,
      workspace,
      model: 'efficient',
      executable: 'C:/evil/evil.exe',
    });
    expect(override.status).toBe(400);
    expect(override.json).toEqual({
      error: {
        code: 'E_SESSION_OVERRIDE_REJECTED',
        message:
          'Session binding rejects field "executable"; the bridge is bound to runId, workspace and model at launch',
        field: 'executable',
      },
    });
    const wrongModel = await bridge.api('POST', '/session', { runId: bridge.runId, workspace, model: 'auto' });
    expect(wrongModel.status).toBe(409);
    expect(wrongModel.json).toEqual({
      error: {
        code: 'E_BINDING_MISMATCH',
        message: 'Session binding model "auto" does not match the pinned model efficient',
        field: 'model',
      },
    });
    const wrongWorkspace = await bridge.api('POST', '/session', {
      runId: bridge.runId,
      workspace: join(bridge.root, 'other'),
      model: 'efficient',
    });
    expect(wrongWorkspace.status).toBe(409);
    expect(wrongWorkspace.json).toEqual({
      error: {
        code: 'E_BINDING_MISMATCH',
        message: `Session binding workspace ${JSON.stringify(join(bridge.root, 'other'))} does not match this bridge workspace ${workspace}`,
        field: 'workspace',
      },
    });

    // Prompting is a recorded request/response pair over the streamed updates.
    const promptText = 'Reply with exactly the single word: pong';
    const prompted = await bridge.api('POST', '/prompt', { text: promptText });
    expect(prompted.status).toBe(200);
    expect(prompted.json).toEqual({ promptId: 4, sessionId, state: 'streaming' });

    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(pick(result, ['type', 'promptId', 'stopReason', 'usage', 'observedModel'])).toEqual({
      type: 'prompt.result',
      promptId: 4,
      stopReason: 'end_turn',
      usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
      observedModel: 'efficient',
    });
    expect(String(result.userMessageId)).toMatch(UUID);

    const events = bridge.events();
    expectSequenced(events);
    // The readiness event is published once the handshake finished and the
    // authenticated routes started listening: it follows the recorded
    // initialize -> session/new -> session/set_model exchange and precedes the
    // first prompt.
    expect(events.filter((event) => event.type === 'bridge.ready')).toHaveLength(1);
    const readyIndex = events.findIndex((event) => event.type === 'bridge.ready');
    const setModelResultIndex = events.findIndex((event) => event.type === 'acp.result' && event.id === 3);
    const promptStartedIndex = events.findIndex((event) => event.type === 'prompt.started');
    expect(readyIndex).toBeGreaterThan(setModelResultIndex);
    expect(promptStartedIndex).toBeGreaterThan(readyIndex);

    const requests = events.filter((event) => event.type === 'acp.request');
    expect(requests.map((event) => ({ id: event.id, method: event.method }))).toEqual([
      { id: 1, method: 'initialize' },
      { id: 2, method: 'session/new' },
      { id: 3, method: 'session/set_model' },
      { id: 4, method: 'session/prompt' },
    ]);
    expect(requests.map((event) => event.params)).toEqual([
      { protocolVersion: 1 },
      { cwd: workspace, mcpServers: [] },
      { sessionId, modelId: 'efficient' },
      { sessionId, prompt: [{ type: 'text', text: promptText }] },
    ]);
    const results = events.filter((event) => event.type === 'acp.result');
    expect(results.map((event) => ({ id: event.id, method: event.method }))).toEqual([
      { id: 1, method: 'initialize' },
      { id: 2, method: 'session/new' },
      { id: 3, method: 'session/set_model' },
      { id: 4, method: 'session/prompt' },
    ]);

    const updates = events.filter((event) => event.type === 'session.update').map((event) => event.update);
    expect(updates).toEqual([
      {
        sessionUpdate: 'available_commands_update',
        availableCommands: [
          {
            name: 'fixture-status',
            description: 'Deterministic fixture command (vendor command list intentionally not reproduced)',
            input: { hint: '[status]' },
          },
        ],
      },
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'The' } },
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: ' user wants exactly' } },
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: ' the' } },
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: ' word' } },
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: ' "pong".\n' } },
      { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'pong' } },
    ]);

    const finalHealth = await bridge.api('GET', '/health', undefined, null);
    expect(pick(finalHealth.json as Record<string, unknown>, ['model', 'promptInFlight', 'state'])).toEqual({
      model: { requested: 'efficient', observed: 'efficient' },
      promptInFlight: false,
      state: 'ready',
    });
  },
  30000,
);

test(
  'write-twice scenario: exact permission payloads, allow-once bytes, denial, dedupe and refusal ordering',
  async () => {
    const bridge = startBridge({ scenario: 'write-twice' });
    const ready = await bridge.ready();
    const workspace = bridge.workspace;
    const sessionId = sessionIdOf(ready);

    await bridge.api('POST', '/prompt', { text: 'Create the fixture file, then run the fixture command' });

    const first = await bridge.waitForEvent((event) => event.type === 'permission.request');
    const expectedTarget = join(workspace, 'probe-allow-once.txt');
    expect(
      pick(first, [
        'type',
        'requestId',
        'sessionId',
        'toolName',
        'toolKind',
        'toolCallTitle',
        'argumentsDigest',
        'options',
      ]),
    ).toEqual({
      type: 'permission.request',
      requestId: 0,
      sessionId,
      toolName: 'Write',
      toolKind: 'edit',
      toolCallTitle: 'Edit probe-allow-once.txt',
      argumentsDigest: sha256(JSON.stringify({ content: 'alpha-allow-once', file_path: expectedTarget })),
      options: [
        { optionId: 'proceed_always', kind: 'allow_always', name: 'Allow for this session' },
        { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow' },
        { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
      ],
    });
    expect(String(first.toolCallId)).toMatch(TOOL_CALL_ID);
    expect(existsSync(expectedTarget)).toBe(false);

    // A native optionId may never be supplied by the caller, and an invalid
    // choice is rejected before any option is selected.
    const optionIdAttempt = await bridge.api('POST', '/permission-response', {
      requestId: 0,
      optionId: 'proceed_always',
    });
    expect(optionIdAttempt.status).toBe(400);
    expect(optionIdAttempt.json).toEqual({
      error: {
        code: 'E_INVALID_CHOICE',
        message: 'Permission decisions select a choice (ALLOW_ONCE or DENY), not a native optionId',
        field: 'optionId',
      },
    });
    const invalidChoice = await bridge.api('POST', '/permission-response', { requestId: 999, choice: 'ALLOW_ALWAYS' });
    expect(invalidChoice.status).toBe(400);
    expect(invalidChoice.json).toEqual({
      error: {
        code: 'E_INVALID_CHOICE',
        message: 'Invalid permission choice: "ALLOW_ALWAYS"; expected ALLOW_ONCE or DENY',
      },
    });
    const missingChoice = await bridge.api('POST', '/permission-response', { requestId: 999 });
    expect(missingChoice.status).toBe(400);
    expect(missingChoice.json).toEqual({
      error: { code: 'E_INVALID_CHOICE', message: 'Invalid permission choice: undefined; expected ALLOW_ONCE or DENY' },
    });
    const unknownRequest = await bridge.api('POST', '/permission-response', { requestId: 999, choice: 'DENY' });
    expect(unknownRequest.status).toBe(404);
    expect(unknownRequest.json).toEqual({
      error: {
        code: 'E_UNKNOWN_REQUEST',
        message: 'No pending permission request 999 (status: unknown)',
        requestId: 999,
        status: null,
      },
    });
    expect(bridge.events().filter((event) => event.type === 'permission.response')).toHaveLength(0);
    expect(existsSync(expectedTarget)).toBe(false);

    // ALLOW_ONCE selects the single allow_once option, never allow_always.
    const allowed = await bridge.api('POST', '/permission-response', { requestId: 0, choice: 'ALLOW_ONCE' });
    expect(allowed.status).toBe(200);
    expect(allowed.json).toEqual({
      requestId: 0,
      choice: 'ALLOW_ONCE',
      optionId: 'proceed_once',
      raw: '{"jsonrpc":"2.0","id":0,"result":{"outcome":{"outcome":"selected","optionId":"proceed_once"}}}\n',
      deduplicated: false,
    });
    await bridge.waitForEvent((event) => event.type === 'permission.response' && event.requestId === 0);
    // The completed tool_call_update proves the mock performed the fixture write
    // only after the genuine allow-once decision.
    await bridge.waitForEvent(
      (event) =>
        event.type === 'session.update' &&
        (event.update as Record<string, unknown>).sessionUpdate === 'tool_call_update' &&
        (event.update as Record<string, unknown>).status === 'completed',
    );
    expect(readFileSync(expectedTarget, 'utf8')).toBe('alpha-allow-once');

    // A repeat delivery of the same decision is deduplicated, not re-sent.
    const repeated = await bridge.api('POST', '/permission-response', { requestId: 0, choice: 'ALLOW_ONCE' });
    expect(repeated.status).toBe(200);
    expect(repeated.json).toEqual({
      requestId: 0,
      choice: 'ALLOW_ONCE',
      optionId: 'proceed_once',
      raw: '{"jsonrpc":"2.0","id":0,"result":{"outcome":{"outcome":"selected","optionId":"proceed_once"}}}\n',
      deduplicated: true,
    });
    const conflicting = await bridge.api('POST', '/permission-response', { requestId: 0, choice: 'DENY' });
    expect(conflicting.status).toBe(409);
    expect(conflicting.json).toEqual({
      error: {
        code: 'E_CONTROL_CONFLICT',
        message: 'Permission request 0 was already answered with ALLOW_ONCE; cannot change it to DENY',
        requestId: 0,
      },
    });

    // The execute request offers the recorded execute-shaped allow-always.
    const second = await bridge.waitForEvent(
      (event) => event.type === 'permission.request' && event.requestId === 1,
    );
    const secondTarget = join(workspace, 'probe-write-twice-2.txt');
    const command = `printf 'gamma-permission-twice' > "${secondTarget}"`;
    expect(
      pick(second, [
        'type',
        'requestId',
        'sessionId',
        'toolName',
        'toolKind',
        'toolCallTitle',
        'argumentsDigest',
        'options',
      ]),
    ).toEqual({
      type: 'permission.request',
      requestId: 1,
      sessionId,
      toolName: 'Bash',
      toolKind: 'execute',
      toolCallTitle: command,
      argumentsDigest: sha256(JSON.stringify({ command, description: `Create ${secondTarget} with exact content` })),
      options: [
        { optionId: 'proceed_always_and_save', kind: 'allow_always', name: 'Always allow "printf"' },
        { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow' },
        { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
      ],
    });
    expect(String(second.toolCallId)).toMatch(TOOL_CALL_ID);

    const denied = await bridge.api('POST', '/permission-response', { requestId: 1, choice: 'DENY' });
    expect(denied.status).toBe(200);
    expect(denied.json).toEqual({
      requestId: 1,
      choice: 'DENY',
      optionId: 'cancel',
      raw: '{"jsonrpc":"2.0","id":1,"result":{"outcome":{"outcome":"selected","optionId":"cancel"}}}\n',
      deduplicated: false,
    });

    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(pick(result, ['stopReason', 'observedModel'])).toEqual({
      stopReason: 'end_turn',
      observedModel: 'efficient',
    });
    expect(existsSync(secondTarget)).toBe(false);
    expect(readFileSync(expectedTarget, 'utf8')).toBe('alpha-allow-once');

    const responses = bridge.events().filter((event) => event.type === 'permission.response');
    expect(responses.map((event) => ({ requestId: event.requestId, choice: event.choice, optionId: event.optionId }))).toEqual([
      { requestId: 0, choice: 'ALLOW_ONCE', optionId: 'proceed_once' },
      { requestId: 1, choice: 'DENY', optionId: 'cancel' },
    ]);

    // The mock reports the granted write and the denied tool call in its stream;
    // the granted update carries the fixture's own completion text.
    const toolUpdates = bridge
      .events()
      .filter((event) => event.type === 'session.update')
      .map((event) => event.update as Record<string, unknown>)
      .filter((update) => update.sessionUpdate === 'tool_call_update');
    const completionText = `Fixture write completed at: ${expectedTarget}`;
    expect(toolUpdates).toEqual([
      {
        sessionUpdate: 'tool_call_update',
        toolCallId: first.toolCallId,
        status: 'completed',
        content: [{ type: 'content', content: { type: 'text', text: completionText } }],
        rawOutput: completionText,
      },
      {
        sessionUpdate: 'tool_call_update',
        toolCallId: second.toolCallId,
        status: 'failed',
        content: [{ type: 'content', content: { type: 'text', text: 'Fixture decision denied the write' } }],
        rawOutput: 'Fixture decision denied the write',
      },
    ]);
  },
  30000,
);

test(
  'invalid-auth scenario: the recorded -32000 error is surfaced and the bridge never listens',
  async () => {
    const bridge = startBridge({ scenario: 'invalid-auth' });
    const outcome = await bridge.readyOutcome(15000);
    expect(outcome).toBeNull();
    const exited = await bridge.exit;
    expect(exited).toEqual({ code: 70, signal: null });
    expect(bridge.stdoutLines).toHaveLength(1);
    expect(JSON.parse(bridge.stdoutLines[0] ?? '')).toEqual({
      type: 'bridge.error',
      code: 'E_AUTH_REQUIRED',
      message: 'Authentication required: Authentication is required.',
      rpcCode: -32000,
      method: 'session/new',
    });
    expect(bridge.events()).toEqual([]);
  },
  30000,
);

test(
  'unsupported model: the bridge refuses an unoffered model before set_model and before listening',
  async () => {
    const bridge = startBridge({ scenario: 'unsupported-model', model: 'qmodel_9x' });
    const outcome = await bridge.readyOutcome(15000);
    expect(outcome).toBeNull();
    const exited = await bridge.exit;
    expect(exited).toEqual({ code: 70, signal: null });
    expect(JSON.parse(bridge.stdoutLines[0] ?? '')).toEqual({
      type: 'bridge.error',
      code: 'E_UNSUPPORTED_MODEL',
      message:
        'Model "qmodel_9x" is not offered by the pinned core; offered: ["efficient","qmodel_38max","qfmodel"]',
      offered: ['efficient', 'qmodel_38max', 'qfmodel'],
      requested: 'qmodel_9x',
    });
  },
  30000,
);

test(
  'malformed-frame scenario: a non-JSON line fails the bridge explicitly with its byte length',
  async () => {
    const bridge = startBridge({ scenario: 'malformed-frame' });
    const outcome = await bridge.readyOutcome(15000);
    expect(outcome).toBeNull();
    const exited = await bridge.exit;
    expect(exited).toEqual({ code: 65, signal: null });
    const errorLine = JSON.parse(bridge.stdoutLines[0] ?? '') as Record<string, unknown>;
    expect(errorLine.type).toBe('bridge.error');
    expect(errorLine.code).toBe('E_MALFORMED_FRAME');
    // The fixture writes exactly `{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"` (66 bytes).
    expect(errorLine.bytes).toBe(66);
    expect(errorLine.message).toMatch(/^Malformed frame \(66 bytes\): /);
  },
  30000,
);

test(
  'oversized frame: a frame above the accepted maximum fails the bridge explicitly',
  async () => {
    // 96 bytes admits the bridge's own handshake requests (the 77-byte
    // initialize frame) while rejecting the 591-byte recorded initialize result
    // inbound, so this test exercises the inbound bound.
    const bridge = startBridge({ scenario: 'complete', extraArgs: ['--max-frame-bytes', '96'] });
    const outcome = await bridge.readyOutcome(15000);
    expect(outcome).toBeNull();
    const exited = await bridge.exit;
    expect(exited).toEqual({ code: 65, signal: null });
    // The first child frame is the 591-byte initialize result.
    expect(JSON.parse(bridge.stdoutLines[0] ?? '')).toEqual({
      type: 'bridge.error',
      code: 'E_FRAME_TOO_LARGE',
      message: 'Frame of 591 bytes exceeds the 96-byte limit',
      limit: 96,
      bytes: 591,
    });
  },
  30000,
);

test(
  'outbound frames: a request above the accepted maximum fails the bridge explicitly and is never written',
  async () => {
    // The recorded handshake frames fit the 4096-byte limit (initialize result
    // 591 bytes, session/new result 1587 bytes); the oversized prompt below does
    // not, so the outbound bound must fail the run instead of writing it.
    const bridge = startBridge({ scenario: 'complete', extraArgs: ['--max-frame-bytes', '4096'] });
    const ready = await bridge.ready();
    const sessionId = sessionIdOf(ready);
    await bridge.waitForEvent((event) => event.type === 'session.update');
    const text = 'y'.repeat(6000);
    await bridge.api('POST', '/prompt', { text }).catch(() => null);

    const exited = await Promise.race([bridge.exit, sleep(3000).then(() => null)]);
    expect(exited).toEqual({ code: 65, signal: null });
    expect(bridge.stdoutLines).toHaveLength(2);
    const errorLine = JSON.parse(bridge.stdoutLines[1] ?? '') as Record<string, unknown>;
    const frame = JSON.stringify({
      jsonrpc: '2.0',
      id: 4,
      method: 'session/prompt',
      params: { sessionId, prompt: [{ type: 'text', text }] },
    });
    const bytes = Buffer.byteLength(frame, 'utf8');
    expect(bytes).toBeGreaterThan(4096);
    expect(errorLine).toEqual({
      type: 'bridge.error',
      code: 'E_FRAME_TOO_LARGE',
      message: `Frame of ${bytes} bytes exceeds the 4096-byte limit`,
      limit: 4096,
      bytes,
      direction: 'outbound',
    });
    // The child never saw the oversized frame: the only session update is the
    // handshake's available-commands frame and no prompt stream follows it.
    expect(bridge.events().filter((event) => event.type === 'session.update')).toHaveLength(1);
    expect(bridge.events().filter((event) => event.type === 'prompt.result')).toHaveLength(0);
  },
  30000,
);

test(
  'pause-resume scenario: cooperative cancel is verified, pause/resume are refused with no frames',
  async () => {
    const bridge = startBridge({ scenario: 'pause-resume' });
    const ready = await bridge.ready();
    const sessionId = sessionIdOf(ready);
    const promptText =
      'Run this exact command with the Bash tool: node spawn-writer.mjs ticks.log. Keep streaming until cancelled';
    const prompted = await bridge.api('POST', '/prompt', { text: promptText });
    expect(prompted.status).toBe(200);
    expect(prompted.json).toEqual({ promptId: 4, sessionId, state: 'streaming' });

    const busy = await bridge.api('POST', '/prompt', { text: 'second prompt' });
    expect(busy.status).toBe(409);
    expect(busy.json).toEqual({
      error: { code: 'E_PROMPT_IN_PROGRESS', message: 'A prompt is already in flight (promptId 4)' },
    });

    // The writer fixture is granted with an allow-once decision, which the mock
    // answers by performing the real fixture writes inside the admitted workspace.
    const permission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    expect(permission.toolName).toBe('Bash');
    const allowed = await bridge.api('POST', '/permission-response', {
      requestId: permission.requestId,
      choice: 'ALLOW_ONCE',
    });
    expect(allowed.json.optionId).toBe('proceed_once');
    await bridge.waitForEvent(
      (event) => event.type === 'session.update' && JSON.stringify(event.update).includes('DONE'),
    );
    expect(existsSync(join(bridge.workspace, 'ticks.log'))).toBe(true);
    expect(existsSync(join(bridge.workspace, 'spawn-writer.mjs'))).toBe(true);
    expect(existsSync(join(bridge.workspace, 'writer.mjs'))).toBe(true);

    const health = await bridge.api('GET', '/health', undefined, null);
    expect(pick(health.json as Record<string, unknown>, ['promptInFlight', 'pendingPermissionRequests'])).toEqual({
      promptInFlight: true,
      pendingPermissionRequests: [],
    });

    const pause = await bridge.api('POST', '/control', { action: 'pause' });
    expect(pause.status).toBe(501);
    expect(pause.json).toEqual({
      error: {
        code: 'E_PAUSE_UNSUPPORTED',
        message:
          'pause/resume is not a native ACP method (nativePauseRpcUsed=false): process-level suspension is performed by the owning backend. The bridge exposes cancellation only.',
        action: 'pause',
      },
    });
    const resume = await bridge.api('POST', '/control', { action: 'resume' });
    expect(resume.status).toBe(501);
    expect(resume.json).toEqual({
      error: {
        code: 'E_PAUSE_UNSUPPORTED',
        message:
          'pause/resume is not a native ACP method (nativePauseRpcUsed=false): process-level suspension is performed by the owning backend. The bridge exposes cancellation only.',
        action: 'resume',
      },
    });
    const unsupported = await bridge.api('POST', '/control', { action: 'suspend' });
    expect(unsupported.status).toBe(400);
    expect(unsupported.json).toEqual({
      error: {
        code: 'E_UNSUPPORTED_CONTROL',
        message: 'Unsupported control action: "suspend"',
        action: 'suspend',
      },
    });
    // The peer's own frame log proves the refusals delivered nothing: an
    // out-of-band pause/resume/suspend frame would arrive at the child as an
    // unknown notification and be recorded there.
    expect(diagnosticsOf(bridge).filter((entry) => entry.type === 'peer.unknown_notification')).toEqual([]);

    const cancelled = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(cancelled.status).toBe(200);
    expect(cancelled.json).toEqual({
      action: 'cancel',
      state: 'STOPPED',
      verified: true,
      stopReason: 'cancelled',
      delivery: '1',
      deduplicated: false,
    });
    const repeatedCancel = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(repeatedCancel.status).toBe(200);
    expect(repeatedCancel.json).toEqual({
      action: 'cancel',
      state: 'STOPPED',
      verified: true,
      stopReason: 'cancelled',
      delivery: '1',
      deduplicated: true,
    });

    const notification = await bridge.waitForEvent((event) => event.type === 'acp.notification');
    expect(pick(notification, ['type', 'method', 'params'])).toEqual({
      type: 'acp.notification',
      method: 'session/cancel',
      params: { sessionId },
    });
    expect(bridge.events().filter((event) => event.type === 'acp.notification')).toHaveLength(1);
    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(result.stopReason).toBe('cancelled');
    expect(bridge.events().filter((event) => event.type === 'prompt.error')).toHaveLength(0);
    const finalHealth = await bridge.api('GET', '/health', undefined, null);
    expect(pick(finalHealth.json as Record<string, unknown>, ['state', 'promptInFlight'])).toEqual({
      state: 'ready',
      promptInFlight: false,
    });
  },
  40000,
);

test(
  'cancel-pending scenario: a cancelled request rejects a late ALLOW_ONCE without a write',
  async () => {
    const bridge = startBridge({ scenario: 'cancel-pending' });
    const ready = await bridge.ready();
    const sessionId = sessionIdOf(ready);
    await bridge.api('POST', '/prompt', { text: 'Create probe-cancel-pending.txt with the fixture text' });
    const permission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    expect(permission.requestId).toBe(0);
    expect(permission.toolName).toBe('Write');

    const cancelled = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(cancelled.json).toEqual({
      action: 'cancel',
      state: 'STOPPED',
      verified: true,
      stopReason: 'cancelled',
      delivery: '1',
      deduplicated: false,
    });
    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(result.stopReason).toBe('cancelled');
    const invalidated = await bridge.waitForEvent((event) => event.type === 'permission.cancelled');
    expect(pick(invalidated, ['type', 'requestIds', 'reason'])).toEqual({
      type: 'permission.cancelled',
      requestIds: [0],
      reason: 'prompt-settled',
    });

    const late = await bridge.api('POST', '/permission-response', { requestId: 0, choice: 'ALLOW_ONCE' });
    expect(late.status).toBe(404);
    expect(late.json).toEqual({
      error: {
        code: 'E_UNKNOWN_REQUEST',
        message: 'No pending permission request 0 (status: cancelled)',
        requestId: 0,
        status: 'cancelled',
      },
    });
    expect(bridge.events().filter((event) => event.type === 'permission.response')).toHaveLength(0);
    expect(existsSync(join(bridge.workspace, 'probe-cancel-pending.txt'))).toBe(false);
    expect(diagnosticsOf(bridge)).toContainEqual({
      type: 'peer.cancel',
      sessionId,
      pending: 'permission',
      pendingRequestIds: [0],
    });
  },
  30000,
);

test(
  'permission expiry: an unanswered request is answered fail-closed with the reject option',
  async () => {
    const bridge = startBridge({ scenario: 'deny-write', extraArgs: ['--permission-timeout-ms', '200'] });
    await bridge.ready();
    await bridge.api('POST', '/prompt', { text: 'Overwrite probe-deny.txt with the fixture text' });
    const permission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    const expired = await bridge.waitForEvent((event) => event.type === 'permission.expired');
    expect(pick(expired, ['type', 'requestId', 'optionId', 'raw', 'windowMs'])).toEqual({
      type: 'permission.expired',
      requestId: permission.requestId,
      optionId: 'cancel',
      raw: `{"jsonrpc":"2.0","id":${String(permission.requestId)},"result":{"outcome":{"outcome":"selected","optionId":"cancel"}}}\n`,
      windowMs: 200,
    });
    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(result.stopReason).toBe('end_turn');
    expect(existsSync(join(bridge.workspace, 'probe-deny.txt'))).toBe(false);
    const late = await bridge.api('POST', '/permission-response', {
      requestId: permission.requestId,
      choice: 'ALLOW_ONCE',
    });
    expect(late.status).toBe(404);
    expect(late.json).toEqual({
      error: {
        code: 'E_UNKNOWN_REQUEST',
        message: `No pending permission request ${String(permission.requestId)} (status: expired)`,
        requestId: permission.requestId,
        status: 'expired',
      },
    });
  },
  30000,
);

test(
  'permission-expiry scenario: a request the core expires internally cannot be answered late',
  async () => {
    const bridge = startBridge({ scenario: 'permission-expiry' });
    await bridge.ready();
    await bridge.api('POST', '/prompt', { text: 'Create probe-expiry.txt with the fixture text' });
    const permission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(result.stopReason).toBe('end_turn');
    const late = await bridge.api('POST', '/permission-response', { requestId: permission.requestId, choice: 'DENY' });
    expect(late.status).toBe(404);
    expect(late.json).toEqual({
      error: {
        code: 'E_UNKNOWN_REQUEST',
        message: `No pending permission request ${String(permission.requestId)} (status: cancelled)`,
        requestId: permission.requestId,
        status: 'cancelled',
      },
    });
    expect(bridge.events().filter((event) => event.type === 'permission.response')).toHaveLength(0);
    expect(existsSync(join(bridge.workspace, 'probe-expiry.txt'))).toBe(false);
    expect(diagnosticsOf(bridge)).toContainEqual({
      type: 'peer.permission_expired',
      requestId: permission.requestId,
      windowMs: 300,
    });
  },
  30000,
);

test(
  'prompt failure is not a cancellation: permissions pending when the child dies are expired, never cancelled',
  async () => {
    const bridge = startBridge({ scenario: 'deny-write' });
    await bridge.ready();
    await bridge.api('POST', '/prompt', { text: 'Overwrite probe-deny.txt with the fixture text' });
    const permission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    const health = await bridge.api('GET', '/health', undefined, null);
    const childPid = health.json.childPid as number;
    expect(typeof childPid).toBe('number');
    // Kill the child while its permission request is still pending: the prompt
    // then fails with the child exit, which is not a user cancellation.
    killTree(childPid);
    const exited = await bridge.waitForEvent((event) => event.type === 'child.exit');
    expect(exited.type).toBe('child.exit');
    const promptError = await bridge.waitForEvent((event) => event.type === 'prompt.error');
    expect(pick(promptError, ['type', 'promptId', 'code'])).toEqual({
      type: 'prompt.error',
      promptId: 4,
      code: 'E_CHILD_EXITED',
    });
    const late = await bridge.api('POST', '/permission-response', {
      requestId: permission.requestId,
      choice: 'ALLOW_ONCE',
    });
    expect(late.status).toBe(404);
    expect(late.json).toEqual({
      error: {
        code: 'E_UNKNOWN_REQUEST',
        message: `No pending permission request ${String(permission.requestId)} (status: expired)`,
        requestId: permission.requestId,
        status: 'expired',
      },
    });
    // A failed prompt never reports its leftover permissions as cancelled.
    expect(bridge.events().filter((event) => event.type === 'permission.cancelled')).toHaveLength(0);
    expect(existsSync(join(bridge.workspace, 'probe-deny.txt'))).toBe(false);
  },
  30000,
);

test(
  'disconnect scenario: the child exit is reported honestly and no prompt result is fabricated',
  async () => {
    const bridge = startBridge({ scenario: 'disconnect' });
    await bridge.ready();
    await bridge.api('POST', '/prompt', { text: 'Reply with the fixture acknowledgment' });
    const exited = await bridge.waitForEvent((event) => event.type === 'child.exit');
    expect(pick(exited, ['type', 'code', 'signal', 'message'])).toEqual({
      type: 'child.exit',
      code: 7,
      signal: null,
      message: 'Qoder child exited with code 7',
    });
    const promptError = await bridge.waitForEvent((event) => event.type === 'prompt.error');
    expect(pick(promptError, ['type', 'promptId', 'code', 'message', 'details'])).toEqual({
      type: 'prompt.error',
      promptId: 4,
      code: 'E_CHILD_EXITED',
      message: 'Qoder child exited with code 7',
      details: { code: 7, signal: null },
    });
    expect(bridge.events().filter((event) => event.type === 'prompt.result')).toHaveLength(0);

    const health = await bridge.api('GET', '/health', undefined, null);
    expect(pick(health.json as Record<string, unknown>, ['state', 'childAlive', 'promptInFlight'])).toEqual({
      state: 'exited',
      childAlive: false,
      promptInFlight: false,
    });
    const prompt = await bridge.api('POST', '/prompt', { text: 'again' });
    expect(prompt.status).toBe(409);
    expect(prompt.json).toEqual({
      error: { code: 'E_RUNTIME_EXITED', message: 'The run-owned Qoder runtime has exited' },
    });
    const session = await bridge.api('GET', '/session');
    expect(session.status).toBe(409);
    expect(session.json).toEqual({
      error: { code: 'E_RUNTIME_EXITED', message: 'The run-owned Qoder runtime has exited' },
    });
    // The two scripted thought chunks still arrived before the disconnect.
    const updates = bridge
      .events()
      .filter((event) => event.type === 'session.update')
      .map((event) => event.update as Record<string, unknown>);
    expect(updates.slice(-2)).toEqual([
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'connection' } },
      { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: ' lost' } },
    ]);
  },
  30000,
);

test(
  'event replay: bounded buffering produces exact sequences and an explicit gap refusal',
  async () => {
    const bridge = startBridge({ scenario: 'complete', extraArgs: ['--event-buffer-size', '8'] });
    await bridge.ready();
    await bridge.api('POST', '/prompt', { text: 'Reply with exactly the single word: pong' });
    await bridge.waitForEvent((event) => event.type === 'prompt.result');
    await sleep(150);

    const gap = await bridge.api('GET', '/events?after=0');
    expect(gap.status).toBe(409);
    const gapError = gap.json.error as Record<string, unknown>;
    expect(gapError.requestedAfter).toBe(0);
    expect(typeof gapError.retainedFrom).toBe('number');
    expect(typeof gapError.lastSeq).toBe('number');
    expect(pick(gapError, ['code', 'message'])).toEqual({
      code: 'E_REPLAY_GAP',
      message: `Event 1 was evicted; retained events start at ${String(gapError.retainedFrom)}`,
    });

    const lastSeq = gapError.lastSeq as number;
    const tail = await bridge.api('GET', `/events?after=${String(lastSeq - 3)}`);
    expect(tail.status).toBe(200);
    const tailEvents = tail.json.events as BridgeEvent[];
    expect(tailEvents.map((event) => event.seq)).toEqual([lastSeq - 2, lastSeq - 1, lastSeq]);
    expect(tail.json.retainedFrom).toBe(gapError.retainedFrom);
    const ahead = await bridge.api('GET', `/events?after=${String(lastSeq + 5)}`);
    expect(ahead.status).toBe(409);
    expect(pick(ahead.json.error as Record<string, unknown>, ['code', 'requestedAfter', 'lastSeq'])).toEqual({
      code: 'E_REPLAY_AHEAD',
      requestedAfter: lastSeq + 5,
      lastSeq,
    });

    // SSE follow mode replays the same exact frames with `id:` lines.
    const controller = new AbortController();
    const firstExpected = tailEvents[0] as BridgeEvent;
    const stream = await fetch(
      `${bridge.endpoint}/events?after=${String(firstExpected.seq - 1)}&follow=1`,
      { headers: { [CONTROL_SECRET_HEADER]: bridge.secret }, signal: controller.signal },
    );
    expect(stream.status).toBe(200);
    expect(stream.headers.get('content-type')).toBe('text/event-stream');
    const reader = stream.body?.getReader();
    const decoder = new TextDecoder();
    let text = '';
    while (!text.includes('\n\n')) {
      const chunk = await reader?.read();
      if (chunk === undefined || chunk.done) break;
      text += decoder.decode(chunk.value, { stream: true });
    }
    controller.abort();
    expect(text.split('\n\n')[0]).toBe(`id: ${String(firstExpected.seq)}\ndata: ${JSON.stringify(firstExpected)}`);
  },
  30000,
);

test(
  'SSE backpressure: a consumer that stops reading is closed instead of buffering without bound',
  async () => {
    const events = new EventBuffer({ capacity: 100, maxBytes: 1 << 20 });
    const server = createBridgeServer({
      secret: 'backpressure-control-secret',
      events,
      maxSseBacklogBytes: 4096,
      handlers: {
        health: () => ({ status: 'ok' }),
        sessionBinding: () => {
          throw new Error('unused in this test');
        },
        prompt: () => {
          throw new Error('unused in this test');
        },
        control: async () => {
          throw new Error('unused in this test');
        },
        permissionResponse: async () => {
          throw new Error('unused in this test');
        },
      },
    });
    await new Promise<void>((resolve) => server.listen({ host: '127.0.0.1', port: 0 }, () => resolve()));
    try {
      const address = server.address();
      const port = typeof address === 'object' && address !== null ? address.port : 0;
      const response = await new Promise<IncomingMessage>((resolveListener, rejectListener) => {
        const request = httpRequest(
          {
            host: '127.0.0.1',
            port,
            path: '/events?after=0&follow=1',
            headers: { [CONTROL_SECRET_HEADER]: 'backpressure-control-secret' },
          },
          (incoming) => resolveListener(incoming),
        );
        request.on('error', rejectListener);
        request.end();
      });
      expect(response.statusCode).toBe(200);
      // The consumer never reads, so the socket stays paused and the server's
      // writes stop draining; the bounded backlog must close this stream instead
      // of retaining events without limit.
      const closed = new Promise<boolean>((resolveClosed) => {
        response.on('close', () => resolveClosed(true));
        response.on('error', () => resolveClosed(true));
      });
      for (let index = 0; index < 40; index += 1) {
        events.push('flood.event', { index, blob: 'z'.repeat(1024) });
      }
      expect(await Promise.race([closed, sleep(2000).then(() => false)])).toBe(true);
      // A consumer that cannot keep up must not take the bridge down with it.
      const health = await fetch(`http://127.0.0.1:${String(port)}/health`);
      expect(health.status).toBe(200);
      expect(await health.json()).toEqual({ status: 'ok' });
    } finally {
      server.closeAllConnections();
      await new Promise<void>((resolveClose) => server.close(() => resolveClose()));
    }
  },
  15000,
);

test(
  'secret destinations: the worker MCP token never reaches the child environment and is redacted',
  async () => {
    const workerToken = `worker-token-${randomBytes(16).toString('hex')}`;
    const credential = `qoder-pat-${randomBytes(16).toString('hex')}`;
    const childEnv = buildChildEnvironment({
      platform: 'win32',
      base: {
        SystemRoot: 'C:\\Windows',
        QODER_BRIDGE_CONTROL_SECRET: 'the-bridge-control-secret-value',
        QODER_PERSONAL_ACCESS_TOKEN: 'ambient-personal-token',
        PATH: 'C:\\Windows\\System32',
      },
      childEnv: { ARIA_PEER_CONTROL_TOKEN: 'peer-token-0123456789', ARIA_PEER_SCENARIO: 'complete' },
      credential: { name: 'QODER_PERSONAL_ACCESS_TOKEN', value: credential },
    });
    expect(childEnv).toEqual({
      SystemRoot: 'C:\\Windows',
      PATH: 'C:\\Windows\\System32',
      ARIA_PEER_CONTROL_TOKEN: 'peer-token-0123456789',
      ARIA_PEER_SCENARIO: 'complete',
      QODER_PERSONAL_ACCESS_TOKEN: credential,
    });
    expect(childSpawnOptions({ executable: 'C:\\pinned\\qodercli.exe', args: ['--acp'], cwd: 'C:\\run\\ws', env: childEnv })).toEqual({
      cwd: 'C:\\run\\ws',
      env: childEnv,
      shell: false,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    const bridge = startBridge({
      scenario: 'complete',
      credential,
      worker: { url: 'http://127.0.0.1:9411/mcp', token: workerToken },
    });
    const ready = await bridge.ready();
    const sessionId = sessionIdOf(ready);
    const sessionNew = await bridge.waitForEvent(
      (event) => event.type === 'acp.request' && event.method === 'session/new',
    );
    expect(sessionNew.params).toEqual({
      cwd: bridge.workspace,
      mcpServers: [
        {
          name: 'aria-worker',
          type: 'http',
          url: 'http://127.0.0.1:9411/mcp',
          // The pinned CLI's ACP schema requires an ARRAY of {name, value} pairs;
          // an object map is rejected with -32602 (e2e/qoder/slice-a/03-mcp-auth.md).
          headers: [{ name: 'Authorization', value: `Bearer ${REDACTED}` }],
        },
      ],
    });
    await bridge.api('POST', '/prompt', { text: 'Reply with exactly the single word: pong' });
    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(result.stopReason).toBe('end_turn');
    expect(sessionId).toBe(ready.sessionId);

    const surfaced = `${bridge.stdoutLines.join('\n')}\n${bridge.stderrText()}\n${JSON.stringify(bridge.events())}`;
    expect(surfaced).not.toContain(workerToken);
    expect(surfaced).not.toContain(credential);
    expect(surfaced).not.toContain(bridge.secret);
    expect(surfaced).not.toContain('ambient-personal-token');
  },
  30000,
);

test(
  'child environment: platform-essential PATH reaches the spawned child, ambient hostile variables never do',
  () => {
    const root = mkdtempSync(join(tmpdir(), 'aria-child-env-'));
    roots.push(root);
    const ambientCredential = `ambient-token-${randomBytes(8).toString('hex')}`;
    const base: NodeJS.ProcessEnv = {
      ...process.env,
      NODE_OPTIONS: '--require=/nonexistent-ambient-hostile-hook.js',
      QODER_PERSONAL_ACCESS_TOKEN: ambientCredential,
      QODER_BRIDGE_CONTROL_SECRET: 'ambient-control-secret-value',
      ARIA_HOST_SDK_ENDPOINT: 'http://evil.invalid/sdk',
    };
    const childEnv = buildChildEnvironment({
      platform: process.platform,
      base,
      childEnv: { ARIA_PEER_SCENARIO: 'complete' },
    });
    const expectedKeys = process.platform === 'win32' ? ['PATH', 'SystemRoot'] : ['PATH'];
    expect(Object.keys(childEnv).sort()).toEqual([...expectedKeys, 'ARIA_PEER_SCENARIO'].sort());
    expect(childEnv.PATH).toBe(process.env.PATH);
    // Every ambient, credential-shaped and SDK-entrypoint variable stays out.
    for (const excluded of [
      'NODE_OPTIONS',
      'QODER_PERSONAL_ACCESS_TOKEN',
      'QODER_BRIDGE_CONTROL_SECRET',
      'ARIA_HOST_SDK_ENDPOINT',
    ]) {
      expect(childEnv[excluded]).toBeUndefined();
    }
    // Windows exposes PATH with unpredictable casing in the real process.env.
    expect(
      buildChildEnvironment({ platform: 'win32', base: { Path: 'C:\\Windows\\System32' }, childEnv: {} }),
    ).toEqual({ PATH: 'C:\\Windows\\System32' });
    // POSIX gets PATH and nothing else ambient (SystemRoot is Windows-only).
    expect(
      buildChildEnvironment({
        platform: 'linux',
        base: { PATH: '/usr/bin:/bin', HOME: '/root', SystemRoot: 'ignored' },
        childEnv: {},
      }),
    ).toEqual({ PATH: '/usr/bin:/bin' });

    // A real spawn through the bridge's own spawn options proves PATH is visible
    // to the child (an env-shebang wrapper needs it) while the hostile and
    // credential-shaped ambient variables are not.
    const probe = spawnSync(process.execPath, ['-e', 'process.stdout.write(JSON.stringify(process.env))'], {
      ...childSpawnOptions({ executable: process.execPath, args: [], cwd: root, env: childEnv }),
      encoding: 'utf8',
    });
    expect(probe.status).toBe(0);
    const observed = JSON.parse(probe.stdout) as Record<string, string>;
    expect(observed.PATH).toBe(process.env.PATH);
    expect(observed.ARIA_PEER_SCENARIO).toBe('complete');
    expect(observed.NODE_OPTIONS).toBeUndefined();
    expect(observed.QODER_PERSONAL_ACCESS_TOKEN).toBeUndefined();
    expect(observed.QODER_BRIDGE_CONTROL_SECRET).toBeUndefined();
    expect(observed.ARIA_HOST_SDK_ENDPOINT).toBeUndefined();
    expect(JSON.stringify(observed)).not.toContain(ambientCredential);
  },
  15000,
);

test(
  'launch failure redaction: the out-of-band stderr path redacts exactly like every other output path',
  async () => {
    const workerToken = `worker-token-${randomBytes(16).toString('hex')}`;
    const secretRoot = mkdtempSync(join(tmpdir(), 'aria-stderr-redaction-'));
    roots.push(secretRoot);
    const credentialFile = join(secretRoot, 'credential.secret');
    writeFileSync(credentialFile, 'credential-value-not-used-by-the-launch', 'utf8');
    const bridge = startBridge({
      scenario: 'complete',
      worker: { url: 'http://127.0.0.1:9411/mcp', token: workerToken },
      // An invalid credential environment name fails the launch after the
      // secrets were read; that failure is printed by the out-of-band
      // main().catch path and must be redacted like every other output path.
      extraArgs: ['--credential-env', workerToken, '--credential-file', credentialFile],
    });
    const exited = await bridge.exit;
    expect(exited).toEqual({ code: 78, signal: null });
    expect(bridge.stdoutLines).toHaveLength(0);
    const stderrLines = bridge.stderrText().trim().split('\n');
    expect(stderrLines).toHaveLength(1);
    expect(JSON.parse(stderrLines[0] ?? '')).toEqual({
      type: 'bridge.error',
      code: 'E_CONFIG',
      message: `Invalid credential environment name: ${REDACTED}`,
    });
    expect(bridge.stderrText()).not.toContain(workerToken);
  },
  15000,
);

test(
  'event buffer: capacity is enforced, retainedFrom advances and the replay gap is explicit',
  () => {
    const buffer = new EventBuffer({
      capacity: 3,
      secrets: ['super-secret-value'],
      now: () => '2026-09-23T00:00:00.000Z',
    });
    for (let index = 1; index <= 5; index += 1) buffer.push('test.event', { index, note: 'super-secret-value' });
    expect(buffer.lastSeq()).toBe(5);
    expect(buffer.retainedFrom()).toBe(3);
    expect(buffer.since(0)).toEqual({ ok: false, reason: 'gap', requestedAfter: 0, retainedFrom: 3, lastSeq: 5 });
    expect(buffer.since(2)).toEqual({
      ok: true,
      events: [
        { seq: 3, at: '2026-09-23T00:00:00.000Z', type: 'test.event', index: 3, note: REDACTED },
        { seq: 4, at: '2026-09-23T00:00:00.000Z', type: 'test.event', index: 4, note: REDACTED },
        { seq: 5, at: '2026-09-23T00:00:00.000Z', type: 'test.event', index: 5, note: REDACTED },
      ],
      lastSeq: 5,
      retainedFrom: 3,
    });
    expect(buffer.since(6)).toEqual({ ok: false, reason: 'ahead', requestedAfter: 6, lastSeq: 5, retainedFrom: 3 });
    expect(buffer.since(-1)).toEqual({ ok: false, reason: 'invalid', requestedAfter: -1, lastSeq: 5, retainedFrom: 3 });

    const byteBounded = new EventBuffer({ capacity: 100, maxBytes: 300, now: () => '2026-09-23T00:00:00.000Z' });
    for (let index = 1; index <= 5; index += 1) byteBounded.push('test.event', { index, blob: 'y'.repeat(100) });
    // Each event serializes to 181 bytes; two of them exceed the 300-byte
    // budget, so only the newest event is retained.
    expect(byteBounded.lastSeq()).toBe(5);
    expect(byteBounded.retainedFrom()).toBe(5);
    expect(byteBounded.since(4)).toEqual({
      ok: true,
      events: [{ seq: 5, at: '2026-09-23T00:00:00.000Z', type: 'test.event', index: 5, blob: 'y'.repeat(100) }],
      lastSeq: 5,
      retainedFrom: 5,
    });
    const oversized = new EventBuffer({ capacity: 100, maxBytes: 100, now: () => '2026-09-23T00:00:00.000Z' });
    oversized.push('test.event', { blob: 'y'.repeat(400) });
    // A single event above the budget is still retained so a consumer always has
    // one recovery point instead of an empty stream.
    expect(oversized.lastSeq()).toBe(1);
    expect(oversized.retainedFrom()).toBe(1);
  },
  10000,
);

test(
  'two prompts in one bridge: the second prompt is cancelled for real, never from the first prompt record',
  async () => {
    const bridge = startBridge({ scenario: 'pause-resume' });
    const ready = await bridge.ready();
    const sessionId = sessionIdOf(ready);
    const promptText =
      'Run this exact command with the Bash tool: node spawn-writer.mjs ticks.log. Keep streaming until cancelled';

    // First prompt: granted writer fixture, then a verified cancellation.
    const firstPrompted = await bridge.api('POST', '/prompt', { text: promptText });
    expect(firstPrompted.status).toBe(200);
    expect(firstPrompted.json).toEqual({ promptId: 4, sessionId, state: 'streaming' });
    const firstPermission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    expect(firstPermission.requestId).toBe(0);
    const firstAllow = await bridge.api('POST', '/permission-response', { requestId: 0, choice: 'ALLOW_ONCE' });
    expect(firstAllow.json.optionId).toBe('proceed_once');
    await bridge.waitForEvent(
      (event) => event.type === 'session.update' && JSON.stringify(event.update).includes('DONE'),
    );
    const firstCancel = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(firstCancel.status).toBe(200);
    expect(firstCancel.json).toEqual({
      action: 'cancel',
      state: 'STOPPED',
      verified: true,
      stopReason: 'cancelled',
      delivery: '1',
      deduplicated: false,
    });
    const firstResult = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(pick(firstResult, ['promptId', 'stopReason'])).toEqual({ promptId: 4, stopReason: 'cancelled' });

    // Second prompt in the same bridge process: same child, same session, and it
    // is genuinely still streaming when its own cancel arrives.
    const secondPrompted = await bridge.api('POST', '/prompt', { text: promptText });
    expect(secondPrompted.status).toBe(200);
    expect(secondPrompted.json).toEqual({ promptId: 5, sessionId, state: 'streaming' });
    const secondPermission = await bridge.waitForEvent(
      (event) => event.type === 'permission.request' && event.requestId === 1,
    );
    expect(secondPermission.requestId).toBe(1);
    const secondAllow = await bridge.api('POST', '/permission-response', { requestId: 1, choice: 'ALLOW_ONCE' });
    expect(secondAllow.json.optionId).toBe('proceed_once');
    // The second prompt's own streaming marker (and no second prompt.result yet)
    // proves the prompt was live and unsettled when the cancel below was issued.
    await bridge.waitForEvent(
      (event) =>
        event.type === 'session.update' &&
        event.seq > firstResult.seq &&
        JSON.stringify(event.update).includes('DONE'),
    );
    expect(
      bridge
        .events()
        .filter((event) => event.type === 'prompt.result')
        .map((event) => event.promptId),
    ).toEqual([4]);

    const secondCancel = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(secondCancel.status).toBe(200);
    expect(secondCancel.json).toEqual({
      action: 'cancel',
      state: 'STOPPED',
      verified: true,
      stopReason: 'cancelled',
      delivery: '2',
      deduplicated: false,
    });
    const secondResult = await bridge.waitForEvent(
      (event) => event.type === 'prompt.result' && event.promptId === 5,
    );
    expect(secondResult.stopReason).toBe('cancelled');

    // The child received both cancels: two outbound notifications and two
    // `peer.cancel` diagnostics, one per prompt.
    expect(
      bridge
        .events()
        .filter((event) => event.type === 'acp.notification')
        .map((event) => pick(event, ['method', 'params'])),
    ).toEqual([
      { method: 'session/cancel', params: { sessionId } },
      { method: 'session/cancel', params: { sessionId } },
    ]);
    await bridge.waitForEvent(
      () => diagnosticsOf(bridge).filter((entry) => entry.type === 'peer.cancel').length === 2,
    );
    expect(diagnosticsOf(bridge).filter((entry) => entry.type === 'peer.cancel')).toEqual([
      { type: 'peer.cancel', sessionId, pending: null, pendingRequestIds: [] },
      { type: 'peer.cancel', sessionId, pending: null, pendingRequestIds: [] },
    ]);
  },
  40000,
);

test(
  'idle cancel: a cancel with no prompt in flight is never recorded against the next prompt',
  async () => {
    const bridge = startBridge({ scenario: 'pause-resume' });
    const ready = await bridge.ready();
    const sessionId = sessionIdOf(ready);

    const idle = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(idle.status).toBe(200);
    expect(idle.json).toEqual({
      action: 'cancel',
      state: 'RUNNING',
      verified: false,
      reason: 'no-prompt-in-flight',
      delivery: '1',
      deduplicated: false,
    });
    // Nothing reached the child: an idle cancel is an observation, not a delivery.
    expect(bridge.events().filter((event) => event.type === 'acp.notification')).toHaveLength(0);

    const prompted = await bridge.api('POST', '/prompt', {
      text: 'Run this exact command with the Bash tool: node spawn-writer.mjs ticks.log. Keep streaming until cancelled',
    });
    expect(prompted.status).toBe(200);
    expect(prompted.json).toEqual({ promptId: 4, sessionId, state: 'streaming' });
    const permission = await bridge.waitForEvent((event) => event.type === 'permission.request');
    expect(permission.requestId).toBe(0);
    await bridge.api('POST', '/permission-response', { requestId: 0, choice: 'ALLOW_ONCE' });
    await bridge.waitForEvent(
      (event) => event.type === 'session.update' && JSON.stringify(event.update).includes('DONE'),
    );

    // The idle cancel above must not be replayed: this prompt gets a real
    // delivery with its own counter value.
    const cancelled = await bridge.api('POST', '/control', { action: 'cancel' });
    expect(cancelled.status).toBe(200);
    expect(cancelled.json).toEqual({
      action: 'cancel',
      state: 'STOPPED',
      verified: true,
      stopReason: 'cancelled',
      delivery: '2',
      deduplicated: false,
    });
    const result = await bridge.waitForEvent((event) => event.type === 'prompt.result');
    expect(pick(result, ['promptId', 'stopReason'])).toEqual({ promptId: 4, stopReason: 'cancelled' });
    expect(bridge.events().filter((event) => event.type === 'acp.notification')).toHaveLength(1);
  },
  40000,
);

test(
  'route framing: unknown routes, wrong methods and oversized bodies fail explicitly',
  async () => {
    const bridge = startBridge({ scenario: 'complete' });
    await bridge.ready();
    const notFound = await bridge.api('GET', '/nope');
    expect(notFound.status).toBe(404);
    expect(notFound.json).toEqual({ error: { code: 'E_NOT_FOUND', message: 'Unknown route: GET /nope' } });
    const wrongMethod = await bridge.api('GET', '/prompt');
    expect(wrongMethod.status).toBe(405);
    expect(wrongMethod.json).toEqual({
      error: { code: 'E_METHOD_NOT_ALLOWED', message: 'GET is not allowed on /prompt' },
    });
    const badJson = await fetch(`${bridge.endpoint}/control`, {
      method: 'POST',
      headers: { [CONTROL_SECRET_HEADER]: bridge.secret, 'content-type': 'application/json' },
      body: '{not json',
    });
    expect(badJson.status).toBe(400);
    const badJsonError = ((await badJson.json()) as Record<string, unknown>).error as Record<string, unknown>;
    expect(badJsonError.code).toBe('E_BAD_JSON');
    expect(String(badJsonError.message)).toMatch(/^Request body is not valid JSON: /);
    const badPrompt = await bridge.api('POST', '/prompt', { text: '   ' });
    expect(badPrompt.status).toBe(400);
    expect(badPrompt.json).toEqual({
      error: { code: 'E_BAD_REQUEST', message: 'Prompt body requires a non-empty "text" string' },
    });
    const huge = await fetch(`${bridge.endpoint}/control`, {
      method: 'POST',
      headers: { [CONTROL_SECRET_HEADER]: bridge.secret, 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'cancel', padding: 'x'.repeat(70000) }),
    });
    expect(huge.status).toBe(413);
    expect((await huge.json()) as Record<string, unknown>).toEqual({
      error: { code: 'E_BODY_TOO_LARGE', message: 'Request body exceeds the 65536-byte limit' },
    });
  },
  30000,
);
