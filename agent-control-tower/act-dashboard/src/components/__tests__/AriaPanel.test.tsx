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

// The panel subscribes to /topic/events for the first time (spec A3). The
// socket is a live connection in the browser and an unconnectable one under
// jsdom, so it is replaced by this controllable bridge: tests emit frames and
// flip the connection state, nothing else.
const wsBridge = vi.hoisted(() => {
  type Frame = { type: string; payload: Record<string, unknown>; timestamp: string };
  const handlers = new Set<(frame: Frame) => void>();
  const state = { isConnected: false };
  return {
    handlers,
    state,
    emit(event: { type: string; payload?: Record<string, unknown> }) {
      const frame: Frame = {
        type: event.type,
        payload: event.payload ?? {},
        timestamp: new Date().toISOString(),
      };
      handlers.forEach((handler) => handler(frame));
    },
    subscribe(handler: (frame: Frame) => void) {
      handlers.add(handler);
      return { unsubscribe: () => { handlers.delete(handler); } };
    },
  };
});

vi.mock('../../hooks/useWebSocket', () => ({
  useWebSocket: () => ({
    lastMessage: null,
    isConnected: wsBridge.state.isConnected,
    send: () => {},
    subscribe: wsBridge.subscribe,
  }),
}));

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
vi.mock('../../api/approvals', () => ({ listApprovals: vi.fn(), getApproval: vi.fn() }));

import { streamMessage } from '../../api/aria';
import { getLatestConversation, getConversationTimeline, deleteConversation } from '../../api/ariaConversations';
import { listSkills } from '../../api/skills';
import { getApproval, listApprovals } from '../../api/approvals';
import { cancelRun } from '../../api/runs';

