// The Qwen connection, its config, the forwarding seams and the 119-minute reopen
// (spec X2, X3, X9, X10). Every test drives the real session against a fake Qwen
// WebSocket server, so what is asserted is the exact wire the provider receives.
//
// The credentials here are synthetic and must stay that way: the real key and
// workspace live only in the deployment's environment.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WebSocketServer, type WebSocket } from 'ws';
import type { IncomingHttpHeaders } from 'node:http';
import { startSession, type SessionAction } from '../src/session.js';

const KEY = 'synthetic-dashscope-key';
const WORKSPACE = 'synthetic-workspace';
const REOPEN_MS = 119 * 60 * 1000;

interface Received {
  type: string;
  [key: string]: unknown;
}

interface Connection {
  headers: IncomingHttpHeaders;
  messages: Received[];
  socket: WebSocket;
}

/** Test-only recorder for the actions the session hands to the bridge writer. */
function createRecorder() {
  const actions: SessionAction[] = [];
  const waiters: Array<{ match: (action: SessionAction) => boolean; resolve: () => void }> = [];
  return {
    actions,
    push(action: SessionAction) {
      actions.push(action);
      for (const waiter of [...waiters]) {
        if (waiter.match(action)) {
          waiters.splice(waiters.indexOf(waiter), 1);
          waiter.resolve();
        }
      }
    },
    waitFor(match: (action: SessionAction) => boolean): Promise<void> {
      if (actions.some(match)) {
        return Promise.resolve();
      }
      return new Promise((resolve) => waiters.push({ match, resolve }));
    },
  };
}

/**
 * A fake Qwen realtime endpoint: accepts connections, answers `session.update` with
 * `session.updated`, records everything, and lets a test script extra events per
 * connection.
 */
async function fakeQwen(script: { onConnection?: (connection: Connection, index: number) => void } = {}) {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  const connections: Connection[] = [];
  await new Promise<void>((resolve) => server.once('listening', () => resolve()));
  server.on('connection', (socket, request) => {
    const index = connections.length;
    const connection: Connection = { headers: request.headers, messages: [], socket };
    connections.push(connection);
    socket.send(JSON.stringify({ type: 'session.created', session: { id: 'sess_synthetic' } }));
    socket.on('message', (data) => {
      const message = JSON.parse(data.toString()) as Received;
      connection.messages.push(message);
      if (message.type === 'session.update') {
        socket.send(JSON.stringify({ type: 'session.updated', session: {} }));
      }
    });
    script.onConnection?.(connection, index);
  });
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('fake server has no port');
  }
  return {
    url: `ws://127.0.0.1:${address.port}/api-ws/v1/realtime?model=test`,
    connections,
    send(index: number, event: Record<string, unknown>) {
      const connection = connections[index];
      if (!connection) {
        throw new Error(`no connection ${index}`);
      }
      connection.socket.send(JSON.stringify(event));
    },
    async close() {
      for (const connection of connections) {
        connection.socket.terminate();
      }
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}

const sessions: Array<{ close(): Promise<void> }> = [];

afterEach(async () => {
  vi.useRealTimers();
  while (sessions.length > 0) {
    await sessions.pop()?.close();
  }
});

async function open(fake: Awaited<ReturnType<typeof fakeQwen>>, options: {
  context?: Array<{ role: string; text: string }>;
  clock?: () => number;
} = {}) {
  const recorder = createRecorder();
  const fatal: number[] = [];
  const session = startSession({
    url: fake.url,
    apiKey: KEY,
    workspace: WORKSPACE,
    context: options.context ?? [],
    onAction: (action) => recorder.push(action),
    onFatal: (code) => fatal.push(code),
    reopenAfterMs: REOPEN_MS,
    ...(options.clock === undefined ? {} : { clock: options.clock }),
  });
  sessions.push({ close: () => session.close() });
  await session.ready();
  return { session, recorder, fatal };
}

// `setImmediate` and `Date.now` stay real even while the tests fake the session's timers.
const waitUntil = async (check: () => boolean, label: string) => {
  const deadline = Date.now() + 2000;
  while (!check()) {
    if (Date.now() > deadline) {
      throw new Error(`timed out: ${label}`);
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
};

describe('session: config and handshake', () => {
  it('connects with the workspace header and the exact session config', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    const connection = fake.connections[0]!;

    expect(connection.headers['x-dashscope-workspace']).toBe(WORKSPACE);
    expect(connection.headers.authorization).toBe(`Bearer ${KEY}`);
    // The credentials reach the provider, never stdout (the log test holds that side).
    expect(JSON.stringify(connection.headers)).not.toContain('live-session');

    const update = connection.messages.find((message) => message.type === 'session.update');
    expect(update).toBeDefined();
    const config = update!.session as Record<string, unknown>;
    expect(config.modalities).toEqual(['text', 'audio']);
    expect(config.audio).toEqual({
      input: {
        format: {
          type: 'pcm',
          sample_rate: 16000,
          sample_format: 's16le',
          channels: 1,
          packing: 'interleaved',
          channel_layout: 'mono',
        },
      },
      output: { voice: 'Kiki', format: { type: 'pcm', sample_rate: 24000 } },
    });
    expect(config.input_audio_transcription).toEqual({ model: 'qwen3-asr-flash-realtime' });
    // The P0-unverified point: the contract asks for server-side semantic turn detection.
    expect(config.turn_detection).toEqual({ type: 'semantic_vad_ec' });
    expect(config.tool_choice).toBe('auto');
    const tools = config.tools as Array<{ type: string; function: { name: string; parameters: unknown } }>;
    expect(tools.map((tool) => tool.function.name)).toEqual([
      'get_time',
      'get_run',
      'get_latest_conversation',
      'list_approvals',
      'list_agents',
      'get_dashboard_summary',
      'get_workflow',
    ]);
    for (const tool of tools) {
      expect(tool.type).toBe('function');
      expect((tool.function.parameters as { type: string }).type).toBe('object');
    }
    await session.close();
  });

  it('seeds the conversation context as message items without asking for a response', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake, {
      context: [
        { role: 'user', text: '早' },
        { role: 'assistant', text: '早晨' },
      ],
    });
    const messages = fake.connections[0]!.messages;
    await waitUntil(
      () => messages.filter((message) => message.type === 'conversation.item.create').length === 2,
      'seeded context',
    );
    const items = messages.filter((message) => message.type === 'conversation.item.create');
    expect(items).toEqual([
      {
        type: 'conversation.item.create',
        item: { type: 'message', role: 'user', content: [{ type: 'input_text', text: '早' }] },
      },
      {
        type: 'conversation.item.create',
        item: { type: 'message', role: 'assistant', content: [{ type: 'text', text: '早晨' }] },
      },
    ]);
    expect(messages.some((message) => message.type === 'response.create')).toBe(false);
    await session.close();
  });
});

