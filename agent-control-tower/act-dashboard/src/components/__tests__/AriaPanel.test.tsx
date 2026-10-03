import { describe, it, expect, beforeEach, beforeAll, vi, type Mock } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AriaPanel from '../AriaPanel';
import type { StreamCallbacks } from '../../api/aria';
import type { Approval } from '../../types';

// jsdom does not implement element scrolling — the auto-scroll effect needs it.
beforeAll(() => {
  Element.prototype.scrollTo = () => {};
});

vi.mock('../../api/aria', () => ({
  streamMessage: vi.fn(),
  sendMessage: vi.fn(),
}));
vi.mock('../../api/ariaConversations', () => ({
  getLatestConversation: vi.fn(),
  getConversationTimeline: vi.fn(),
  deleteConversation: vi.fn(),
}));
vi.mock('../../api/runs', () => ({ cancelRun: vi.fn() }));
vi.mock('../../api/skills', () => ({ listSkills: vi.fn() }));
vi.mock('../../api/approvals', () => ({ listApprovals: vi.fn() }));

import { streamMessage } from '../../api/aria';
import { getLatestConversation, getConversationTimeline, deleteConversation } from '../../api/ariaConversations';
import { listSkills } from '../../api/skills';
import { listApprovals } from '../../api/approvals';

const mockStream = streamMessage as Mock;
const mockGetLatest = getLatestConversation as Mock;
const mockGetTimeline = getConversationTimeline as Mock;
const mockDeleteConversation = deleteConversation as Mock;
const mockListSkills = listSkills as Mock;
const mockListApprovals = listApprovals as Mock;

type StreamArgs = [
  string, // conversationId
  string, // message
  Array<{ role: string; content: string }>, // history
  StreamCallbacks,
  AbortSignal?,
  { isCancelled?: () => boolean; skillId?: string }?, // options
];

const SKILLS = [
  {
    id: 's1',
    name: 'Dev Workflow',
    description: 'Dev loop',
    enabled: true,
    stage: 'SKILL',
    template: 'steps: []',
  },
];

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AriaPanel />
    </QueryClientProvider>,
  );
}

function textarea() {
  return screen.getByPlaceholderText(/Ask Aria anything/);
}

async function openPanel() {
  await userEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
  return textarea();
}

function lastOptions(call: number) {
  return mockStream.mock.calls[call][5] as { skillId?: string } | undefined;
}

beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  mockGetLatest.mockResolvedValue(null);
  mockGetTimeline.mockResolvedValue([]);
  mockDeleteConversation.mockResolvedValue(undefined);
  mockListSkills.mockResolvedValue(SKILLS);
  mockListApprovals.mockResolvedValue([]);
  mockStream.mockImplementation(async (...args: StreamArgs) => {
    args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: 'chat' });
  });
});

describe('AriaPanel slash-command skill handling', () => {
  it('keyboard selection clears the input and the sent message carries the skillId', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    expect(await screen.findByRole('option', { name: /dev-workflow/ })).toBeInTheDocument();
    await userEvent.type(ta, '{Enter}'); // keyboard select
    expect(ta).toHaveValue(''); // no /dev residue
    await userEvent.type(ta, 'do the thing');
    await userEvent.type(ta, '{Enter}'); // send
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('do the thing');
    expect(lastOptions(0)?.skillId).toBe('s1');
  });

  it('mouse selection clears the input and the sent message carries the skillId', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    const option = await screen.findByRole('option', { name: /dev-workflow/ });
    await userEvent.click(option);
    expect(ta).toHaveValue('');
    await userEvent.type(ta, 'do the thing');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('do the thing');
    expect(lastOptions(0)?.skillId).toBe('s1');
  });

  it('does not include skillId when no skill is pending', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, 'plain hello');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('plain hello');
    expect(lastOptions(0)?.skillId).toBeUndefined();
  });

  it('Enter with zero slash matches sends the raw text as a normal message', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/zzz');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('/zzz');
    expect(lastOptions(0)?.skillId).toBeUndefined();
  });

  it('retry re-sends the original message with the same skillId', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onError?.('boom');
    });
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    await userEvent.click(await screen.findByRole('option', { name: /dev-workflow/ }));
    await userEvent.type(ta, 'do the thing');
    await userEvent.type(ta, '{Enter}');
    // The raw error text is replaced by the honest, retry-free report; the retry
    // affordance stays for a failure that is not an approval wait.
    expect(await screen.findByText(/no approval is pending/)).toBeInTheDocument();
    expect(screen.queryByText(/boom/)).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2));
    expect(mockStream.mock.calls[1][1]).toBe('do the thing');
    expect(lastOptions(1)?.skillId).toBe('s1');
  });

  it('starting a new conversation disarms the pending skill', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    await userEvent.click(await screen.findByRole('option', { name: /dev-workflow/ }));
    expect(screen.getByText('/Dev Workflow')).toBeInTheDocument(); // chip visible
    await userEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    expect(screen.queryByText('/Dev Workflow')).not.toBeInTheDocument(); // chip gone
    await userEvent.type(ta, 'hello again');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(lastOptions(0)?.skillId).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// The panel used to tell the operator "the request may have timed out. Please
// try again." — harmful now that a run waiting on a governed tool approval is a
// normal state (the platform MCP is wired into the Aria run and mutating tools
// stay per-call approved). A timeout / dropped stream must instead report what
// the operator-only review queue actually knows, and must never invite a resend
// while the run may still be alive.
// ---------------------------------------------------------------------------

function pendingAsk(id: string): Approval {
  return {
    id,
    runId: 'run-1',
    toolCallId: null,
    status: 'PENDING',
    reason: 'write_file on the workspace',
    requestedAt: '2026-09-30T10:00:00Z',
    decidedAt: null,
    expiresAt: '2026-09-30T10:10:00Z',
  };
}

/** Sends one suggestion against a stream that never answers, then fires the panel's timeout. */
async function sendThenTimeOut() {
  mockStream.mockImplementation(async (...args: StreamArgs) => {
    args[3]?.onThinking?.('run-1');
    return new Promise<void>(() => {}); // never settles — the panel's own timer reports
  });
  renderPanel();
  await act(async () => {}); // flush the conversation load so the id line is bound
  fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
  fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(600_000); // CLIENT_TIMEOUT_MS, module-private
  });
  await act(async () => {}); // let the pending-approval probe's state update land
}

