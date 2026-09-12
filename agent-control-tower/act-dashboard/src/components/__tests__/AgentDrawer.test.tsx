import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AgentDrawer } from '../AgentDrawer';
import { listRuns, getRunProgress } from '../../api/runs';
import type { RunProgressEntry } from '../../api/runs';
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
// Drawer open/agent state is mutable so D5 tests can close and reopen the
// drawer (the real DrawerContext drives this through Layout).
const drawerState = vi.hoisted(() => ({ open: true, agentId: 'a-1' }));
vi.mock('../DrawerContext', () => ({
  useDrawerContext: () => ({
    state: {
      agentDrawer: { open: drawerState.open, agentId: drawerState.agentId },
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
  const utils = render(
    <QueryClientProvider client={qc}>
      <AgentDrawer />
    </QueryClientProvider>,
  );
  return { ...utils, qc };
}

/** Re-render against the SAME QueryClient (react-query cache must persist). */
function rerenderDrawer(rerender: (ui: ReactElement) => void, qc: QueryClient) {
  rerender(
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

  // Review fix: the backlog fetch resolution must MERGE with the stream, not
  // replace it — a live frame folded mid-fetch used to be wiped and never return.
  it('drawer_liveFrame_duringBacklogFetch_notDropped', async () => {
    // Deferred promise: the backlog fetch stays pending so we can land a live
    // WS frame inside the race window deterministically.
    let resolveBacklog!: (v: RunProgressEntry[]) => void;
    vi.mocked(listRuns).mockResolvedValue([runningRun]);
    vi.mocked(getRunProgress).mockImplementation(
      () => new Promise<RunProgressEntry[]>((res) => { resolveBacklog = res; }),
    );
    const { container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    // Live frame (seq above the backlog max) folds while the fetch is pending.
    act(() => {
      push({ type: 'run.progress', payload: { runId: 'r-1', agentId: 'a-1', kind: 'TOOL_RESULT', content: 'live during fetch', seq: 5 }, timestamp: '2026-09-06T06:00:05Z' });
    });
    expect(await screen.findByText(/live during fetch/)).toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(1);

    // Backlog resolves late: it must be PREPENDED onto the live line, not
    // replace it.
    await act(async () => {
      resolveBacklog(backlog);
    });
    expect(await screen.findByText(/thinking fragment/)).toBeInTheDocument();
    expect(screen.getByText(/shell_exec/)).toBeInTheDocument();
    expect(screen.getByText(/live during fetch/)).toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(3);
  });

  // Review fix: when a run completes, query invalidation makes activeRun
  // undefined — the replay id (lastRunIdRef) must keep the history on screen.
  it('drawer_completion_keepsHistory', async () => {
    vi.mocked(listRuns).mockResolvedValue([runningRun]);
    vi.mocked(getRunProgress).mockResolvedValue(backlog);
    ui();

    expect(await screen.findByText(/thinking fragment/)).toBeInTheDocument();

    // Run completes: lifecycle event invalidates the runs query; the refetched
    // list no longer holds an active run, but the stream must survive.
    vi.mocked(listRuns).mockResolvedValue([
      { ...runningRun, status: 'COMPLETED', finalOutput: 'done', completedAt: '2026-09-06T06:01:00Z' },
    ]);
    act(() => {
      push({ type: 'run.completed', payload: { runId: 'r-1', agentId: 'a-1', status: 'COMPLETED' }, timestamp: '2026-09-06T06:01:00Z' });
    });
    await waitFor(() => expect(screen.getByText(/Idle — awaiting work/)).toBeInTheDocument());
    expect(screen.getByText(/thinking fragment/)).toBeInTheDocument();
    expect(screen.getByText(/shell_exec/)).toBeInTheDocument();
  });
});

// Defect D5: the Live Activity Stream must keep — and re-seed — history for the
// agent's latest run even when that run already COMPLETED between polls.
describe('AgentDrawer completed-run history (D5)', () => {
  const completedRun: Run = {
    id: 'r-3', agentId: 'a-1', status: 'COMPLETED', promptSeed: 'Finished work',
    maxIterations: 15, totalTokensUsed: 900, iterationCount: 5,
    errorMessage: null, finalOutput: 'Delivered the thing',
    createdAt: '2026-09-06T06:04:00Z', completedAt: '2026-09-06T06:05:00Z',
  };
  const failedRun: Run = {
    id: 'r-4', agentId: 'a-1', status: 'FAILED', promptSeed: 'Broken work',
    maxIterations: 15, totalTokensUsed: 40, iterationCount: 2,
    errorMessage: 'provider exploded', finalOutput: null,
    createdAt: '2026-09-06T06:00:00Z', completedAt: '2026-09-06T06:02:00Z',
  };
  const backlog: RunProgressEntry[] = [
    { id: 'pc-1', runId: 'r-3', agentId: 'a-1', iteration: 1, kind: 'THINKING', seq: 1, content: 'historical thinking', toolName: null, createdAt: '2026-09-06T06:00:01Z' },
    { id: 'pc-2', runId: 'r-3', agentId: 'a-1', iteration: 1, kind: 'TOOL_CALL', seq: 2, content: 'historical output', toolName: 'shell_exec', createdAt: '2026-09-06T06:00:02Z' },
  ];

  beforeEach(() => {
    // Call history must not leak across tests: this describe asserts how many
    // times the backlog fetch ran.
    vi.clearAllMocks();
    mockCtx.lastMessage = null;
    mockCtx.isConnected = false;
    wsHandlers = [];
    drawerState.open = true;
    drawerState.agentId = 'a-1';
    vi.mocked(listRuns).mockResolvedValue([]);
    vi.mocked(getRunProgress).mockResolvedValue([]);
  });

  it('agentDrawer_completedRun_replaysPersistedBacklog', async () => {
    vi.mocked(listRuns).mockResolvedValue([completedRun]);
    vi.mocked(getRunProgress).mockResolvedValue(backlog);
    const { container } = ui();

    expect(await screen.findByText(/historical thinking/)).toBeInTheDocument();
    expect(screen.getByText(/shell_exec/)).toBeInTheDocument();
    expect(vi.mocked(getRunProgress)).toHaveBeenCalledWith('r-3');
    expect(container.querySelectorAll('.stream .ln').length).toBe(2);
  });

  it('agentDrawer_reopen_keepsHistoryAndReseedsBacklog', async () => {
    vi.mocked(listRuns).mockResolvedValue([completedRun]);
    vi.mocked(getRunProgress).mockResolvedValue(backlog);
    const { container, qc, rerender } = ui();
    expect(await screen.findByText(/historical thinking/)).toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(2);

    // Close the drawer: the agent target stays, only `open` flips.
    drawerState.open = false;
    await act(async () => {
      rerenderDrawer(rerender, qc);
    });
    // Reopen: the buffer must survive the round-trip (it used to be wiped on
    // close) and the persisted backlog must be re-seeded on open.
    drawerState.open = true;
    await act(async () => {
      rerenderDrawer(rerender, qc);
    });

    expect(await screen.findByText(/historical thinking/)).toBeInTheDocument();
    expect(screen.getByText(/shell_exec/)).toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(2);
    expect(vi.mocked(getRunProgress)).toHaveBeenCalledTimes(2);
  });

  it('agentDrawer_completedRun_showsFinalOutput', async () => {
    vi.mocked(listRuns).mockResolvedValue([completedRun]);
    ui();

    // The old gate (activeRun.status === 'COMPLETED') was unreachable because
    // activeRun only ever holds RUNNING/INITIALIZING/PAUSED.
    expect(await screen.findByText('Run Result')).toBeInTheDocument();
    expect(await screen.findByText('Delivered the thing')).toBeInTheDocument();
  });

  it('agentDrawer_failedRun_showsErrorMessage', async () => {
    vi.mocked(listRuns).mockResolvedValue([failedRun]);
    ui();

    expect(await screen.findByText('Run Failed')).toBeInTheDocument();
    expect(await screen.findByText('provider exploded')).toBeInTheDocument();
  });

  it('agentDrawer_replaysNewestRun_whenListIsUnsorted', async () => {
    // GET /api/v1/runs returns rows unsorted: the OLDEST run (failed) comes
    // first. The replay target must still be the newest run (completed).
    vi.mocked(listRuns).mockResolvedValue([failedRun, completedRun]);
    vi.mocked(getRunProgress).mockResolvedValue(backlog);
    const { container } = ui();

    expect(await screen.findByText(/historical thinking/)).toBeInTheDocument();
    expect(vi.mocked(getRunProgress)).toHaveBeenCalledWith('r-3');
    expect(vi.mocked(getRunProgress)).not.toHaveBeenCalledWith('r-4');
    expect(screen.queryByText('provider exploded')).not.toBeInTheDocument();
    expect(container.querySelectorAll('.stream .ln').length).toBe(2);
  });
});
