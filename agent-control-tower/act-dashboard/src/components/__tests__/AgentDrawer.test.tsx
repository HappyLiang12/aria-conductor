import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AgentDrawer } from '../AgentDrawer';
import { listRuns, getRunProgress } from '../../api/runs';
import type { Run, WsEvent } from '../../types';

vi.mock('../../api/agents', () => ({
  getAgent: vi.fn().mockResolvedValue({
    id: 'a-1', name: 'SDD DEV Agent', role: 'dev', agentType: 'ADK',
    healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode',
    createdAt: '2026-01-01T00:00:00Z',
  }),
}));
vi.mock('../../api/runs', () => ({
  listRuns: vi.fn().mockResolvedValue([]),
  getRunProgress: vi.fn().mockResolvedValue([]),
  pauseRun: vi.fn().mockResolvedValue({}),
  resumeRun: vi.fn().mockResolvedValue({}),
  cancelRun: vi.fn().mockResolvedValue({}),
  injectRunMessage: vi.fn().mockResolvedValue({}),
}));

// Task 5 model: the context exposes subscribe(handler) => { unsubscribe }.
// `push` records lastMessage (legacy fold) AND dispatches to every subscribed
// handler, mirroring the real useWebSocket broadcast semantics.
type WsHandler = (e: WsEvent) => void;
let wsHandlers: WsHandler[] = [];
const mockCtx = {
  lastMessage: null as WsEvent | null,
  isConnected: false,
  subscribe: (handler: WsHandler) => {
    wsHandlers.push(handler);
    return {
      unsubscribe: () => {
        wsHandlers = wsHandlers.filter((h) => h !== handler);
      },
    };
  },
};
vi.mock('../Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));
vi.mock('../DrawerContext', () => ({
  useDrawerContext: () => ({
    state: {
      agentDrawer: { open: true, agentId: 'a-1' },
      taskDrawer: { open: false, itemId: null },
    },
    openTaskDrawer: vi.fn(),
    closeTaskDrawer: vi.fn(),
    openAgentDrawer: vi.fn(),
    closeAgentDrawer: vi.fn(),
  }),
}));

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AgentDrawer />
    </QueryClientProvider>,
  );
}

function push(event: WsEvent) {
  mockCtx.lastMessage = event;
  mockCtx.isConnected = true;
  for (const handler of [...wsHandlers]) handler(event);
}

describe('AgentDrawer live stream (S2)', () => {
  beforeEach(() => {
    mockCtx.lastMessage = null;
    mockCtx.isConnected = false;
    wsHandlers = [];
  });

  it('renders parsed thinking detail from run.iteration events', async () => {
    const { rerender } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    act(() => {
      push({
        type: 'run.iteration',
        payload: {
          agentId: 'a-1', iteration: 1, maxIterations: 15,
          thinking: 'Locating the step scheduler in WorkflowService',
          toolCalls: [{ name: 'bash', arguments: 'rg -n scheduler', result: '3 matches' }],
        },
        timestamp: '2026-08-29T06:00:00Z',
      });
      rerender(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <AgentDrawer />
        </QueryClientProvider>,
      );
    });

    // S2 contract: the parsed thinking must reach the screen (previously dropped).
    expect(screen.getByText(/Locating the step scheduler/)).toBeInTheDocument();
    expect(screen.getByText(/3 matches/)).toBeInTheDocument();
  });

  it('caps the stream window at 60 lines', async () => {
    const { rerender, container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    act(() => {
      for (let i = 0; i < 70; i++) {
        push({
          type: 'run.iteration',
          payload: { agentId: 'a-1', iteration: i, thinking: `line ${i}` },
          timestamp: `2026-08-29T06:00:${String(i % 60).padStart(2, '0')}Z`,
        });
        rerender(<QueryClientProvider client={qc}><AgentDrawer /></QueryClientProvider>);
      }
    });

    const lines = container.querySelectorAll('.stream .ln');
    expect(lines.length).toBeLessThanOrEqual(60);
    expect(lines.length).toBeGreaterThan(0);
  });

  it('renders run.progress events by kind and dedupes by seq (S11)', async () => {
    const { rerender, container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    act(() => {
      push({ type: 'run.progress', payload: { runId: 'a-1', agentId: 'a-1', kind: 'THINKING', content: 'pump thinking line', seq: 1 }, timestamp: '2026-08-29T06:00:00Z' });
      rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);
    });
    expect(screen.getByText(/pump thinking line/)).toBeInTheDocument();

    act(() => {
      push({ type: 'run.progress', payload: { runId: 'a-1', agentId: 'a-1', kind: 'TOOL_CALL', content: '', toolName: 'bash', seq: 2 }, timestamp: '2026-08-29T06:00:01Z' });
      rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);
    });
    expect(screen.getAllByText(/bash/).length).toBeGreaterThan(0);

    // duplicate seq must not create a second line
    const linesBefore = container.querySelectorAll('.stream .ln').length;
    act(() => {
      push({ type: 'run.progress', payload: { runId: 'a-1', agentId: 'a-1', kind: 'TOOL_CALL', content: '', toolName: 'bash', seq: 2 }, timestamp: '2026-08-29T06:00:02Z' });
      rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);
    });
    expect(container.querySelectorAll('.stream .ln').length).toBe(linesBefore);
  });

  it('collapse toggle hides the stream body but keeps the header', async () => {
    const { rerender, container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    const toggle = screen.getByRole('button', { name: /collapse/i });
    act(() => { fireEvent.click(toggle); });
    rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);

    const stream = container.querySelector('.stream');
    expect(stream).not.toBeNull();
    expect(stream!.classList.contains('collapsed')).toBe(true);
    // header stays visible for re-expansion
    expect(screen.getByRole('button', { name: /expand/i })).toBeInTheDocument();
  });
});

