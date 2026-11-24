// The event allowlist and the phone-facing ordering rules (spec X4-X6, X8).
//
// Pure in the sense that matters: no socket and no I/O — the caller supplies the clock
// and receives actions to apply. Everything the provider sends that is not on the
// allowlist is dropped and counted, so a new provider event can never reach the phone
// by accident.
//
// Two shape decisions are inherited from the Phase 0 live probe and the phone's caption
// semantics (`LiveCaption` replaces a line wholesale on each event):
//   - an input transcription delta carries the running text (`text` + `stash`), so the
//     bridge forwards it as-is instead of accumulating;
//   - an output transcription delta is a fragment (`delta`), so the bridge accumulates
//     and sends the running text with a new `seq`.
import {
  MAX_SESSION_SEC,
  MODEL,
  PROTOCOL_VERSION,
  type ErrorCode,
  type ErrorEvent,
  type InputTranscriptEvent,
  type InterruptedEvent,
  type OutputTranscriptEvent,
  type PhoneEvent,
  type ReadyEvent,
  type ResumedEvent,
  type SpeechEndEvent,
} from './protocol.js';

/** One provider event. Only `type` is trusted; the rest is read field by field. */
export interface QwenEvent {
  type?: string;
  [key: string]: unknown;
}

/** What a provider event asks the bridge to do next. */
export type Translation =
  | { kind: 'control'; event: PhoneEvent }
  | { kind: 'audio'; dataBase64: string }
  | { kind: 'tool_call'; id: string; name: string; arguments: Record<string, unknown> }
  | { kind: 'turn_done' }
  /** Barge-in: send `response.cancel` upstream (never crosses to the phone). */
  | { kind: 'cancel' };

export interface Translator {
  /** Map one provider event to the actions it implies; `[]` when nothing crosses. */
  onEvent(event: QwenEvent): Translation[];
  /**
   * The phone cancelled the response itself: silence the rest of its audio until
   * `response.done`, the same window a barge-in opens (spec X6).
   */
  phoneCancel(): void;
  /**
   * A new utterance begins and the provider will not say so itself: with no turn
   * detection (AR-on) there is no `speech_started`, so the bridge re-arms the one
   * `speech_end` per utterance right before it commits the audio explicitly.
   */
  beginUtterance(): void;
  /**
   * Spec D4: in AR-on the provider runs with no turn detection, so it never ends an
   * utterance itself. The bridge's own audio-energy end-of-speech asks for the one
   * `speech_end` this utterance is owed; a silent `[]` when it was already sent.
   */
  syntheticSpeechEnd(): Translation[];
  /** Aria's own `ready` shape; see {@link ReadyEvent}. */
  readyEvent(): ReadyEvent;
  /** `session_expired` is an error code, not an event type (phone contract). */
  expiredEvent(): ErrorEvent;
  resumedEvent(handleAgeSec: number): ResumedEvent;
  /** How many off-allowlist events were dropped so far (metadata for the log line). */
  dropped(): number;
}

export interface TranslatorOptions {
  clock?: () => number;
}

const asText = (value: unknown): string => (typeof value === 'string' ? value : '');

const asObject = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;

/** A function call's arguments are a JSON string on the wire; Java wants the object. */
const parseArguments = (value: unknown): Record<string, unknown> => {
  if (typeof value === 'string') {
    try {
      return asObject(JSON.parse(value)) ?? {};
    } catch {
      return {};
    }
  }
  return asObject(value) ?? {};
};

/** The grid the model reports boxes on (spec D3): a 0-1000 square over the image. */
const HIGHLIGHT_GRID = 1000;

/** A parsed `highlight_target` call, ready to become an `ar_render` v2 render. */
export interface HighlightTarget {
  label: string;
  /** Normalized `[x,y,w,h]` in `[0,1]`, the phone's render contract. */
  bbox: [number, number, number, number];
  text: string;
}

const clampGrid = (value: number): number => Math.min(HIGHLIGHT_GRID, Math.max(0, value));

/**
 * Spec D3: the model answers `box_2d:[y0,x0,y1,x1]` on a 0-1000 grid; the phone renders
 * `bbox=[x0,y0,x1-x0,y1-y0]/1000`. Both corners are clamped to the grid first, so the
 * size is measured inside the frame and every component lands in `[0,1]`. Anything the
 * bridge cannot place with a positive area — a short or non-numeric box, swapped corners,
 * a box clamped to nothing, a blank label or text — is refused, and the model is told to
 * say it cannot see.
 */
