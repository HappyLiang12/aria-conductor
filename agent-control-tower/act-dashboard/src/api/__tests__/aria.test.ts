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

// ---------------------------------------------------------------------------
// Ordered decision signals (spec A2, Task 5's SSE contract): a parked turn
// announces its ask on the same ordered stream as the turn, so the panel can
// render a card in the conversation instead of waiting for the 600 s timeout.
// ---------------------------------------------------------------------------

describe('aria streamMessage decision signals', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('dispatches approval_required and waiting_input', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: approval_required\ndata: {"runId":"r1","approvalId":"a1","toolName":"mcp__aria-conductor__remember_fact","riskTier":"WRITE_LOCAL","toolPolicy":"WORKER_WRITE","optionsJson":"allow_once,deny","expiresAt":"2026-11-11T00:10:00Z"}\n\n',
        'event: waiting_input\ndata: {"runId":"r1","approvalId":"a2","question":"Which PR?"}\n\n',
        'event: done\ndata: {"runId":"r1","conversationId":"c1","intent":""}\n\n',
      ]),
    );
    const onApprovalRequired = vi.fn();
    const onWaitingInput = vi.fn();
    const onDone = vi.fn();

    await streamMessage('c1', 'hi', [], { onApprovalRequired, onWaitingInput, onDone });

    expect(onApprovalRequired).toHaveBeenCalledWith({
      runId: 'r1',
      approvalId: 'a1',
      toolName: 'mcp__aria-conductor__remember_fact',
      riskTier: 'WRITE_LOCAL',
      toolPolicy: 'WORKER_WRITE',
      optionsJson: 'allow_once,deny',
      expiresAt: '2026-11-11T00:10:00Z',
    });
    expect(onWaitingInput).toHaveBeenCalledWith({
      runId: 'r1',
      approvalId: 'a2',
      question: 'Which PR?',
    });
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('dispatches memory_proposal with the proposal summary the panel merges by id', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: memory_proposal\ndata: {"id":"p1","subjectType":"FACT","entityId":"e1","factKey":"preferred_channel","valueText":"email","sensitivity":"INTERNAL"}\n\n',
      ]),
    );
    const onMemoryProposal = vi.fn();

    await streamMessage('c1', 'hi', [], { onMemoryProposal });

    expect(onMemoryProposal).toHaveBeenCalledWith({
      id: 'p1',
      subjectType: 'FACT',
      entityId: 'e1',
      factKey: 'preferred_channel',
      valueText: 'email',
      sensitivity: 'INTERNAL',
    });
  });

  it('leaves an unknown signal alone and still dispatches the known ones around it', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: run.queued\ndata: {"runId":"r1"}\n\n',
        'event: message\ndata: {"content":"still here"}\n\n',
        'event: done\ndata: {"runId":"r1","conversationId":"c1","intent":""}\n\n',
      ]),
    );
    const onMessage = vi.fn();
    const onError = vi.fn();

    await streamMessage('c1', 'hi', [], { onMessage, onError });

    // The unknown frame changes nothing and is not an error…
    expect(onError).not.toHaveBeenCalled();
    // …while the known frame on the same stream is delivered (positive control).
    expect(onMessage).toHaveBeenCalledWith('still here');
  });
});

describe('aria streamMessage tool result success flag', () => {
  // tool-event-contract.md: `tool_result` carries `ok` (= !isError) and the
  // dispatcher reads it as `p.ok !== false`, so an explicit false is an error
  // while an absent field (an older backend) still renders as success.
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('passes ok through and treats an absent flag as success', async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: tool_call\ndata: {"runId":"r1","name":"write_file","args":"{}","toolCallId":null}\n\n',
        'event: tool_result\ndata: {"runId":"r1","name":"write_file","result":"denied by policy","ok":false,"toolCallId":null}\n\n',
        'event: tool_result\ndata: {"runId":"r1","name":"read_file","result":"{}","toolCallId":null}\n\n',
        'event: tool_result\ndata: {"runId":"r1","name":"list_runs","result":"[]","ok":true,"toolCallId":null}\n\n',
      ]),
    );
    const onToolResult = vi.fn();

    await streamMessage('c1', 'hi', [], { onToolResult });

    expect(onToolResult).toHaveBeenNthCalledWith(1, 'write_file', 'denied by policy', false);
    // Absent `ok`: an older backend, rendered as success rather than as an error
    // the frame never claimed.
    expect(onToolResult).toHaveBeenNthCalledWith(2, 'read_file', '{}', true);
    expect(onToolResult).toHaveBeenNthCalledWith(3, 'list_runs', '[]', true);
  });
});
