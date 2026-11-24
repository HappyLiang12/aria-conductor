import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import MainChatPage from '../MainChatPage';
import type { StreamCallbacks } from '../../api/aria';
import type { WsEvent } from '../../types';

vi.mock('../../api/aria', () => ({
  streamMessage: vi.fn(),
}));
vi.mock('../../api/ariaConversations', () => ({
  listConversations: vi.fn(),
  getLatestConversation: vi.fn(),
  getConversationTimeline: vi.fn(),
  deleteConversation: vi.fn(),
}));
vi.mock('../../api/runs', () => ({
  cancelRun: vi.fn().mockResolvedValue({}),
  getRunProgress: vi.fn().mockResolvedValue([]),
  getRunToolCalls: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../api/skills', () => ({ listSkills: vi.fn().mockResolvedValue([]) }));

// The generated assistant-ui thread owns the transcript DOM (it has its own
// test surface); the page owns the shell, the switcher, the composer, the stop
// control and the empty state, so those stay real here. The mock still renders
// the `indicator` the page passes, so the live ProgressTimeline wiring is
// exercised through the page instead of being swallowed by the stub.
vi.mock('../../chat/AriaThread', () => ({
  AriaThread: ({ indicator }: { indicator?: ReactNode }) => (
    <div className="mc-stack">thread{indicator}</div>
  ),
}));

type WsHandler = (event: WsEvent) => void;
const wsHandlers: WsHandler[] = [];
const wsContext = {
  lastMessage: null as WsEvent | null,
  isConnected: true,
  subscribe: (handler: WsHandler) => {
    wsHandlers.push(handler);
    return {
      unsubscribe: () => {
        const index = wsHandlers.indexOf(handler);
        if (index >= 0) wsHandlers.splice(index, 1);
      },
    };
  },
};
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => wsContext,
}));

import { streamMessage } from '../../api/aria';
import { deleteConversation, getConversationTimeline, getLatestConversation, listConversations } from '../../api/ariaConversations';
import { cancelRun, getRunProgress } from '../../api/runs';

const mockStream = streamMessage as unknown as ReturnType<typeof vi.fn>;
const mockList = listConversations as unknown as ReturnType<typeof vi.fn>;
const mockLatest = getLatestConversation as unknown as ReturnType<typeof vi.fn>;
const mockTimeline = getConversationTimeline as unknown as ReturnType<typeof vi.fn>;
const mockDelete = deleteConversation as unknown as ReturnType<typeof vi.fn>;
const mockCancel = cancelRun as unknown as ReturnType<typeof vi.fn>;
const mockProgress = getRunProgress as unknown as ReturnType<typeof vi.fn>;

function renderPage(initialEntry = '/aria') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <MainChatPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * The page opens one SSE request per turn; hold it open until the turn is
 * aborted. The returned handle is the signal and per-turn options the page
 * actually handed the stream, so a Stop can be proven to abort the client
 * request (not merely call the server cancel) and to mark the turn cancelled.
 */
function holdStream() {
  const held: {
    signal?: AbortSignal;
    options?: Parameters<typeof streamMessage>[5];
  } = {};
  mockStream.mockImplementation((
    _conversationId: string,
    _message: string,
    _history: unknown,
    _callbacks: StreamCallbacks,
    signal?: AbortSignal,
    options?: Parameters<typeof streamMessage>[5],
  ) => {
    held.signal = signal;
    held.options = options;
    return new Promise<void>((_resolve, reject) => {
      signal?.addEventListener('abort', () => {
        reject(new DOMException('aborted', 'AbortError'));
      });
    });
  });
  return held;
}

async function sendText(text: string) {
  const textarea = screen.getByPlaceholderText(/Ask Aria anything/);
  fireEvent.change(textarea, { target: { value: text } });
  fireEvent.keyDown(textarea, { key: 'Enter' });
  await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
}

