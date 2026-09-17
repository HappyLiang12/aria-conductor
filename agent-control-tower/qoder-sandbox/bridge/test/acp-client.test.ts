/**
 * B3a vitest suite: drives `AcpClient` against the committed fake CLI
 * (`test/fixtures/fake-qodercli.mjs`) and pins the protocol facts observed in Slice A
 * (A3 handshake/MCP config, A4 permission semantics + cancel decision, A5 model
 * attestation), i.e. the frozen contracts C0.3 (ACP call sequence) and C0.4
 * (permission option selection) from docs/superpowers/plans/2026-09-17-qoder-cli-provider.md.
 *
 * All credentials here are synthetic (`test-worker-token`); the fixture is spawned as
 * `node <fixture> <scenario>` through `process.execPath` so the suite passes on
 * Git Bash/Windows as well as Linux.
 */
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';

import {
  AcpClient,
  AcpClosedError,
  AcpError,
  AcpRpcError,
  CANCEL_METHOD_DECISION,
  DEFAULT_ARGS,
  DEFAULT_COMMAND,
  DEFAULT_CWD,
  PermissionAlreadyResolvedError,
  UnknownPermissionRequestError,
  UnsupportedOptionsError,
  resolveSpawnPlan,
  type AcpClientOptions,
  type AcpEvent,
  type SessionSpec,
} from '../src/acp-client.js';
import {
  CHILD_ENV_ALLOWLIST,
  FORBIDDEN_CHILD_ENV_KEYS,
  assertAllowlistDisjoint,
  buildChildEnv,
  forbiddenKeysPresent,
} from '../src/env.js';

const FIXTURE = fileURLToPath(new URL('./fixtures/fake-qodercli.mjs', import.meta.url));
const SYNTHETIC_PAT = 'test-worker-token';
const MODEL = 'efficient';
const PROMPT = 'Reply with exactly: ok';
const OPTION_MENU = [
  { optionId: 'zz_always', name: 'Allow for this session', kind: 'allow_always' },
  { optionId: 'zz_once', name: 'Allow', kind: 'allow_once' },
  { optionId: 'zz_reject', name: 'Reject', kind: 'reject_once' },
];

type EventOf<T extends AcpEvent['type']> = Extract<AcpEvent, { type: T }>;

class EventLog {
  readonly events: AcpEvent[] = [];

  private readonly waiters: Array<{ match: (event: AcpEvent) => boolean; resolve: (event: AcpEvent) => void }> = [];

  push(event: AcpEvent): void {
    this.events.push(event);
    for (const waiter of [...this.waiters]) {
      if (waiter.match(event)) {
        this.waiters.splice(this.waiters.indexOf(waiter), 1);
        waiter.resolve(event);
      }
    }
  }

  /** Wait for the first event of `type` (optionally matching `extra`); also matches past events. */
  async waitFor<T extends AcpEvent['type']>(
    type: T,
    extra?: (event: EventOf<T>) => boolean,
    timeoutMs = 10_000,
  ): Promise<EventOf<T>> {
    const match = (event: AcpEvent): event is EventOf<T> =>
      event.type === type && (extra ? extra(event as EventOf<T>) : true);
    const existing = this.events.find(match);
    if (existing) {
      return existing;
    }
    return new Promise<EventOf<T>>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`timed out after ${timeoutMs} ms waiting for ACP event '${type}'`));
      }, timeoutMs);
      this.waiters.push({
        match,
        resolve: event => {
          clearTimeout(timer);
          resolve(event as EventOf<T>);
        },
      });
    });
  }

  /** Raw lines the fixture received from the client, in order (the outward evidence). */
  received(): Array<Record<string, unknown>> {
    return this.events
      .filter((event): event is EventOf<'notification'> => event.type === 'notification' && event.method === 'fixture/recv')
      .map(event => JSON.parse(String((event.params as { raw?: unknown } | undefined)?.raw ?? '{}')) as Record<string, unknown>);
  }

  /** The fixture's startup report (argv, env key names, direct-child pid evidence). */
  hello(): Promise<{
    scenario: string;
    argv: string[];
    pid: number;
    ppid: number;
    cwd: string;
    envKeys: string[];
    tokenPresent: boolean;
  }> {
    return this.waitFor('notification', event => event.method === 'fixture/hello').then(
      event => event.params as never,
    );
  }

  /** The fixture's scenario completion report. */
  done(): Promise<{ scenario: string; note: string; facts: Record<string, unknown> }> {
    return this.waitFor('notification', event => event.method === 'fixture/done').then(
      event => event.params as never,
    );
  }
}

