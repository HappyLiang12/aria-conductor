// The event allowlist, the per-stream `seq`, the speech_end clock and barge-in ordering
// (spec X4-X6, X8). Every case here is a phone-facing claim: an event the bridge must
// produce, an event it must not, or an ordering the phone's caption/playback relies on.
import { describe, expect, it } from 'vitest';
import { createTranslator, parseHighlightCall, type Translation } from '../src/translate.js';

const RECEIVED_AT = 1_732_435_200_123;

const clock = () => RECEIVED_AT;

/** The translator's actions, minus the ones a test is not asserting (drop accounting). */
const actions = (translator: ReturnType<typeof createTranslator>, type: string) =>
  translator.onEvent({ type });

describe('translate: allowlist', () => {
  it('maps speech_stopped to speech_end with the receive clock', () => {
    const translator = createTranslator({ clock });
    const [translation] = translator.onEvent({ type: 'input_audio_buffer.speech_stopped' });
    expect(translation).toEqual({
      kind: 'control',
      event: { type: 'speech_end', v: 1, tMs: RECEIVED_AT },
    });
  });

  it('maps the semantic-VAD commit to speech_end (the provider sends no speech_stopped)', () => {
    const translator = createTranslator({ clock });
    const [translation] = translator.onEvent({ type: 'input_audio_buffer.committed' });
    expect(translation).toEqual({
      kind: 'control',
      event: { type: 'speech_end', v: 1, tMs: RECEIVED_AT },
    });
  });

  it('yields exactly one speech_end when both events describe the same utterance', () => {
    const translator = createTranslator({ clock });
    expect(translator.onEvent({ type: 'input_audio_buffer.speech_stopped' })).toHaveLength(1);
    expect(translator.onEvent({ type: 'input_audio_buffer.committed' })).toEqual([]);
  });

  it('arms speech_end again after the next speech_started', () => {
    const translator = createTranslator({ clock });
    translator.onEvent({ type: 'input_audio_buffer.speech_stopped' });
    translator.onEvent({ type: 'input_audio_buffer.speech_started' });
    expect(translator.onEvent({ type: 'input_audio_buffer.committed' })).toHaveLength(1);
  });

  it('re-arms speech_end for the next utterance when the caller begins one explicitly', () => {
    const translator = createTranslator({ clock });
    expect(translator.onEvent({ type: 'input_audio_buffer.committed' })).toHaveLength(1);
    // No turn detection (AR-on): the provider never sends `speech_started`, so the bridge
    // says where the next utterance starts before it commits that audio.
    translator.beginUtterance();
    expect(translator.onEvent({ type: 'input_audio_buffer.committed' })).toHaveLength(1);
  });

  it('maps input transcription deltas and the completion to one input stream', () => {
    const translator = createTranslator({ clock });
    const delta = translator.onEvent({
      type: 'conversation.item.input_audio_transcription.delta',
      item_id: 'item_1',
      text: '幫我',
      stash: '',
    });
    expect(delta).toEqual([
      {
        kind: 'control',
        event: { type: 'input_transcript', v: 1, seq: 1, text: '幫我', final: false },
      },
    ]);
    // The provider's own carrying text is cumulative and the phone replaces the line
    // wholesale, so the preview delta carries text + stash.
    const preview = translator.onEvent({
      type: 'conversation.item.input_audio_transcription.delta',
      item_id: 'item_1',
      text: '幫我查下',
      stash: '單車',
    });
    expect(preview).toEqual([
      {
        kind: 'control',
        event: { type: 'input_transcript', v: 1, seq: 2, text: '幫我查下單車', final: false },
      },
    ]);
    const done = translator.onEvent({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'item_1',
      transcript: '幫我查下單車扭力',
    });
    expect(done).toEqual([
      {
        kind: 'control',
        event: { type: 'input_transcript', v: 1, seq: 3, text: '幫我查下單車扭力', final: true },
      },
    ]);
  });

  it('accumulates output transcript deltas and freezes the line on done', () => {
    const translator = createTranslator({ clock });
    expect(
      translator.onEvent({ type: 'response.audio_transcript.delta', delta: '等我' }),
    ).toEqual([
      {
        kind: 'control',
        event: { type: 'output_transcript', v: 1, seq: 1, text: '等我', final: false },
      },
    ]);
    expect(
      translator.onEvent({ type: 'response.audio_transcript.delta', delta: '查下' }),
    ).toEqual([
      {
        kind: 'control',
        event: { type: 'output_transcript', v: 1, seq: 2, text: '等我查下', final: false },
      },
    ]);
    // `done` may or may not carry the whole transcript; the accumulated text is the base.
    expect(translator.onEvent({ type: 'response.audio_transcript.done' })).toEqual([
      {
        kind: 'control',
        event: { type: 'output_transcript', v: 1, seq: 3, text: '等我查下', final: true },
      },
    ]);
    expect(
      translator.onEvent({ type: 'response.audio_transcript.done', transcript: '等我查下先' }),
    ).toEqual([
      {
        kind: 'control',
        event: { type: 'output_transcript', v: 1, seq: 4, text: '等我查下先', final: true },
      },
    ]);
  });

  it('carries 24 kHz PCM deltas as audio and nothing else', () => {
    const translator = createTranslator({ clock });
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'AAAABBBB' })).toEqual([
      { kind: 'audio', dataBase64: 'AAAABBBB' },
    ]);
  });

  it('answers function calls with a tool_call and ends a tool-free response', () => {
    const translator = createTranslator({ clock });
    expect(
      translator.onEvent({
        type: 'response.function_call_arguments.done',
        call_id: 'call_7',
        name: 'get_time',
        arguments: '{"zone":"Asia/Hong_Kong"}',
      }),
    ).toEqual([
      {
        kind: 'tool_call',
        id: 'call_7',
        name: 'get_time',
        arguments: { zone: 'Asia/Hong_Kong' },
      },
    ]);
    // A response that called a tool is not the end of the turn: the continuation is.
    expect(
      translator.onEvent({ type: 'response.done', response: { status: 'completed' } }),
    ).toEqual([]);
    expect(
      translator.onEvent({ type: 'response.done', response: { status: 'completed' } }),
    ).toEqual([{ kind: 'turn_done' }]);
  });

  it('drops and counts every event outside the allowlist', () => {
    const translator = createTranslator({ clock });
    expect(actions(translator, 'mystery.delta')).toEqual([]);
    expect(actions(translator, 'response.output_item.added')).toEqual([]);
    expect(actions(translator, 'mystery.delta')).toEqual([]);
    expect(translator.dropped()).toBe(3);
  });
});

