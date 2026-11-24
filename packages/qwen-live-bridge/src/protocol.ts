// The stdio wire between the Java `QwenLiveBridgeProcess` and this child (spec L1).
// One JSON object per line. The Java reader is the authority for what it accepts
// (`QwenLiveBridgeProcess.dispatch`), so every shape below mirrors a case there:
//
//   Java -> bridge : hello, audio{dir:"in"}, text, mic, ar, cancel, image, commit,
//                    tool_result, tower, close
//   bridge -> Java : audio{dir:"out"}, control{event}, tool_call, turn_done, fatal
//
// A `fatal` line is written when the child cannot go on (Task 6 maps it to a close
// code); the child then exits. Log lines are not part of this channel: they are
// metadata written to stderr only, so stdout stays pure control JSON (spec L1, L6).

/** The `v` every phone event carries (contract X4). */
export const PROTOCOL_VERSION = 1 as const;

/** The Qwen model the phone's `ready.model` names and this bridge configures. */
export const MODEL = 'qwen3.8-omni-flash-realtime';

/** `ready.maxSessionSec`: the provider's hard cap, always advertised this way. */
export const MAX_SESSION_SEC = 7200;

// --- Java -> bridge (stdin) ---------------------------------------------------------------

/** One seeded conversation turn, exactly as the timeline shows it (Java `LiveContextSource.Entry`). */
export interface ContextEntry {
  role: string;
  text: string;
}

/** `{"type":"hello","v":1,"conversationId":…,"ar":…,"arCommit":…,"context":[…]}` — the first line of a socket. */
export interface HelloLine {
  type: 'hello';
  v: number;
  conversationId: string;
  /** The phone's AR mode (spec D5); absent is AR-off. */
  ar?: boolean;
  /**
   * The phone drives every AR turn itself, first look included (D5 amendment, 2026-11-25):
   * the commit guard is skipped, so the handshake already runs with no turn detection.
   */
  arCommit?: boolean;
  context?: ContextEntry[];
}

/** One phone PCM chunk (16 kHz s16le mono), re-encoded from the binary frame by Java. */
export interface AudioInLine {
  type: 'audio';
  dir: 'in';
  dataBase64: string;
}

/** Typed phone input; the bridge makes it a user message item and asks for a response. */
export interface TextLine {
  type: 'text';
  text: string;
}

/** The phone's mic gate; Java already drops binary frames while it is off. */
export interface MicLine {
  type: 'mic';
  on: boolean;
}

/**
 * The phone's AR mode (spec X17, D5). It gates the bridge's turn detection and commit
 * grace (D4) and changes no other frame; a repeat of the current mode is a no-op.
 */
export interface ArLine {
  type: 'ar';
  on: boolean;
}

/** The phone's explicit barge-in mirror; idempotent against the provider's own VAD. */
export interface CancelLine {
  type: 'cancel';
}

/** One validated JPEG frame, held until the utterance's `commit{image:true}`. */
export interface ImageLine {
  type: 'image';
  dataBase64: string;
}

/** End of the utterance's audio; `image:true` commits the pending frame too. */
export interface CommitLine {
  type: 'commit';
  image: boolean;
}

/** The answer to a `tool_call`, keyed by the id the bridge asked with. */
export interface ToolResultLine {
  type: 'tool_result';
  id: string;
  ok: boolean;
  output: string;
}

/** A TOWER injection: the model is told the result and speaks it as its own turn. */
export interface TowerLine {
  type: 'tower';
  text: string;
}

/** The socket is going away; close the provider socket and exit 0. */
export interface CloseLine {
  type: 'close';
}

export type InboundLine =
  | HelloLine
  | AudioInLine
  | TextLine
  | MicLine
  | ArLine
  | CancelLine
  | ImageLine
  | CommitLine
  | ToolResultLine
  | TowerLine
  | CloseLine;

// --- bridge -> Java (stdout) --------------------------------------------------------------