describe('session: phone input', () => {
  it('forwards PCM as input_audio_buffer.append', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.appendAudio('AAECAwQ=');
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'input_audio_buffer.append'),
      'audio append',
    );
    expect(fake.connections[0]!.messages.at(-1)).toEqual({
      type: 'input_audio_buffer.append',
      audio: 'AAECAwQ=',
    });
    await session.close();
  });

  it('sends the image before the commit and asks for the response', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.attachImage('IMAGEBASE64');
    session.commit({ image: true });
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'commit',
    );
    const types = fake.connections[0]!.messages.map((message) => message.type);
    const image = types.indexOf('input_image_buffer.append');
    const commit = types.indexOf('input_audio_buffer.commit');
    const create = types.indexOf('response.create');
    expect(image).toBeGreaterThanOrEqual(0);
    expect(commit).toBe(image + 1);
    expect(create).toBe(commit + 1);
    expect(fake.connections[0]!.messages[image]).toEqual({
      type: 'input_image_buffer.append',
      image: 'IMAGEBASE64',
    });
    await session.close();
  });

  it('never sends a pending image that the commit did not ask for', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.attachImage('IMAGEBASE64');
    session.commit({ image: false });
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'commit',
    );
    const types = fake.connections[0]!.messages.map((message) => message.type);
    expect(types).not.toContain('input_image_buffer.append');
    await session.close();
  });

  it('sends typed text as a user message item and asks for the response', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.sendText('幾點');
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'text',
    );
    const messages = fake.connections[0]!.messages;
    expect(messages.at(-2)).toEqual({
      type: 'conversation.item.create',
      item: { type: 'message', role: 'user', content: [{ type: 'input_text', text: '幾點' }] },
    });
    expect(messages.at(-1)).toEqual({ type: 'response.create' });
    await session.close();
  });
});

