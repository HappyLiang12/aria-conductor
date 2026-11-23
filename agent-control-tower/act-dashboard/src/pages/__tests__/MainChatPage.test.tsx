import { describe, it, expect, vi, beforeEach } from 'vitest';
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

function push(frame: Record<string, unknown>) {
  act(() => {
    wsHandlers.forEach((handler) => handler({ type: 'run.progress', payload: frame, timestamp: '2026-11-24T00:00:00Z' }));
  });
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/aria']}>
        <MainChatPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
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
});

describe('MainChatPage', () => {
  it('lists conversations, marks the latest as active and renders its timeline', async () => {
    renderPage();

    expect(await screen.findByRole('button', { name: /Delete conversation Deploy the crew/i })).toBeTruthy();
    expect(await screen.findByText('Crew deployed.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Deploy the crew' }).getAttribute('aria-current')).toBe('true');
    expect(mockTimeline).toHaveBeenCalledWith('conv-1');
  });

  it('sends a turn over SSE, grows the live answer in place and keeps the final message authoritative', async () => {
    renderPage();
    await screen.findByText('Crew deployed.');

    const textarea = screen.getByPlaceholderText(/Ask Aria anything/);
    fireEvent.change(textarea, { target: { value: 'Status?' } });
    fireEvent.keyDown(textarea, { key: 'Enter' });

    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));
    expect(screen.getByText('Status?')).toBeTruthy();
    const callbacks = mockStream.mock.calls[0][3] as StreamCallbacks;

    act(() => callbacks.onThinking?.('run-1'));
    await screen.findByRole('button', { name: /Stop the turn/i });

    push({ runId: 'run-1', kind: 'ANSWER', partId: 'a1', content: 'Raft needs', seq: 1 });
    expect(await screen.findByText('Raft needs')).toBeTruthy();

    push({ runId: 'run-1', kind: 'ANSWER', partId: 'a1', content: 'Raft needs a majority.', seq: 2 });
    expect(await screen.findByText('Raft needs a majority.')).toBeTruthy();
    expect(screen.queryByText('Raft needs')).toBeNull();

    act(() => callbacks.onMessage?.('Raft needs a majority. The crew is healthy.'));
    act(() => callbacks.onDone?.({ runId: 'run-1', conversationId: 'conv-1', intent: 'status' }));

    expect(await screen.findByText('Raft needs a majority. The crew is healthy.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: /Stop the turn/i })).toBeNull());
    expect(mockProgress).toHaveBeenCalledWith('run-1', 0);
  });

  it('stops the turn through the server cancel path once the run id is known', async () => {
    renderPage();
    await screen.findByText('Crew deployed.');

    fireEvent.change(screen.getByPlaceholderText(/Ask Aria anything/), { target: { value: 'Long job' } });
    fireEvent.click(screen.getByRole('button', { name: /Send message/i }));
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1));

    const callbacks = mockStream.mock.calls[0][3] as StreamCallbacks;
    act(() => callbacks.onThinking?.('run-42'));
    fireEvent.click(await screen.findByRole('button', { name: /Stop the turn/i }));

    expect(mockCancel).toHaveBeenCalledWith('run-42');
    await waitFor(() => expect(screen.getByRole('button', { name: /Send message/i })).toBeTruthy());
  });

  it('deletes a conversation only after confirmation and then starts a new chat', async () => {
    mockDelete.mockResolvedValue(undefined);
    renderPage();
    await screen.findByText('Crew deployed.');

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
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/aria?conversationId=conv-2']}>
          <MainChatPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('Check runs', { selector: '.ai-msg-text' })).toBeTruthy();
    expect(mockTimeline).toHaveBeenCalledWith('conv-2');
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
    await screen.findByText('Crew deployed.');

    fireEvent.click(screen.getByRole('button', { name: 'Check runs' }));

    expect(await screen.findByText('Check runs', { selector: '.ai-msg-text' })).toBeTruthy();
    expect(mockTimeline).toHaveBeenCalledWith('conv-2');
  });
});
