// The event allowlist and the phone-facing ordering rules (spec X4-X6, X8).
//
// Pure in the sense that matters: no socket and no I/O — the caller supplies the clock
// and receives actions to apply. Everything the provider sends that is not on the
// allowlist is dropped and counted, so a new provider event can never reach the phone
// by accident.
//
// Two shape decisions are inherited from the P0 probe (`docs/superpowers/plans/
// 2026-11-23-phase0-p0-gemini-live-spike.md`) and the phone's caption semantics
// (`LiveCaption` replaces a line wholesale on each event):
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

  /** Between `response.created` and `response.done`: a barge-in is only meaningful here. */
  let responseActive = false;
  /** After a barge-in: the cancelled response's audio must not reach the playback queue. */
  let suppressAudio = false;
  /** A tool call in the current response means this `response.done` is not the turn's end. */
  let toolCalled = false;

  const inputTranscript = (text: string, final: boolean): InputTranscriptEvent => {
    lastInputText = text;
    inputSeq += 1;
    return { type: 'input_transcript', v: PROTOCOL_VERSION, seq: inputSeq, text, final };
  };

  const outputTranscript = (text: string, final: boolean): OutputTranscriptEvent => {
    outputText = text;
    outputSeq += 1;
    return { type: 'output_transcript', v: PROTOCOL_VERSION, seq: outputSeq, text, final };
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
        case 'input_audio_buffer.speech_stopped': {
          const speechEnd: SpeechEndEvent = {
            type: 'speech_end',
            v: PROTOCOL_VERSION,
            tMs: clock(),
          };
          return control(speechEnd);
        }
        case 'input_audio_buffer.speech_started': {
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
