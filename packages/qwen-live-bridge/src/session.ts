// The Qwen realtime connection and its lifecycle (spec X2, X3, X9, X10).
//
// One session per phone socket. It owns the provider WebSocket, the session config, the
// seven declared read-only tools, the forwarding seams and the 119-minute reopen. It
// never logs and never touches stdout: what it learns is handed to the caller as actions.
//
// The credentials arrive only as option values (the Java parent clears its environment
// and passes exactly these), so nothing here reads or echoes the process environment.
import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import type { ContextEntry, PhoneEvent } from './protocol.js';
import { PROTOCOL_VERSION } from './protocol.js';
import { createTranslator, parseHighlightCall, type Translator } from './translate.js';

/** What the session asks the caller to write to stdout. */
export type SessionAction =
  | { kind: 'control'; event: PhoneEvent }
  | { kind: 'audio'; dataBase64: string }
  | { kind: 'tool_call'; id: string; name: string; arguments: Record<string, unknown> }
  | { kind: 'turn_done' };

export interface SessionOptions {
  /** The provider endpoint; the caller derives it from the workspace unless configured. */
  url: string;
  apiKey: string;
  workspace: string;
  /** The conversation's last turns, seeded on every open and never answered by the model. */
  context?: ContextEntry[];
  onAction: (action: SessionAction) => void;
  /** The session cannot continue: 1011 dead provider socket, 4012 failed reopen. */
  onFatal: (code: number) => void;
  clock?: () => number;
  /** The provider's session cap minus one minute (spec X9). */
  reopenAfterMs?: number;
}

export interface LiveSession {
  /** Resolves once the provider session is configured and the context is seeded. */
  ready(): Promise<void>;
  appendAudio(dataBase64: string): void;
  sendText(text: string): void;
  attachImage(dataBase64: string): void;
  commit(options?: { image?: boolean }): void;
  cancel(): void;
  toolResult(id: string, ok: boolean, output: string): void;
  tower(text: string): void;
  close(): Promise<void>;
  /** Metadata only: frames refused while reconfiguring, results for unknown calls. */
  stats(): { refusedSends: number; droppedToolResults: number };
}