export function parseHighlightCall(args: Record<string, unknown>): HighlightTarget | null {
  const label = args.label;
  const text = args.text;
  if (typeof label !== 'string' || label.trim() === '') {
    return null;
  }
  if (typeof text !== 'string' || text.trim() === '') {
    return null;
  }
  const box = args.box_2d;
  if (!Array.isArray(box) || box.length !== 4) {
    return null;
  }
  if (!box.every((value) => typeof value === 'number' && Number.isFinite(value))) {
    return null;
  }
  const [y0 = 0, x0 = 0, y1 = 0, x1 = 0] = box as number[];
  if (x1 <= x0 || y1 <= y0) {
    return null;
  }
  // Clamped corners, divided after the clamp, keep the sizes exact: a box that runs past
  // the frame edge gives 0.1, not 0.09999999999999998.
  const x0c = clampGrid(x0);
  const y0c = clampGrid(y0);
  const x1c = clampGrid(x1);
  const y1c = clampGrid(y1);
  const bbox: [number, number, number, number] = [
    x0c / HIGHLIGHT_GRID,
    y0c / HIGHLIGHT_GRID,
    (x1c - x0c) / HIGHLIGHT_GRID,
    (y1c - y0c) / HIGHLIGHT_GRID,
  ];
  if (bbox[2] <= 0 || bbox[3] <= 0) {
    return null;
  }
  return { label, bbox, text };
}

interface ProviderFailure {
  code: ErrorCode;
  retryable: boolean;
  message: string;
}

/**
 * The provider's error text is never forwarded: it can quote the request (and the key),
 * and the phone must not render provider strings. The mapping is therefore by class of
 * failure, with one fixed sentence per class.
 */
function classifyFailure(event: QwenEvent): ProviderFailure {
  const error = asObject(event.error) ?? event;
  const signal = `${asText(error.code)} ${asText(error.type)} ${asText(error.message)}`
    .toLowerCase();
  if (/429|rate.?limit|too many requests|quota|throttl/.test(signal)) {
    return {
      code: 'provider_rate_limited',
      retryable: true,
      message: 'Qwen rate limited the session',
    };
  }
  if (/401|403|unauthor|forbidden|auth|api.?key|credential|permission/.test(signal)) {
    return {
      code: 'auth_failed',
      retryable: false,
      message: 'Qwen rejected the credentials',
    };
  }
  return {
    code: 'provider_unavailable',
    retryable: true,
    message: 'Qwen is unavailable',
  };
}

/**
 * One translator per socket. It outlives a provider reconnect on purpose: `seq` is a
 * property of the socket's caption streams, not of the Qwen session (spec X4).
 */
export function createTranslator(options: TranslatorOptions = {}): Translator {
  const clock = options.clock ?? Date.now;

  let inputSeq = 0;
  let outputSeq = 0;
  let lastInputText = '';
  let outputText = '';
  let droppedCount = 0;


const NAMED_ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
};

/**
 * Qwen's transcription text is HTML-escaped on the wire (`&#10;` for a newline, `&quot;`
 * for a quote). The phone renders the text as-is, so the bridge is the one place that
 * decodes it: a single pass keeps `&amp;lt;` from turning into `<`.
 */
function decodeEntities(text: string): string {
  return text.replace(/&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);/g, (whole, body: string) => {
    if (body.startsWith('#')) {
      const code = body[1] === 'x' || body[1] === 'X'
        ? Number.parseInt(body.slice(2), 16)
        : Number.parseInt(body.slice(1), 10);
      return Number.isFinite(code) && code >= 0 && code <= 0x10ffff
        ? String.fromCodePoint(code)
        : whole;
    }
    return NAMED_ENTITIES[body.toLowerCase()] ?? whole;
  });
}

/**
 * A transcript delta can split an entity (`&#1` then `0;`), so a non-final frame keeps
 * a trailing partial entity out of the caption until the rest arrives.
 */
