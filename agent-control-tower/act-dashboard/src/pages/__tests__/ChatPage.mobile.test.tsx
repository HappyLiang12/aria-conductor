import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import ChatPage from '../ChatPage';

let runsData: unknown[] = [];

vi.mock('../../api/runs', () => ({
  listRuns: vi.fn().mockImplementation(() => Promise.resolve(runsData)),
  getRunTrajectory: vi.fn().mockResolvedValue([]),
  injectRunMessage: vi.fn().mockResolvedValue({ id: 'inj-1', turnNumber: 1 }),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([
    {
      id: 'a-1',
      name: 'Aria',
      role: 'assistant',
      agentType: 'ADK',
      healthStatus: 'HEALTHY',
      description: '',
      model: '',
      provider: 'opencode',
      createdAt: '2026-01-01T00:00:00Z',
    },
  ]),
}));
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => ({ lastMessage: null, isConnected: false }),
}));
// The run context widgets are covered elsewhere; this suite is about the
// conversation shell's copy and mobile pane state.
vi.mock('../../components/WorkflowStepper', () => ({ default: () => null }));
vi.mock('../../components/DelegationTree', () => ({ default: () => null }));
vi.mock('../../components/ReviewQueue', () => ({ default: () => null }));

const run = (id: string, status = 'COMPLETED') => ({
  id,
  agentId: 'a-1',
  status,
  promptSeed: '',
  maxIterations: 10,
  iterationCount: 1,
  totalTokensUsed: 0,
  errorMessage: null,
  finalOutput: null,
  createdAt: '2026-08-29T06:00:00Z',
  completedAt: null,
  conversationId: null,
});

function renderChat() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={qc}>
        <ChatPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  runsData = [run('1111aaaa-0000'), run('2222bbbb-0000')];
});

// Task 9: the page is a per-run operator/agent transcript, not a claim of
// agent-to-agent messaging, and small screens switch panes instead of squeezing
// a 320px list beside the transcript.
describe('ChatPage run conversations (Task 9)', () => {
  it('titles the page as run conversations with honest participant copy', async () => {
    renderChat();

    expect(
      await screen.findByRole('heading', { name: 'Run conversations' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Operator and agent transcript for each run'),
    ).toBeInTheDocument();
    // Participants are joined without an agent-to-agent arrow.
    expect(await screen.findByText('Operator · Aria')).toBeInTheDocument();
    expect(screen.queryByText(/↔/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Agent ↔ Agent/)).not.toBeInTheDocument();
  });

  it('opens thread mode and keeps the draft when going Back', async () => {
    const user = userEvent.setup();
    renderChat();
    const shell = screen.getByTestId('chat-shell');
    expect(shell).toHaveAttribute('data-mobile-pane', 'list');

    await user.click(await screen.findByRole('button', { name: /run 1111/i }));
    expect(shell).toHaveAttribute('data-mobile-pane', 'thread');

    await user.type(screen.getByRole('textbox', { name: /inject message/i }), 'keep this draft');
    await user.click(screen.getByRole('button', { name: /back to conversations/i }));

    expect(shell).toHaveAttribute('data-mobile-pane', 'list');
    // Back is navigation, not a reset: the draft survives the round trip.
    expect(screen.getByDisplayValue('keep this draft')).toBeInTheDocument();
  });

  it('keeps the selected thread when Back returns to the list', async () => {
    const user = userEvent.setup();
    renderChat();
    const shell = screen.getByTestId('chat-shell');

    await user.click(await screen.findByRole('button', { name: /run 2222/i }));
    expect(shell).toHaveAttribute('data-mobile-pane', 'thread');

    await user.click(screen.getByRole('button', { name: /back to conversations/i }));
    expect(shell).toHaveAttribute('data-mobile-pane', 'list');

    const rows = document.querySelectorAll('.chat-ch');
    expect(rows).toHaveLength(2);
    const active = document.querySelector('.chat-ch.active');
    expect(active).not.toBeNull();
    expect(active?.textContent).toContain('2222');

    // Reopening resumes the same thread rather than selecting the first one.
    await user.click(await screen.findByRole('button', { name: /run 2222/i }));
    expect(shell).toHaveAttribute('data-mobile-pane', 'thread');
    expect(document.querySelector('.chat-thread-panel .h-ttl')?.textContent).toContain('2222');
  });

  it('reports a failed transcript instead of claiming the run has no messages', async () => {
    const { getRunTrajectory } = await import('../../api/runs');
    vi.mocked(getRunTrajectory).mockRejectedValueOnce(new Error('trajectory unavailable'));
    renderChat();

    await userEvent.click(await screen.findByRole('button', { name: /run 1111/i }));

    expect(await screen.findByText(/Failed to load this transcript/)).toBeInTheDocument();
    expect(screen.queryByText(/No messages recorded yet/)).not.toBeInTheDocument();
  });
});