const mockStream = streamMessage as Mock;
const mockGetLatest = getLatestConversation as Mock;
const mockGetTimeline = getConversationTimeline as Mock;
const mockDeleteConversation = deleteConversation as Mock;
const mockListSkills = listSkills as Mock;
const mockListApprovals = listApprovals as Mock;
const mockGetApproval = getApproval as Mock;
const mockCancelRun = cancelRun as Mock;

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
  // The by-id read is only consulted for an ask that left the pending list; an
  // unanswered probe (null) must never settle a card on its own.
  mockGetApproval.mockResolvedValue(null);
  mockCancelRun.mockResolvedValue(undefined);
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
    // affordance stays for a failure that is not an approval wait. The legacy
    // path reconciles against the timeline first (one bounded re-check), so
    // this assertion must outwait that re-check window.
    expect(await screen.findByText(/no approval is pending/, undefined, { timeout: 3000 })).toBeInTheDocument();
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

    // The legacy path reconciles against the timeline first — one bounded
    // re-check — before landing on today's approvals copy.
    expect(
      await screen.findByText(/1 pending ask in the Review Queue/, undefined, { timeout: 3000 }),
    ).toBeInTheDocument();
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

  it('a legacy stream error resolves to the failed-turn bubble when the timeline has a failure entry', async () => {
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
    mockListApprovals.mockResolvedValue([pendingAsk('a1')]);
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      // The engine's own legacy error event: a plain message, no turnFailed
      // payload — the misreporting case this reconcile exists for.
      args[3]?.onError?.('legacy engine error');
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    expect(await screen.findByText(/relay dead/)).toBeInTheDocument();
    expect(screen.getByText(/上一回合失敗/)).toBeInTheDocument();
    expect(screen.queryByText(/pending ask/i)).not.toBeInTheDocument();
    // The truthful timeline entry replaces the approvals probe entirely.
    expect(mockListApprovals).not.toHaveBeenCalled();

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2));
    expect(mockStream.mock.calls[1][1]).toBe('what next');
  });

  it('a legacy stream error with no failure entry keeps the approvals copy', async () => {
    mockGetTimeline.mockResolvedValue([
      // The last entry is not an error entry — no failed turn to report.
      { role: 'user', content: 'hello', timestamp: '2026-09-30T10:00:00Z', runId: 'r0' },
    ]);
    mockListApprovals.mockResolvedValue([pendingAsk('a1')]);
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onError?.('legacy engine error');
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    // Exactly one bounded re-check: first read + one re-read, then today's copy.
    expect(
      await screen.findByText(/1 pending ask in the Review Queue/, undefined, { timeout: 3000 }),
    ).toBeInTheDocument();
    expect(mockGetTimeline).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/上一回合失敗/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// One-click synthesis (Feature B2): the batch-completion notification composes
// the prompt and hands it over through the `aria:compose` window event. The
// panel owns the SSE path, so the event must go through sendStreamed exactly
// like a typed message — and the listener must not survive unmount.
// ---------------------------------------------------------------------------

describe('AriaPanel aria:compose handling', () => {
  it('an aria:compose event sends the prompt through the stream path', async () => {
    renderPanel();
    await act(async () => {}); // flush the conversation load

    act(() => {
      window.dispatchEvent(new CustomEvent('aria:compose', { detail: { prompt: 'summarize the batch' } }));
    });

    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
    expect(mockStream.mock.calls[0][1]).toBe('summarize the batch');
  });

  it('an aria:compose event opens the panel', async () => {
    renderPanel();
    await act(async () => {}); // flush the conversation load
    // The 彙整 button lives outside the panel — with the panel closed the
    // composed turn must still become visible instead of running unseen.
    expect(screen.queryByRole('dialog', { name: 'Aria assistant' })).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new CustomEvent('aria:compose', { detail: { prompt: 'summarize the batch' } }));
    });

    expect(await screen.findByRole('dialog', { name: 'Aria assistant' })).toBeInTheDocument();
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
    expect(mockStream.mock.calls[0][1]).toBe('summarize the batch');
  });

  it('stops listening for aria:compose on unmount', async () => {
    const { unmount } = renderPanel();
    await act(async () => {});
    unmount();

    act(() => {
      window.dispatchEvent(new CustomEvent('aria:compose', { detail: { prompt: 'late prompt' } }));
    });

    expect(mockStream).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// In-flow decisions (spec A3, findings F1/F4/F7). An ask announced on the
// turn's own ordered stream becomes a card inside the conversation; the
// composer is gated until it settles; the WebSocket plane and the REST
// reconcile merge into the same card by ask id, never a duplicate; restored
// tool rows become an activity strip, never raw JSON bubbles.
// ---------------------------------------------------------------------------

const TOOL = 'mcp__aria-conductor__remember_fact';

function summary(conversationId = 'conv-1') {
  return { conversationId, lastMessageAt: '2026-11-11T09:00:00Z', runCount: 2 };
}

/** A pending tool ask on the REST plane, carrying the governance labels. */
function pendingToolAsk(id = 'a1', runId = 'r1'): Approval {
  return {
    id,
    runId,
    toolCallId: null,
    status: 'PENDING',
    reason: 'write_file on the workspace',
    requestedAt: '2026-11-11T09:00:00Z',
    decidedAt: null,
    expiresAt: '2026-11-11T09:10:00Z',
    toolName: TOOL,
    riskTier: 'WRITE_LOCAL',
    toolPolicy: 'WORKER_WRITE',
  };
}

async function openPanelForTest() {
  await act(async () => {}); // flush the conversation load
  fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
}

beforeEach(() => {
  wsBridge.handlers.clear();
  wsBridge.state.isConnected = false;
});

describe('AriaPanel in-flow decisions', () => {
  it('renders an approval_required frame as a card and gates the composer until it settles', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({
        runId: 'r1',
        approvalId: 'a1',
        toolName: TOOL,
        riskTier: 'WRITE_LOCAL',
        toolPolicy: 'WORKER_WRITE',
      });
      // The turn is parked; the operator's client may stop reading the stream
      // (timeout) while the ask is still the thing to answer.
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();

    // Positive control: with no ask outstanding the composer is usable.
    expect(textarea()).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(screen.getByText('WRITE_LOCAL')).toBeInTheDocument();
    expect(screen.getByText('WORKER_WRITE')).toBeInTheDocument();
    // A decision is pending: the composer is gated with an explicit reason.
    expect(screen.getByText('Answer or cancel the pending decision first.')).toBeInTheDocument();
    expect(textarea()).toBeDisabled();
  });

  it('settles the card from the WebSocket plane, releasing the composer without a duplicate', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    act(() => {
      wsBridge.emit({ type: 'approval.decided', payload: { approvalId: 'a1', decision: 'APPROVED' } });
    });

    expect(screen.getByText(/allowed once/i)).toBeInTheDocument();
    expect(screen.queryByText('Answer or cancel the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    // A re-delivery of the same decision (another plane, a reconnect) draws nothing new.
    act(() => {
      wsBridge.emit({ type: 'approval.decided', payload: { approvalId: 'a1', decision: 'APPROVED' } });
    });
    expect(screen.getAllByText(TOOL)).toHaveLength(1);
    expect(screen.getByText(/allowed once/i)).toBeInTheDocument();
  });

  it('leaves an ask untouched when a decision names a different id', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    act(() => {
      wsBridge.emit({ type: 'approval.decided', payload: { approvalId: 'other-ask', decision: 'APPROVED' } });
    });

    // The card is still the operator's to decide (positive control for the
    // unknown-id no-op: the same frame with a1 settles it in the test above).
    expect(screen.getByText('Answer or cancel the pending decision first.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
  });

  it('renders a waiting_input frame as a question card that gates the composer', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onWaitingInput?.({ runId: 'r1', approvalId: 'a2', question: 'Which PR should I review?' });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText('Which PR should I review?')).toBeInTheDocument();
    expect(screen.getByLabelText(/your answer/i)).toBeInTheDocument();
    expect(textarea()).toBeDisabled();

    act(() => {
      wsBridge.emit({ type: 'approval.decided', payload: { approvalId: 'a2', decision: 'APPROVED' } });
    });
    expect(screen.getByText(/answered/i)).toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('restores tool rows as an activity strip instead of raw JSON bubbles', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'how many runs', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
      {
        role: 'tool',
        content: 'tool_call: mcp__aria-conductor__list_runs {"status":"RUNNING"}',
        timestamp: '2026-11-11T09:00:01Z',
        runId: 'r1',
      },
      { role: 'tool', content: '{"total":3}', timestamp: '2026-11-11T09:00:02Z', runId: 'r1' },
      { role: 'assistant', content: 'Three runs are live.', timestamp: '2026-11-11T09:00:03Z', runId: 'r1' },
    ]);
    mockListApprovals.mockResolvedValue([]);
    renderPanel();
    await openPanelForTest();

    expect(await screen.findByText('mcp__aria-conductor__list_runs')).toBeInTheDocument();
    expect(screen.getByText(/1 tool call/)).toBeInTheDocument();
    expect(screen.getByText('Three runs are live.')).toBeInTheDocument();
    // No raw row and no raw result JSON in the transcript.
    expect(screen.queryByText(/tool_call:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\{"total":3\}/)).not.toBeInTheDocument();
  });

  it('merges a REST-reconciled pending ask with the card already on screen', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my channel', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    mockListApprovals.mockResolvedValue([pendingToolAsk()]);
    renderPanel();
    await openPanelForTest();

    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(screen.getByText('WORKER_WRITE')).toBeInTheDocument();
    const reconcilesAfterMount = mockGetTimeline.mock.calls.length;

    // A second reconcile (focus / reconnect) re-delivers the same pending ask.
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesAfterMount);
    expect(screen.getAllByText(TOOL)).toHaveLength(1);
  });

  it('re-reconciles the conversation when the socket reconnects', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'hello', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    mockListApprovals.mockResolvedValue([]);
    renderPanel();
    await openPanelForTest();
    const reconcilesAfterMount = mockGetTimeline.mock.calls.length;

    wsBridge.state.isConnected = true;
    fireEvent.change(textarea(), { target: { value: 'still here?' } });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesAfterMount);
  });

  it('stops the cleared conversation\'s parked turn from writing into the new one', async () => {
    let emitLateFrame: ((content: string) => void) | null = null;
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      emitLateFrame = (content: string) => args[3]?.onMessage?.(content);
      return new Promise<void>(() => {}); // the parked turn keeps its stream open
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));

    // The card belonged to the conversation that was just cleared.
    expect(screen.queryByText(TOOL)).not.toBeInTheDocument();
    expect(screen.queryByText('Answer or cancel the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    // A frame the cleared turn emits afterwards must not land in the new transcript.
    act(() => { emitLateFrame?.('late reply from the parked turn'); });
    expect(screen.queryByText('late reply from the parked turn')).not.toBeInTheDocument();
  });

  it('names the runs the timeline shows as still unanswered', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'first', timestamp: '2026-11-11T09:00:00Z', runId: 'run-aaaa1111' },
      { role: 'assistant', content: 'done', timestamp: '2026-11-11T09:00:01Z', runId: 'run-aaaa1111' },
      { role: 'user', content: 'second', timestamp: '2026-11-11T09:00:02Z', runId: 'run-bbbb2222' },
    ]);
    mockListApprovals.mockResolvedValue([]);
    renderPanel();
    await openPanelForTest();

    expect(await screen.findByText(/Queued · run-bbbb/)).toBeInTheDocument();
    // The answered turn is not queued (negative control).
    expect(screen.queryByText(/Queued · run-aaaa/)).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// A pending card is never settled by a reconcile in the shipped panel, so a