/** The provider's cap is 120 minutes; the bridge leaves a minute to reopen (spec X9). */
export const DEFAULT_REOPEN_MS = 119 * 60 * 1000;
/** Audio held while the session (re)configures: ~4 s of 20 ms phone chunks. */
export const MAX_PENDING_AUDIO = 200;
/** Non-audio operations held while the session (re)configures (a commit, a result, a tower). */
const MAX_PENDING_OPS = 200;
/** A provider that neither creates nor updates a session in this window is unusable. */
export const READY_TIMEOUT_MS = 15_000;
/** How long a close frame may take before the socket is torn down. */
const CLOSE_GRACE_MS = 500;
/** The fixed read errors' arg names mirror `LiveToolExecutor` (`id`). */
export interface ToolDeclaration {
  type: 'function';
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

const STRING_ID = {
  id: { type: 'string', description: 'The entity id (a UUID).' },
} as const;

const NO_ARGUMENTS = { type: 'object', properties: {}, required: [] } as const;

/** The seven read-only tools of spec X7; the parameter names are Java's (Task 3). */
export const TOOL_DECLARATIONS: ToolDeclaration[] = [
  {
    type: 'function',
    function: {
      name: 'get_time',
      description: 'The current date and time. Always use this instead of guessing the clock.',
      parameters: NO_ARGUMENTS,
    },
  },
  {
    type: 'function',
    function: {
      name: 'get_run',
      description: 'One agent run by id: its agent, status, timing and latest output preview.',
      parameters: { type: 'object', properties: STRING_ID, required: ['id'] },
    },
  },
  {
    type: 'function',
    function: {
      name: 'get_latest_conversation',
      description: 'The most recently active conversation and its latest turns.',
      parameters: NO_ARGUMENTS,
    },
  },
  {
    type: 'function',
    function: {
      name: 'list_approvals',
      description: 'Approvals waiting for the operator, or by status.',
      parameters: {
        type: 'object',
        properties: {
          status: {
            type: 'string',
            description: 'Filter by status; omit for everything pending or recent.',
          },
        },
        required: [],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'list_agents',
      description: 'The agents on this Aria installation: name, core mode and health.',
      parameters: NO_ARGUMENTS,
    },
  },
  {
    type: 'function',
    function: {
      name: 'get_dashboard_summary',
      description: 'The dashboard totals: runs, failures, pending approvals and agents.',
      parameters: NO_ARGUMENTS,
    },
  },
  {
    type: 'function',
    function: {
      name: 'get_workflow',
      description: 'One workflow chain by id: its steps and their latest outcomes.',
      parameters: { type: 'object', properties: STRING_ID, required: ['id'] },
    },
  },
  {
    // The AR display tool (spec D2): the bridge executes it, so Java's tool executor
    // never sees the name. The declaration is the verbatim phone/catalogue contract.
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
  },
];

/** The display tool the bridge answers itself; every other call is Java's (spec D2). */
const HIGHLIGHT_TOOL = 'highlight_target';
/** How long the phone keeps a highlight on the HUD (spec D1). */
const HIGHLIGHT_TTL_MS = 8000;

/**
 * Short and role-only: the phone speaks Cantonese and the tools carry the facts. The AR
 * line is the only instruction about a picture (spec D2): answer a look question with a
 * render, and say 睇唔到 rather than guessing a box when nothing can be placed.
 */
const INSTRUCTIONS = [
  '你是 Aria，香港廣東話語音助理。',
  '只可以用廣東話回答，句子要短，適合即時語音。',
  '時間、run、approval、agent、workflow 等事實一定要用工具查，唔准靠記憶或者估計。',
  '問時間要用 get_time 嘅 now（本地時間），唔好自己推算時區。',
  '工具結果返嚟之後，用一句話講重點，唔需要讀出全部內容。',
  '[tower] 開頭嘅訊息係背景系統通知，用一句話講返俾用戶聽。',
  '有圖嘅時候，用戶講『睇下／邊度／邊個』，先叫 highlight_target，再用一句最多 15 字講出答案。冇圖或者唔肯定，就講睇唔到，唔准估框。',
].join('\n');

/** A reconnect that cannot reach the provider: the phone gets 4012, not a retry loop. */
class OpenFailure extends Error {
  constructor(readonly reason: string) {
    super(reason);
  }
}

export function startSession(options: SessionOptions): LiveSession {
  const clock = options.clock ?? Date.now;
  const reopenAfterMs = options.reopenAfterMs ?? DEFAULT_REOPEN_MS;
  const translator: Translator = createTranslator({ clock });

  let socket: WebSocket | null = null;
  let configured = false;
  let closing = false;
  let fataled = false;
  /** Consecutive provider-initiated closes; a healthy gap starts the count over. */
  let silentReopens = 0;
  let lastSilentReopenAt = 0;
  let firstOpenedAt: number | null = null;
  let reopenTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingImage: string | null = null;
  /** A `speech_stopped` since the previous commit already asked for the answer. */
  let speechStoppedSinceCommit = false;
  /** Frames held while the session (re)configures; replayed after `session.updated`. */
  let pendingSends: Array<Record<string, unknown>> = [];
  let pendingAudio = 0;
  let pendingOps = 0;
  /** Calls issued to Java and not yet answered, in the order the model issued them. */
  let callOrder: string[] = [];
  const outstandingCalls = new Set<string>();
  const answeredResults = new Map<string, Record<string, unknown>>();
  /** A render already reached the phone in this utterance (spec D1: at most one per turn). */
  let renderedThisTurn = false;
  let refusedSends = 0;
  let droppedToolResults = 0;

  const emit = (action: SessionAction) => {
    if (!fataled && !closing) {
      options.onAction(action);
    }
  };

  const emitEvent = (event: PhoneEvent) => emit({ kind: 'control', event });

  /**
   * Write one provider frame if the socket can take it. The handshake uses this directly;
   * everything the phone sends goes through {@link send}, which also requires the session
   * to be configured.
   */
  const transmit = (payload: Record<string, unknown>): boolean => {
    const ws = socket;
    if (ws === null || ws.readyState !== WebSocket.OPEN) {
      return false;
    }
    ws.send(JSON.stringify(payload));
    return true;
  };

  const send = (payload: Record<string, unknown>, audio = false) => {
    if (closing || fataled) {
      return;
    }
    if (configured && transmit(payload)) {
      return;
    }
    if (audio) {
      if (pendingAudio >= MAX_PENDING_AUDIO) {
        refusedSends += 1;
        return;
      }
      pendingAudio += 1;
    } else {
      if (pendingOps >= MAX_PENDING_OPS) {
        refusedSends += 1;
        return;
      }
      pendingOps += 1;
    }
    pendingSends.push(payload);
  };

  /** The new session is configured: everything held for it goes out, in arrival order. */
  const flushPendingSends = () => {
    const held = pendingSends;
    pendingSends = [];
    pendingAudio = 0;
    pendingOps = 0;
    for (const payload of held) {
      if (!transmit(payload)) {
        refusedSends += 1;
      }
    }
  };

  /** Nothing about the previous provider session survives into the next one. */
  const resetProviderState = () => {
    pendingImage = null;
    speechStoppedSinceCommit = false;
    callOrder = [];
    outstandingCalls.clear();
    answeredResults.clear();
    renderedThisTurn = false;
  };

  const clearReopen = () => {
    if (reopenTimer !== null) {
      clearTimeout(reopenTimer);
      reopenTimer = null;
    }
  };

  const fail = (code: number) => {
    if (fataled) {
      return;
    }
    fataled = true;
    clearReopen();
    const dead = socket;
    socket = null;
    teardown(dead);
    options.onFatal(code);
  };

  const sessionConfig = () => ({
    modalities: ['text', 'audio'],
    instructions: INSTRUCTIONS,
    audio: {
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
      output: {
        voice: 'Kiki',
        format: { type: 'pcm', sample_rate: 24000 },
      },
    },
    input_audio_transcription: { model: 'qwen3-asr-flash-realtime' },
    // The contract's server-side semantic turn detection (spec X2). P0 ran with null,
    // so this shape is the one point of the config that no probe has confirmed.
    turn_detection: { type: 'semantic_vad_ec' },
    tools: TOOL_DECLARATIONS,
    tool_choice: 'auto',
  });

  /** The conversation so far, as message items. Seeding never asks for a response. */
  const seedContext = () => {
    for (const entry of options.context ?? []) {
      const role = entry.role === 'assistant' ? 'assistant' : 'user';
      send({
        type: 'conversation.item.create',
        item: {
          type: 'message',
          role,
          content: [{ type: role === 'assistant' ? 'text' : 'input_text', text: entry.text }],
        },
      });
    }
  };

  /**
   * Inject every answered call the conversation can already continue from, in the order
   * the model issued them, then ask for one continuation once nothing is outstanding. A
   * second `response.create` while the first is already answering is the double-request
   * the phone would see as an error.
   */
  const flushAnsweredCalls = () => {
    for (;;) {
      const head = callOrder[0];
      const item = head === undefined ? undefined : answeredResults.get(head);
      if (head === undefined || item === undefined) {
        break;
      }
      callOrder.shift();
      answeredResults.delete(head);
      outstandingCalls.delete(head);
      send({ type: 'conversation.item.create', item });
    }
    if (outstandingCalls.size === 0) {
      send({ type: 'response.create' });
    }
  };

  /**
   * Spec D2: `highlight_target` is the bridge's own display call, never Java's. A placeable
   * box becomes one `ar_render` v2 control (spec D1: no `runId`, one per utterance) and a
   * `{ack:true,renderId}` answer; an unplaceable box, or a second call in the same
   * utterance, answers `{ack:false}` with no render so the model says 睇唔到. The answer
   * joins the same ordered queue as Java's tool results.
   */
  const answerHighlight = (id: string, args: Record<string, unknown>) => {
    const target = renderedThisTurn ? null : parseHighlightCall(args);
    let output: Record<string, unknown> = { ack: false };
    if (target !== null) {
      renderedThisTurn = true;
      const renderId = randomUUID();
      emitEvent({
        type: 'ar_render',
        v: PROTOCOL_VERSION,
        renderId,
        render: {
          primitive: 'highlight',
          target: { label: target.label, bbox: target.bbox },
          text: target.text,
          ttlMs: HIGHLIGHT_TTL_MS,
          coord: 'normalized',
          safetyAlert: null,
        },
      });
      output = { ack: true, renderId };
    }
    // Track it like a Java call so one `response.create` still closes the batch.
    if (!outstandingCalls.has(id)) {
      outstandingCalls.add(id);
      callOrder.push(id);
    }
    answeredResults.set(id, {
      type: 'function_call_output',
      call_id: id,
      output: JSON.stringify(output),
    });
    flushAnsweredCalls();
  };

  /** Apply one provider event: the translator decides, this function obeys. */
  const dispatch = (event: Record<string, unknown>) => {
    if (event.type === 'input_audio_buffer.speech_stopped') {
      // With `semantic_vad_ec` the provider answers this itself; a phone commit that
      // follows must not ask for a second response (it would race the first).
      speechStoppedSinceCommit = true;
    }
    for (const translation of translator.onEvent(event)) {
      if (translation.kind === 'cancel') {
        send({ type: 'response.cancel' });
        continue;
      }
      if (translation.kind === 'tool_call') {
        if (translation.name === HIGHLIGHT_TOOL) {
          // Display only: the bridge answers it; Java must never see the call.
          answerHighlight(translation.id, translation.arguments);
          continue;
        }
        // Track the call before the result can arrive, so an answer is never "unknown".
        if (!outstandingCalls.has(translation.id)) {
          outstandingCalls.add(translation.id);
          callOrder.push(translation.id);
        }
      }
      if (translation.kind === 'control' && translation.event.type === 'speech_end') {
        // Spec D1: one render per *utterance*, and the utterance starts at the translator's
        // `speech_end` — the same boundary Java begins a turn on. `response.done` is not it:
        // a highlight turn ends with the tool response, and the spoken answer is a second
        // response in the same utterance.
        renderedThisTurn = false;
      }
      if (translation.kind === 'turn_done') {
        // Backstop: a turn the provider ends without a further utterance still starts clean.
        renderedThisTurn = false;
      }
      emit(translation);
    }
  };

  /**
   * Open one provider socket and resolve once its session is configured. A failure
   * during the handshake rejects with {@link OpenFailure}; a close after it is a fatal
   * dead socket. The identity check keeps a swapped-out socket silent.
   */
  const open = (): Promise<void> =>
    new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(options.url, {
        headers: {
          Authorization: `Bearer ${options.apiKey}`,
          'X-DashScope-WorkSpace': options.workspace,
        },
      });
      socket = ws;
      configured = false;

      let settled = false;
      const timeout = setTimeout(() => {
        if (!settled) {
          settled = true;
          try {
            ws.terminate();
          } catch {
            // Already gone.
          }
          reject(new OpenFailure('ready timeout'));
        }
      }, READY_TIMEOUT_MS);
      timeout.unref?.();

      const settle = (failure: string | null) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timeout);
        if (failure === null) {
          resolve();
        } else {
          reject(new OpenFailure(failure));
        }
      };