describe('translate: seq', () => {
  it('is monotonic per stream and never shared between them', () => {
    const translator = createTranslator({ clock });
    const seqOf = (translations: Translation[]) =>
      translations.map((translation) =>
        translation.kind === 'control' ? (translation.event as { seq: number }).seq : -1,
      );
    expect(
      seqOf(translator.onEvent({ type: 'response.audio_transcript.delta', delta: 'a' })),
    ).toEqual([1]);
    expect(
      seqOf(
        translator.onEvent({ type: 'conversation.item.input_audio_transcription.delta', text: 'b' }),
      ),
    ).toEqual([1]);
    expect(
      seqOf(translator.onEvent({ type: 'response.audio_transcript.delta', delta: 'c' })),
    ).toEqual([2]);
    expect(
      seqOf(
        translator.onEvent({
          type: 'conversation.item.input_audio_transcription.completed',
          transcript: 'd',
        }),
      ),
    ).toEqual([2]);
  });

  it('keeps counting across a reconnect (one socket = one stream lifetime)', () => {
    const translator = createTranslator({ clock });
    translator.onEvent({ type: 'response.audio_transcript.delta', delta: 'a' });
    // The session reuses this translator for the re-opened Qwen socket.
    const seq = translator.onEvent({ type: 'response.audio_transcript.delta', delta: 'b' });
    expect(seq).toEqual([
      {
        kind: 'control',
        event: { type: 'output_transcript', v: 1, seq: 2, text: 'ab', final: false },
      },
    ]);
  });
});

