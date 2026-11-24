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
