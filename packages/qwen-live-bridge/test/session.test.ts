// The Qwen connection, its config, the forwarding seams and the 119-minute reopen
// (spec X2, X3, X9, X10). Every test drives the real session against a fake Qwen
// WebSocket server, so what is asserted is the exact wire the provider receives.
//
// The credentials here are synthetic and must stay that way: the real key and
// workspace live only in the deployment's environment.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WebSocketServer, type WebSocket } from 'ws';
import type { IncomingHttpHeaders } from 'node:http';
import {
  AR_FRAME_GRACE_MS,
  AR_UPDATE_SETTLE_MS,
  MAX_PENDING_AUDIO,
  startSession,
  type SessionAction,
} from '../src/session.js';

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
  ar?: boolean;
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
    ...(options.ar === undefined ? {} : { ar: options.ar }),
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
    // The AR display tool and its Cantonese instruction (spec D2). The declaration is the
    // verbatim contract the phone and the tool catalogue agree on.
    expect(config.instructions).toContain(
      '有圖嘅時候，用戶講『睇下／邊度／邊個』，先叫 highlight_target，再用一句最多 15 字講出答案。冇圖或者唔肯定，就講睇唔到，唔准估框。',
    );
    const tools = config.tools as Array<{
      type: string;
      function: { name: string; description: string; parameters: unknown };
    }>;
    expect(tools.map((tool) => tool.function.name)).toEqual([
      'get_time',
      'get_run',
      'get_latest_conversation',
      'list_approvals',
      'list_agents',
      'get_dashboard_summary',
      'get_workflow',
      'list_skills',
      'list_runs',
      'get_agent',
      'start_agent_run',
      'run_skill',
      'highlight_target',
    ]);
    expect(tools.map((tool) => tool.function.name)).not.toContain('decide_approval');
    expect(tools.find((tool) => tool.function.name === 'highlight_target')).toEqual({
      type: 'function',
      function: {
        name: 'highlight_target',
        description: 'Point the HUD at exactly one object the wearer just asked about. Display only.',
        parameters: {
          type: 'object',
          properties: {
            label: { type: 'string' },
            box_2d: { type: 'array', items: { type: 'number' }, minItems: 4, maxItems: 4 },
            text: { type: 'string' },
          },
          required: ['label', 'box_2d', 'text'],
        },
      },
    });
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