const createdDirs: string[] = [];
const createdClients: AcpClient[] = [];

function workspace(): string {
  const dir = mkdtempSync(join(tmpdir(), 'b3a-'));
  createdDirs.push(dir);
  return dir;
}

function sessionSpec(dir: string): SessionSpec {
  return {
    cwd: dir,
    modelId: MODEL,
    mcpServers: [
      {
        name: 'aria-stub',
        url: 'http://127.0.0.1:9/mcp',
        headers: [{ name: 'Authorization', value: `Bearer ${SYNTHETIC_PAT}` }],
      },
    ],
  };
}

function start(scenario: string, overrides: Partial<AcpClientOptions> = {}) {
  const dir = workspace();
  const client = new AcpClient({
    command: process.execPath,
    args: [FIXTURE, scenario],
    cwd: dir,
    env: {
      PATH: process.env.PATH,
      HOME: process.env.HOME ?? 'C:/b3a-home',
      TERM: 'xterm',
      QODER_PERSONAL_ACCESS_TOKEN: SYNTHETIC_PAT,
    },
    killGraceMs: 500,
    ...overrides,
  });
  createdClients.push(client);
  const log = new EventLog();
  client.onEvent(event => log.push(event));
  return { client, log, dir };
}

afterEach(async () => {
  for (const client of createdClients.splice(0)) {
    try {
      client.close();
    } catch {
      /* already dead */
    }
  }
  await new Promise(resolve => setTimeout(resolve, 100));
  for (const dir of createdDirs.splice(0)) {
    try {
      rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
    } catch {
      /* a just-killed child may still hold its cwd on Windows */
    }
  }
});

describe('production spawn plan (C0.3 step 1)', () => {
  it('spawns `qodercli` with argv exactly ["--acp"], cwd /workspace, no shell', () => {
    expect(DEFAULT_COMMAND).toBe('qodercli');
    expect(DEFAULT_ARGS).toEqual(['--acp']);
    expect(DEFAULT_CWD).toBe('/workspace');

    const plan = resolveSpawnPlan({});
    expect(plan.command).toBe('qodercli');
    expect(plan.args).toEqual(['--acp']);
    expect(plan.cwd).toBe('/workspace');
    expect(plan.shell).toBe(false);
    expect(Object.keys(plan.env).every(key => (CHILD_ENV_ALLOWLIST as readonly string[]).includes(key))).toBe(true);
  });

  it('keeps the spawn plan injectable for the fixture (command/args/cwd overrides)', () => {
    const plan = resolveSpawnPlan({ command: 'node', args: ['fixture.mjs', 'happy'], cwd: '/tmp/ws' });
    expect(plan.command).toBe('node');
    expect(plan.args).toEqual(['fixture.mjs', 'happy']);
    expect(plan.cwd).toBe('/tmp/ws');
    expect(plan.shell).toBe(false);
  });
});