describe('translate: barge-in', () => {
  it('emits interrupted and the cancel before any further audio delta', () => {
    const translator = createTranslator({ clock });
    // A response is in flight.
    expect(translator.onEvent({ type: 'response.created' })).toEqual([]);
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'AAAA' })).toEqual([
      { kind: 'audio', dataBase64: 'AAAA' },
    ]);
    // The user talks over it: interrupted first, then the upstream cancel.
    expect(translator.onEvent({ type: 'input_audio_buffer.speech_started' })).toEqual([
      { kind: 'control', event: { type: 'interrupted', v: 1, reason: 'user_speech' } },
      { kind: 'cancel' },
    ]);
    // Nothing of the cancelled response may reach the phone's playback queue.
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'BBBB' })).toEqual([]);
    // The provider's own response.done closes the suppression window.
    expect(translator.onEvent({ type: 'response.done', response: { status: 'cancelled' } })).toEqual(
      [],
    );
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'CCCC' })).toEqual([
      { kind: 'audio', dataBase64: 'CCCC' },
    ]);
  });

  it('does not interrupt when no response is active', () => {
    const translator = createTranslator({ clock });
    expect(translator.onEvent({ type: 'input_audio_buffer.speech_started' })).toEqual([]);
    // The next response is not suppressed by a barge-in that never happened.
    expect(translator.onEvent({ type: 'response.created' })).toEqual([]);
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'AAAA' })).toEqual([
      { kind: 'audio', dataBase64: 'AAAA' },
    ]);
  });

  it('suppresses audio after a phone cancel until response.done', () => {
    const translator = createTranslator({ clock });
    expect(translator.onEvent({ type: 'response.created' })).toEqual([]);
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'AAAA' })).toEqual([
      { kind: 'audio', dataBase64: 'AAAA' },
    ]);
    // The phone hung up on the response itself; no `interrupted` echoes back to it.
    translator.phoneCancel();
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'BBBB' })).toEqual([]);
    // The same window as a barge-in: the provider's response.done closes it.
    expect(translator.onEvent({ type: 'response.done', response: { status: 'cancelled' } })).toEqual(
      [],
    );
    expect(translator.onEvent({ type: 'response.audio.delta', delta: 'CCCC' })).toEqual([
      { kind: 'audio', dataBase64: 'CCCC' },
    ]);
  });
});

describe('translate: provider error', () => {
  it('maps a rate-limit error to the closed code set and retryable', () => {
    const translator = createTranslator({ clock });
    const [translation] = translator.onEvent({
      type: 'error',
      error: { code: '429', message: 'rate limited for key sk-synthetic-should-not-leak' },
    });
    expect(translation).toEqual({
      kind: 'control',
      event: {
        type: 'error',
        v: 1,
        code: 'provider_rate_limited',
        retryable: true,
        message: 'Qwen rate limited the session',
      },
    });
    expect(JSON.stringify(translation)).not.toContain('sk-synthetic-should-not-leak');
  });

  it('maps an auth error to auth_failed, not retryable', () => {
    const translator = createTranslator({ clock });
    const [translation] = translator.onEvent({
      type: 'error',
      error: { code: 'invalid_api_key', message: 'Unauthorized' },
    });
    expect(translation).toEqual({
      kind: 'control',
      event: {
        type: 'error',
        v: 1,
        code: 'auth_failed',
        retryable: false,
        message: 'Qwen rejected the credentials',
      },
    });
  });

  it('maps anything else to provider_unavailable, retryable', () => {
    const translator = createTranslator({ clock });
    expect(
      translator.onEvent({ type: 'error', error: { message: 'internal server error' } }),
    ).toEqual([
      {
        kind: 'control',
        event: {
          type: 'error',
          v: 1,
          code: 'provider_unavailable',
          retryable: true,
          message: 'Qwen is unavailable',
        },
      },
    ]);
  });
});

