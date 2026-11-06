import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { streamMessage } from '../aria';

/** Minimal SSE response double: a body whose reader yields the given chunks. */
function sseResponse(chunks: string[]) {
  let i = 0;
  return {
    ok: true,
    body: {
      getReader: () => ({
        read: async () =>
          i < chunks.length
            ? { value: new TextEncoder().encode(chunks[i++]), done: false }
            : { value: undefined, done: true },
      }),
    },
  };
}

describe('aria streamMessage terminal error detail', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('a failed turn error event reaches onError as the enriched payload', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: error\ndata: {"message":"Aria streaming failed: relay dead","turnFailed":true,"runId":"r1","reason":"relay dead"}\n\n',
      ]),
    );
    const onError = vi.fn();

    await streamMessage('conv-1', 'hi', [], { onError });

    expect(onError).toHaveBeenCalledWith({
      message: 'Aria streaming failed: relay dead',
      turnFailed: true,
      runId: 'r1',
      reason: 'relay dead',
    });
  });

  it('a legacy transport error event still reaches onError as a plain message', async () => {
    fetchMock.mockResolvedValue(
      sseResponse(['event: error\ndata: {"message":"stream timed out"}\n\n']),
    );
    const onError = vi.fn();

    await streamMessage('conv-1', 'hi', [], { onError });

    expect(onError).toHaveBeenCalledWith('stream timed out');
  });
});