function transcriptText(raw: string, final: boolean): string {
  const complete = final ? raw : raw.replace(/&[#a-zA-Z0-9]*$/, '');
  return decodeEntities(complete);
}

  /** Between `response.created` and `response.done`: a barge-in is only meaningful here. */
  let responseActive = false;
  /** After a barge-in: the cancelled response's audio must not reach the playback queue. */
  let suppressAudio = false;
  /** A tool call in the current response means this `response.done` is not the turn's end. */
  let toolCalled = false;
  /** One utterance yields one `speech_end`, whether it ends by VAD or by the commit. */
  let speechEnded = false;

  const inputTranscript = (text: string, final: boolean): InputTranscriptEvent => {
    lastInputText = text;
    inputSeq += 1;
    return { type: 'input_transcript', v: PROTOCOL_VERSION, seq: inputSeq, text: transcriptText(text, final), final };
  };

  const outputTranscript = (text: string, final: boolean): OutputTranscriptEvent => {
    outputText = text;
    outputSeq += 1;
    return { type: 'output_transcript', v: PROTOCOL_VERSION, seq: outputSeq, text: transcriptText(text, final), final };
  };

  const control = (event: PhoneEvent): Translation[] => [{ kind: 'control', event }];

  const endResponse = (event: QwenEvent): Translation[] => {
    const status = asText(asObject(event.response)?.status ?? event.status);
    const wasToolTurn = toolCalled;
    responseActive = false;
    suppressAudio = false;
    toolCalled = false;
    outputText = '';
    // A cancelled or failed response is not the end of a turn: the user's next utterance
    // continues it, and settling the turn's live run on it would settle a run that is
    // still (or never) in flight.
    if (wasToolTurn || (status !== '' && status !== 'completed')) {
      return [];
    }
    return [{ kind: 'turn_done' }];
  };

  return {
    onEvent(event) {
      switch (event.type) {
        case 'input_audio_buffer.speech_stopped':
        case 'input_audio_buffer.committed': {
          // `semantic_vad_ec` ends an utterance with a commit and never sends
          // `speech_stopped` (probed live 2026-11-24); a provider that sends both must
          // still produce exactly one `speech_end`.
          if (speechEnded) return [];
          speechEnded = true;
          const speechEnd: SpeechEndEvent = {
            type: 'speech_end',
            v: PROTOCOL_VERSION,
            tMs: clock(),
          };
          return control(speechEnd);
        }
        case 'input_audio_buffer.speech_started': {
          speechEnded = false;
          if (!responseActive) {
            return [];
          }
          suppressAudio = true;
          const interrupted: InterruptedEvent = {
            type: 'interrupted',
            v: PROTOCOL_VERSION,
            reason: 'user_speech',
          };
          // Ordering is the contract: interrupted reaches the phone before any further
          // audio delta, and the provider is told to stop producing them.
          return [{ kind: 'control', event: interrupted }, { kind: 'cancel' }];
        }
        case 'conversation.item.input_audio_transcription.delta': {
          const text = asText(event.text) + asText(event.stash);
          return text === '' ? [] : control(inputTranscript(text, false));
        }
        case 'conversation.item.input_audio_transcription.completed': {
          const text = asText(event.transcript) || lastInputText;
          return control(inputTranscript(text, true));
        }
        case 'response.created': {
          responseActive = true;
          suppressAudio = false;
          toolCalled = false;
          outputText = '';
          return [];
        }
        case 'response.audio_transcript.delta': {
          const delta = asText(event.delta);
          if (delta === '') {
            return [];
          }
          return control(outputTranscript(outputText + delta, false));
        }
        case 'response.audio_transcript.done': {
          const text = asText(event.transcript) || outputText;
          return control(outputTranscript(text, true));
        }
        case 'response.audio.delta': {
          const delta = asText(event.delta);
          if (delta === '' || suppressAudio) {
            return [];
          }
          return [{ kind: 'audio', dataBase64: delta }];
        }
        case 'response.function_call_arguments.done': {
          toolCalled = true;
          return [
            {
              kind: 'tool_call',
              id: asText(event.call_id) || asText(event.callId),
              name: asText(event.name),
              arguments: parseArguments(event.arguments),
            },
          ];
        }
        case 'response.done': {
          return endResponse(event);
        }
        case 'error': {
          const failure = classifyFailure(event);
          const error: ErrorEvent = {
            type: 'error',
            v: PROTOCOL_VERSION,
            code: failure.code,
            retryable: failure.retryable,
            message: failure.message,
          };
          return control(error);
        }
        default: {
          droppedCount += 1;
          return [];
        }
      }
    },

    readyEvent() {
      return {
        type: 'ready',
        v: PROTOCOL_VERSION,
        model: MODEL,
        modalities: ['text', 'audio'],
        maxSessionSec: MAX_SESSION_SEC,
      };
    },

    phoneCancel() {
      // `response.created` clears the window, so a cancel with no response in flight
      // never silences the next one.
      suppressAudio = true;
    },

    beginUtterance() {
      speechEnded = false;
    },

    syntheticSpeechEnd() {
      if (speechEnded) {
        return [];
      }
      speechEnded = true;
      const speechEnd: SpeechEndEvent = {
        type: 'speech_end',
        v: PROTOCOL_VERSION,
        tMs: clock(),
      };
      return control(speechEnd);
    },

    expiredEvent() {
      return {
        type: 'error',
        v: PROTOCOL_VERSION,
        code: 'session_expired',
        retryable: true,
        message: 'Live session expired',
      };
    },

    resumedEvent(handleAgeSec) {
      return { type: 'resumed', v: PROTOCOL_VERSION, handleAgeSec };
    },

    dropped() {
      return droppedCount;
    },
  };
}
