// The Qwen connection, its config, the forwarding seams and the 119-minute reopen
// (spec X2, X3, X9, X10). Every test drives the real session against a fake Qwen
// WebSocket server, so what is asserted is the exact wire the provider receives.
//
// The credentials here are synthetic and must stay that way: the real key and
// workspace live only in the deployment's environment.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WebSocketServer, type WebSocket } from 'ws';
import type { IncomingHttpHeaders } from 'node:http';
import { MAX_PENDING_AUDIO, startSession, type SessionAction } from '../src/session.js';

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
async function fakeQwen(
  script: {
    onConnection?: (connection: Connection, index: number) => void;
    /** Default true; a test returning false answers `session.update` by hand. */
    respondToUpdate?: (index: number) => boolean;
  } = {},
) {
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
      if (message.type === 'session.update' && (script.respondToUpdate?.(index) ?? true)) {
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

describe('session: one response per commit', () => {
  const typesOf = (index: number, fake: Awaited<ReturnType<typeof fakeQwen>>) =>
    fake.connections[index]!.messages.map((message) => message.type);

  it('does not ask for a second response when speech_stopped already did', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);
    // `semantic_vad_ec` already made the provider answer when the user stopped talking.
    fake.send(0, { type: 'input_audio_buffer.speech_stopped' });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'speech_end',
    );

    session.commit();
    await waitUntil(() => typesOf(0, fake).includes('input_audio_buffer.commit'), 'commit');
    expect(typesOf(0, fake).filter((type) => type === 'response.create')).toEqual([]);
    await session.close();
  });

  it('asks for the response when no speech_stopped arrived since the previous commit', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.commit();
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'first commit');
    // The flag is per commit: the next commit without a speech_stopped asks again.
    session.commit();
    await waitUntil(
      () => typesOf(0, fake).filter((type) => type === 'response.create').length === 2,
      'second commit',
    );
    const types = typesOf(0, fake);
    expect(types.slice(-2)).toEqual(['input_audio_buffer.commit', 'response.create']);
    await session.close();
  });

  it('asks for the response on an image commit even after speech_stopped', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);
    session.attachImage('IMAGEBASE64');
    fake.send(0, { type: 'input_audio_buffer.speech_stopped' });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'speech_end',
    );

    session.commit({ image: true });
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'image commit');
    const types = typesOf(0, fake);
    const image = types.indexOf('input_image_buffer.append');
    const commit = types.indexOf('input_audio_buffer.commit');
    expect(commit).toBe(image + 1);
    expect(types.slice(commit)).toEqual([
      'input_audio_buffer.commit',
      'response.create',
    ]);
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

  it('answers two calls in one response with one response.create, injected in call order', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);
    fake.send(0, {
      type: 'response.function_call_arguments.done',
      call_id: 'call_1',
      name: 'get_time',
      arguments: '{}',
    });
    fake.send(0, {
      type: 'response.function_call_arguments.done',
      call_id: 'call_2',
      name: 'list_agents',
      arguments: '{}',
    });
    await waitUntil(
      () => recorder.actions.filter((action) => action.kind === 'tool_call').length === 2,
      'two calls',
    );
    const messages = fake.connections[0]!.messages;
    const injected = () =>
      messages
        .filter((message) => message.type === 'conversation.item.create')
        .map((message) => (message.item as { call_id: string }).call_id);

    // Java answers the second call first: its queue is not the model's order.
    session.toolResult('call_2', true, 'agents');
    // Round-trip a provider frame: by the time it is translated, sends have drained.
    fake.send(0, { type: 'input_audio_buffer.speech_stopped' });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'speech_end',
    );
    expect(injected()).toEqual([]);
    expect(messages.some((message) => message.type === 'response.create')).toBe(false);

    session.toolResult('call_1', true, 'time');
    await waitUntil(
      () => messages.some((message) => message.type === 'response.create'),
      'continuation',
    );
    expect(injected()).toEqual(['call_1', 'call_2']);
    expect(messages.filter((message) => message.type === 'response.create')).toHaveLength(1);
    expect(messages.at(-1)).toEqual({ type: 'response.create' });
    await session.close();
  });

  it('drops a tool_result this session never issued and never forwards it', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);
    const messages = fake.connections[0]!.messages;

    session.toolResult('call_ghost', true, 'nope');
    // Sync point: once the session has translated a provider frame, its sends are drained.
    fake.send(0, { type: 'input_audio_buffer.speech_stopped' });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'speech_end',
    );
    expect(messages.some((message) => message.type === 'conversation.item.create')).toBe(false);
    expect(messages.some((message) => message.type === 'response.create')).toBe(false);
    expect(session.stats().droppedToolResults).toBe(1);

    // A real call still works after the drop, and a duplicate answer is dropped too.
    fake.send(0, {
      type: 'response.function_call_arguments.done',
      call_id: 'call_9',
      name: 'get_time',
      arguments: '{}',
    });
    await recorder.waitFor(
      (action) => action.kind === 'tool_call' && action.id === 'call_9',
    );
    session.toolResult('call_9', true, 'once');
    session.toolResult('call_9', true, 'twice');
    await waitUntil(
      () => messages.some((message) => message.type === 'response.create'),
      'continuation',
    );
    expect(
      messages.filter((message) => message.type === 'conversation.item.create'),
    ).toEqual([
      {
        type: 'conversation.item.create',
        item: { type: 'function_call_output', call_id: 'call_9', output: 'once' },
      },
    ]);
    expect(messages.filter((message) => message.type === 'response.create')).toHaveLength(1);
    expect(session.stats().droppedToolResults).toBe(2);
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

  it('queues sends while the session reconfigures and replays them after session.updated', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    // The second socket does not answer `session.update`: the test decides when it does.
    const fake = await fakeQwen({ respondToUpdate: (index) => index === 0 });
    const { session } = await open(fake);

    await vi.advanceTimersByTimeAsync(REOPEN_MS);
    await waitUntil(() => fake.connections.length === 2, 'reopen socket');
    vi.useRealTimers();
    const reopened = fake.connections[1]!;
    await waitUntil(
      () => reopened.messages.some((message) => message.type === 'session.update'),
      'session.update on the new socket',
    );

    session.appendAudio('QUEUED');
    await new Promise((resolve) => setTimeout(resolve, 30));
    // A dead socket is never written to while the session is reconfiguring.
    expect(reopened.messages.filter((message) => message.type === 'input_audio_buffer.append')).toEqual(
      [],
    );

    fake.send(1, { type: 'session.updated', session: {} });
    await waitUntil(
      () => reopened.messages.some((message) => message.type === 'input_audio_buffer.append'),
      'replayed audio',
    );
    expect(reopened.messages.filter((message) => message.type === 'input_audio_buffer.append')).toEqual(
      [{ type: 'input_audio_buffer.append', audio: 'QUEUED' }],
    );
    expect(session.stats().refusedSends).toBe(0);
    await session.close();
  });

  it('bounds the queue while reconfiguring and counts what it refused', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const fake = await fakeQwen({ respondToUpdate: (index) => index === 0 });
    const { session } = await open(fake);
    await vi.advanceTimersByTimeAsync(REOPEN_MS);
    await waitUntil(() => fake.connections.length === 2, 'reopen socket');
    const reopened = fake.connections[1]!;
    await waitUntil(
      () => reopened.messages.some((message) => message.type === 'session.update'),
      'session.update on the new socket',
    );

    for (let index = 0; index < MAX_PENDING_AUDIO + 1; index += 1) {
      session.appendAudio('AAAA');
    }
    fake.send(1, { type: 'session.updated', session: {} });
    await waitUntil(
      () =>
        reopened.messages.filter((message) => message.type === 'input_audio_buffer.append')
          .length === MAX_PENDING_AUDIO,
      'replayed audio',
    );
    expect(session.stats().refusedSends).toBe(1);
    await session.close();
  });

  it('ignores a provider frame that is literal null and keeps working', async () => {
    const fake = await fakeQwen();
    const { session, recorder, fatal } = await open(fake);
    fake.connections[0]!.socket.send('null');
    fake.send(0, { type: 'input_audio_buffer.speech_stopped' });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'speech_end',
    );
    expect(fatal).toEqual([]);
    await session.close();
  });

  it('forgets a held image when the session reopens', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    const fake = await fakeQwen();
    const { session } = await open(fake);
    session.attachImage('IMAGEBASE64');

    await vi.advanceTimersByTimeAsync(REOPEN_MS);
    await waitUntil(() => fake.connections.length === 2, 'reopen socket');
    const reopened = fake.connections[1]!;
    session.commit({ image: true });
    await waitUntil(
      () => reopened.messages.some((message) => message.type === 'input_audio_buffer.commit'),
      'commit',
    );
    expect(reopened.messages.some((message) => message.type === 'input_image_buffer.append')).toBe(
      false,
    );
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

  it('rebuilds the session when the provider socket dies mid-session', async () => {
    const fake = await fakeQwen();
    const { session, fatal } = await open(fake);
    fake.connections[0]!.socket.terminate();
    await waitUntil(() => fake.connections.length === 2, 'silent reopen');
    expect(fatal).toEqual([]);
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

describe('session: provider-initiated close', () => {
  const controlTypes = (actions: SessionAction[]) =>
    actions
      .filter((action) => action.kind === 'control')
      .map((action) => (action as { event: { type: string } }).event.type);

  it('rebuilds a provider-closed session in place without telling the phone', async () => {
    const fake = await fakeQwen({
      onConnection: (connection, index) => {
        if (index === 0) setTimeout(() => connection.socket.close(), 50);
      },
    });
    const { session, recorder, fatal } = await open(fake);

    await waitUntil(() => fake.connections.length === 2, 'silent reopen');
    const reopened = fake.connections[1]!;
    await waitUntil(
      () => reopened.messages.some((message) => message.type === 'session.update'),
      'the second session is configured',
    );

    expect(fatal).toEqual([]);
    expect(controlTypes(recorder.actions)).not.toContain('session_expired');
    await session.close();
  });

  it('gives up with 1011 when the provider keeps closing immediately', async () => {
    const fake = await fakeQwen({
      onConnection: (connection) => setTimeout(() => connection.socket.close(), 20),
    });
    const { session, fatal } = await open(fake);

    await waitUntil(() => fatal.length > 0, 'the reopen budget runs out');

    expect(fatal).toEqual([1011]);
    expect(fake.connections.length).toBeLessThanOrEqual(5);
    await session.close();
  });
});