describe('session: tools and TOWER', () => {
  it('turns a function call into a tool_call and answers it with function_call_output', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);

    fake.send(0, {
      type: 'response.function_call_arguments.done',
      call_id: 'call_7',
      name: 'get_time',
      arguments: '{"zone":"Asia/Hong_Kong"}',
    });
    await recorder.waitFor((action) => action.kind === 'tool_call');
    expect(recorder.actions).toContainEqual({
      kind: 'tool_call',
      id: 'call_7',
      name: 'get_time',
      arguments: { zone: 'Asia/Hong_Kong' },
    });

    session.toolResult('call_7', true, '{"now":"2026-11-24T02:00:00Z"}');
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'tool continuation',
    );
    const messages = fake.connections[0]!.messages;
    expect(messages.at(-2)).toEqual({
      type: 'conversation.item.create',
      item: {
        type: 'function_call_output',
        call_id: 'call_7',
        output: '{"now":"2026-11-24T02:00:00Z"}',
      },
    });
    expect(messages.at(-1)).toEqual({ type: 'response.create' });
    await session.close();
  });

  it('injects a TOWER result as a message item and asks for the response', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.tower('[tower] 單車鏈條檢查完成');
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'tower',
    );
    const messages = fake.connections[0]!.messages;
    expect(messages.at(-2)).toEqual({
      type: 'conversation.item.create',
      item: {
        type: 'message',
        role: 'user',
        content: [{ type: 'input_text', text: '[tower] 單車鏈條檢查完成' }],
      },
    });
    expect(messages.at(-1)).toEqual({ type: 'response.create' });
    await session.close();
  });

  it('cancels the live response on a phone barge-in', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.cancel();
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.cancel'),
      'cancel',
    );
    await session.close();
  });
});

describe('session: lifecycle', () => {
  it('reopens at 119 minutes, reseeds and says resumed', async () => {
    // Only the timers are faked: the fake provider is real I/O. The clock is injected so
    // `handleAgeSec` measures the same 119 minutes the reopen timer does.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    let now = 1_000_000;
    const fake = await fakeQwen();
    const { session, recorder, fatal } = await open(fake, {
      context: [{ role: 'user', text: '早' }],
      clock: () => now,
    });

    now += REOPEN_MS;
    await vi.advanceTimersByTimeAsync(REOPEN_MS);
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'error',
    );
    expect(recorder.actions).toContainEqual({
      kind: 'control',
      event: {
        type: 'error',
        v: 1,
        code: 'session_expired',
        retryable: true,
        message: 'Live session expired',
      },
    });

    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'resumed',
    );
    expect(recorder.actions).toContainEqual({
      kind: 'control',
      event: { type: 'resumed', v: 1, handleAgeSec: 7140 },
    });
    expect(fake.connections).toHaveLength(2);
    const reopened = fake.connections[1]!.messages;
    await waitUntil(
      () => reopened.some((message) => message.type === 'conversation.item.create'),
      'reseeded context',
    );
    expect(reopened.some((message) => message.type === 'session.update')).toBe(true);
    expect(
      reopened.filter((message) => message.type === 'conversation.item.create'),
    ).toEqual([
      {
        type: 'conversation.item.create',
        item: { type: 'message', role: 'user', content: [{ type: 'input_text', text: '早' }] },
      },
    ]);
    expect(reopened.some((message) => message.type === 'response.create')).toBe(false);
    expect(fatal).toEqual([]);
    await session.close();
  });

  it('reports fatal 4012 when the reopen cannot connect', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const fake = await fakeQwen({
      onConnection: (_connection, index) => {
        if (index > 0) {
          // The provider refuses the second socket outright.
          fake.connections[index]!.socket.terminate();
        }
      },
    });
    const { session, fatal } = await open(fake);
    await vi.advanceTimersByTimeAsync(REOPEN_MS);
    await waitUntil(() => fatal.length > 0, 'fatal');
    expect(fatal).toEqual([4012]);
    await session.close();
  });

  it('reports fatal 1011 when the provider socket dies mid-session', async () => {
    const fake = await fakeQwen();
    const { session, fatal } = await open(fake);
    fake.connections[0]!.socket.terminate();
    await waitUntil(() => fatal.length > 0, 'fatal');
    expect(fatal).toEqual([1011]);
    await session.close();
  });

  it('does not report a fatal on an orderly close', async () => {
    const fake = await fakeQwen();
    const { session, fatal } = await open(fake);
    await session.close();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(fatal).toEqual([]);
  });
});