describe('environment allowlist (plan Step 3)', () => {
  it('forwards only allowlisted keys and drops other providers credentials and DB keys', () => {
    const child = buildChildEnv({
      PATH: '/usr/local/bin:/usr/bin',
      HOME: '/root',
      LANG: 'C.UTF-8',
      TERM: 'xterm',
      QODER_PERSONAL_ACCESS_TOKEN: SYNTHETIC_PAT,
      DEEPSEEK_API_KEY: 'synthetic-deepseek-key',
      LLM_API_KEY: 'synthetic-llm-key',
      OPENAI_API_KEY: 'synthetic-openai-key',
      ANTHROPIC_API_KEY: 'synthetic-anthropic-key',
      DATABASE_URL: 'jdbc:postgresql://localhost:5432/aria',
      SPRING_DATASOURCE_PASSWORD: 'synthetic-db-password',
      QODER_SDK_ACCESS_TOKEN: 'synthetic-sdk-token',
      QODER_AGENT_SDK_ENTRYPOINT: '1',
      NODE_OPTIONS: '--inspect',
    });
    expect(child).toEqual({
      PATH: '/usr/local/bin:/usr/bin',
      HOME: '/root',
      LANG: 'C.UTF-8',
      TERM: 'xterm',
      QODER_PERSONAL_ACCESS_TOKEN: SYNTHETIC_PAT,
    });
    expect(forbiddenKeysPresent(child)).toEqual([]);
  });

  it('fails closed if the allowlist and the forbidden list ever intersect', () => {
    const shipped = [...CHILD_ENV_ALLOWLIST, ...FORBIDDEN_CHILD_ENV_KEYS];
    expect(new Set(shipped).size).toBe(shipped.length);
    expect(() => assertAllowlistDisjoint(['PATH', 'DEEPSEEK_API_KEY'], FORBIDDEN_CHILD_ENV_KEYS)).toThrowError(
      /DEEPSEEK_API_KEY/,
    );
  });

  it('the spawned CLI child never sees another provider credential (fixture-observed env)', async () => {
    const { log } = start('happy', {
      env: {
        PATH: process.env.PATH,
        HOME: '/tmp/b3a-home',
        LANG: 'C.UTF-8',
        TERM: 'xterm',
        QODER_PERSONAL_ACCESS_TOKEN: SYNTHETIC_PAT,
        DEEPSEEK_API_KEY: 'synthetic-deepseek-key',
        LLM_API_KEY: 'synthetic-llm-key',
        OPENAI_API_KEY: 'synthetic-openai-key',
        SPRING_DATASOURCE_PASSWORD: 'synthetic-db-password',
        DATABASE_URL: 'jdbc:postgresql://localhost:5432/aria',
        QODER_SDK_ACCESS_TOKEN: 'synthetic-sdk-token',
      },
    });
    const hello = await log.hello();
    for (const key of [
      'DEEPSEEK_API_KEY',
      'LLM_API_KEY',
      'OPENAI_API_KEY',
      'SPRING_DATASOURCE_PASSWORD',
      'DATABASE_URL',
      'QODER_SDK_ACCESS_TOKEN',
    ]) {
      expect(hello.envKeys).not.toContain(key);
    }
    expect(hello.envKeys).toContain('PATH');
    expect(hello.envKeys).toContain('HOME');
    expect(hello.envKeys).toContain('QODER_PERSONAL_ACCESS_TOKEN');
    expect(hello.tokenPresent).toBe(true);
    // Direct child, no shell in between: the fixture is `node <fixture> <scenario>`,
    // reports the spawned argv verbatim and its parent is this process.
    expect(hello.argv).toEqual([FIXTURE, 'happy']);
    expect(hello.ppid).toBe(process.pid);
  });
});

describe('C0.3 ACP call sequence against the fake CLI', () => {
  it('initialize -> session/new -> set_model -> prompt, then a kind-selected nested permission reply', async () => {
    const { client, log, dir } = start('happy');
    const created = await client.createSession(sessionSpec(dir));
    expect(created).toEqual({ sessionId: 'sess-1' });

    client.prompt('sess-1', PROMPT);
    const permission = await log.waitFor('permission_request');
    // A4 id-collision: the CLI's own request id equals our pending prompt request id;
    // dispatch must key on "has `method`?", never on the numeric id.
    expect(permission.requestId).toBe('4');
    expect(permission.toolName).toBe('Write');
    expect(permission.toolCallId).toBe('call_1');
    expect(permission.title).toBeNull();
    expect(permission.options).toEqual(OPTION_MENU);

    const decision = client.decide(permission.requestId, true);
    expect(decision).toEqual({ outcome: 'selected', optionId: 'zz_once' });
    expect(permission.options.map(option => option.kind)).toContain('allow_always');

    const done = await log.done();
    expect(done.note).toBe('turn-completed');

    // EXACT outward sequence (fixture-echoed raw lines), including the nested reply
    // `{outcome:{outcome:'selected',optionId}}` sent on the CLI's own JSON-RPC id (C0.3
    // step 6 wrapper, exercised on the real CLI four times in A4).
    expect(log.received()).toEqual([
      { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } },
      {
        jsonrpc: '2.0',
        id: 2,
        method: 'session/new',
        params: {
          cwd: dir,
          mcpServers: [
            {
              type: 'http',
              name: 'aria-stub',
              url: 'http://127.0.0.1:9/mcp',
              headers: [{ name: 'Authorization', value: `Bearer ${SYNTHETIC_PAT}` }],
            },
          ],
        },
      },
      { jsonrpc: '2.0', id: 3, method: 'session/set_model', params: { sessionId: 'sess-1', modelId: MODEL } },
      {
        jsonrpc: '2.0',
        id: 4,
        method: 'session/prompt',
        params: { sessionId: 'sess-1', prompt: [{ type: 'text', text: PROMPT }] },
      },
      { jsonrpc: '2.0', id: 4, result: { outcome: { outcome: 'selected', optionId: 'zz_once' } } },
    ]);

    // session/update notifications are surfaced and never answered.
    expect(
      log.events
        .filter((event): event is EventOf<'session_update'> => event.type === 'session_update')
        .map(event => (event.update as { sessionUpdate?: string }).sessionUpdate),
    ).toEqual(['tool_call', 'tool_call_update', 'agent_message_chunk']);

    // A5 facts are surfaced, not asserted as cost: the only model attestation is the
    // post-turn `_meta.quota.model_usage[0].model`; usage counters stay zero.
    const result = await log.waitFor('prompt_result');
    const promptResult = result.result as {
      stopReason: string;
      usage: Record<string, unknown>;
      _meta: { quota: { model_usage: Array<{ model: string }> } };
    };
    expect(promptResult.stopReason).toBe('end_turn');
    expect(promptResult._meta.quota.model_usage[0]?.model).toBe('efficient');
    expect(promptResult.usage).toEqual({ inputTokens: 0, outputTokens: 0, totalTokens: 0 });

    // Decisions never fabricate options for unknown or already-resolved requests.
    expect(() => client.decide('does-not-exist', true)).toThrowError(UnknownPermissionRequestError);
    expect(() => client.decide(permission.requestId, true)).toThrowError(PermissionAlreadyResolvedError);

    client.close();
    await expect(client.createSession(sessionSpec(dir))).rejects.toMatchObject({ code: 'CLIENT_CLOSED' });
  });
});