// decision the panel never heard about (a missed `approval.expired` frame, a
// Cancel while the turn is parked, a decision taken in another window) left the
// composer gated forever behind a card whose Allow then only 409s. The durable
// snapshot is authoritative for an ask it no longer lists: the card must say
// what became of it and the composer must come back. A card the server still
// lists stays the operator's to decide (the positive control in each case).
// ---------------------------------------------------------------------------

describe('AriaPanel settle-on-reconcile for asks the snapshot no longer lists', () => {
  function mountWithPendingAsk() {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my channel', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    mockListApprovals.mockResolvedValue([pendingToolAsk('a1', 'r1')]);
  }

  it('settles a missed expiry as expired with its reason and releases the composer', async () => {
    mountWithPendingAsk();
    renderPanel();
    await openPanelForTest();

    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(textarea()).toBeDisabled();

    // The expiry frame was lost while the socket was down; the server no longer
    // lists the ask and reports it expired with the backend's own reason.
    mockListApprovals.mockResolvedValue([]);
    mockGetApproval.mockResolvedValue({
      ...pendingToolAsk('a1', 'r1'),
      status: 'EXPIRED',
      reason: 'Auto-rejected: approval expired',
    });
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    const settled = await screen.findByText(/expired before a decision/i);
    expect(settled.textContent).toMatch(/auto-rejected: approval expired/i);
    expect(screen.queryByRole('button', { name: /allow once/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/pending decision first/i)).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('leaves a still-pending ask gated when the snapshot still lists it', async () => {
    mountWithPendingAsk();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    // Positive control for the settle above: the same reconcile, with the ask
    // still PENDING, must change nothing.
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(screen.getByText(TOOL)).toBeInTheDocument();
    expect(screen.getByText(/pending decision first/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
    expect(textarea()).toBeDisabled();
    expect(mockGetApproval).not.toHaveBeenCalled();
  });

  it('settles a parked ask when the operator cancels its run', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      return new Promise<void>(() => {}); // parked: the turn keeps its stream open
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(textarea()).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel request' }));
    await act(async () => {});

    expect(mockCancelRun).toHaveBeenCalledWith('r1');
    // The run is cancelled, so the ask can no longer be decided: the card says
    // so (the cancel is the decision) instead of staying actionable.
    const settled = await screen.findByText(/expired before a decision/i);
    expect(settled.textContent).toMatch(/cancel/i);
    expect(screen.queryByRole('button', { name: /allow once/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/pending decision first/i)).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('reports an ask already decided elsewhere instead of leaving it actionable', async () => {
    mountWithPendingAsk();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    mockListApprovals.mockResolvedValue([]);
    mockGetApproval.mockResolvedValue({ ...pendingToolAsk('a1', 'r1'), status: 'APPROVED' });
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(await screen.findByText(/allowed once/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /allow once/i })).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('reports an ask the server no longer has as expired instead of inviting a retry', async () => {
    mountWithPendingAsk();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    // The PENDING list dropped it and the by-id read says it is gone (404).
    mockListApprovals.mockResolvedValue([]);
    mockGetApproval.mockRejectedValue({ response: { status: 404 } });
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(await screen.findByText(/expired before a decision/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /allow once/i })).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });
});
