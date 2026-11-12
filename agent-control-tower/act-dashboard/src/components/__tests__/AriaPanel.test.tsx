import { describe, it, expect, beforeEach, beforeAll, vi, type Mock } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

// The panel is rendered inside `WebSocketProvider` in the app (Layout.tsx), so
// it must consume the app-wide connection through the shared context — the same
// socket ConnectionBanner reports — and never open one of its own. This mock is
// that context; a panel that went back to `useWebSocket()` would subscribe to a
// connection this bridge never sees, and every frame case below would fail.
vi.mock('../Layout', () => ({
  useWebSocketContext: () => ({
    lastMessage: null,
    isConnected: wsBridge.state.isConnected,
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
vi.mock('../../api/runs', () => ({ cancelRun: vi.fn(), getRun: vi.fn() }));
vi.mock('../../api/skills', () => ({ listSkills: vi.fn() }));
vi.mock('../../api/approvals', () => ({
  listApprovals: vi.fn(),
  getApproval: vi.fn(),
  decideApproval: vi.fn(),
  answerAsk: vi.fn(),
}));
// The memory proposal card owns both the decide call and the entity batch read;
// the panel's only memory read is the PENDING inbox it reconciles against.
vi.mock('../../api/memory', () => ({
  decideMemoryProposal: vi.fn(),
  listMemoryProposals: vi.fn(),
}));

import { streamMessage } from '../../api/aria';
import { getLatestConversation, getConversationTimeline, deleteConversation } from '../../api/ariaConversations';
import { listSkills } from '../../api/skills';
import { decideApproval, answerAsk, getApproval, listApprovals } from '../../api/approvals';
import { cancelRun, getRun } from '../../api/runs';
import { decideMemoryProposal, listMemoryProposals } from '../../api/memory';
import type { MemoryProposalView } from '../../api/memory';
// The inbox page size has ONE client-side source (the card that renders a
// proposal batch); the panel's reconcile imports it rather than carrying a
// second copy that could disagree with the card about a full page.
import { PROPOSAL_INBOX_LIMIT } from '../AriaProposalCard';

const mockStream = streamMessage as Mock;
const mockGetLatest = getLatestConversation as Mock;
const mockGetTimeline = getConversationTimeline as Mock;
const mockDeleteConversation = deleteConversation as Mock;
const mockListSkills = listSkills as Mock;
const mockListApprovals = listApprovals as Mock;
const mockGetApproval = getApproval as Mock;
const mockDecideApproval = decideApproval as Mock;
const mockAnswer = answerAsk as Mock;
const mockCancelRun = cancelRun as Mock;
const mockGetRun = getRun as Mock;
const mockDecideMemoryProposal = decideMemoryProposal as Mock;
const mockListMemoryProposals = listMemoryProposals as Mock;

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

/** The FULL conversation id: the header truncates the visible text, `title` carries it whole. */
function conversationIdOf(container: HTMLElement): string {
  return (container.querySelector('.ai-conversation-id') as HTMLElement | null)?.title ?? '';
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
  mockDecideApproval.mockResolvedValue({ approvalId: 'a1', approved: true, status: 'processed' });
  // The PENDING inbox the panel reconciles against: nothing to decide unless a
  // test lists it. A decision answers the entity/fact view the route returns.
  mockListMemoryProposals.mockResolvedValue([]);
  mockDecideMemoryProposal.mockResolvedValue({
    id: 'p1',
    subjectType: 'FACT',
    entityId: 'ent-1',
    factKey: 'release.channel',
    valueText: 'email',
    predicate: null,
    toEntityId: null,
    status: 'ACTIVE',
    origin: 'OPERATOR',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: null,
    validFrom: null,
  });
  mockCancelRun.mockResolvedValue(undefined);
  // The queued indicator asks the run itself: only an admitted (PENDING) run is
  // queued, so the default fixture is the admitted case.
  mockGetRun.mockResolvedValue({ id: 'run-bbbb2222', status: 'PENDING' });
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

/** Sends one message against a stream that never answers, then fires the panel's timeout. */
async function sendThenTimeOut() {
  mockStream.mockImplementation(async (...args: StreamArgs) => {
    args[3]?.onThinking?.('run-1');
    return new Promise<void>(() => {}); // never settles — the panel's own timer reports
  });
  renderPanel();
  await act(async () => {}); // flush the conversation load so the id line is bound
  fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
  // Sent through the composer rather than the empty state's suggestion: a
  // reconciled conversation renders its transcript, so the suggestions are gone
  // and only the composer sends. A userEvent cannot drive this under the fake
  // timers the timeout needs.
  fireEvent.change(textarea(), { target: { value: 'brief me on overnight runs' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send message' }));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(600_000); // CLIENT_TIMEOUT_MS, module-private
  });
  await act(async () => {}); // let the pending-approval probe's state update land
}

describe('AriaPanel run-uncertain reporting (timeout / dropped stream)', () => {
  it('a client timeout with pending asks names the count, points at the Review Queue and offers no retry', async () => {
    vi.useFakeTimers();
    try {
      // T32 Item 4: the reconcile must really consult the queue before the report
      // does. With an empty timeline it reads no approvals at all, so "consulted
      // once" was true by construction and pinned nothing. A completed run's rows
      // give it run ids to intersect, so this fixture would produce a second read
      // unless the two surfaces share one.
      mockGetLatest.mockResolvedValue(summary());
      mockGetTimeline.mockResolvedValue([
        { role: 'user', content: 'brief me', timestamp: '2026-09-30T10:00:00Z', runId: 'run-1' },
        { role: 'assistant', content: 'done', timestamp: '2026-09-30T10:01:00Z', runId: 'run-1' },
      ]);
      // The waiting asks belong to another run of the same backend-wide queue
      // (they are not this conversation's cards, so the composer stays usable).
      mockListApprovals.mockResolvedValue([
        { ...pendingAsk('a1'), runId: 'run-9' },
        { ...pendingAsk('a2'), runId: 'run-9' },
      ]);
      await sendThenTimeOut();

      expect(screen.getByText(/2 pending asks in the Review Queue/)).toBeInTheDocument();
      // Task 37 Item 3: the count comes from a snapshot the panel read earlier
      // (the reconcile's, before this turn), so the report must say when it was
      // taken. Stated as current, a lost frame would invite a resend onto a live
      // run — the number is a fact about the last refresh, not right now.
      expect(
        screen.getByText(/2 pending asks in the Review Queue as of this panel's last refresh/),
      ).toBeInTheDocument();
      // The one read is the reconcile's: it happened before this turn was sent,
      // and the timeout report consumed it instead of asking a second time.
      expect(mockGetTimeline).toHaveBeenCalled();
      expect(mockListApprovals).toHaveBeenCalledTimes(1);
      expect(mockListApprovals).toHaveBeenCalledWith('PENDING');
      expect(mockListApprovals.mock.invocationCallOrder[0]).toBeLessThan(
        mockStream.mock.invocationCallOrder[0],
      );
      expect(screen.queryByText(/please try again/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
      expect(screen.getByText(/Conversation ID/)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  // The control for sharing one read: the snapshot is only reused while nothing
  // says the queue changed. A live approval event drops it, so the timeout report
  // re-reads and names the count the queue holds now — never a stale one.
  it('drops the shared queue snapshot on a live approval event and re-reads for the report', async () => {
    vi.useFakeTimers();
    try {
      mockGetLatest.mockResolvedValue(summary());
      mockGetTimeline.mockResolvedValue([
        { role: 'user', content: 'brief me', timestamp: '2026-09-30T10:00:00Z', runId: 'run-1' },
        { role: 'assistant', content: 'done', timestamp: '2026-09-30T10:01:00Z', runId: 'run-1' },
      ]);
      mockListApprovals.mockResolvedValue([{ ...pendingAsk('a1'), runId: 'run-9' }]);
      mockStream.mockImplementation(async (...args: StreamArgs) => {
        args[3]?.onThinking?.('run-1');
        return new Promise<void>(() => {});
      });
      renderPanel();
      await act(async () => {}); // the mount reconcile reads the queue once
      fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
      fireEvent.change(textarea(), { target: { value: 'brief me on overnight runs' } });
      fireEvent.click(screen.getByRole('button', { name: 'Send message' }));

      // Another ask joined the queue while this turn runs.
      mockListApprovals.mockResolvedValue([
        { ...pendingAsk('a1'), runId: 'run-9' },
        { ...pendingAsk('a2'), runId: 'run-9' },
      ]);
      act(() => {
        wsBridge.emit({ type: 'approval.requested', payload: { approvalId: 'a2', runId: 'run-9' } });
      });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(600_000);
      });
      await act(async () => {});

      expect(screen.getByText(/2 pending asks in the Review Queue/)).toBeInTheDocument();
      expect(mockListApprovals).toHaveBeenCalledTimes(2);
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
      // Task 37 / Item 6 (T35 audit row 15): saying the check was refused without
      // naming a remedy left the operator at a dead end. The report now carries
      // the shared remedy — and never the advice that cannot work.
      expect(
        screen.getByText(/open the dashboard on the machine that runs the backend/i),
      ).toBeInTheDocument();
      expect(screen.queryByText(/reload the page and retry/i)).not.toBeInTheDocument();
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

  // -------------------------------------------------------------------------
  // T54 F3, against the T53 backend contract: run 961868f4 failed because the
  // Qoder core rejected the stored credential at session/new. The message now
  // STARTS with `E_CREDENTIAL_REJECTED` (the timeline clips it to 300 chars) and
  // keeps the phrase "Qoder runtime credential". The bubble must point at the
  // credential surface and the action, live and restored alike.
  // -------------------------------------------------------------------------
  const CREDENTIAL_REJECTED_REASON = 'E_CREDENTIAL_REJECTED: the core rejected the saved Qoder '
    + 'runtime credential at session/new (E_AUTH_REQUIRED) — a credential failure, not a transport '
    + 'failure; replace the saved credential at PUT /api/v1/cores/qoder/credential and re-dispatch '
    + 'the run. The runtime endpoint of run 961868f4-1a5a-44d3-8923-6129991c16e4 did not '
    + "authenticate the run's control secret (no authenticated answer within PT30S).";

  it('turns a refused credential into an actionable remedy that opens the credential surface', async () => {
    const configureListener = vi.fn();
    window.addEventListener('act:open-configure', configureListener);
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onError?.({ turnFailed: true, reason: CREDENTIAL_REJECTED_REASON });
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    const remedy = await screen.findByTestId('credential-remedy');
    expect(remedy).toHaveTextContent(/Qoder runtime credential/);
    expect(remedy).toHaveTextContent(/rejected/i);
    expect(remedy).toHaveTextContent(/Qoder personal access token/i);
    // The reason itself is not hidden — the operator can still quote it.
    expect(screen.getByText(/control secret/)).toBeInTheDocument();

    await userEvent.click(within(remedy).getByRole('button', { name: /LLM Providers/ }));
    expect(configureListener).toHaveBeenCalledTimes(1);
    expect((configureListener.mock.calls[0][0] as CustomEvent).detail).toEqual({ tab: 'llm' });
    window.removeEventListener('act:open-configure', configureListener);
  });

  it('carries the remedy onto a restored credential failure too', async () => {
    mockGetLatest.mockResolvedValue({ conversationId: 'conv-1', lastMessageAt: '2026-11-11T09:00:00Z', runCount: 1 });
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'Hi, I am Happy', timestamp: '2026-11-11T09:00:00Z', runId: 'r0' },
      {
        role: 'assistant',
        content: `回合執行失敗：${CREDENTIAL_REJECTED_REASON}`,
        timestamp: '2026-11-11T09:01:00Z',
        runId: 'r1',
        error: true,
        retryPrompt: 'Hi, I am Happy',
      },
    ]);
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));

    const remedy = await screen.findByTestId('credential-remedy');
    expect(remedy).toHaveTextContent(/Qoder runtime credential/);
  });

  // Positive controls: an ordinary failure keeps the plain bubble, and a
  // transport failure recorded BEFORE the classification is not relabelled as
  // a credential problem — the marker is the evidence.
  it('adds no credential remedy to an unrelated failure', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onError?.({ turnFailed: true, reason: 'relay dead' });
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    expect(screen.getByText(/relay dead/)).toBeInTheDocument();
    expect(screen.queryByTestId('credential-remedy')).not.toBeInTheDocument();
  });

  it('adds no credential remedy to an unclassified transport failure', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onError?.({
        turnFailed: true,
        reason: "The runtime endpoint of run r1 did not authenticate the run's control secret.",
      });
    });
    renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Brief me on overnight runs' }));
    await act(async () => {});

    expect(screen.getByText(/control secret/)).toBeInTheDocument();
    expect(screen.queryByTestId('credential-remedy')).not.toBeInTheDocument();
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

/** One row of `GET /api/v1/memory/proposals?status=PENDING`, as the server serves it. */
function proposalRow(over: Partial<MemoryProposalView> = {}): MemoryProposalView {
  return {
    id: 'p1',
    subjectType: 'FACT',
    entityId: 'ent-1',
    factKey: 'release.channel',
    valueText: 'email',
    predicate: null,
    toEntityId: null,
    status: 'PENDING',
    origin: 'EXTRACTION',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: '2026-11-11T09:00:00Z',
    validFrom: null,
    ...over,
  };
}

/** One streamed turn that raises a FACT proposal, then finishes. */
function streamProposal(): void {
  mockStream.mockImplementation(async (...args: StreamArgs) => {
    args[3]?.onThinking?.('r1');
    args[3]?.onMemoryProposal?.({
      runId: 'r1',
      id: 'p1',
      subjectType: 'FACT',
      factKey: 'release.channel',
      valueText: 'email',
      sensitivity: 'INTERNAL',
    });
    args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
  });
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
    expect(screen.getByText('Answer the pending decision first.')).toBeInTheDocument();
    expect(textarea()).toBeDisabled();
  });

  // T45 Item 3 (spec A3): the operator must see what they are approving. The SSE
  // frame carries no arguments (RunSignalSseListener.java:84-92), so the card
  // reads the ask's own record — GET /api/v1/approvals/{id} serves
  // ApprovalDetail.arguments, the read the panel already has.
  it('shows the argument summary the ask\'s own record carries, not a blind approve', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    mockGetApproval.mockResolvedValue({
      ...pendingToolAsk('a1', 'r1'),
      arguments: JSON.stringify({ command: 'rm -rf build', cwd: '/repo' }),
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    // The summary is compact, but it is the real payload: both keys, in order.
    const summary = await screen.findByText(/command: rm -rf build/);
    expect(summary.textContent).toMatch(/cwd: \/repo/);
    expect(mockGetApproval).toHaveBeenCalledWith('a1');
    // Nothing was decided by the read.
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /deny/i })).toBeEnabled();
  });

  // F-B1 (T54): an ACP ask's record stores the WHOLE permission.request
  // envelope as `arguments` (live evidence B-approvals-T1.json, ask
  // c63cc395-…). The card must show what the tool will do — the envelope's
  // `input` — and none of the protocol plumbing (seq/at/type/requestId/
  // sessionId/toolCallId/inputBytes/argumentsDigest/options) that the operator
  // cannot act on and that discloses the payload as protocol internals.
  it('renders an ACP permission envelope as its tool input, never as a protocol dump', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({
        runId: 'r1',
        approvalId: 'a1',
        toolName: TOOL,
        riskTier: 'WRITE_LOCAL',
        toolPolicy: 'WORKER_WRITE',
      });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    // Verbatim from the live acceptance run (B-approvals-T1.json), the ask the
    // operator was shown.
    mockGetApproval.mockResolvedValue({
      ...pendingToolAsk('a1', 'r1'),
      arguments: JSON.stringify({
        seq: 248,
        at: '2026-11-11T23:26:57.051Z',
        type: 'permission.request',
        requestId: 7,
        sessionId: '5708a4ad-6ef0-4605-861d-0fc7c05cc799',
        toolCallId: 'call_76afe3236bab41868add80af',
        toolName: TOOL,
        toolKind: 'other',
        toolCallTitle: 'remember_fact (aria-conductor)',
        input: {
          canonicalKey: 'person:operator',
          factKey: 'morning_briefing_content',
          kind: 'person',
          sourceRef: 'message:good-morning-briefing-2026-11-12',
          valueText: 'Wants a morning briefing covering the fleet.',
        },
        inputBytes: 295,
        inputTruncated: false,
        argumentsDigest: 'a790e991fa9d6983f46dcade8c5dcb13c5079e20419538e44094c878f5c60f7f',
        options: [
          { optionId: 'proceed_always_and_save', kind: 'allow_always', name: 'Always allow "remember_fact"' },
          { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow' },
          { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
        ],
      }),
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    const card = screen.getByText(TOOL).closest('.ai-decision-card') as HTMLElement;
    expect(card).not.toBeNull();
    // The card is drawn with its governance labels and its two decisions.
    expect(card.textContent).toContain('WRITE_LOCAL');
    expect(card.textContent).toContain('WORKER_WRITE');
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^deny$/i })).toBeEnabled();

    // The payload the operator is deciding on is still shown, by key.
    expect(card.textContent).toContain('canonicalKey: person:operator');
    expect(card.textContent).toContain('factKey: morning_briefing_content');
    expect(card.textContent).toContain('valueText: Wants a morning briefing covering the fleet.');

    // …and none of the protocol envelope is painted anywhere in the card.
    for (const leaked of [
      'permission.request',
      'seq:',
      'sessionId',
      'requestId',
      'toolCallId',
      'inputBytes',
      'argumentsDigest',
      'inputTruncated',
      'proceed_always_and_save',
      'reject_once',
    ]) {
      expect(card.textContent).not.toContain(leaked);
    }

    // F-B1 whole-transcript guard: the leak class is "a structured signal also
    // paints as raw text", so the invariant is about the WHOLE transcript, not
    // just the card body. A sibling raw bubble above the card would have
    // painted these; the transcript must hold them nowhere, and the ask must
    // appear exactly once (the card), not twice.
    const transcript = document.body.textContent ?? '';
    for (const leaked of [
      'permission.request',
      'argumentsDigest',
      'a790e991fa9d6983f46dcade8c5dcb13c5079e20419538e44094c878f5c60f7f',
      '5708a4ad-6ef0-4605-861d-0fc7c05cc799',
      'call_76afe3236bab41868add80af',
      'inputBytes',
      'inputTruncated',
      '"options"',
    ]) {
      expect(transcript).not.toContain(leaked);
    }
    expect(screen.getAllByText(TOOL)).toHaveLength(1);
    expect(screen.getAllByText(/canonicalKey: person:operator/)).toHaveLength(1);
  });

  it('says the arguments could not be read instead of drawing an empty summary', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    mockGetApproval.mockRejectedValue(new Error('Network Error'));
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(await screen.findByText(/arguments could not be read/i)).toBeInTheDocument();
    // Still the operator's to decide — with the tool name it does know.
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
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
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
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
    expect(screen.getByText('Answer the pending decision first.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
  });

  it('carries the expiry reason the frame reports onto the settled card', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    // The frame the backend broadcasts carries its own reason
    // (EventBroadcastListener: `approval.expired` + reason), which the Review
    // panel shows; dropping it leaves the card saying only "expired".
    act(() => {
      wsBridge.emit({
        type: 'approval.expired',
        payload: { approvalId: 'a1', runId: 'r1', reason: 'Auto-rejected: approval expired' },
      });
    });

    const settled = await screen.findByText(/expired before a decision/i);
    expect(settled.textContent).toMatch(/auto-rejected: approval expired/i);
    expect(textarea()).toBeEnabled();
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

  // T45 Item 2: `/answer` 409s when the ask expired, the run was finalized or a
  // concurrent answer won (ApprovalController.java:239-262). The card used to say
  // "the question is still waiting; retry", leaving it pending and the composer
  // locked behind a refusal a retry can never fix.
  it('settles a refused clarification answer and releases the composer', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onWaitingInput?.({ runId: 'r1', approvalId: 'a2', question: 'Which PR should I review?' });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    mockAnswer.mockRejectedValueOnce({
      response: { status: 409, data: { error: 'run is not waiting for operator input' } },
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText('Which PR should I review?')).toBeInTheDocument();
    expect(textarea()).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/your answer/i), { target: { value: 'PR #42' } });
    fireEvent.click(screen.getByRole('button', { name: /answer/i }));

    const settled = await screen.findByText(/expired before an answer/i);
    expect(settled.textContent).toMatch(/run is not waiting for operator input/i);
    expect(screen.queryByText(/still waiting; retry/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/your answer/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
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

  it('merges a live SSE ask with the REST pending list that repeats it', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my channel', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    // Nothing pending on the REST plane at mount: the card below is the SSE
    // plane's, the ordering this merge exists for (SSE first, REST second).
    mockListApprovals.mockResolvedValue([]);
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({
        runId: 'r1',
        approvalId: 'a1',
        toolName: TOOL,
        riskTier: 'WRITE_LOCAL',
        toolPolicy: 'WORKER_WRITE',
      });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    // The restored turn is on screen, so the empty-state suggestions are gone:
    // this turn is typed like any other.
    fireEvent.change(textarea(), { target: { value: 'remember my channel' } });
    fireEvent.keyDown(textarea(), { key: 'Enter', shiftKey: false });
    expect(await screen.findByText(TOOL)).toBeInTheDocument();

    // The next reconcile returns the same ask by id: it must merge into the card
    // on screen, not draw a second one and not reopen/duplicate it.
    mockListApprovals.mockResolvedValue([pendingToolAsk('a1', 'r1')]);
    const reconcilesBefore = mockGetTimeline.mock.calls.length;
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesBefore);
    expect(screen.getAllByText(TOOL)).toHaveLength(1);
    expect(screen.getAllByText('WORKER_WRITE')).toHaveLength(1);
    // A merge, not a flip: the card is still the operator's to decide.
    expect(screen.getByText(/pending decision first/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
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

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    });

    // The card belonged to the conversation that was just cleared.
    expect(screen.queryByText(TOOL)).not.toBeInTheDocument();
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    // A frame the cleared turn emits afterwards must not land in the new transcript.
    act(() => { emitLateFrame?.('late reply from the parked turn'); });
    expect(screen.queryByText('late reply from the parked turn')).not.toBeInTheDocument();
  });

  // T46 Item 2 (final review Minor 5): Clear calls
  // `DELETE /api/v1/aria/conversations/{id}`, whose route cancels every
  // RUNNING / PENDING / WAITING_INPUT run of that conversation and then purges
  // its tool calls, trajectories and audit events
  // (`AriaConversationController.java:193-235`). The copy used to say the run
  // "keeps living server-side", which is false, and it is the only wording the
  // operator gets at the moment they escape a parked turn.
  //
  // T48 Item 1 (P1): the button now WAITS for that call before it resets the
  // local transcript, because the server can refuse it (the refusal cases are
  // their own describe below). A resolved delete is therefore the only path that
  // clears locally — which this test pins as the positive control beside them.
  it('describes Clear as the cancellation it is, deletes the conversation it names, and clears once it answered', async () => {
    mockGetLatest.mockResolvedValue(summary('conv-1'));
    const { container } = renderPanel();
    await openPanelForTest();

    const clear = screen.getByRole('button', { name: 'Clear and start new conversation' });
    const title = clear.getAttribute('title') ?? '';
    expect(title).toMatch(/cancels/i);
    expect(title).toMatch(/deletes/i);
    // The false claim in every form it was written in: Clear does not keep a
    // run alive, so no copy may say it does.
    expect(title).not.toMatch(/stays? alive|keeps? living|server-side/i);
    expect(conversationIdOf(container)).toBe('conv-1');

    await act(async () => { fireEvent.click(clear); });

    // The remedy the copy promises is the call the button actually makes on the
    // conversation it names…
    expect(mockDeleteConversation).toHaveBeenCalledWith('conv-1');
    // …and an answered delete IS the local clear: a new conversation, no error.
    await waitFor(() => expect(conversationIdOf(container)).not.toBe('conv-1'));
    expect(conversationIdOf(container)).not.toBe('');
    expect(screen.getByText(/How can I help/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('names the runs the timeline shows as still unanswered', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'first', timestamp: '2026-11-11T09:00:00Z', runId: 'run-aaaa1111' },
      { role: 'assistant', content: 'done', timestamp: '2026-11-11T09:00:01Z', runId: 'run-aaaa1111' },
      { role: 'user', content: 'second', timestamp: '2026-11-11T09:00:02Z', runId: 'run-bbbb2222' },
    ]);
    mockListApprovals.mockResolvedValue([]);
    mockGetRun.mockResolvedValue({ id: 'run-bbbb2222', status: 'PENDING' });
    renderPanel();
    await openPanelForTest();

    expect(await screen.findByText(/Queued · run-bbbb/)).toBeInTheDocument();
    // The indicator is the run's own status, not the shape of the transcript.
    expect(mockGetRun).toHaveBeenCalledWith('run-bbbb2222');
    // The answered turn is not queued (negative control).
    expect(screen.queryByText(/Queued · run-aaaa/)).not.toBeInTheDocument();
  });

  it('does not call a running or dead trailing turn queued', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'first', timestamp: '2026-11-11T09:00:00Z', runId: 'run-aaaa1111' },
      { role: 'assistant', content: 'done', timestamp: '2026-11-11T09:00:01Z', runId: 'run-aaaa1111' },
      { role: 'user', content: 'second', timestamp: '2026-11-11T09:00:02Z', runId: 'run-bbbb2222' },
    ]);
    mockListApprovals.mockResolvedValue([]);
    // A trailing prompt whose run is RUNNING is a hungry/hung turn, not an
    // admitted one; it must not read as "Queued" on a governance surface.
    mockGetRun.mockResolvedValue({ id: 'run-bbbb2222', status: 'RUNNING' });
    renderPanel();
    await openPanelForTest();

    // Positive control: the transcript did render the trailing prompt.
    expect(await screen.findByText('second')).toBeInTheDocument();
    expect(screen.queryByText(/Queued ·/)).not.toBeInTheDocument();
  });

  it('clears the queued pill once the operator sends a new prompt', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'second', timestamp: '2026-11-11T09:00:02Z', runId: 'run-bbbb2222' },
    ]);
    mockListApprovals.mockResolvedValue([]);
    mockGetRun.mockResolvedValue({ id: 'run-bbbb2222', status: 'PENDING' });
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByText(/Queued · run-bbbb/)).toBeInTheDocument();

    // The next turn is the one being watched; leaving the old pill up would name
    // a run that is no longer the transcript's trailing turn.
    fireEvent.change(textarea(), { target: { value: 'third' } });
    fireEvent.keyDown(textarea(), { key: 'Enter', shiftKey: false });
    await act(async () => {});

    expect(screen.queryByText(/Queued ·/)).not.toBeInTheDocument();
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

// ---------------------------------------------------------------------------
// The panel used to call `useWebSocket()` directly, which builds a second
// per-instance STOMP connection per tab: two server sessions, two
// SUBSCRIBEs on /topic/events, two copies of every broadcast frame, and a
// reconnect flag that can disagree with the one ConnectionBanner reports. It is
// rendered inside `WebSocketProvider`, so the shared context is its socket.
// ---------------------------------------------------------------------------

describe('AriaPanel shared WebSocket', () => {
  it('takes exactly one subscription from the shared context and releases it on unmount', async () => {
    const { unmount } = renderPanel();
    await act(async () => {}); // flush the conversation load

    // One subscription, held through the app-wide connection the provider owns.
    expect(wsBridge.handlers.size).toBe(1);

    unmount();
    expect(wsBridge.handlers.size).toBe(0);
  });
});

describe('AriaPanel live tool results', () => {
  // The `tool_result` frame is produced by
  // `act-execution/.../telemetry/CoreEventTelemetry.java` `streamSignal`
  // (`:160-190`, which maps `tool.result` → `tool_result` and always sends `ok`)
  // and consumed by `src/api/aria.ts` (`onToolResult`). The panel used to
  // dispatch every live result as `ok: true`, so a denied or failed tool
  // rendered green (✓) in the strip — a false claim about the one
  // governance-relevant field of the live strip.
  it('renders a failed live tool result as an error', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onToolCall?.('mcp__aria-conductor__write_file');
      args[3]?.onToolResult?.('mcp__aria-conductor__write_file', 'denied by policy', false);
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(/1 tool call · 1 failed/i)).toBeInTheDocument();
  });

  it('hands the live call\'s arguments to the strip', async () => {
    // The `tool_call` frame carries the server-redacted `args`
    // (`CoreEventTelemetry#streamSignal`). The panel used to drop them, so the
    // expanded strip could only ever show a result, never the arguments.
    const args = '{"path":"notes.md","content":"[REDACTED]"}';
    mockStream.mockImplementation(async (...call: StreamArgs) => {
      call[3]?.onThinking?.('r1');
      call[3]?.onToolCall?.('mcp__aria-conductor__write_file', args);
      call[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(/^1 tool call$/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /1 tool call/ }));
    expect(screen.getByText(args)).toBeInTheDocument();
  });

  it('renders a successful live tool result as ok', async () => {
    // Positive control for the case above: ok: true is still a success, so the
    // failed-count only appears when the frame said so.
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onToolCall?.('mcp__aria-conductor__list_runs');
      args[3]?.onToolResult?.('mcp__aria-conductor__list_runs', '[]', true);
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    expect(await screen.findByText(/^1 tool call$/i)).toBeInTheDocument();
    expect(screen.queryByText(/failed/i)).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Spec A2 (design:353-355): an ask raised while this panel's own stream is dead
// reaches it over the WebSocket, and the panel reads the approval by id — the
// frame carries no tool name, tier or policy. This is the dead-stream plane the
// three-plane design exists for: after a client timeout, a cancel or a dropped
// SSE connection, a later ask in the open conversation must still become a card
// instead of pushing the operator to the Review Queue (F1).
// ---------------------------------------------------------------------------

describe('AriaPanel WebSocket-requested asks (dead stream)', () => {
  function mountConversationWithRun() {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my channel', timestamp: '2026-11-11T09:00:00Z', runId: 'r9' },
    ]);
  }

  it('fetches an ask raised after the stream died by id and renders its card', async () => {
    mountConversationWithRun();
    mockGetApproval.mockResolvedValue({
      id: 'a9',
      runId: 'r9',
      status: 'PENDING',
      toolCallId: null,
      reason: 'write_file on the workspace',
      requestedAt: '2026-11-11T09:00:00Z',
      decidedAt: null,
      expiresAt: '2026-11-11T09:10:00Z',
      toolName: TOOL,
      riskTier: 'WRITE_LOCAL',
      toolPolicy: 'WORKER_WRITE',
    });
    renderPanel();
    await openPanelForTest();

    act(() => {
      wsBridge.emit({ type: 'approval.requested', payload: { approvalId: 'a9', runId: 'r9' } });
    });
    await act(async () => {});

    expect(mockGetApproval).toHaveBeenCalledWith('a9');
    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(screen.getByText('WORKER_WRITE')).toBeInTheDocument();
    expect(screen.getByText(/pending decision first/i)).toBeInTheDocument();
    expect(textarea()).toBeDisabled();
  });

  it('ignores an ask raised for another conversation\'s run', async () => {
    mountConversationWithRun();
    mockGetApproval.mockResolvedValue({
      id: 'a-foreign',
      runId: 'another-conversations-run',
      status: 'PENDING',
      reason: 'unrelated',
      requestedAt: '2026-11-11T09:00:00Z',
      decidedAt: null,
      expiresAt: '2026-11-11T09:10:00Z',
      toolName: 'mcp__aria-conductor__foreign_tool',
    });
    renderPanel();
    await openPanelForTest();

    act(() => {
      wsBridge.emit({
        type: 'approval.requested',
        payload: { approvalId: 'a-foreign', runId: 'another-conversations-run' },
      });
    });
    await act(async () => {});

    // Negative control for the case above: a single-conversation panel must not
    // render another conversation's ask, and must not gate on it.
    expect(mockGetApproval).not.toHaveBeenCalled();
    expect(screen.queryByText('mcp__aria-conductor__foreign_tool')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('renders a fetched clarification ask as a question card', async () => {
    mountConversationWithRun();
    mockGetApproval.mockResolvedValue({
      id: 'a10',
      runId: 'r9',
      status: 'PENDING',
      reason: 'which pull request?',
      content: 'Which PR should I review?',
      source: 'CLARIFICATION',
      requestedAt: '2026-11-11T09:00:00Z',
      decidedAt: null,
      expiresAt: '2026-11-11T09:10:00Z',
    });
    renderPanel();
    await openPanelForTest();

    act(() => {
      wsBridge.emit({ type: 'approval.requested', payload: { approvalId: 'a10', runId: 'r9' } });
    });
    await act(async () => {});

    expect(await screen.findByText('Which PR should I review?')).toBeInTheDocument();
    expect(screen.getByLabelText(/your answer/i)).toBeInTheDocument();
    expect(textarea()).toBeDisabled();
  });

  it('re-reads the conversation when the socket reports a run waiting for input', async () => {
    mountConversationWithRun();
    renderPanel();
    await openPanelForTest();
    const reconcilesAfterMount = mockGetTimeline.mock.calls.length;

    act(() => {
      wsBridge.emit({ type: 'run.waiting_input', payload: { runId: 'r9', question: 'Which PR?' } });
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesAfterMount);
  });
});

// ---------------------------------------------------------------------------
// The decision is taken in the panel, not only in the Review Queue: clicking
// Allow once must POST the decision, settle the card, lift the gate, and leave
// the parked turn's own stream open so the turn resumes on it. The report
// claimed "approving resumes the same turn" with no executed evidence; this is
// that evidence at the unit tier (the live path stays the acceptance phase's).
// ---------------------------------------------------------------------------

describe('AriaPanel deciding an ask in place', () => {
  it('posts the decision, settles the card, lifts the gate and keeps the stream open', async () => {
    let emitAfterDecision: ((content: string) => void) | null = null;
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onApprovalRequired?.({ runId: 'r1', approvalId: 'a1', toolName: TOOL });
      emitAfterDecision = (content: string) => args[3]?.onMessage?.(content);
      return new Promise<void>(() => {}); // parked: the turn holds its stream open
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByText(TOOL)).toBeInTheDocument();
    expect(screen.getByText(/pending decision first/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    await waitFor(() =>
      expect(mockDecideApproval).toHaveBeenCalledWith('a1', expect.objectContaining({ approved: true })),
    );
    expect(await screen.findByText(/allowed once/i)).toBeInTheDocument();
    expect(screen.queryByText(/pending decision first/i)).not.toBeInTheDocument();
    // The turn is still running (the stream never ended), so the busy control is
    // Cancel — that is the honest state, not a locked composer.
    expect(screen.getByRole('button', { name: 'Cancel request' })).toBeInTheDocument();

    // The decision did not abort the parked turn's stream: a frame sent after it
    // still lands in this transcript.
    act(() => { emitAfterDecision?.('resumed reply'); });
    expect(await screen.findByText('resumed reply')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Memory proposals (m5, Task 12b): a PENDING proposal is a card the operator
// decides in place, and it does NOT gate the composer (T45 Item 1): the gate
// the spec defines is for a parked turn — an approval or a clarification
// (spec:229-234) — and the proposal inbox is global, so gating on a proposal
// let one conversation's undecided proposal disable another conversation. The
// panel mounts the real `AriaProposalCard` (it owns the decide call) and
// reconciles PENDING proposals from the memory inbox on mount / focus /
// reconnect.
// ---------------------------------------------------------------------------

describe('AriaPanel memory proposals', () => {
  it('renders a live memory proposal as a decidable card, leaving the composer usable', async () => {
    streamProposal();
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    const card = await screen.findByTestId('aria-proposal-card');
    expect(card).toHaveAttribute('data-subject-type', 'FACT');
    expect(card).toHaveAttribute('data-state', 'pending');
    // The card names the write it proposes...
    expect(screen.getByText('release.channel')).toBeInTheDocument();
    expect(screen.getByText('email')).toBeInTheDocument();
    expect(screen.getByText('INTERNAL')).toBeInTheDocument();
    // ...it carries the decision controls the gate refers to...
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /reject/i })).toBeEnabled();
    // ...and it does not lock the conversation: a proposal parks no turn, so it
    // is not a decision the composer must wait for.
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('ignores a proposal frame it cannot classify instead of drawing a blank card', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onThinking?.('r1');
      args[3]?.onMemoryProposal?.({ runId: 'r1', id: 'p2', subjectType: 'SOMETHING_NEW' });
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: '' });
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));

    // Positive control: the turn rendered (its prompt is on screen).
    expect(await screen.findByText('My schedule today')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('aria-proposal-card')).not.toBeInTheDocument());
    expect(textarea()).toBeEnabled();
  });

  it('decides on the card, releases the composer, and a stale snapshot never reopens it', async () => {
    streamProposal();
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();
    // A proposal parks no turn, so the composer is already usable (T45 Item 1).
    expect(textarea()).toBeEnabled();

    // The panel must not decide anything itself: the card's click is the only
    // decide call, or one decision would render two settlements.
    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    await waitFor(() => expect(mockDecideMemoryProposal).toHaveBeenCalledWith('p1', 'APPROVE'));
    expect(mockDecideMemoryProposal).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(/now active/i)).toBeInTheDocument();
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    // A reconcile whose snapshot still lists the decided proposal (read before
    // the decision committed) must not reopen the card: the decision stands.
    mockListMemoryProposals.mockResolvedValue([proposalRow()]);
    const reconcilesBefore = mockGetTimeline.mock.calls.length;
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesBefore);
    expect(screen.getAllByTestId('aria-proposal-card')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(screen.getByText(/now active/i)).toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  // Task 37 Item 1, panel level: the card settles, so the composer must be usable
  // again. Before this, a fact whose parent entity is still PENDING kept the gate
  // ("Answer the pending decision first.") on an ask this card can never decide.
  it('releases the composer when a fact decision is blocked on its pending entity', async () => {
    streamProposal();
    mockDecideMemoryProposal.mockRejectedValue({
      response: {
        status: 409,
        data: {
          ok: false,
          errorType: 'CONFLICT',
          message:
            'memory fact p1 belongs to entity ent-7, which is still PENDING; decide the entity '
            + 'proposal ent-7 to promote or retire the whole batch instead of approving one fact',
        },
      },
    });
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    const notice = await screen.findByTestId('aria-proposal-blocked-on-entity');
    expect(notice).toHaveTextContent(/decide the entity proposal ent-7/i);
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
  });

  // T45 Item 1, the measured failure: the PENDING proposal inbox is GLOBAL, so
  // a proposal raised in another conversation is restored into this transcript.
  // It used to disable this composer, so conversation A's undecided proposal
  // blocked conversation B. The card is still drawn (it is the operator's to
  // decide) but it must not park this conversation's turn.
  it('restores the PENDING proposals the global inbox lists without disabling this conversation', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my editor', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    mockListMemoryProposals.mockResolvedValue([
      proposalRow({ id: 'p9', entityId: null, factKey: 'preferred_editor', valueText: 'vim' }),
    ]);
    renderPanel();
    await openPanelForTest();

    const card = await screen.findByTestId('aria-proposal-card');
    expect(mockListMemoryProposals).toHaveBeenCalledWith('PENDING');
    expect(screen.getByText('preferred_editor')).toBeInTheDocument();
    expect(screen.getByText('vim')).toBeInTheDocument();
    // Arrival order: the restored turn stays above the card it raised.
    const prompt = screen.getByText('remember my editor');
    expect(prompt.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // The card does not park a turn: the composer stays usable and a turn can
    // be sent while the proposal is still pending.
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    await userEvent.type(textarea(), 'carry on with the report');
    await userEvent.type(textarea(), '{Enter}');
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
    expect(mockStream.mock.calls[0][1]).toBe('carry on with the report');
    // The proposal is still pending: sending a turn decides nothing for it.
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
  });

  it('keeps a live proposal through a reconcile that does not list it yet', async () => {
    streamProposal();
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    // The snapshot was read before the turn's rows landed: it holds neither the
    // prompt nor the proposal. The live frame must survive it.
    mockListMemoryProposals.mockResolvedValue([]);
    const reconcilesBefore = mockGetTimeline.mock.calls.length;
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesBefore);
    expect(screen.getAllByTestId('aria-proposal-card')).toHaveLength(1);
    // Still the operator's to decide — carried, not settled by an empty read.
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });
});

// ---------------------------------------------------------------------------
// A proposal decided elsewhere (the Memory page, another operator) kept this
// panel's card pending forever and its next decide call could only 409. The
// durable PENDING inbox is authoritative for a proposal it no longer lists, so
// the card must settle and the composer come back. The inbox has no by-id
// outcome read — its bounded ACTIVE/RETIRED pages cannot tell an approval from
// a later retirement — so the card says "decided elsewhere" instead of
// inventing a verdict. A card the inbox still lists stays the operator's to
// decide, and only a read that actually answered may settle anything.
// ---------------------------------------------------------------------------

describe('AriaPanel proposals decided elsewhere', () => {
  function mountWithPendingProposal(): void {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my channel', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    mockListMemoryProposals.mockResolvedValue([proposalRow()]);
  }

  it('settles a pending proposal the inbox no longer lists, without inventing a verdict', async () => {
    mountWithPendingProposal();
    renderPanel();
    await openPanelForTest();

    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();
    expect(textarea()).toBeEnabled();

    // Decided on the Memory page or by another operator: the inbox no longer
    // lists it as PENDING and offers no by-id outcome read.
    mockListMemoryProposals.mockResolvedValue([]);
    const reconcilesBefore = mockGetTimeline.mock.calls.length;
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesBefore);
    const settled = await screen.findByTestId('aria-proposal-decided-elsewhere');
    expect(settled).toHaveTextContent(/no longer pending/i);
    // Honest about what the surface cannot say.
    expect(settled).toHaveTextContent(/cannot say/i);
    // The card stays in the transcript to say WHY it settled, in a state of its
    // own — the reducer's `decided-elsewhere`, never an invented verdict.
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'decided-elsewhere');
    expect(screen.queryByText(/will never become memory/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/now active/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument();
    expect(screen.queryByText('Answer the pending decision first.')).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('leaves a still-listed proposal pending and gated', async () => {
    mountWithPendingProposal();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    // Positive control for the settle above: the same reconcile with the
    // proposal still PENDING must change nothing.
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(screen.getByTestId('aria-proposal-card')).toBeInTheDocument();
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(textarea()).toBeEnabled();
  });

  it('keeps a confirmed pending proposal when the PENDING read fails', async () => {
    mountWithPendingProposal();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    // A refused or failed read proves nothing about the proposal; only a read
    // that actually answered may settle a card.
    mockListMemoryProposals.mockRejectedValueOnce(new Error('inbox unavailable'));
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(screen.getByTestId('aria-proposal-card')).toBeInTheDocument();
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(textarea()).toBeEnabled();
  });

  it('keeps a live proposal that arrives while a restore is in flight', async () => {
    streamProposal();
    mockGetLatest.mockResolvedValue(summary());
    renderPanel();
    await openPanelForTest();

    // A focus reconcile starts and its timeline read stays in flight…
    let resolveTimeline!: (rows: unknown[]) => void;
    mockGetTimeline.mockImplementationOnce(() => new Promise((resolve) => {
      resolveTimeline = resolve;
    }));
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    // …while the live frame lands.
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    // The snapshot resolves without the proposal: the read predates its row, so
    // the live card survives it and stays the operator's to decide.
    mockListMemoryProposals.mockResolvedValue([]);
    await act(async () => {
      resolveTimeline([]);
    });
    await act(async () => {});

    expect(screen.getAllByTestId('aria-proposal-card')).toHaveLength(1);
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(textarea()).toBeEnabled();
  });

  it('settles a live proposal once a PENDING read has spoken for it', async () => {
    streamProposal();
    renderPanel();
    await openPanelForTest();
    fireEvent.click(screen.getByRole('button', { name: 'My schedule today' }));
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    // A PENDING read now lists it: the durable snapshot speaks for it.
    mockListMemoryProposals.mockResolvedValue([proposalRow()]);
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'pending');

    // Then it is decided elsewhere: the next read no longer lists it.
    mockListMemoryProposals.mockResolvedValue([]);
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(await screen.findByTestId('aria-proposal-decided-elsewhere')).toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'decided-elsewhere');
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  // Task 16's first disclosed limit: the PENDING inbox is ONE bounded page
  // (`MemoryProposalService.INBOX_LIMIT`), so a proposal can fall off it while it
  // is still pending. A capped read therefore cannot prove a departure, and the
  // panel must not claim "decided elsewhere" for a proposal it can still see.
  // The page length is the SHARED source, so this stays true if the server cap
  // is ever lowered: the rule is "below the cap", never a number of its own.
  it('does not call a proposal decided elsewhere when the PENDING page filled the cap', async () => {
    mountWithPendingProposal();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    // A full page of OTHER still-pending proposals: this one may simply be below it.
    mockListMemoryProposals.mockResolvedValue(
      Array.from({ length: PROPOSAL_INBOX_LIMIT }, (_, i) =>
        proposalRow({ id: `other-${i}`, entityId: 'ent-other' }),
      ),
    );
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    // p1 is the proposal this test cares about (the page also lists a full page —
    // the restore is not conversation-scoped yet, m3): it is still pending.
    const card = screen.getAllByTestId('aria-proposal-card')
      .find((el) => el.getAttribute('data-proposal-id') === 'p1');
    expect(card).toHaveAttribute('data-state', 'pending');
    // Still the operator's to decide; the composer is not its hostage.
    expect(within(card as HTMLElement).getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(textarea()).toBeEnabled();
  });

  // Positive control for the rule above, at the boundary: one row below the cap
  // IS a provably complete page, so a departure may be claimed there.
  it('settles a departure from a page that provably fits below the cap', async () => {
    mountWithPendingProposal();
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();

    mockListMemoryProposals.mockResolvedValue(
      Array.from({ length: PROPOSAL_INBOX_LIMIT - 1 }, (_, i) =>
        proposalRow({ id: `other-${i}`, entityId: 'ent-other' }),
      ),
    );
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(await screen.findByTestId('aria-proposal-decided-elsewhere')).toBeInTheDocument();
    const card = screen.getAllByTestId('aria-proposal-card')
      .find((el) => el.getAttribute('data-proposal-id') === 'p1');
    expect(card).toHaveAttribute('data-state', 'decided-elsewhere');
  });
});

// ---------------------------------------------------------------------------
// Retry transcript shape (m6): a failed turn stays in the transcript as history
// and the retried turn is appended below it. The old panel silently removed the
// trailing failure; neither behaviour was pinned. This pins the chosen one.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Task 41 Major 3: `blocked-on-entity` is a present-tense server claim ("its
// entity ent-7 is still pending"), and nothing used to move a blocked card when
// a later PENDING read no longer listed its row — the entity card could read
// `approved` beside a fact card still insisting "decide the entity proposal".
// The reconcile read already proves a departure for a pending card; it must do
// the same for a blocked one, with the same cap rule and the same honest
// `decided-elsewhere` state (never a fabricated approved/rejected).
// ---------------------------------------------------------------------------
describe('AriaPanel blocked-on-entity settlement on reconcile', () => {
  const PARENT_PENDING_409 = {
    response: {
      status: 409,
      data: {
        ok: false,
        errorType: 'CONFLICT',
        message:
          'memory fact p1 belongs to entity ent-7, which is still PENDING; decide the entity '
          + 'proposal ent-7 to promote or retire the whole batch instead of approving one fact',
      },
    },
  };

  /** Mounts a PENDING inbox card, Approves it, and lands in `blocked-on-entity`. */
  async function mountBlocked(): Promise<void> {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'remember my channel', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    mockListMemoryProposals.mockResolvedValue([proposalRow()]);
    mockDecideMemoryProposal.mockRejectedValue(PARENT_PENDING_409);
    renderPanel();
    await openPanelForTest();
    expect(await screen.findByTestId('aria-proposal-card')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    await screen.findByTestId('aria-proposal-blocked-on-entity');
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute(
      'data-state',
      'blocked-on-entity',
    );
  }

  function blockedCard(): HTMLElement {
    const card = screen
      .getAllByTestId('aria-proposal-card')
      .find((el) => el.getAttribute('data-proposal-id') === 'p1');
    expect(card).toBeDefined();
    return card as HTMLElement;
  }

  it('settles a blocked card the PENDING inbox no longer lists as decided-elsewhere', async () => {
    await mountBlocked();

    // The entity proposal was approved (its card is gone from this snapshot
    // because this restore is the global inbox): the fact row left PENDING too.
    mockListMemoryProposals.mockResolvedValue([]);
    const reconcilesBefore = mockGetTimeline.mock.calls.length;
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(mockGetTimeline.mock.calls.length).toBeGreaterThan(reconcilesBefore);
    // Honest settlement: the bounded inbox cannot say approved vs rejected, so
    // the card must never fabricate either verdict…
    expect(blockedCard()).toHaveAttribute('data-state', 'decided-elsewhere');
    expect(within(blockedCard()).getByTestId('aria-proposal-decided-elsewhere')).toHaveTextContent(
      /no longer pending/i,
    );
    expect(screen.queryByText(/now active/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/will never become memory/i)).not.toBeInTheDocument();
    // …and the stale present-tense claim is gone (no contradiction with the
    // entity card the operator just approved).
    expect(screen.queryByTestId('aria-proposal-blocked-on-entity')).not.toBeInTheDocument();
    expect(screen.queryByText(/is still pending/i)).not.toBeInTheDocument();
    expect(textarea()).toBeEnabled();
  });

  it('keeps a blocked card while the PENDING inbox still lists its row', async () => {
    await mountBlocked();

    // Positive control: the entity proposal is still undecided, so the row is
    // still PENDING and the card's claim is still true.
    mockListMemoryProposals.mockResolvedValue([proposalRow()]);
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(blockedCard()).toHaveAttribute('data-state', 'blocked-on-entity');
    expect(within(blockedCard()).getByTestId('aria-proposal-blocked-on-entity')).toHaveTextContent(
      /is still pending/i,
    );
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
  });

  it('does not settle a blocked card from a PENDING page that filled the cap', async () => {
    await mountBlocked();

    // p1 may simply be below the cap, so absence proves nothing here either.
    mockListMemoryProposals.mockResolvedValue(
      Array.from({ length: PROPOSAL_INBOX_LIMIT }, (_, i) =>
        proposalRow({ id: `other-${i}`, entityId: 'ent-other' }),
      ),
    );
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});

    expect(blockedCard()).toHaveAttribute('data-state', 'blocked-on-entity');
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
  });
});

describe('AriaPanel retry transcript shape', () => {
  it('keeps the failed turn as history above the retried prompt', async () => {
    mockListApprovals.mockResolvedValue([]);
    mockStream.mockImplementationOnce(async (...args: StreamArgs) => {
      args[3]?.onError?.('boom');
    });
    mockStream.mockImplementation(async () => { /* the retried turn answers */ });
    renderPanel();
    await openPanelForTest();
    await userEvent.type(textarea(), 'do the thing');
    await userEvent.type(textarea(), '{Enter}');

    expect(
      await screen.findByText(/no approval is pending/, undefined, { timeout: 3000 }),
    ).toBeInTheDocument();

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2));

    // The failure is history, not erased; the retried prompt sits below it.
    expect(screen.getByText(/no approval is pending/)).toBeInTheDocument();
    expect(screen.getAllByText('do the thing')).toHaveLength(2);
  });
});

// ---------------------------------------------------------------------------
// The cleared-conversation guard covered the SSE plane only. A REST read (the
// mount load or a reconcile) that resolves after Clear must not write the
// cleared conversation's transcript into the new one, nor revert its id —
// the same class of bug the SSE guard closes (fbf0a665).
// ---------------------------------------------------------------------------

describe('AriaPanel cleared-conversation guard on the REST plane', () => {
  function conversationIdText(container: HTMLElement): string {
    return (container.querySelector('.ai-conversation-id') as HTMLElement | null)?.title ?? '';
  }

  it('drops a mount load that resolves after the operator cleared the conversation', async () => {
    let resolveLatest: (value: unknown) => void = () => {};
    mockGetLatest.mockReturnValue(new Promise((resolve) => { resolveLatest = resolve; }));
    const { container } = renderPanel();
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));

    // Clear before the load answers: this tab now owns a conversation of its own.
    fireEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    const clearedId = conversationIdText(container);
    expect(clearedId).not.toBe('');

    mockGetTimeline.mockResolvedValue([
      { role: 'user', content: 'history of the cleared conversation', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
    ]);
    await act(async () => { resolveLatest(summary('conv-old')); });
    await act(async () => {});

    // Positive control for the guard: a load that resolves normally IS adopted
    // (the merge/gate cases above), so this absence is the guard working.
    expect(screen.queryByText('history of the cleared conversation')).not.toBeInTheDocument();
    expect(mockGetTimeline).not.toHaveBeenCalled();
    expect(conversationIdText(container)).toBe(clearedId);
  });

  it('drops a reconcile that resolves after the operator cleared the conversation', async () => {
    mockGetLatest.mockResolvedValue(summary());
    let resolveReconcile: (value: unknown) => void = () => {};
    mockGetTimeline
      .mockResolvedValueOnce([
        { role: 'user', content: 'first turn', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
      ])
      .mockReturnValueOnce(new Promise((resolve) => { resolveReconcile = resolve; }));
    mockListApprovals.mockResolvedValue([]);
    const { container } = renderPanel();
    await openPanelForTest();
    expect(await screen.findByText('first turn')).toBeInTheDocument();

    // A focus reconcile starts, the operator clears while it is in flight.
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });
    await act(async () => {});
    // The second read is the in-flight one (the mount read was the first), so the
    // resolution below is really the stale reconcile and not a no-op.
    expect(mockGetTimeline).toHaveBeenCalledTimes(2);
    // Clear waits for its DELETE to answer (T48 Item 1), so it is flushed inside act.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    });
    const clearedId = conversationIdText(container);
    expect(screen.queryByText('first turn')).not.toBeInTheDocument();

    await act(async () => {
      resolveReconcile([
        { role: 'user', content: 'first turn', timestamp: '2026-11-11T09:00:00Z', runId: 'r1' },
      ]);
    });
    await act(async () => {});

    expect(screen.queryByText('first turn')).not.toBeInTheDocument();
    expect(conversationIdText(container)).toBe(clearedId);
  });
});

// ---------------------------------------------------------------------------
// Task 37 review fold-in: both REST reconciles swallowed an operator refusal —
// the only effect was a suppressed console line, so the operator saw a panel
// that simply had no approvals and no proposals. The panel keeps its own error
// surface: a refusal there reads as the shared notice, a fault as its text.
// ---------------------------------------------------------------------------

describe('AriaPanel reconcile refusals (Task 37 review fold-in)', () => {
  const timelineWithRun = [
    { role: 'user', content: 'brief me', timestamp: '2026-09-30T10:00:00Z', runId: 'run-1' },
  ];

  async function reconcileOnce() {
    renderPanel();
    await act(async () => {}); // the mount reconcile
    await openPanel();
  }

  it('shows the operator notice when the pending-approval read is refused', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue(timelineWithRun);
    mockListApprovals.mockRejectedValue({
      response: { status: 403, data: { message: 'RECONCILE_RAW_SERVER_TEXT' } },
    });

    await reconcileOnce();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/reading the approval queue is operator-only/i);
    expect(notice).toHaveTextContent(/loopback requests are the operator automatically/i);
    expect(screen.queryByText(/RECONCILE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
  });

  it('shows the operator notice when the pending-proposal read is refused', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue(timelineWithRun);
    mockListMemoryProposals.mockRejectedValue({ response: { status: 401, data: {} } });

    await reconcileOnce();

    expect(await screen.findByTestId('operator-session-notice'))
      .toHaveTextContent(/reading the memory proposal inbox is operator-only/i);
  });

  // Task 41 m1: with BOTH reads refused (the ordinary no-session case) the two
  // labels were joined with "and" and fed to one sentence, producing
  // "Reading the approval queue and Reading the memory proposal inbox is
  // operator-only" — a capital mid-sentence and a singular verb for two reads.
  it('joins both refused reads into one grammatical notice', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue(timelineWithRun);
    mockListApprovals.mockRejectedValue({ response: { status: 401, data: {} } });
    mockListMemoryProposals.mockRejectedValue({ response: { status: 401, data: {} } });

    await reconcileOnce();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(
      /reading the approval queue or the memory proposal inbox is operator-only/i,
    );
    // Positive control: the old joined form (and its stray capital) is gone.
    expect(notice).not.toHaveTextContent(/and Reading/i);
  });

  // Task 41 m2: a refusal from the cleared conversation stayed on screen in the
  // brand-new, empty transcript until the next reconcile (which a fresh
  // conversation runs only after a turn is sent).
  it('clears a reconcile refusal together with its conversation', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue(timelineWithRun);
    mockListApprovals.mockRejectedValue({ response: { status: 401, data: {} } });

    await reconcileOnce();
    expect(await screen.findByTestId('operator-session-notice')).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    });

    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  // Positive controls: a fault is not a refusal. It keeps the server's text and
  // must not fabricate a session notice.
  it('shows the server text and no notice when the approval read fails', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue(timelineWithRun);
    mockListApprovals.mockRejectedValue({
      response: { status: 500, data: { message: 'approval store unavailable' } },
    });

    await reconcileOnce();

    expect(await screen.findByText(/approval store unavailable/)).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  it('shows the server text and no notice when the proposal read fails', async () => {
    mockGetLatest.mockResolvedValue(summary());
    mockGetTimeline.mockResolvedValue(timelineWithRun);
    mockListMemoryProposals.mockRejectedValue({
      response: { status: 500, data: { message: 'proposal inbox unavailable' } },
    });

    await reconcileOnce();

    expect(await screen.findByText(/proposal inbox unavailable/)).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// T48 Item 1 (P1, re-review N1). Clear's DELETE resolves operator authority for
// a MUTATION (`AriaConversationController.java:193-203`), and a browser holding
// the operator session cookie has the call validated against `X-CSRF-Token`
// (`OperatorAuthorityResolver`: the cookie branch runs before the loopback one).
// The old call was a bare `fetch`, so it never carried the token, and the
// panel's silent `.catch` cleared the transcript anyway — the title promised a
// server-side purge that never happened and the operator saw no error at all.
//
// A refused (or failed) Clear now keeps this transcript, because the server
// still holds it, and reports the refusal where the operator is looking: the
// action, the fact that nothing was cleared, the remedy, and the control that
// retries it (the Clear button itself — never a "Retry" that would resend a
// prompt). The success path above is the positive control for the retention.
// ---------------------------------------------------------------------------
describe('AriaPanel Clear refusal (T48 Item 1)', () => {
  async function openConversationWithOneTurn() {
    mockGetLatest.mockResolvedValue(summary('conv-1'));
    mockGetTimeline.mockResolvedValue([
      {
        role: 'user',
        content: 'the turn the server still holds',
        timestamp: '2026-11-11T09:00:00Z',
        runId: 'r1',
      },
    ]);
    const rendered = renderPanel();
    await openPanelForTest();
    expect(await screen.findByText('the turn the server still holds')).toBeInTheDocument();
    return rendered;
  }

  async function clickClear() {
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    });
  }

  it('keeps the transcript and names the operator remedy when the server refuses Clear', async () => {
    const { container } = await openConversationWithOneTurn();
    mockDeleteConversation.mockRejectedValue({
      response: {
        status: 403,
        data: {
          error: 'Operator authority required: deleting a conversation is an operator decision. '
            + 'Deleting a conversation cancels its runs and destroys its history; do it from the '
            + 'dashboard as the local operator.',
        },
      },
    });

    await clickClear();

    // Nothing was purged locally, so the transcript is still what the server holds.
    expect(screen.getByText('the turn the server still holds')).toBeInTheDocument();
    expect(conversationIdOf(container)).toBe('conv-1');

    const bubble = await screen.findByText(/nothing was cleared/i);
    // Names the action that was refused, as the authority contract does…
    expect(bubble).toHaveTextContent(/clearing the conversation is operator-only/i);
    // …and the remedy plus the control that retries, which is the point of not
    // swallowing it: an error must point at the place that fixes it.
    expect(bubble).toHaveTextContent(/loopback requests are the operator automatically/i);
    expect(bubble).toHaveTextContent(/press Clear again/i);
    // No "Retry" that would resend a prompt: this failure is not a turn.
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    // The Clear control itself survives for the retry the copy asks for.
    expect(screen.getByRole('button', { name: 'Clear and start new conversation' })).toBeEnabled();
  });

  it('keeps the transcript and shows the server text when Clear fails for its own reason', async () => {
    const { container } = await openConversationWithOneTurn();
    mockDeleteConversation.mockRejectedValue({
      response: { status: 500, data: { message: 'conversation store unavailable' } },
    });

    await clickClear();

    expect(screen.getByText('the turn the server still holds')).toBeInTheDocument();
    expect(conversationIdOf(container)).toBe('conv-1');
    const bubble = await screen.findByText(/conversation store unavailable/i);
    expect(bubble).toHaveTextContent(/nothing was cleared/i);
    // Positive control for the refusal test above: a fault is NOT an authority
    // problem, so it must not be dressed in the operator-session remedy.
    expect(bubble).not.toHaveTextContent(/operator-only/i);
    expect(bubble).not.toHaveTextContent(/loopback requests are the operator automatically/i);
  });

  it('retries the delete on the next Clear and clears once it answers', async () => {
    const { container } = await openConversationWithOneTurn();
    mockDeleteConversation.mockRejectedValueOnce({
      response: { status: 500, data: { message: 'conversation store unavailable' } },
    });

    await clickClear();
    expect(await screen.findByText(/nothing was cleared/i)).toBeInTheDocument();
    expect(conversationIdOf(container)).toBe('conv-1');

    // The remedy the copy names: press Clear again. The route answers this time.
    await clickClear();

    expect(mockDeleteConversation).toHaveBeenCalledTimes(2);
    expect(mockDeleteConversation).toHaveBeenLastCalledWith('conv-1');
    await waitFor(() => expect(conversationIdOf(container)).not.toBe('conv-1'));
    expect(screen.queryByText('the turn the server still holds')).not.toBeInTheDocument();
  });

  // T49 (N-T48-4). Clear now awaits a destructive DELETE that drives the verified
  // run stop, so it can take seconds. The operator must see that it is working and
  // must not be able to issue a second DELETE with a second click.
  it('holds Clear while its DELETE is in flight and issues no second delete', async () => {
    const { container } = await openConversationWithOneTurn();
    let answer: (() => void) | undefined;
    mockDeleteConversation.mockImplementation(() => new Promise<void>((resolve) => {
      answer = () => resolve();
    }));

    const clear = screen.getByRole('button', { name: 'Clear and start new conversation' });
    // Positive control for the held state below: idle Clear is enabled and says Clear.
    expect(clear).toBeEnabled();
    expect(clear).toHaveTextContent(/^Clear$/);

    await clickClear();

    // The call is in flight: the same control now reports it and refuses input.
    expect(clear).toBeDisabled();
    expect(clear).toHaveAttribute('aria-busy', 'true');
    expect(clear).toHaveTextContent(/clearing/i);
    await act(async () => {
      fireEvent.click(clear);
    });
    expect(mockDeleteConversation).toHaveBeenCalledTimes(1);
    // Nothing was claimed cleared while the server had not answered.
    expect(conversationIdOf(container)).toBe('conv-1');
    expect(screen.getByText('the turn the server still holds')).toBeInTheDocument();

    await act(async () => {
      answer?.();
    });
    await waitFor(() => expect(conversationIdOf(container)).not.toBe('conv-1'));
    expect(clear).toBeEnabled();
  });

  // T50 (F-2). Clear's DELETE reads the conversation's runs, drives the verified stop of
  // each live one (seconds, not microseconds) and only then detaches them. The backend now
  // detaches by conversation, so a run created in that window cannot resurrect the cleared
  // conversation; the panel must not create one either. Its send path was guarded by
  // busy/gated only, so a prompt entered while the bounded DELETE was in flight went out
  // with the old conversationId still in state. The composer is held for the whole clear.
  it('holds the composer while Clear is in flight, and sends once it answered', async () => {
    const { container } = await openConversationWithOneTurn();
    let answer: (() => void) | undefined;
    mockDeleteConversation.mockImplementation(() => new Promise<void>((resolve) => {
      answer = () => resolve();
    }));

    await userEvent.type(textarea(), 'typed while idle');
    // Positive control: idle, the typed prompt is sendable.
    expect(screen.getByRole('button', { name: 'Send message' })).toBeEnabled();

    await clickClear();

    // In flight: the composer is held, and neither input route can enter the window.
    expect(textarea()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled();
    fireEvent.keyDown(textarea(), { key: 'Enter' });
    // The compose event (the 彙整 button lives outside the panel) has no disabled control
    // in front of it; the send guard itself must hold it too.
    await act(async () => {
      window.dispatchEvent(new CustomEvent('aria:compose', {
        detail: { prompt: 'composed while the clear was in flight' },
      }));
    });
    expect(mockStream).not.toHaveBeenCalled();

    await act(async () => { answer?.(); });
    await waitFor(() => expect(conversationIdOf(container)).not.toBe('conv-1'));

    // The hold is released with the clear, and the same Enter route now sends: this is the
    // positive control for the two "not called" assertions above.
    const afterClear = textarea();
    expect(afterClear).toBeEnabled();
    await userEvent.type(afterClear, 'after the clear{Enter}');
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
  });

  // A bounded timeout is a third outcome, not a refusal: the request was sent, so
  // "nothing was cleared" would be a guess. The copy says only what is known.
  it('says Clear did not answer in time instead of claiming nothing was cleared', async () => {
    const { container } = await openConversationWithOneTurn();
    mockDeleteConversation.mockRejectedValue(
      Object.assign(new Error('timeout of 20000ms exceeded'), { code: 'ECONNABORTED' }),
    );

    await clickClear();

    expect(screen.getByText('the turn the server still holds')).toBeInTheDocument();
    expect(conversationIdOf(container)).toBe('conv-1');
    const bubble = await screen.findByText(/did not answer in time/i);
    expect(bubble).toHaveTextContent(/may still be clearing/i);
    expect(bubble).toHaveTextContent(/press Clear again/i);
    // The "nothing was cleared" branch is exactly the claim a timeout cannot make;
    // that wording is pinned by the two tests above, which is its positive control.
    expect(bubble).not.toHaveTextContent(/nothing was cleared/i);
    expect(screen.getByRole('button', { name: 'Clear and start new conversation' })).toBeEnabled();
  });
});