describe('C0.4 permission option selection', () => {
  it('denies with the offered reject_once option (never allow_always, never the first option)', async () => {
    const { client, log, dir } = start('deny');
    await client.createSession(sessionSpec(dir));
    client.prompt('sess-1', PROMPT);
    const permission = await log.waitFor('permission_request');
    expect(permission.options.map(option => option.kind)).toEqual(['allow_always', 'allow_once', 'reject_once']);

    expect(client.decide(permission.requestId, false)).toEqual({ outcome: 'selected', optionId: 'zz_reject' });
    const done = await log.done();
    expect(done.note).toBe('turn-completed');
    const replies = log.received().filter(message => message.method === undefined);
    expect(replies).toEqual([
      { jsonrpc: '2.0', id: 4, result: { outcome: { outcome: 'selected', optionId: 'zz_reject' } } },
    ]);
  });

  it('an allow_always-only offer fails closed: typed UNSUPPORTED_OPTIONS, no reply, no fallback', async () => {
    const { client, log, dir } = start('allow-always-only');
    await client.createSession(sessionSpec(dir));
    client.prompt('sess-1', PROMPT);
    const permission = await log.waitFor('permission_request');
    expect(permission.requestId).toBe('cli-perm-77');
    expect(permission.options.map(option => option.kind)).toEqual(['allow_always']);

    let thrown: unknown;
    try {
      client.decide(permission.requestId, true);
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(UnsupportedOptionsError);
    expect((thrown as AcpError).code).toBe('UNSUPPORTED_OPTIONS');

    // The failed approval sent nothing; the request stays pending so a later denial
    // can still resolve it (C0.4: fail closed, never fall back to allow_always).
    expect(client.decide(permission.requestId, false)).toEqual({ outcome: 'cancelled', optionId: null });
    const done = await log.done();
    expect(done.note).toBe('cancelled-reply-observed');

    const replies = log.received().filter(message => message.method === undefined);
    expect(replies).toHaveLength(1);
    // INFERRED, NOT EXERCISED against the real CLI: A4 exercised only the nested
    // `selected` wrapper; the flat `{outcome:'cancelled'}` form from the probe was never
    // confirmed. This pins OUR intent (nested wrapper, consistent with the exercised
    // shape), not the CLI's behavior.
    expect(replies[0]).toEqual({ jsonrpc: '2.0', id: 'cli-perm-77', result: { outcome: { outcome: 'cancelled' } } });
  });
});

describe('unsupported client-method requests', () => {
  it('answers with an explicit JSON-RPC error and never a fabricated result', async () => {
    const { client, log } = start('unsupported');
    const done = await log.done();
    expect(done.note).toBe('unsupported-refused');
    expect(done.facts.unsupportedErrorSeen).toBe(true);
    expect(done.facts.unsupportedResultPresent).toBe(false);

    const response = log.received().find(message => message.method === undefined);
    expect(response).toEqual({
      jsonrpc: '2.0',
      id: 0,
      error: { code: -32601, message: expect.stringContaining('fs/read_text_file') },
    });
    const event = await log.waitFor('unsupported_request');
    expect(event.method).toBe('fs/read_text_file');
  });
});

describe('cancel (A4 CANCEL-METHOD-DECISION)', () => {
  it('cancels with the session/cancel notification (no id) and leaves the pending request unanswered', async () => {
    const { client, log, dir } = start('cancel');
    await client.createSession(sessionSpec(dir));
    client.prompt('sess-1', PROMPT);
    const permission = await log.waitFor('permission_request');

    client.cancel('sess-1');
    const done = await log.done();
    expect(done.note).toBe('cancelled');
    expect(done.facts.cancelSeen).toBe(true);
    expect(done.facts.cancelHadId).toBe(false);

    const cancelMessage = log.received().find(message => message.method === 'session/cancel');
    expect(cancelMessage).toEqual({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId: 'sess-1' } });
    expect(cancelMessage).not.toHaveProperty('id');

    // Fail closed: the pending permission request is abandoned, not answered.
    expect(log.received().filter(message => message.method === undefined)).toHaveLength(0);
    expect(() => client.decide(permission.requestId, true)).toThrowError(PermissionAlreadyResolvedError);

    const result = await log.waitFor('prompt_result');
    expect((result.result as { stopReason?: string }).stopReason).toBe('cancelled');
  });

  it('records the A4 decision text verbatim as a named constant', () => {
    expect(CANCEL_METHOD_DECISION).toBe(
      'session/cancel exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted); fallback=none',
    );
  });
});

