import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RunsPage } from '../RunsPage';
import { getRunTrajectory } from '../../api/runs';
import type { WsEvent } from '../../types';

let runsData: unknown[] = [];
vi.mock('../../api/runs', () => ({
  listRuns: vi.fn().mockImplementation(() => Promise.resolve(runsData)),
  createRun: vi.fn().mockResolvedValue({ id: 'r-1' }),
  cancelRun: vi.fn().mockResolvedValue({}),
  pauseRun: vi.fn().mockResolvedValue({}),
  resumeRun: vi.fn().mockResolvedValue({}),
  getRunTrajectory: vi.fn().mockResolvedValue([]),
  getRunToolCalls: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
}));

let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));

function ui(qc: QueryClient) {
  return render(
    <QueryClientProvider client={qc}>
      <RunsPage />
    </QueryClientProvider>,
  );
}

describe('RunsPage WS invalidation whitelist (S1)', () => {
  it('does not invalidate the runs list for high-frequency run.progress', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);

    act(() => {
      for (let i = 0; i < 10; i++) {
        mockCtx = {
          lastMessage: { type: 'run.progress', payload: { runId: 'r-1', kind: 'thinking', seq: i }, timestamp: 't' },
          isConnected: true,
        };
        rerender(
          <QueryClientProvider client={qc}>
            <RunsPage />
          </QueryClientProvider>,
        );
      }
    });

    expect(spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['runs'])))
      .toHaveLength(0);
  });

  it('still refreshes the runs list on lifecycle events', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);

    act(() => {
      mockCtx = { lastMessage: { type: 'run.completed', payload: { runId: 'r-1', status: 'FAILED' }, timestamp: 't' }, isConnected: true };
      rerender(
        <QueryClientProvider client={qc}>
          <RunsPage />
        </QueryClientProvider>,
      );
    });

    const runCalls = spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['runs']));
    expect(runCalls.length).toBeGreaterThanOrEqual(1);
  });
});

const runningRun = (id: string, status: string) => ({
  id, agentId: 'a-1', status, promptSeed: 'do the thing', maxIterations: 10,
  iterationCount: 1, totalTokensUsed: 0, errorMessage: null, finalOutput: null,
  createdAt: '2026-08-29T06:00:00Z', completedAt: null, conversationId: null,
});

describe('RunsPage live trajectory (S3)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
    runsData = [runningRun('r-9', 'RUNNING')];
    (getRunTrajectory as ReturnType<typeof vi.fn>).mockClear();
  });
  afterEach(() => { vi.useRealTimers(); });

  it('refetches trajectory every 5s while the expanded run is RUNNING', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={qc}><RunsPage /></QueryClientProvider>);
    await waitFor(() => expect(screen.getAllByText('Details').length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByText('Details')[0]);
    await waitFor(() => expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));
    const before = (getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length;

    // real-time wait: react-query schedules refetchInterval with the timer
    // implementation captured at query start, so fake timers started later
    // would not fire it.
    await act(async () => { await new Promise((r) => setTimeout(r, 5500)); });
    expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(before);
  }, 20000);

  it('stops refetching once the run reaches a terminal status', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(<QueryClientProvider client={qc}><RunsPage /></QueryClientProvider>);
    await waitFor(() => expect(screen.getAllByText('Details').length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByText('Details')[0]);
    await waitFor(() => expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));

    // run completes: lifecycle event refreshes the list, terminal status stops polling
    runsData = [runningRun('r-9', 'FAILED')];
    act(() => {
      mockCtx = { lastMessage: { type: 'run.completed', payload: { runId: 'r-9', status: 'FAILED' }, timestamp: 't' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><RunsPage /></QueryClientProvider>);
    });
    await waitFor(() => expect(screen.getAllByText(/FAILED/).length).toBeGreaterThan(0));
    const after = (getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length;

    await act(async () => { await new Promise((r) => setTimeout(r, 6000)); });
    expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBe(after);
  }, 20000);

  it('invalidates trajectory precisely for the expanded run only', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = render(<QueryClientProvider client={qc}><RunsPage /></QueryClientProvider>);
    await waitFor(() => expect(screen.getAllByText('Details').length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByText('Details')[0]);
    await waitFor(() => expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));
    const before = (getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length;

    // event for ANOTHER run: no refetch
    act(() => {
      mockCtx = { lastMessage: { type: 'run.iteration', payload: { runId: 'r-other', iteration: 1 }, timestamp: 't' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><RunsPage /></QueryClientProvider>);
    });
    await act(async () => {});
    expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBe(before);

    // event for the expanded run: precise refetch
    act(() => {
      mockCtx = { lastMessage: { type: 'run.iteration', payload: { runId: 'r-9', iteration: 2 }, timestamp: 't2' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><RunsPage /></QueryClientProvider>);
    });
    await act(async () => {});
    expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(before);
  });
});