describe('AriaPanel run-uncertain reporting (timeout / dropped stream)', () => {
  it('a client timeout with pending asks names the count, points at the Review Queue and offers no retry', async () => {
    vi.useFakeTimers();
    try {
      mockListApprovals.mockResolvedValue([pendingAsk('a1'), pendingAsk('a2')]);
      await sendThenTimeOut();

      expect(screen.getByText(/2 pending asks in the Review Queue/)).toBeInTheDocument();
      expect(mockListApprovals).toHaveBeenCalledTimes(1);
      expect(mockListApprovals).toHaveBeenCalledWith('PENDING');
      expect(screen.queryByText(/please try again/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByText(/Conversation ID/)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a refused pending-approval check (401, operator-only) reports honestly and keeps the conversation id', async () => {
    vi.useFakeTimers();
    try {
      mockListApprovals.mockRejectedValue({ response: { status: 401 } });
      await sendThenTimeOut();

      expect(screen.getByText(/may be waiting for your approval/)).toBeInTheDocument();
      expect(screen.getByText(/open the Review Queue to check/)).toBeInTheDocument();
      expect(screen.getByText(/refused \(operator-only\)/)).toBeInTheDocument();
      expect(screen.queryByText(/please try again/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByText(/Conversation ID/)).toBeInTheDocument();
      expect(screen.getByText(/include this when reporting issues/)).toBeInTheDocument();
      // The id line must still carry the real conversation id, not an empty anchor.
      expect(
        screen.getByText(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/),
      ).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a stream that errors before done reports the pending-approval wait, not the raw retry text', async () => {
    mockListApprovals.mockResolvedValue([pendingAsk('a1')]);
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      // exactly what streamMessage emits when the SSE connection drops
      args[3]?.onError?.('Connection closed unexpectedly. Please try again.');
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    expect(screen.getByText(/1 pending ask in the Review Queue/)).toBeInTheDocument();
    expect(screen.queryByText(/please try again/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/connection closed unexpectedly/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(screen.getByText(/Conversation ID/)).toBeInTheDocument();
  });

  it('an ordinary successful stream neither consults the approvals API nor adds a wait message', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onMessage?.('All systems nominal.');
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: 'chat' });
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    expect(screen.getByText('All systems nominal.')).toBeInTheDocument();
    expect(mockListApprovals).not.toHaveBeenCalled();
    expect(screen.queryByText(/waiting for your approval/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Conversation ID/)).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// A failed TURN reports the actual failure reason and offers a retry that
// resends the turn's own prompt — from the timeline (A1 synthetic entries) and
// from the enriched stream error payload (A4). The pending-ask copy stays the
// fallback for a run whose state is unknown.
// ---------------------------------------------------------------------------

describe('AriaPanel failed-turn reporting (timeline + stream)', () => {
  it('a failed turn from the timeline renders its reason and offers retry using its own prompt', async () => {
    mockGetLatest.mockResolvedValue({ conversationId: 'conv-1', lastMessageAt: '2026-09-30T10:01:00Z', runCount: 2 });
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'what next', timestamp: '2026-09-30T10:00:00Z', runId: 'r0' },
      {
        role: 'assistant',
        content: '回合執行失敗：relay dead',
        timestamp: '2026-09-30T10:01:00Z',
        runId: 'r1',
        error: true,
        retryPrompt: 'what next',
      },
    ]);
    renderPanel();
    await act(async () => {}); // flush the conversation load

    await openPanel();
    expect(await screen.findByText(/relay dead/)).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
    expect(mockStream.mock.calls[0][1]).toBe('what next');
  });

  it('a stream turn failure shows the reason instead of the pending-ask copy', async () => {
    mockListApprovals.mockResolvedValue(
      Array.from({ length: 14 }, (_, i) => pendingAsk(`a${i}`)),
    );
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      // the enriched error payload streamMessage emits for a failed turn (A4)
      args[3]?.onError?.({ turnFailed: true, reason: 'relay dead' });
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    expect(screen.getByText(/relay dead/)).toBeInTheDocument();
    expect(screen.getByText(/上一回合失敗/)).toBeInTheDocument();
    expect(screen.queryByText(/pending ask/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