describe('failure propagation', () => {
  it('rejects the pending handshake when the CLI exits early', async () => {
    const { client, log, dir } = start('exit-early');
    await expect(client.createSession(sessionSpec(dir))).rejects.toMatchObject({
      code: 'PROCESS_EXITED',
      detail: { code: 3 },
    });
    const exit = await log.waitFor('child_exit');
    expect(exit.code).toBe(3);
  });

  it('surfaces a mid-turn process exit', async () => {
    const { client, log, dir } = start('exit-mid-turn');
    await client.createSession(sessionSpec(dir));
    client.prompt('sess-1', PROMPT);
    const exit = await log.waitFor('child_exit');
    expect(exit.code).toBe(4);
  });

  it('rejects the handshake when the CLI binary cannot be spawned', async () => {
    const { client, log, dir } = start('happy', { command: 'definitely-not-a-real-qodercli-binary' });
    await expect(client.createSession(sessionSpec(dir))).rejects.toMatchObject({ code: 'SPAWN_FAILED' });
    await log.waitFor('child_error');
  });

  it('surfaces a JSON-RPC handshake error instead of a silent fallback', async () => {
    const { client, dir } = start('handshake-error');
    const rejection = await client.createSession(sessionSpec(dir)).then(
      () => null,
      (error: unknown) => error as AcpRpcError,
    );
    expect(rejection).toBeInstanceOf(AcpRpcError);
    expect(rejection?.code).toBe('RPC_ERROR');
    expect(rejection?.rpcCode).toBe(-32000);
    expect(rejection?.message).toContain('Authentication required');
  });

  it('refuses a model that is not in the advertised list (no silent fallback)', async () => {
    const { client, log, dir } = start('unknown-model');
    await expect(client.createSession(sessionSpec(dir))).rejects.toMatchObject({ code: 'UNKNOWN_MODEL' });
    const done = await log.done();
    expect(done.note).toBe('no-set-model');
    expect(done.facts.setModelSeen).toBe(false);
    expect(log.received().some(message => message.method === 'session/set_model')).toBe(false);
  });

  it('stops the run with a governance error on mode escalation (C0.4)', async () => {
    const { client, log, dir } = start('mode-escalation');
    await client.createSession(sessionSpec(dir));
    client.prompt('sess-1', PROMPT);
    const governance = await log.waitFor('governance_error');
    expect(governance.code).toBe('MODE_ESCALATION');
    expect(governance.message).toContain('acceptEdits');
    await log.waitFor('child_exit');
    expect(() => client.prompt('sess-1', PROMPT)).toThrowError(AcpClosedError);
  });
});