describe('session: AR grace (D4) and the ar capability (D5)', () => {
  const typesOf = (index: number, fake: Awaited<ReturnType<typeof fakeQwen>>) =>
    fake.connections[index]!.messages.map((message) => message.type);
  const updatesOf = (fake: Awaited<ReturnType<typeof fakeQwen>>) =>
    fake.connections[0]!.messages
      .filter((message) => message.type === 'session.update')
      .map((message) => (message.session as { turn_detection: unknown }).turn_detection);
  const appendsOf = (fake: Awaited<ReturnType<typeof fakeQwen>>) =>
    fake.connections[0]!.messages.filter(
      (message) => message.type === 'input_image_buffer.append',
    );
  const speechEnds = (recorder: ReturnType<typeof createRecorder>) =>
    recorder.actions.filter(
      (action) => action.kind === 'control' && action.event.type === 'speech_end',
    ).length;
  /** Only the grace timer is faked: the fake provider is real I/O, driven by `setImmediate`. */
  const fakeGraceTimers = () =>
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });

  it('configures no turn detection when hello.ar is true and semantic_vad_ec when it is false', async () => {
    const arFake = await fakeQwen();
    const { session: arSession } = await open(arFake, { ar: true });
    expect(updatesOf(arFake)).toEqual([null]);
    await arSession.close();

    const offFake = await fakeQwen();
    const { session: offSession } = await open(offFake, { ar: false });
    expect(updatesOf(offFake)).toEqual([{ type: 'semantic_vad_ec' }]);
    await offSession.close();
  });

  it('reconfigures a live session in place on an ar toggle and never reseeds the context', async () => {
    const fake = await fakeQwen();
    const { session } = await open(fake, { ar: true, context: [{ role: 'user', text: '早' }] });

    session.setAr(false);
    await waitUntil(() => updatesOf(fake).length === 2, 'the semantic_vad_ec update');
    // AR back on: the no-turn-detection config again, on the same socket.
    session.setAr(true);
    await waitUntil(() => updatesOf(fake).length === 3, 'the null update');
    expect(updatesOf(fake)).toEqual([null, { type: 'semantic_vad_ec' }, null]);

    // A duplicate declaration is the same mode: nothing new crosses.
    session.setAr(true);
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(updatesOf(fake)).toHaveLength(3);
    // The mid-session updates are reconfigurations, not handshakes: the seed is not replayed.
    expect(
      fake.connections[0]!.messages.filter(
        (message) => message.type === 'conversation.item.create',
      ),
    ).toHaveLength(1);
    expect(fake.connections).toHaveLength(1);
    await session.close();
  });

  it('holds the AR commit for its frame and appends the image before the commit', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session } = await open(fake, { ar: true });

    session.appendAudio('AAAA');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(150);
    // Nothing reaches the provider while the grace is open.
    expect(typesOf(0, fake)).not.toContain('input_audio_buffer.commit');

    session.attachImage('ARIMAGE');
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'the held commit');
    const types = typesOf(0, fake);
    const image = types.indexOf('input_image_buffer.append');
    expect(image).toBeGreaterThanOrEqual(0);
    expect(types.slice(image)).toEqual([
      'input_image_buffer.append',
      'input_audio_buffer.commit',
      'response.create',
    ]);
    expect(appendsOf(fake)).toEqual([{ type: 'input_image_buffer.append', image: 'ARIMAGE' }]);
    await session.close();
  });

  it('commits without an image and asks for a response when no frame arrives in time', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session } = await open(fake, { ar: true });

    session.appendAudio('AAAA');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS - 1);
    expect(typesOf(0, fake)).not.toContain('input_audio_buffer.commit');

    await vi.advanceTimersByTimeAsync(1);
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'the grace expiry');
    expect(typesOf(0, fake).slice(-2)).toEqual(['input_audio_buffer.commit', 'response.create']);
    expect(typesOf(0, fake)).not.toContain('input_image_buffer.append');
    await session.close();
  });

  it('speaks a second utterance: its commit yields its own speech_end', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake, { ar: true });
    const heartbeat = (label: string) =>
      waitUntil(() => typesOf(0, fake).includes('response.create'), label);

    session.appendAudio('AAAA');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(150);
    session.attachImage('ARIMAGE');
    await heartbeat('first utterance');
    fake.send(0, { type: 'input_audio_buffer.committed' });
    await waitUntil(() => speechEnds(recorder) === 1, 'first speech_end');

    session.appendAudio('BBBB');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(150);
    session.attachImage('ARIMAGE2');
    await waitUntil(
      () => typesOf(0, fake).filter((type) => type === 'input_audio_buffer.commit').length === 2,
      'second commit',
    );
    fake.send(0, { type: 'input_audio_buffer.committed' });
    await waitUntil(() => speechEnds(recorder) === 2, 'second speech_end');
    expect(appendsOf(fake).map((message) => message.image)).toEqual(['ARIMAGE', 'ARIMAGE2']);
    await session.close();
  });

  it('drops a frame that arrives after the flushed commit and carries none to the next utterance', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake, { ar: true });

    session.appendAudio('AAAA');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(150);
    session.attachImage('FIRST');
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'first flush');

    // The turn's one frame already crossed: a late one belongs to no turn (spec D8).
    session.attachImage('LATE');
    await vi.advanceTimersByTimeAsync(30);
    expect(appendsOf(fake).map((message) => message.image)).toEqual(['FIRST']);

    // The next utterance asks for an image but sends none: the late frame is not it.
    session.appendAudio('BBBB');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS);
    await waitUntil(
      () => typesOf(0, fake).filter((type) => type === 'input_audio_buffer.commit').length === 2,
      'second commit',
    );
    expect(appendsOf(fake).map((message) => message.image)).toEqual(['FIRST']);

    // The provider's turn end opens a fresh turn: a frame can cross again.
    fake.send(0, { type: 'response.done', response: { status: 'completed' } });
    await recorder.waitFor((action) => action.kind === 'turn_done');
    session.attachImage('NEXT');
    session.appendAudio('CCCC');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS);
    await waitUntil(() => appendsOf(fake).length === 2, 'the next turn frame');
    expect(appendsOf(fake).map((message) => message.image)).toEqual(['FIRST', 'NEXT']);
    await session.close();
  });

  it('flushes a held commit when AR turns off instead of stranding the utterance', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session } = await open(fake, { ar: true });

    session.appendAudio('AAAA');
    session.commit({ image: true });
    session.setAr(false);
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'the upgrade');
    expect(typesOf(0, fake)).toContain('input_audio_buffer.commit');
    // The config-switch half: the update that goes with the flush turns the provider's
    // own VAD back on, so the provider and the bridge agree on AR-off.
    await waitUntil(() => updatesOf(fake).length === 2, 'the semantic_vad_ec update');
    expect(updatesOf(fake)).toEqual([null, { type: 'semantic_vad_ec' }]);
    await session.close();
  });

  it('keeps the frame a held commit waits for when the previous turn ends inside the grace', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake, { ar: true });

    // The Phase 2 order: audio, frame, commit. The frame is held for the commit.
    session.appendAudio('AAAA');
    session.attachImage('ARIMAGE');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(100);
    // The overlapping response ending is not this utterance's boundary: wiping the held
    // frame here would send the commit with no image.
    fake.send(0, { type: 'response.done', response: { status: 'completed' } });
    await recorder.waitFor((action) => action.kind === 'turn_done');

    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS - 100);
    await waitUntil(() => typesOf(0, fake).includes('response.create'), 'the held commit');
    expect(appendsOf(fake)).toEqual([{ type: 'input_image_buffer.append', image: 'ARIMAGE' }]);
    const types = typesOf(0, fake);
    expect(types.slice(types.indexOf('input_image_buffer.append'))).toEqual([
      'input_image_buffer.append',
      'input_audio_buffer.commit',
      'response.create',
    ]);
    await session.close();
  });

  it('reverts an ar switch the provider refuses before session.updated', async () => {
    fakeGraceTimers();
    let updates = 0;
    const fake = await fakeQwen({
      // The handshake is answered; the mid-session switch is refused with an `error`.
      respondToUpdate: () => {
        updates += 1;
        return updates === 1;
      },
    });
    const { session, recorder } = await open(fake, { ar: false });

    session.setAr(true);
    await waitUntil(() => updatesOf(fake).length === 2, 'the refused update');
    fake.send(0, {
      type: 'error',
      error: { code: 'invalid_value', message: 'turn_detection refused' },
    });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'error',
    );

    // The provider still runs semantic_vad_ec, so the bridge must believe AR-off again:
    // the next commit crosses at once instead of being held for a frame (nothing below
    // advances the grace timer, so a deferral could never flush).
    expect(session.stats().refusedArSwitches).toBe(1);
    session.commit();
    await waitUntil(
      () => typesOf(0, fake).includes('input_audio_buffer.commit'),
      'the AR-off commit',
    );
    expect(typesOf(0, fake).filter((type) => type === 'input_audio_buffer.commit')).toHaveLength(1);
    await session.close();
  });

  it('serialises rapid ar toggles so the last mode lands, not the first', async () => {
    fakeGraceTimers();
    // The handshake is answered by the fake; every ar change is answered by the test, so
    // the phone can toggle again while the previous update is still in flight.
    let updates = 0;
    const fake = await fakeQwen({
      respondToUpdate: () => {
        updates += 1;
        return updates === 1;
      },
    });
    const { session, recorder } = await open(fake, { ar: false });
    /** A provider frame round-trip: everything the session queued has been written. */
    const drained = async () => {
      const before = speechEnds(recorder);
      fake.send(0, { type: 'input_audio_buffer.speech_started', audio_start_ms: 1 });
      fake.send(0, { type: 'input_audio_buffer.speech_stopped', audio_end_ms: 1 });
      await waitUntil(() => speechEnds(recorder) === before + 1, 'the round-trip');
    };

    session.setAr(true);
    session.setAr(false);
    await waitUntil(() => updatesOf(fake).length >= 2, 'the AR-on update');
    await drained();
    // One update at a time: the toggle back waits instead of racing the first.
    expect(updatesOf(fake)).toEqual([{ type: 'semantic_vad_ec' }, null]);

    // The provider acknowledges AR-on, so the queued wish (AR-off) crosses now.
    fake.send(0, { type: 'session.updated', session: {} });
    await waitUntil(() => updatesOf(fake).length >= 3, 'the AR-off update');
    expect(updatesOf(fake)).toEqual([
      { type: 'semantic_vad_ec' },
      null,
      { type: 'semantic_vad_ec' },
    ]);
    fake.send(0, { type: 'session.updated', session: {} });
    await drained();
    expect(session.stats().refusedArSwitches).toBe(0);

    // The final mode is AR-off: the commit is not held for a frame (nothing advances the
    // grace timer below, so a deferral could never flush).
    session.commit();
    await waitUntil(() => typesOf(0, fake).includes('input_audio_buffer.commit'), 'the commit');
    await session.close();
  });

  it('reverts a refused ar switch to the applied mode and keeps the newer wish', async () => {
    fakeGraceTimers();
    // The handshake and every update after the first ar change are answered.
    let updates = 0;
    const fake = await fakeQwen({
      respondToUpdate: () => {
        updates += 1;
        return updates !== 2;
      },
    });
    const { session, recorder } = await open(fake, { ar: false });

    // off → on → off while the refused AR-on update is still in flight.
    session.setAr(true);
    session.setAr(false);
    await waitUntil(() => updatesOf(fake).length >= 2, 'the refused update');
    fake.send(0, {
      type: 'error',
      error: { code: 'invalid_value', message: 'turn_detection refused' },
    });
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'error',
    );

    // One refusal, and the provider never left semantic_vad_ec: the bridge is AR-off
    // again, which the wish that arrived meanwhile already asked for (no second update).
    expect(session.stats().refusedArSwitches).toBe(1);
    session.commit();
    await waitUntil(() => typesOf(0, fake).includes('input_audio_buffer.commit'), 'the AR-off commit');
    expect(updatesOf(fake)).toHaveLength(2);

    // Asking again after the refusal is a new request: this one is accepted, and the
    // commit after it follows the acknowledged mode (held for its frame).
    session.setAr(true);
    await waitUntil(() => updatesOf(fake).length === 3, 'the accepted update');
    session.appendAudio('AAAA');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS - 1);
    expect(typesOf(0, fake).filter((type) => type === 'input_audio_buffer.commit')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    await waitUntil(
      () => typesOf(0, fake).filter((type) => type === 'input_audio_buffer.commit').length === 2,
      'the held commit',
    );
    expect(session.stats().refusedArSwitches).toBe(1);
    await session.close();
  });

  it('retries an ar update the provider never answers and only then lets it go', async () => {
    fakeGraceTimers();
    // Only the handshake is answered: every mid-session switch is silently dropped.
    let updates = 0;
    const fake = await fakeQwen({ respondToUpdate: () => (updates += 1) === 1 });
    const { session } = await open(fake, { ar: false });

    session.setAr(true);
    await waitUntil(() => updatesOf(fake).length === 2, 'the unanswered update');
    await vi.advanceTimersByTimeAsync(AR_UPDATE_SETTLE_MS - 1);
    expect(session.stats().refusedArSwitches).toBe(0);

    // Silence is not a refusal: the wish is re-sent once, still AR-on, and nothing is
    // counted against it yet.
    await vi.advanceTimersByTimeAsync(1);
    await waitUntil(() => updatesOf(fake).length === 3, 'the retried update');
    expect(updatesOf(fake)).toEqual([{ type: 'semantic_vad_ec' }, null, null]);
    expect(session.stats().refusedArSwitches).toBe(0);

    // Only the second unanswered send gives the wish up: one refusal, and the bridge is back
    // on the mode the provider really runs.
    await vi.advanceTimersByTimeAsync(AR_UPDATE_SETTLE_MS);
    expect(session.stats().refusedArSwitches).toBe(1);
    expect(updatesOf(fake)).toHaveLength(3);

    // The in-flight marker and the spent retry are gone, so asking again crosses instead of
    // being swallowed.
    session.setAr(true);
    await waitUntil(() => updatesOf(fake).length === 4, 'the fresh update');
    expect(updatesOf(fake)).toEqual([{ type: 'semantic_vad_ec' }, null, null, null]);
    await session.close();
  });

  it('retries the wished ar mode and keeps it when the acknowledgement lands late', async () => {
    fakeGraceTimers();
    // Only the handshake is answered: the AR-off switch is dropped, so its settle re-sends
    // the same wish, and the acknowledgement below lands after that retry went out.
    let updates = 0;
    const fake = await fakeQwen({ respondToUpdate: () => (updates += 1) === 1 });
    const { session } = await open(fake, { ar: true });

    session.setAr(false);
    await waitUntil(() => updatesOf(fake).length === 2, 'the unanswered update');
    await vi.advanceTimersByTimeAsync(AR_UPDATE_SETTLE_MS);
    await waitUntil(() => updatesOf(fake).length === 3, 'the retried wish');
    // The retry carries the mode the phone asked for, never the one it asked to leave.
    expect(updatesOf(fake)).toEqual([
      null,
      { type: 'semantic_vad_ec' },
      { type: 'semantic_vad_ec' },
    ]);
    expect(session.stats().refusedArSwitches).toBe(0);

    // The provider took AR-off after all: the late acknowledgement settles the retry onto the
    // wish and reconciles nothing, so no flip back to the previous mode is ever sent.
    fake.send(0, { type: 'session.updated', session: {} });
    session.appendAudio('AAAA');
    await waitUntil(() => typesOf(0, fake).includes('input_audio_buffer.append'), 'the round-trip');
    expect(updatesOf(fake)).toHaveLength(3);
    expect(updatesOf(fake).at(-1)).toEqual({ type: 'semantic_vad_ec' });
    expect(session.stats().refusedArSwitches).toBe(0);

    // AR-off is the applied mode, so the next commit follows it at once: an AR-on commit
    // would be held for its frame (the frozen grace timer would never flush it).
    session.commit();
    await waitUntil(
      () => typesOf(0, fake).includes('input_audio_buffer.commit'),
      'the AR-off commit',
    );
    expect(typesOf(0, fake).filter((type) => type === 'input_audio_buffer.commit')).toHaveLength(1);
    expect(session.stats().refusedArSwitches).toBe(0);
    await session.close();
  });

  it('drops an ar update queued on a dying socket and reconciles the rebuilt session', async () => {
    // The handshake is answered on the first socket only; the rebuilt handshake is held so
    // a wish can arrive between `session.created` and its acknowledgement.
    const fake = await fakeQwen({ respondToUpdate: (index) => index === 0 });
    const updatesOn = (index: number) =>
      fake.connections[index]!.messages
        .filter((message) => message.type === 'session.update')
        .map((message) => (message.session as { turn_detection: unknown }).turn_detection);
    const { session } = await open(fake, { ar: true });

    // Pausing the server holds the close handshake open, so the client sits CLOSING while
    // the bridge still believes the session configured: the toggle below is queued, never
    // written, and the newer wish waits behind it.
    const dying = fake.connections[0]!;
    dying.socket.pause();
    dying.socket.close();
    await new Promise((resolve) => setTimeout(resolve, 20));
    session.setAr(false);
    session.setAr(true);
    expect(updatesOn(0)).toEqual([null]);

    // Completing the close rebuilds the provider session in place.
    dying.socket.terminate();
    await waitUntil(() => fake.connections.length === 2, 'the rebuilt socket');
    await waitUntil(() => updatesOn(1).length === 1, 'the rebuilt handshake');
    // The handshake carries the phone's current wish, and the queued stale update was not
    // replayed into it.
    expect(updatesOn(1)).toEqual([null]);

    // A wish that arrives during the handshake is reconciled once the session is configured:
    // one fresh update, not the stale replay plus a correction.
    session.setAr(false);
    fake.send(1, { type: 'session.updated', session: {} });
    await waitUntil(() => updatesOn(1).length === 2, 'the fresh update');
    expect(updatesOn(1)).toEqual([null, { type: 'semantic_vad_ec' }]);
    await session.close();
  });

  it('keeps an acknowledged ar switch, so the next commit is held for its frame', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session } = await open(fake, { ar: false });

    session.setAr(true);
    await waitUntil(() => updatesOf(fake).length === 2, 'the AR-on update');
    expect(updatesOf(fake)).toEqual([{ type: 'semantic_vad_ec' }, null]);

    session.appendAudio('AAAA');
    session.commit({ image: true });
    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS - 1);
    expect(typesOf(0, fake)).not.toContain('input_audio_buffer.commit');
    await vi.advanceTimersByTimeAsync(1);
    await waitUntil(() => typesOf(0, fake).includes('input_audio_buffer.commit'), 'the grace');
    expect(session.stats().refusedArSwitches).toBe(0);
    await session.close();
  });

  it('cancels a held commit when the session turns fatal', async () => {
    fakeGraceTimers();
    const fake = await fakeQwen();
    const { session, fatal } = await open(fake, { ar: true });
    /** A provider frame round-trip proves the rebuilt session is configured. */
    const reconfigured = async (index: number) => {
      await waitUntil(
        () => fake.connections[index]?.messages.some((message) => message.type === 'session.update'),
        `reopen ${index}`,
      );
      session.appendAudio('AAAA');
      await waitUntil(
        () => typesOf(index, fake).includes('input_audio_buffer.append'),
        `reopen ${index} configured`,
      );
    };
    // Three quick provider closes reopen in place; the fourth is the fatal one.
    for (let index = 0; index < 3; index += 1) {
      fake.connections[index]!.socket.terminate();
      await reconfigured(index + 1);
    }

    const armedBeforeCommit = vi.getTimerCount();
    session.commit({ image: true });
    expect(vi.getTimerCount()).toBe(armedBeforeCommit + 1);

    const before = fake.connections[3]!.messages.length;
    fake.connections[3]!.socket.terminate();
    await waitUntil(() => fatal.length === 1, 'the fatal');
    expect(fatal).toEqual([1011]);

    // The grace timer died with the session: nothing is left to fire into a dead socket.
    expect(vi.getTimerCount()).toBe(armedBeforeCommit - 1);
    await vi.advanceTimersByTimeAsync(AR_FRAME_GRACE_MS + 50);
    expect(fake.connections[3]!.messages).toHaveLength(before);
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

describe('session: highlight_target', () => {
  // The `ar_render` v2 control event the phone renders (spec D1). The bridge builds it
  // from the model's display-only tool call; Java forwards the control line verbatim.
  interface RenderEvent {
    type: 'ar_render';
    v: number;
    renderId: string;
    render: {
      primitive: string;
      target: { label: string; bbox: number[] };
      text: string;
      ttlMs: number;
      coord: string;
      safetyAlert: unknown;
    };
  }

  const renderEvents = (actions: SessionAction[]): RenderEvent[] =>
    actions
      .filter(
        (action) =>
          action.kind === 'control' &&
          (action as { event: { type: string } }).event.type === 'ar_render',
      )
      .map((action) => (action as unknown as { event: RenderEvent }).event);

  /** The model's POC answer (spec D3): [y0,x0,y1,x1] on a 0-1000 grid. */
  const POC_ARGUMENTS = JSON.stringify({
    label: '5mm 六角匙',
    box_2d: [586, 508, 859, 859],
    text: '5mm 六角匙',
  });

  const highlightCall = (
    fake: Awaited<ReturnType<typeof fakeQwen>>,
    callId: string,
    args = POC_ARGUMENTS,
  ) =>
    fake.send(0, {
      type: 'response.function_call_arguments.done',
      call_id: callId,
      name: 'highlight_target',
      arguments: args,
    });

  const injected = (fake: Awaited<ReturnType<typeof fakeQwen>>) =>
    fake.connections[0]!.messages.filter((message) => message.type === 'conversation.item.create');

  it('renders one ar_render control, acks the model and never forwards a tool_call', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);

    highlightCall(fake, 'call_h1');
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'ar_render',
    );

    const [render] = renderEvents(recorder.actions);
    expect(render).toBeDefined();
    expect(render!.renderId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    expect(render).toEqual({
      type: 'ar_render',
      v: 1,
      renderId: render!.renderId,
      render: {
        primitive: 'highlight',
        target: { label: '5mm 六角匙', bbox: [0.508, 0.586, 0.351, 0.273] },
        text: '5mm 六角匙',
        ttlMs: 8000,
        coord: 'normalized',
        safetyAlert: null,
      },
    });
    // Exactly the contract's fields: a display-only render carries no runId (spec D1).
    expect(Object.keys(render!).sort()).toEqual(['render', 'renderId', 'type', 'v']);
    // The bridge executes it: Java is never asked to run a tool.
    expect(recorder.actions.some((action) => action.kind === 'tool_call')).toBe(false);

    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'highlight acknowledgement',
    );
    const messages = fake.connections[0]!.messages;
    expect(messages.at(-2)).toEqual({
      type: 'conversation.item.create',
      item: {
        type: 'function_call_output',
        call_id: 'call_h1',
        output: JSON.stringify({ ack: true, renderId: render!.renderId }),
      },
    });
    expect(messages.at(-1)).toEqual({ type: 'response.create' });
    await session.close();
  });

  it.each([
    ['three numbers', { label: 'a', box_2d: [586, 508, 859], text: 'a' }],
    ['swapped corners', { label: 'a', box_2d: [859, 859, 586, 508], text: 'a' }],
  ])('declines a %s box without rendering', async (_name, args) => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);

    highlightCall(fake, 'call_bad', JSON.stringify(args));
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'decline acknowledgement',
    );

    expect(renderEvents(recorder.actions)).toEqual([]);
    expect(recorder.actions.some((action) => action.kind === 'tool_call')).toBe(false);
    expect(injected(fake)).toEqual([
      {
        type: 'conversation.item.create',
        item: {
          type: 'function_call_output',
          call_id: 'call_bad',
          output: '{"ack":false}',
        },
      },
    ]);
    expect(fake.connections[0]!.messages.at(-1)).toEqual({ type: 'response.create' });
    await session.close();
  });

  it('renders once per utterance: response.done between calls does not refill the budget', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);

    highlightCall(fake, 'call_h1');
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'ar_render',
    );
    // The highlight turn's own response ends here; the spoken answer is a *second* response
    // in the same user utterance (spec D1). That end must not hand out a second render.
    fake.send(0, { type: 'response.done', response: { status: 'completed' } });
    highlightCall(fake, 'call_h2');
    await waitUntil(() => injected(fake).length === 2, 'second acknowledgement');

    expect(renderEvents(recorder.actions)).toHaveLength(1);
    const outputs = injected(fake).map(
      (message) => (message.item as { output: string }).output,
    );
    expect(outputs).toHaveLength(2);
    expect(JSON.parse(outputs[0]!)).toEqual({
      ack: true,
      renderId: renderEvents(recorder.actions)[0]!.renderId,
    });
    expect(outputs[1]).toBe('{"ack":false}');
    await session.close();
  });

  it('refills the budget at the next utterance, with a fresh renderId', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);

    // Utterance one: the provider ends the speech, the model highlights once.
    fake.send(0, { type: 'input_audio_buffer.speech_started', audio_start_ms: 1 });
    fake.send(0, { type: 'input_audio_buffer.speech_stopped', audio_end_ms: 1 });
    highlightCall(fake, 'call_h1');
    await recorder.waitFor(
      (action) => action.kind === 'control' && action.event.type === 'ar_render',
    );

    // Utterance two: the translator's speech_end is the boundary Java also begins a turn on.
    fake.send(0, { type: 'input_audio_buffer.speech_started', audio_start_ms: 2 });
    fake.send(0, { type: 'input_audio_buffer.speech_stopped', audio_end_ms: 2 });
    highlightCall(fake, 'call_h2');
    await waitUntil(() => renderEvents(recorder.actions).length === 2, 'second utterance render');
    // The render control reaches the recorder before the acknowledgement is written, so the
    // provider-side receipt is what the assertion below actually waits on.
    await waitUntil(() => injected(fake).length === 2, 'second acknowledgement');

    const [first, second] = renderEvents(recorder.actions);
    expect(first!.renderId).not.toBe(second!.renderId);
    expect(injected(fake).map((message) => JSON.parse((message.item as { output: string }).output))).toEqual([
      { ack: true, renderId: first!.renderId },
      { ack: true, renderId: second!.renderId },
    ]);
    await session.close();
  });

  it('queues the ack behind a Java tool call so one response.create answers the batch', async () => {
    const fake = await fakeQwen();
    const { session, recorder } = await open(fake);

    fake.send(0, {
      type: 'response.function_call_arguments.done',
      call_id: 'call_time',
      name: 'get_time',
      arguments: '{}',
    });
    highlightCall(fake, 'call_h1');
    await waitUntil(
      () => recorder.actions.some((action) => action.kind === 'tool_call'),
      'java tool call',
    );

    // Only the read tool crosses to Java; the render is the bridge's own.
    expect(
      recorder.actions
        .filter((action) => action.kind === 'tool_call')
        .map((action) => (action as { id: string }).id),
    ).toEqual(['call_time']);
    expect(renderEvents(recorder.actions)).toHaveLength(1);
    // The highlight answer waits for the earlier call: one continuation, in call order.
    expect(injected(fake)).toEqual([]);

    session.toolResult('call_time', true, 'time');
    await waitUntil(
      () => fake.connections[0]!.messages.some((message) => message.type === 'response.create'),
      'continuation',
    );
    expect(injected(fake).map((message) => (message.item as { call_id: string }).call_id)).toEqual([
      'call_time',
      'call_h1',
    ]);
    expect(
      fake.connections[0]!.messages.filter((message) => message.type === 'response.create'),
    ).toHaveLength(1);
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
