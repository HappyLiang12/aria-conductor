// The Qwen realtime connection and its lifecycle (spec X2, X3, X9, X10).
//
// One session per phone socket. It owns the provider WebSocket, the session config, the
// fixed tool set, the forwarding seams and the 119-minute reopen. It
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
  /** The phone's AR mode from `hello.ar` (spec D5); absent is AR-off. */
  ar?: boolean;
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
  /**
   * The phone's AR mode changed (spec D5): the next commit is held for its frame, or the
   * Phase 2 auto-commit is back. A configured session is reconfigured in place (D4).
   */
  setAr(on: boolean): void;
  cancel(): void;
  toolResult(id: string, ok: boolean, output: string): void;
  tower(text: string): void;
  close(): Promise<void>;
  /**
   * Metadata only: frames refused while reconfiguring, results for unknown calls, an
   * `ar` switch the provider refused (or never answered within its settle window); the
   * mode was reverted, never forwarded, and AR-on wishes the commit guard held back.
   */
  stats(): {
    refusedSends: number;
    droppedToolResults: number;
    refusedArSwitches: number;
    arGuardHolds: number;
  };
}

/** The provider's cap is 120 minutes; the bridge leaves a minute to reopen (spec X9). */
export const DEFAULT_REOPEN_MS = 119 * 60 * 1000;
/** Audio held while the session (re)configures: ~4 s of 20 ms phone chunks. */
export const MAX_PENDING_AUDIO = 200;
/** Non-audio operations held while the session (re)configures (a commit, a result, a tower). */
const MAX_PENDING_OPS = 200;
/** A provider that neither creates nor updates a session in this window is unusable. */
export const READY_TIMEOUT_MS = 15_000;
/**
 * Spec D4: in AR the phone commits at its speech end and its frame follows ~150 ms later,
 * so the provider commit is held for at most this long waiting for that frame.
 */
export const AR_FRAME_GRACE_MS = 300;
/**
 * Spec D4/D5: a `session.update` the provider neither acknowledges nor refuses in this
 * window is settled as refused. Without a bound a silently dropped update would sit in
 * flight forever and every later `setAr` would only move the wish.
 */
export const AR_UPDATE_SETTLE_MS = 2_000;
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