describe('AgentDrawer backlog replay + stub removal (Task 6)', () => {
  const runningRun: Run = {
    id: 'r-1', agentId: 'a-1', status: 'RUNNING', promptSeed: 'Do the thing',
    maxIterations: 15, totalTokensUsed: 0, iterationCount: 3,
    errorMessage: null, finalOutput: null,
    createdAt: '2026-09-06T06:00:00Z', completedAt: null,
  };
  const failedRun: Run = {
    id: 'r-2', agentId: 'a-1', status: 'FAILED', promptSeed: 'Broken run',
    maxIterations: 15, totalTokensUsed: 0, iterationCount: 2,
    errorMessage: 'boom', finalOutput: null,
    createdAt: '2026-09-06T06:00:00Z', completedAt: null,
  };
  const backlog = [
    { id: 'p-1', runId: 'r-1', agentId: 'a-1', iteration: 1, kind: 'THINKING', seq: 1, content: 'thinking fragment', toolName: null, createdAt: '2026-09-06T06:00:01Z' },
    { id: 'p-2', runId: 'r-1', agentId: 'a-1', iteration: 1, kind: 'TOOL_CALL', seq: 2, content: 'shell output', toolName: 'shell_exec', createdAt: '2026-09-06T06:00:02Z' },
  ];

  beforeEach(() => {
    mockCtx.lastMessage = null;
    mockCtx.isConnected = false;
    wsHandlers = [];
    vi.mocked(listRuns).mockResolvedValue([]);
    vi.mocked(getRunProgress).mockResolvedValue([]);
  });

  it('drawer_showsBacklogHistory_onOpen', async () => {
    vi.mocked(listRuns).mockResolvedValue([runningRun]);
    vi.mocked(getRunProgress).mockResolvedValue(backlog);
    const { container } = ui();

    expect(await screen.findByText(/thinking fragment/)).toBeInTheDocument();
    expect(screen.getByText(/shell_exec/)).toBeInTheDocument();
    // seeded demo line must be gone
    expect(screen.queryByText(/Connected to /)).not.toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(2);
  });

  it('drawer_liveFrames_append_afterBacklog', async () => {
    vi.mocked(listRuns).mockResolvedValue([runningRun]);
    vi.mocked(getRunProgress).mockResolvedValue(backlog);
    const { container } = ui();

    expect(await screen.findByText(/thinking fragment/)).toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(2);

    act(() => {
      push({ type: 'run.progress', payload: { runId: 'r-1', agentId: 'a-1', kind: 'TOOL_RESULT', content: 'live output', seq: 3 }, timestamp: '2026-09-06T06:00:03Z' });
    });
    expect(await screen.findByText(/live output/)).toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(3);

    // duplicate seq must be deduped, not re-rendered
    act(() => {
      push({ type: 'run.progress', payload: { runId: 'r-1', agentId: 'a-1', kind: 'TOOL_RESULT', content: 'live output', seq: 3 }, timestamp: '2026-09-06T06:00:04Z' });
    });
    expect(container.querySelectorAll('.stream .ln').length).toBe(3);
  });

  it('drawer_failedRun_isNotActiveRun', async () => {
    vi.mocked(listRuns).mockResolvedValue([failedRun]);
    ui();

    await waitFor(() => expect(screen.getByText(/Idle — awaiting work/)).toBeInTheDocument());
    expect(screen.queryByText('Active Run')).not.toBeInTheDocument();
  });

  it('drawer_noPumpStubLine', async () => {
    ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());
    expect(screen.queryByText(/GET \/session\/:id\/message/)).not.toBeInTheDocument();
  });

  it('drawer_tokenBarsRemoved', async () => {
    vi.mocked(listRuns).mockResolvedValue([runningRun]);
    ui();

    await waitFor(() => expect(screen.getByText(/Iter 3 \/ 15/)).toBeInTheDocument());
    expect(screen.queryByText('Tokens')).not.toBeInTheDocument();
    expect(screen.queryByText('Context')).not.toBeInTheDocument();
    expect(screen.queryByText(/^cap /)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/ 200k/)).not.toBeInTheDocument();
  });
});