describe('translate: highlight_target geometry', () => {
  // The POC boxes (spec D3): the model answers [y0,x0,y1,x1] on a 0-1000 grid, the phone
  // renders a normalized [x,y,w,h] in [0,1]. GT2 is 273 px tall and 351 px wide (the phone
  // POC spec `2026-11-24-p3-grounding-poc.md`), so [586,508,859,859] is
  // [0.508, 0.586, 0.351, 0.273] -- the height is y1-y0, not a repeat of the width.
  const POC_BOX = [586, 508, 859, 859];

  it('maps the POC box to a normalized [x,y,w,h] bbox', () => {
    expect(
      parseHighlightCall({ label: '5mm 六角匙', box_2d: POC_BOX, text: '5mm 六角匙' }),
    ).toEqual({
      label: '5mm 六角匙',
      bbox: [0.508, 0.586, 0.351, 0.273],
      text: '5mm 六角匙',
    });
  });

  it('clamps a box that runs past the grid to the unit square', () => {
    expect(parseHighlightCall({ label: 'a', box_2d: [900, 900, 1200, 1200], text: 'a' })).toEqual({
      label: 'a',
      bbox: [0.9, 0.9, 0.1, 0.1],
      text: 'a',
    });
    expect(parseHighlightCall({ label: 'a', box_2d: [-20, -10, 400, 400], text: 'a' })).toEqual({
      label: 'a',
      bbox: [0, 0, 0.4, 0.4],
      text: 'a',
    });
  });

  it('refuses a box that is not exactly four finite numbers', () => {
    expect(parseHighlightCall({ label: 'a', box_2d: [586, 508, 859], text: 'a' })).toBeNull();
    expect(parseHighlightCall({ label: 'a', box_2d: [586, 508, 859, 859, 900], text: 'a' })).toBeNull();
    expect(parseHighlightCall({ label: 'a', box_2d: ['586', 508, 859, 859], text: 'a' })).toBeNull();
    expect(
      parseHighlightCall({ label: 'a', box_2d: [586, Number.NaN, 859, 859], text: 'a' }),
    ).toBeNull();
    expect(parseHighlightCall({ label: 'a', box_2d: 'box', text: 'a' })).toBeNull();
    expect(parseHighlightCall({ label: 'a', text: 'a' })).toBeNull();
  });

  it('refuses an inverted or zero-size box', () => {
    // y1 < y0 and x1 < x0: the corners are swapped.
    expect(parseHighlightCall({ label: 'a', box_2d: [859, 859, 586, 508], text: 'a' })).toBeNull();
    expect(parseHighlightCall({ label: 'a', box_2d: [586, 508, 586, 859], text: 'a' })).toBeNull();
    // A box clamped entirely outside the grid collapses to zero size.
    expect(parseHighlightCall({ label: 'a', box_2d: [1200, 1200, 1400, 1400], text: 'a' })).toBeNull();
  });

  it('refuses a blank label or blank text', () => {
    expect(parseHighlightCall({ label: '  ', box_2d: POC_BOX, text: 'a' })).toBeNull();
    expect(parseHighlightCall({ label: 'a', box_2d: POC_BOX, text: '   ' })).toBeNull();
    expect(parseHighlightCall({ box_2d: POC_BOX, text: 'a' })).toBeNull();
    expect(parseHighlightCall({ label: 7, box_2d: POC_BOX, text: 'a' })).toBeNull();
  });
});

describe('translate: lifecycle controls', () => {
  it('builds the three bridge-owned control events', () => {
    const translator = createTranslator({ clock });
    // `ready` is Aria's (LiveWebSocketHandler sends it on hello); the bridge cannot name
    // the session it does not own. The builder exists so the surface stays in one place.
    expect(translator.readyEvent()).toEqual({
      type: 'ready',
      v: 1,
      model: 'qwen3.8-omni-flash-realtime',
      modalities: ['text', 'audio'],
      maxSessionSec: 7200,
    });
    expect(translator.expiredEvent()).toEqual({
      type: 'error',
      v: 1,
      code: 'session_expired',
      retryable: true,
      message: 'Live session expired',
    });
    expect(translator.resumedEvent(7140)).toEqual({ type: 'resumed', v: 1, handleAgeSec: 7140 });
  });
});

describe('translate: transcript entities', () => {
  const outputEvent = (translator: ReturnType<typeof createTranslator>, delta: string) =>
    translator.onEvent({ type: 'response.audio_transcript.delta', delta });

  it('decodes numeric and named entities into plain text', () => {
    const translator = createTranslator({ clock });
    const [first] = outputEvent(translator, '第一行&#10;第二行 &quot;引號&quot; &lt;tag&gt; &amp;');
    expect((first as { event: { text: string } }).event.text)
      .toBe('第一行\n第二行 "引號" <tag> &');
  });

  it('decodes a hex entity and leaves an unknown named entity alone', () => {
    const translator = createTranslator({ clock });
    const [first] = outputEvent(translator, '&#x41; &nbsp;');
    expect((first as { event: { text: string } }).event.text).toBe('A &nbsp;');
  });

  it('never double-decodes an escaped entity', () => {
    const translator = createTranslator({ clock });
    const [first] = outputEvent(translator, '&amp;lt;');
    expect((first as { event: { text: string } }).event.text).toBe('&lt;');
  });

  it('holds back an entity split across two deltas until it completes', () => {
    const translator = createTranslator({ clock });
    const [first] = outputEvent(translator, '行&#1');
    expect((first as { event: { text: string } }).event.text).toBe('行');
    const [second] = outputEvent(translator, '0;行');
    expect((second as { event: { text: string } }).event.text).toBe('行\n行');
  });

  it('decodes entities in the input transcript as well', () => {
    const translator = createTranslator({ clock });
    const [first] = translator.onEvent({
      type: 'conversation.item.input_audio_transcription.delta',
      text: '你&#10;好',
      stash: '',
    });
    expect((first as { event: { text: string } }).event.text).toBe('你\n好');
  });
});