      ws.on('message', (data) => {
        let parsed: unknown;
        try {
          parsed = JSON.parse(data.toString());
        } catch {
          return;
        }
        // A frame of literal `null` (or any non-object) carries no event: ignore it.
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          return;
        }
        const event = parsed as Record<string, unknown>;
        if (socket !== ws) {
          return;
        }
        switch (event.type) {
          case 'session.created':
            // Before `session.updated`: the one frame that must bypass the configured gate.
            transmit({ type: 'session.update', session: sessionConfig() });
            return;
          case 'session.updated':
            configured = true;
            seedContext();
            flushPendingSends();
            if (firstOpenedAt === null) {
              firstOpenedAt = clock();
            }
            settle(null);
            return;
          default:
            if (configured) {
              dispatch(event);
            }
            return;
        }
      });

      ws.on('error', () => {
        if (!settled) {
          settle('socket error');
          return;
        }
        if (socket === ws && !closing) {
          fail(1011);
        }
      });

      ws.on('close', () => {
        if (!settled) {
          settle('socket closed during the handshake');
          return;
        }
        if (socket === ws && !closing && !fataled) {
          providerClosed();
        }
      });
    });

  const scheduleReopen = () => {
    clearReopen();
    reopenTimer = setTimeout(() => {
      reopenTimer = null;
      void reopen();
    }, reopenAfterMs);
    reopenTimer.unref?.();
  };

  /**
   * Spec X9: the provider caps a session at 120 minutes and has no resumption handle,
   * so the bridge tells the phone, drops the socket, re-opens and reseeds from Aria's
   * context. The phone sees one `session_expired` and then one `resumed`.
   */
  const reopen = async () => {
    emitEvent(translator.expiredEvent());
    // From here until the new `session.updated`, nothing may reach the old socket: what
    // the phone sends is held (bounded) and replayed into the new session.
    configured = false;
    resetProviderState();
    const old = socket;
    socket = null;
    teardown(old);
    try {
      await open();
    } catch {
      fail(4012);
      return;
    }
    const openedAt = firstOpenedAt ?? clock();
    const handleAgeSec = Math.max(0, Math.round((clock() - openedAt) / 1000));
    emitEvent(translator.resumedEvent(handleAgeSec));
    scheduleReopen();
  };

  /**
   * The provider closed a configured session by itself (observed at ~300 s on the SG
   * endpoint). The phone's socket never closed, so this is not its failure: rebuild the
   * provider session in place, reseed, and keep the phone's live state untouched. A
   * session that keeps dying right after it opens is a real failure: three consecutive
   * quick reopens, then 1011; a failed handshake is 4012 (spec X9).
   */
  const providerClosed = () => {
    const now = clock();
    silentReopens = now - lastSilentReopenAt < 10_000 ? silentReopens + 1 : 1;
    lastSilentReopenAt = now;
    if (silentReopens > 3) {
      fail(1011);
      return;
    }
    configured = false;
    resetProviderState();
    const old = socket;
    socket = null;
    teardown(old);
    void open().catch(() => fail(4012));
  };

  const startup = (async () => {
    try {
      await open();
    } catch {
      fail(1011);
      throw new Error('provider session failed to open');
    }
    scheduleReopen();
  })();
  // A caller that never awaits `ready()` must still not see an unhandled rejection.
  startup.catch(() => undefined);

  return {
    ready: () => startup,

    appendAudio(dataBase64) {
      send({ type: 'input_audio_buffer.append', audio: dataBase64 }, true);
    },

    sendText(text) {
      send({
        type: 'conversation.item.create',
        item: { type: 'message', role: 'user', content: [{ type: 'input_text', text }] },
      });
      send({ type: 'response.create' });
    },

    attachImage(dataBase64) {
      // Held until the utterance's commit: the phone's ordering is audio, image, commit,
      // and the provider's image buffer must see the same order (spec X10).
      pendingImage = dataBase64;
    },

    commit(commitOptions = {}) {
      const image = commitOptions.image === true ? pendingImage : null;
      if (image !== null) {
        send({ type: 'input_image_buffer.append', image });
      }
      pendingImage = null;
      // `semantic_vad_ec` already answered on `speech_stopped`; asking again here races
      // that response and the provider rejects the second request. An image (or a commit
      // after no `speech_stopped` at all) still has to ask for one.
      const askForResponse = image !== null || !speechStoppedSinceCommit;
      speechStoppedSinceCommit = false;
      send({ type: 'input_audio_buffer.commit' });
      if (askForResponse) {
        send({ type: 'response.create' });
      }
    },

    cancel() {
      // The phone hung up on the answer: the rest of its audio must not reach playback.
      translator.phoneCancel();
      send({ type: 'response.cancel' });
    },

    toolResult(id: string, _ok: boolean, output: string) {
      if (!outstandingCalls.has(id)) {
        // Not a call this session issued (or already answered): the provider would not
        // know the id, and forwarding it would corrupt the conversation.
        droppedToolResults += 1;
        return;
      }
      outstandingCalls.delete(id);
      answeredResults.set(id, { type: 'function_call_output', call_id: id, output });
      // Inject in the order the model issued the calls, even when Java answered out of
      // order; a result whose turn has not come waits in the queue.
      flushAnsweredCalls();
    },

    tower(text) {
      send({
        type: 'conversation.item.create',
        item: { type: 'message', role: 'user', content: [{ type: 'input_text', text }] },
      });
      send({ type: 'response.create' });
    },

    async close() {
      if (closing) {
        return;
      }
      closing = true;
      clearReopen();
      // A frame the phone attached but never committed is not carried past the socket.
      resetProviderState();
      pendingSends = [];
      pendingAudio = 0;
      pendingOps = 0;
      const dead = socket;
      socket = null;
      teardown(dead);
    },

    stats() {
      return { refusedSends, droppedToolResults };
    },
  };

  /** Best-effort orderly close with a bounded grace; the process never outlives it. */
  function teardown(ws: WebSocket | null) {
    if (ws === null || ws.readyState === WebSocket.CLOSED) {
      return;
    }
    try {
      ws.close();
    } catch {
      // Already closing.
    }
    const grace = setTimeout(() => {
      try {
        ws.terminate();
      } catch {
        // Already gone.
      }
    }, CLOSE_GRACE_MS);
    grace.unref?.();
    ws.once('close', () => clearTimeout(grace));
  }
}