beforeEach(() => {
  vi.clearAllMocks();
  wsHandlers.length = 0;
  mockList.mockResolvedValue([
    { conversationId: 'conv-1', title: 'Deploy the crew', lastMessageAt: '2026-11-20T10:00:00Z', runCount: 2 },
  ]);
  mockLatest.mockResolvedValue({ conversationId: 'conv-1', lastMessageAt: '2026-11-20T10:00:00Z', runCount: 2 });
  mockTimeline.mockResolvedValue([
    { role: 'user', content: 'Deploy the crew', timestamp: '2026-11-20T09:59:00Z', runId: 'run-0' },
    { role: 'assistant', content: 'Crew deployed.', timestamp: '2026-11-20T10:00:00Z', runId: 'run-0' },
  ]);
  mockProgress.mockResolvedValue([]);
  mockStream.mockResolvedValue(undefined);
});

describe('MainChatPage', () => {
  it('lists conversations, marks the latest as active and loads its timeline into the thread', async () => {
    renderPage();

    expect(await screen.findByRole('button', { name: /Delete conversation Deploy the crew/i })).toBeTruthy();
    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-1'));
    expect(screen.getByRole('button', { name: 'Deploy the crew' }).getAttribute('aria-current')).toBe('true');
    // The thread owns the transcript; the page still hands it the history it loaded.
    expect(await screen.findByText('thread')).toBeTruthy();
    expect(screen.queryByText('Ask Aria anything.')).toBeNull();
  });

  it('sends the page-loaded history with the turn and replays progress when the run settles', async () => {
    holdStream();
    renderPage();
    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-1'));

    await sendText('Status?');

    const call = mockStream.mock.calls[0];
    expect(call[0]).toBe('conv-1');
    expect(call[1]).toBe('Status?');
    expect(call[2]).toEqual([
      { role: 'user', content: 'Deploy the crew' },
      { role: 'assistant', content: 'Crew deployed.' },
    ]);

    const callbacks = call[3] as StreamCallbacks;
    act(() => callbacks.onThinking?.('run-1'));
    await screen.findByRole('button', { name: /Stop the turn/i });

    act(() => callbacks.onMessage?.('Raft needs a majority. The crew is healthy.'));
    act(() => callbacks.onDone?.({ runId: 'run-1', conversationId: 'conv-1', intent: 'status' }));

    await waitFor(() => expect(screen.queryByRole('button', { name: /Stop the turn/i })).toBeNull());
    expect(await screen.findByRole('button', { name: /Send message/i })).toBeTruthy();
    await waitFor(() => expect(mockProgress).toHaveBeenCalledWith('run-1', 0));
  });

  it('stops the turn through the server cancel path once the run id is known', async () => {
    const held = holdStream();
    renderPage();
    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-1'));
    const listCallsBeforeStop = mockList.mock.calls.length;

    await sendText('Long job');
    const callbacks = mockStream.mock.calls[0][3] as StreamCallbacks;
    act(() => callbacks.onThinking?.('run-42'));
    expect(held.options?.isCancelled?.()).toBe(false);
    fireEvent.click(await screen.findByRole('button', { name: /Stop the turn/i }));

    // Stop must abort the one SSE request the page opened, not just ask the
    // server to cancel the run: deleting `adapter.cancel()` has to fail here.
    await waitFor(() => expect(held.signal?.aborted).toBe(true));
    expect(held.options?.isCancelled?.()).toBe(true);
    // The aborted turn settles, so the composer returns to Send.
    expect(await screen.findByRole('button', { name: /Send message/i })).toBeTruthy();
    await waitFor(() => expect(mockCancel).toHaveBeenCalledWith('run-42'));
    // The stopped turn's conversation exists server-side, so the switcher must
    // refresh once the cancel settles instead of waiting for a page reload.
    await waitFor(() => expect(mockList.mock.calls.length).toBeGreaterThan(listCallsBeforeStop));

    // A cancelled turn must not poison the next one: the flag is cleared when a
    // new turn opens, so a later close is not mistaken for a dropped connection.
    const textarea = screen.getByPlaceholderText(/Ask Aria anything/);
    fireEvent.change(textarea, { target: { value: 'Again' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2));
    expect(held.options?.isCancelled?.()).toBe(false);
  });

  it('renders the live progress frames in the thread indicator', async () => {
    holdStream();
    renderPage();
    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-1'));

    await sendText('Long job');
    const callbacks = mockStream.mock.calls[0][3] as StreamCallbacks;
    act(() => callbacks.onThinking?.('run-1'));
    await screen.findByRole('button', { name: /Stop the turn/i });
    await waitFor(() => expect(wsHandlers.length).toBeGreaterThan(0));

    act(() => {
      wsHandlers.forEach((handler) => handler({
        type: 'run.progress',
        payload: { runId: 'run-1', kind: 'TOOL_CALL', content: '', toolName: 'read_file', seq: 1, partId: 'p1' },
        timestamp: '2026-11-20T10:00:01Z',
      } as WsEvent));
    });

    // The page passes the live ProgressTimeline as the thread's indicator; the
    // mocked thread renders it, so this is the page-level progress assertion.
    expect(await screen.findByText('read_file')).toBeTruthy();
    expect(screen.getByTestId('mc-progress')).toBeTruthy();
  });

  it('never replays a failed turn as assistant history', async () => {
    holdStream();
    mockTimeline.mockResolvedValue([
      { role: 'user', content: 'Deploy the crew', timestamp: '2026-11-20T09:59:00Z', runId: 'run-0' },
      { role: 'assistant', content: '回合執行失敗：boom', timestamp: '2026-11-20T10:00:00Z', runId: 'run-0', error: true },
    ]);
    renderPage();
    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-1'));

    await sendText('Status?');

    expect(mockStream.mock.calls[0][2]).toEqual([{ role: 'user', content: 'Deploy the crew' }]);
  });

  it('keeps a failed server-side cancel visible in the state notice', async () => {
    holdStream();
    mockCancel.mockRejectedValueOnce(new Error('cancel refused'));
    renderPage();
    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-1'));

    await sendText('Long job');
    const callbacks = mockStream.mock.calls[0][3] as StreamCallbacks;
    act(() => callbacks.onThinking?.('run-7'));
    fireEvent.click(await screen.findByRole('button', { name: /Stop the turn/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('cancel refused');
  });

  it('deletes a conversation only after confirmation and then starts a new chat', async () => {
    mockDelete.mockResolvedValue(undefined);
    renderPage();
    await screen.findByRole('button', { name: 'Deploy the crew' });

    fireEvent.click(screen.getByRole('button', { name: /Delete conversation Deploy the crew/i }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(mockDelete).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Delete conversation Deploy the crew/i }));
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('conv-1'));
    await screen.findByText('Ask Aria anything.');
  });

  it('opens the conversation named by the route param instead of the latest one', async () => {
    mockList.mockResolvedValue([
      { conversationId: 'conv-1', title: 'Deploy the crew', lastMessageAt: '2026-11-20T10:00:00Z', runCount: 2 },
      { conversationId: 'conv-2', title: 'Check runs', lastMessageAt: '2026-11-21T10:00:00Z', runCount: 1 },
    ]);
    mockTimeline.mockResolvedValue([
      { role: 'user', content: 'Check runs', timestamp: '2026-11-21T09:00:00Z', runId: 'run-9' },
    ]);
    renderPage('/aria?conversationId=conv-2');

    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-2'));
    expect(mockLatest).not.toHaveBeenCalled();
  });

  it('opens another conversation from the switcher', async () => {
    mockList.mockResolvedValue([
      { conversationId: 'conv-1', title: 'Deploy the crew', lastMessageAt: '2026-11-20T10:00:00Z', runCount: 2 },
      { conversationId: 'conv-2', title: 'Check runs', lastMessageAt: '2026-11-21T10:00:00Z', runCount: 1 },
    ]);
    mockTimeline.mockImplementation(async (id: string) => (
      id === 'conv-2'
        ? [{ role: 'user', content: 'Check runs', timestamp: '2026-11-21T09:00:00Z', runId: 'run-9' }]
        : [{ role: 'assistant', content: 'Crew deployed.', timestamp: '2026-11-20T10:00:00Z', runId: 'run-0' }]
    ));
    renderPage();
    await screen.findByRole('button', { name: 'Check runs' });

    fireEvent.click(screen.getByRole('button', { name: 'Check runs' }));

    await waitFor(() => expect(mockTimeline).toHaveBeenCalledWith('conv-2'));
    expect(await screen.findByRole('button', { name: 'Check runs' })).toBeTruthy();
  });
});