/** The live tool set: seven reads, three ops reads, two approval-gated writes (spec X7/X15). */
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
  // Ops tier reads (spec X15): direct, never gated.
  {
    type: 'function',
    function: {
      name: 'list_skills',
      description: 'List skills by stage and enabled state. Never returns their template.',
      parameters: {
        type: 'object',
        properties: {
          stage: { type: 'string', description: 'SKILL, SCRIPT or WORKFLOW; omit for every stage.' },
          enabled: { type: 'boolean', description: 'true = enabled only, false = disabled only.' },
        },
        required: [],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'list_runs',
      description: 'List recent runs, optionally filtered by agent and status.',
      parameters: {
        type: 'object',
        properties: {
          agentId: { type: 'string', description: 'Agent id (a UUID); omit for every agent.' },
          status: {
            type: 'string',
            description:
              'PENDING, INITIALIZING, RUNNING, PAUSED, WAITING_INPUT, COMPLETED, FAILED, CANCELLED or ABORTED.',
          },
        },
        required: [],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'get_agent',
      description: 'One agent by id: name, type, role, model, provider, health and skills.',
      parameters: { type: 'object', properties: STRING_ID, required: ['id'] },
    },
  },
  // Ops tier writes (spec X15): the operator must approve before they run.
  {
    type: 'function',
    function: {
      name: 'start_agent_run',
      description: 'Start a run for an agent. The operator approves before it starts.',
      parameters: {
        type: 'object',
        properties: {
          agentId: { type: 'string', description: 'Agent id (a UUID).' },
          prompt: { type: 'string', description: 'The instruction the agent starts from.' },
          maxIterations: {
            type: 'integer',
            description: 'Maximum reasoning iterations; omit for the service default.',
          },
        },
        required: ['agentId', 'prompt'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'run_skill',
      description: 'Run an enabled skill with Aria. The operator approves before it starts.',
      parameters: {
        type: 'object',
        properties: {
          skillId: { type: 'string', description: 'The enabled skill id.' },
          prompt: { type: 'string', description: 'Optional instruction; omit to run the skill as-is.' },
        },
        required: ['skillId'],
      },
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
  '[tower] 開頭嘅文字（訊息或者工具結果）都係背景系統通知，用一句話講返俾用戶聽。',
  '有圖嘅時候，用戶講『睇下／邊度／邊個』，先叫 highlight_target，再用一句最多 15 字講出答案。冇圖或者唔肯定，就講睇唔到，唔准估框。',
].join('\n');

/** A phone commit held in AR: the frame side (`due`) and the mode side (`awaitMode`). */
interface DeferredCommit {
  image: boolean;
  timer: ReturnType<typeof setTimeout>;
  awaitMode: boolean;
  due: boolean;
}

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
  /**
   * Spec D4/D5: AR-off keeps Phase 2's server VAD; AR-on has none and commits explicitly.
   * This is the mode the phone asked for last, and what its own commit path follows.
   */
  let arDesired = options.ar === true;
  /**
   * Spec D5 compatibility guard: a phone that never sends a `commit` would get AR's
   * no-detection mode and then silence, because its only speech-end signal is a frame.
   * So an AR-on wish keeps the server VAD until one phone commit proves this session
   * drives its own turns; a phone that never commits degrades to AR-off. It resets only
   * with the bridge session, never with a provider reopen.
   */
  let commitObserved = false;
  /** The mode the provider should run: the phone's wish, gated by the commit guard. */
  const effectiveAr = () => arDesired && commitObserved;
  /**
   * The mode the provider's live session is known to hold. Only the acknowledgement of a
   * `session.update` moves it forward and only a refusal moves it back, so the bridge can
   * never claim a mode the provider is not running.
   */
  let arApplied = effectiveAr();
  /**
   * AR-on: the phone's commit, held for the frame that may still be behind it and, for the
   * commit that lifts the commit guard, for the `ar` update that proves the mode.
   * `due` is the frame side (the grace elapsed or the frame arrived); the mode side is
   * `awaitMode` against `arApplied`.
   */
  let deferredCommit: DeferredCommit | null = null;
  /**
   * AR-on: this utterance's commit already went out, so a frame that arrives now belongs
   * to no turn (spec D8). It lifts at the next turn boundary (`turn_done`) or commit.
   */
  let arCommitted = false;
  /**
   * Spec D4: at most one `session.update` for an `ar` change is in flight. The phone's
   * latest wish waits in `arDesired`, so a rapid off→on→off cannot overwrite the state a
   * refusal has to revert to — the provider answers one update at a time, and
   * `reconcileAr` sends whatever still differs afterwards. The timer is the bounded
   * settle: an update the provider never answers is re-sent once, and only a second
   * silence is treated as refused.
   */
  let arUpdateInFlight: { target: boolean; timer: ReturnType<typeof setTimeout> } | null = null;
  /**
   * The phone's current wish already went out twice without an answer. A timeout is not
   * proof of refusal (the provider may just be slow), so the first silence spends this
   * retry and the second gives the wish up.
   */
  let arRetried = false;
  /**
   * The mode the latest `ar` `session.update` carried. A `session.updated` that lands after
   * the settle already gave that update up still proves the provider took it, so the
   * acknowledgement repairs `arApplied` from here instead of landing on no branch.
   */
  let lastSentAr: boolean | null = null;
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
  let refusedArSwitches = 0;
  /** AR-on wishes the commit guard kept on the server VAD (metadata only). */
  let arGuardHolds = arDesired ? 1 : 0;

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

  /** Returns whether the frame went out on this socket (false: held, refused or dead). */
  const send = (payload: Record<string, unknown>, audio = false): boolean => {
    if (closing || fataled) {
      return false;
    }
    if (configured && transmit(payload)) {
      return true;
    }
    if (audio) {
      if (pendingAudio >= MAX_PENDING_AUDIO) {
        refusedSends += 1;
        return false;
      }
      pendingAudio += 1;
    } else {
      if (pendingOps >= MAX_PENDING_OPS) {
        refusedSends += 1;
        return false;
      }
      pendingOps += 1;
    }
    pendingSends.push(payload);
    return false;
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

  /** Forgets a held AR commit without sending it: the socket it was for is gone. */
  const cancelDeferredCommit = () => {
    if (deferredCommit !== null) {
      clearTimeout(deferredCommit.timer);
      deferredCommit = null;
    }
    arCommitted = false;
  };

  /** Disarms the settle for the one in-flight `ar` update (ack, refusal, reset, close). */
  const forgetArUpdate = () => {
    if (arUpdateInFlight !== null) {
      clearTimeout(arUpdateInFlight.timer);
      arUpdateInFlight = null;
    }
    // A refusal or a dead socket also orphans the sent target: only a late acknowledgement
    // of an update that did land may still read it.
    lastSentAr = null;
    // The abandoned (or answered) send no longer holds the wish's one retry.
    arRetried = false;
  };

  /**
   * Drops every `ar` reconfiguration still waiting in the send queue. A frame queued for
   * a socket that was going away must never be replayed into the rebuilt session: that
   * handshake already carries the phone's current wish, so the queued one is stale by
   * construction and its acknowledgement would land on no in-flight update.
   */
  const dropQueuedArUpdates = () => {
    if (pendingSends.length === 0) {
      return;
    }
    const kept = pendingSends.filter((payload) => payload.type !== 'session.update');
    const dropped = pendingSends.length - kept.length;
    pendingSends = kept;
    pendingOps = Math.max(0, pendingOps - dropped);
  };

  /**
   * The settle of one `ar` update expired. A timeout is not proof of refusal: the provider
   * may still acknowledge the update it holds, and that acknowledgement must land on the
   * mode the phone asked for. So the phone's current wish is re-sent once (the retry keeps
   * `lastSentAr` on it, so either send's late ack adopts it) and `arDesired` is never
   * reverted while a retry can still prove it. Only a second unanswered send gives the wish
   * up — one switch counted and the wish collapsed onto `arApplied`, exactly as an explicit
   * refusal does, so a later `setAr` asks again instead of being swallowed.
   */
  const settleArUpdate = () => {
    if (arUpdateInFlight === null) {
      return;
    }
    clearTimeout(arUpdateInFlight.timer);
    arUpdateInFlight = null;
    if (effectiveAr() === arApplied) {
      // The phone went back to the applied mode while this update was in flight: nothing
      // is left to retry and no switch was refused.
      flushDeferredCommit();
      return;
    }
    if (!arRetried && configured && !closing && !fataled) {
      arRetried = true;
      reconcileAr();
      return;
    }
    refusedArSwitches += 1;
    arDesired = arApplied;
    arRetried = false;
    reconcileAr();
    // The wish was given up: a commit held for it falls back to the VAD path.
    flushDeferredCommit();
  };

  /** Nothing about the previous provider session survives into the next one. */
  const resetProviderState = () => {
    pendingImage = null;
    // Spec D8: an image held for a commit that will never reach this session is dropped,
    // and an AR commit still waiting for its frame dies with the session it belonged to.
    cancelDeferredCommit();
    // An unacknowledged or unanswered `ar` update died with the socket it was sent on;
    // the rebuilt session's own config carries the mode the phone wants, and no settle is
    // left to fire. A queued one is dropped rather than replayed into that handshake.
    forgetArUpdate();
    dropQueuedArUpdates();
    arApplied = effectiveAr();
    speechStoppedSinceCommit = false;
    callOrder = [];
    outstandingCalls.clear();
    answeredResults.clear();
    renderedThisTurn = false;
  };

  /**
   * Spec D4/D8: the one AR commit path. The phone's commit and its frame race, so the
   * image (when the commit asked for one) goes first, then the explicit commit, then the
   * response. `beginUtterance` re-arms the translator's `speech_end` first: with no turn
   * detection the provider never sends `speech_started`, so without it the second
   * utterance's commit would produce no `speech_end` at all.
   *
   * The commit that lifts the guard (spec D5) is held past the frame grace until the
   * provider answers the `ar` update: the provider may still be on the server VAD and
   * have auto-committed and answered that utterance, so a flush would commit an empty
   * buffer or ask for a second answer. When the update is refused or given up, the
   * utterance falls back to the AR-off path — the commit, and a response only when the
   * VAD has not already asked for one — instead of erroring.
   */
  const flushDeferredCommit = () => {
    const held = deferredCommit;
    if (held === null || !held.due) {
      return;
    }
    if (held.awaitMode && !arApplied && effectiveAr()) {
      // The mode this commit belongs to is still unproven: the acknowledgement (or the
      // refusal that collapses the wish) flushes it, never the provider's VAD race.
      return;
    }
    clearTimeout(held.timer);
    deferredCommit = null;
    arCommitted = true;
    const image = held.image ? pendingImage : null;
    pendingImage = null;
    if (image !== null) {
      send({ type: 'input_image_buffer.append', image });
    }
    if (!held.awaitMode || arApplied) {
      translator.beginUtterance();
      send({ type: 'input_audio_buffer.commit' });
      send({ type: 'response.create' });
      return;
    }
    // The guard's mode never applied: the provider still runs `semantic_vad_ec` and has
    // already answered on `speech_stopped`, so only the commit may cross.
    const askForResponse = image !== null || !speechStoppedSinceCommit;
    speechStoppedSinceCommit = false;
    send({ type: 'input_audio_buffer.commit' });
    if (askForResponse) {
      send({ type: 'response.create' });
    }
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
    // The same teardown as close/reset: a held AR commit must not fire into a session
    // that has no socket left to receive it.
    resetProviderState();
    const dead = socket;
    socket = null;
    teardown(dead);
    options.onFatal(code);
  };

  const sessionConfig = (ar: boolean) => ({
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
    // Spec D4, from the live probe: AR-off keeps the Phase 2 server VAD (auto-commit,
    // barge-in). AR-on runs with none, because `semantic_vad_ec` with
    // `create_response:false` never commits nor answers the image turn, while an explicit
    // commit after the image append does; the phone's commit drives the turn instead.
    turn_detection: ar ? null : { type: 'semantic_vad_ec' },
    tools: TOOL_DECLARATIONS,
    tool_choice: 'auto',
  });

  /**
   * Spec D4: one `session.update` for an `ar` change at a time. `arDesired` holds the
   * phone's latest wish; an acknowledgement settles the one that went out and this sends
   * whatever still differs, so off→on→off lands on the last mode instead of overwriting
   * the state a refusal has to revert to. Nothing crosses while the session rebuilds: the
   * handshake's own config carries the effective mode and the ack re-conciles. Each
   * update carries a bounded settle, so one the provider silently drops cannot pin
   * `arUpdateInFlight` forever and swallow every later `setAr`; the first silence re-sends
   * the wish, the second gives it up.
   */
  const reconcileAr = () => {
    if (arUpdateInFlight !== null || !configured || closing || fataled) {
      return;
    }
    const target = effectiveAr();
    if (target === arApplied) {
      return;
    }
    const timer = setTimeout(settleArUpdate, AR_UPDATE_SETTLE_MS);
    timer.unref?.();
    // Even a frame the socket queues (it is going away) counts as in flight: the ack that
    // replays it, or the reopen's reset, is what settles it.
    arUpdateInFlight = { target, timer };
    lastSentAr = target;
    send({ type: 'session.update', session: sessionConfig(target) });
  };

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
    if (event.type === 'error' && arUpdateInFlight !== null) {
      // Spec D4: an `error` before `session.updated` is the provider refusing the
      // mid-session reconfiguration, so it is still running the last applied mode. Revert
      // to that mode (the error itself reaches the phone through the translator unchanged)
      // and count the refusal for the metadata line — the provider's text is never logged,
      // because it can quote the request. Unlike a silent settle, an explicit refusal is
      // proof: it is never retried. The phone's wish collapses onto what the provider really
      // runs, and a later toggle asks again.
      forgetArUpdate();
      arDesired = arApplied;
      refusedArSwitches += 1;
      // A commit held for this mode now falls back to the VAD path rather than waiting for
      // an acknowledgement that will never come.
      flushDeferredCommit();
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
        // Spec D8: an image that never reached a commit belongs to the turn that just
        // ended and is dropped, and AR's next-utterance frame window is open again — but
        // only when no phone commit is held. A `response.done` that lands inside the
        // grace belongs to the previous (or an overlapping) response, and clearing now
        // would wipe the frame the held commit is waiting for.
        if (deferredCommit === null) {
          pendingImage = null;
          arCommitted = false;
        }
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
      /** The mode this socket's handshake config carries; its ack makes it the applied one. */
      let handshakeAr = effectiveAr();

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
            // Its config carries the mode the phone wants right now, and its ack is what
            // makes that mode the applied one.
            handshakeAr = effectiveAr();
            transmit({ type: 'session.update', session: sessionConfig(handshakeAr) });
            return;
          case 'session.updated':
            if (!configured) {
              configured = true;
              seedContext();
              flushPendingSends();
              // The handshake's own config is now the provider's: what it carried is the
              // applied mode (the harness confirmed it), so a refusal can revert to it.
              arApplied = handshakeAr;
              if (firstOpenedAt === null) {
                firstOpenedAt = clock();
              }
              settle(null);
            } else if (arUpdateInFlight !== null) {
              // The `ar` reconfiguration that went out is acknowledged: it is the applied
              // mode now, whatever the phone asked for in the meantime, and its settle is
              // disarmed so it cannot fire against the next one. (A retried wish is answered
              // on this same branch: the ack proves the retry the settle sent did land.)
              const settled = arUpdateInFlight;
              clearTimeout(settled.timer);
              arApplied = settled.target;
              arUpdateInFlight = null;
              arRetried = false;
              lastSentAr = null;
            } else if (lastSentAr !== null) {
              // The provider answered an update the settle already gave up on: it did land
              // after all, so adopt the mode it proves (the latest sent target — on a retry,
              // the wish, never the mode it left) and reconcile the phone's latest wish onto
              // it (a no-op when the two already agree). No second refusal is counted: the
              // settle's one already paid for this silence.
              arApplied = lastSentAr;
              lastSentAr = null;
            }
            // A mid-session `session.update` (an `ar` toggle, spec D4) is an update to a
            // session that already holds its context: re-seeding here would replay every
            // seeded turn into the live conversation. A wish that arrived while this one
            // was in flight (or while the session rebuilt) crosses now, one at a time.
            reconcileAr();
            // The acknowledgement may be the proof a held commit was waiting for (spec D5).
            flushDeferredCommit();
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
      if (arDesired && arCommitted) {
        // Spec D8: this utterance's commit already went out, so the frame belongs to no
        // turn — it is dropped rather than carried into the next one.
        return;
      }
      // Held until the utterance's commit: the phone's ordering is audio, image, commit,
      // and the provider's image buffer must see the same order (spec X10).
      pendingImage = dataBase64;
      if (deferredCommit !== null) {
        // The frame the held commit is waiting for: the frame side of the hold is due now,
        // so flush at once unless the mode it belongs to is still unproven (spec D4/D5).
        deferredCommit.due = true;
        flushDeferredCommit();
      }
    },

    commit(commitOptions = {}) {
      const asksForImage = commitOptions.image === true;
      const liftsGuard = !commitObserved;
      if (liftsGuard) {
        // Spec D5: this phone drives its own turns, so the guard lifts. An AR-on wish that
        // was held on the VAD crosses now; the commit below then waits for its proof.
        commitObserved = true;
        if (arDesired) {
          reconcileAr();
        }
      }
      if (arDesired) {
        // Spec D4: the phone's commit ends its speech, not the turn — the frame it names
        // may still be ~150 ms behind, and the provider takes the image only before the
        // commit. Hold it for the grace or for the frame, whichever comes first.
        if (deferredCommit !== null) {
          clearTimeout(deferredCommit.timer);
        }
        arCommitted = false;
        if (!asksForImage) {
          // The Phase 2 rule holds in AR too: a frame this commit did not ask for is not
          // sent (spec X10).
          pendingImage = null;
        }
        const held = {
          image: asksForImage,
          // A guard-lifting commit belongs to the mode its `ar` update is proving: it must
          // not reach a provider that may still be on the VAD (spec D5).
          awaitMode: liftsGuard,
          due: false,
        } as DeferredCommit;
        held.timer = setTimeout(() => {
          if (deferredCommit !== held) {
            return;
          }
          held.due = true;
          flushDeferredCommit();
        }, AR_FRAME_GRACE_MS);
        held.timer.unref?.();
        deferredCommit = held;
        return;
      }
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

    setAr(on) {
      const next = on === true;
      if (next === arDesired) {
        // A repeat is the same mode: no second reconfiguration crosses.
        return;
      }
      // Spec D4: the switch is proven in place. The provider acknowledges it with
      // `session.updated` and refuses it with an `error`; until one of those arrives it
      // still runs `arApplied`, so only one update may be in flight and this wish waits
      // here (commits follow the wish, not the acknowledgement). While the session is
      // (re)configuring, `reconcileAr` sends it after `session.updated`, so the rebuilt
      // session's own config is never the stale one.
      arDesired = next;
      // A new wish gets its own retry: only this wish's sends spend it.
      arRetried = false;
      if (next && !commitObserved) {
        // Spec D5: the guard holds it on the server VAD until a phone commit proves the
        // phone drives its own turns.
        arGuardHolds += 1;
      }
      if (!next) {
        // Leaving AR with a commit held for a frame that may never come: the utterance is
        // due now rather than stranded when the grace timer is dropped.
        if (deferredCommit !== null) {
          deferredCommit.due = true;
        }
        flushDeferredCommit();
      }
      reconcileAr();
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
      return { refusedSends, droppedToolResults, refusedArSwitches, arGuardHolds };
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