/** One provider PCM delta (24 kHz s16le mono), base64 as the provider sent it. */
export interface AudioOutLine {
  type: 'audio';
  dir: 'out';
  dataBase64: string;
}

/** One phone event, forwarded verbatim by Java (`LiveFrames.toPhoneRaw`). */
export interface ControlLine {
  type: 'control';
  event: PhoneEvent;
}

/** One provider function call; Java executes it and answers with `tool_result`. */
export interface ToolCallLine {
  type: 'tool_call';
  id: string;
  name: string;
  arguments: Record<string, unknown>;
}

/** The end of a turn that called no tool: Java settles the turn's live run. */
export interface TurnDoneLine {
  type: 'turn_done';
}

/**
 * The child cannot continue. `code` is the WebSocket close code Java should use:
 * 4012 when the session expired and the reopen failed, 1011 for a dead provider
 * socket. Task 6 wires the Java side of this line.
 */
export interface FatalLine {
  type: 'fatal';
  code: number;
}

export type OutboundLine = AudioOutLine | ControlLine | ToolCallLine | TurnDoneLine | FatalLine;

// --- phone events (spec X4-X8) ------------------------------------------------------------

/**
 * Aria's `ready`. The bridge builds it (so the event surface lives in one place) but does
 * not send it: Aria's handler emits `ready` on `hello` with the `sessionId` only it owns
 * (spec X1), and the bridge learns nothing about the Qwen session beyond the socket.
 */
export interface ReadyEvent {
  type: 'ready';
  v: 1;
  model: string;
  modalities: string[];
  maxSessionSec: number;
}

/** The bridge's receive wall-clock for the end of user speech (spec X4). */
export interface SpeechEndEvent {
  type: 'speech_end';
  v: 1;
  tMs: number;
}

/** The user's caption so far; `final` freezes the line. */
export interface InputTranscriptEvent {
  type: 'input_transcript';
  v: 1;
  seq: number;
  text: string;
  final: boolean;
}

/** The assistant's caption so far; `final` freezes the line. */
export interface OutputTranscriptEvent {
  type: 'output_transcript';
  v: 1;
  seq: number;
  text: string;
  final: boolean;
}

/** The user talked over a response: flush playback (spec X6). */
export interface InterruptedEvent {
  type: 'interrupted';
  v: 1;
  reason: 'user_speech';
}

/** The closed error set of the phone contract. */
export type ErrorCode =
  | 'auth_failed'
  | 'capability_mismatch'
  | 'frame_too_large'
  | 'frame_invalid'
  | 'provider_rate_limited'
  | 'provider_unavailable'
  | 'session_expired'
  | 'bridge_internal';

/** A provider or lifecycle failure; `message` is never a provider string. */
export interface ErrorEvent {
  type: 'error';
  v: 1;
  code: ErrorCode;
  retryable: boolean;
  message: string;
}

/** The caption band resumes after the bridge re-opened the provider session. */
export interface ResumedEvent {
  type: 'resumed';
  v: 1;
  handleAgeSec: number;
}

/**
 * One display instruction for the HUD (spec D1). The bridge builds it from the model's
 * display-only `highlight_target` call; `bbox` is normalized `[x,y,w,h]` in `[0,1]` and
 * a display-only render carries no `runId`, so the phone accepts its absence.
 */
export interface RenderEvent {
  type: 'ar_render';
  v: 1;
  renderId: string;
  render: {
    primitive: 'highlight';
    target: { label: string; bbox: [number, number, number, number] };
    text: string;
    ttlMs: number;
    coord: 'normalized';
    safetyAlert: null;
  };
}

/**
 * The events this bridge produces. The remaining phone events (`tool_status`,
 * `approval_required`, `waiting_input`, `run_event`, `mode`) need store knowledge the
 * child does not have, so Aria's handler emits them (spec X7, X8).
 */
export type PhoneEvent =
  | ReadyEvent
  | SpeechEndEvent
  | InputTranscriptEvent
  | OutputTranscriptEvent
  | InterruptedEvent
  | ErrorEvent
  | ResumedEvent
  | RenderEvent;
