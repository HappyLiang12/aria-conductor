import { describe, expect, it } from 'vitest';
import { createAriaAdapter } from '../ariaAdapter';

describe('createAriaAdapter', () => {
  it('maps tool, approval, and final message events to cumulative parts', async () => {
    const stream = async (_c: string, _m: string, _h: unknown, cb: any) => {
      cb.onToolCall('bash', '{}', 'tc1');
      cb.onToolResult('bash', 'ok', true, 'tc1');
      cb.onApprovalRequired({ runId: 'r1', approvalId: 'a1', toolName: 'bash' });
      cb.onMessage('All good.');
      cb.onDone?.({ runId: 'r1', conversationId: 'c1', intent: '' });
    };
    const adapter = createAriaAdapter({ conversationId: 'c1', getHistory: () => [], stream: stream as never });
    const chunks: any[] = [];
    const run = adapter.run({
      messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }],
      abortSignal: new AbortController().signal,
    } as never);
    for await (const chunk of run as AsyncIterable<any>) chunks.push(chunk);

    const last = chunks[chunks.length - 1].content;
    expect(last).toContainEqual(expect.objectContaining({ type: 'tool-call', toolCallId: 'tc1', result: 'ok' }));
    expect(last).toContainEqual(expect.objectContaining({ toolName: '__approval', toolCallId: 'approval-a1' }));
    expect(last).toContainEqual({ type: 'text', text: 'All good.' });
  });

  it('flushes a result, the message and done that land while a chunk is held', async () => {
    // `streamMessage` dispatches every event of one read synchronously, so the
    // answer and the terminal frame routinely land while the consumer is
    // suspended inside `yield`. Nothing may be dropped: the pull after the
    // pending tool card must carry the result and the message even though
    // `finished` is already true by then.
    let callbacks: {
      onToolCall: (name: string, args?: string, toolCallId?: string) => void;
      onToolResult: (name: string, result: string, ok: boolean, toolCallId?: string) => void;
      onMessage: (content: string) => void;
      onDone: (data: { runId: string; conversationId: string; intent: string }) => void;
    } | undefined;
    const stream = (_c: string, _m: string, _h: unknown, cb: typeof callbacks) => {
      callbacks = cb;
      // Never settles: the terminal frame, not the promise, ends this run.
      return new Promise<void>(() => {});
    };
    const adapter = createAriaAdapter({ conversationId: 'c1', getHistory: () => [], stream: stream as never });
    const run = adapter.run({
      messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }],
      abortSignal: new AbortController().signal,
    } as never) as AsyncIterable<{ content: any[] }>;
    const iterator = run[Symbol.asyncIterator]();

    // Starting the first pull runs the generator body, which starts the fake
    // stream and hands it these callbacks synchronously.
    const first = iterator.next();
    callbacks!.onToolCall('bash', '{}', 'tc1');

    const pending = await first;
    expect(pending.done).toBe(false);
    expect(pending.value.content).toContainEqual(
      expect.objectContaining({ type: 'tool-call', toolCallId: 'tc1', toolName: 'bash' }),
    );
    expect(pending.value.content.some((p: any) => p.type === 'text')).toBe(false);

    // The consumer holds that chunk; the rest of one read lands now.
    callbacks!.onToolResult('bash', 'ok', true, 'tc1');
    callbacks!.onMessage('All good.');
    callbacks!.onDone({ runId: 'r1', conversationId: 'c1', intent: '' });

    const final = await iterator.next();
    expect(final.done).toBe(false);
    expect(final.value.content).toContainEqual(
      expect.objectContaining({ type: 'tool-call', toolCallId: 'tc1', result: 'ok' }),
    );
    expect(final.value.content).toContainEqual({ type: 'text', text: 'All good.' });

    const end = await iterator.next();
    expect(end.done).toBe(true);
  });

  it('throws the enriched failed-turn payload as an Error, never [object Object]', async () => {
    // assistant-ui only renders Error/string: the object the backend sends must
    // become an Error whose copy names the human reason and the run to inspect.
    const stream = async (_c: string, _m: string, _h: unknown, cb: any) => {
      cb.onError({ message: 'The model declined the request', turnFailed: true, runId: 'run-42', reason: 'policy refusal' });
    };
    const adapter = createAriaAdapter({ conversationId: 'c1', getHistory: () => [], stream: stream as never });
    const run = adapter.run({
      messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }],
      abortSignal: new AbortController().signal,
    } as never);

    const failure = await (async () => {
      try {
        for await (const _chunk of run as AsyncIterable<unknown>) { /* drain */ }
      } catch (error) {
        return error;
      }
      return null;
    })();

    expect(failure).toBeInstanceOf(Error);
    expect((failure as Error).message).toContain('The model declined the request');
    expect((failure as Error).message).toContain('run-42');
    expect((failure as Error).message).toContain('policy refusal');
    expect((failure as Error).message).not.toContain('[object Object]');
  });

  it('does not open the request when the runtime signal is already aborted', async () => {
    let seenSignal: AbortSignal | undefined;
    const stream = (_c: string, _m: string, _h: unknown, _cb: unknown, signal?: AbortSignal) => {
      seenSignal = signal;
      return Promise.resolve();
    };
    const adapter = createAriaAdapter({ conversationId: 'c1', getHistory: () => [], stream: stream as never });
    const controller = new AbortController();
    controller.abort();
    const run = adapter.run({
      messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }],
      abortSignal: controller.signal,
    } as never);

    const chunks: any[] = [];
    for await (const chunk of run as AsyncIterable<any>) chunks.push(chunk);

    expect(seenSignal?.aborted).toBe(true);
    expect(chunks).toEqual([]);
  });

  it('cancel aborts the in-flight request and ends the run', async () => {
    // The page's stop control calls `cancel()`. The stream must learn about it
    // through the signal the adapter owns (not a second request), and the run
    // generator must end — a suspended iterator leaves the page stuck "running".
    let seenSignal: AbortSignal | undefined;
    const stream = (_c: string, _m: string, _h: unknown, _cb: unknown, signal?: AbortSignal) => {
      seenSignal = signal;
      return new Promise<void>((resolve) => {
        signal?.addEventListener('abort', () => resolve());
      });
    };
    const adapter = createAriaAdapter({ conversationId: 'c1', getHistory: () => [], stream: stream as never });
    const run = adapter.run({
      messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }],
      abortSignal: new AbortController().signal,
    } as never);

    const chunks: any[] = [];
    const consumed = (async () => {
      for await (const chunk of run as AsyncIterable<any>) chunks.push(chunk);
    })();
    await Promise.resolve();
    adapter.cancel();
    await consumed;

    expect(seenSignal?.aborted).toBe(true);
    expect(chunks).toEqual([]);
  });
});
