import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act, screen, fireEvent, waitFor, within } from '@testing-library/react';
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

describe('RunsPage run-row keys (S1)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
    runsData = [runningRun('r-1', 'RUNNING'), runningRun('r-2', 'COMPLETED')];
  });

  it('renders multiple run rows without a React key warning', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    ui(qc);

    // let React commit both rows so any key warning is actually emitted.
    // This must be the first test in the file to render run rows: React caches
    // the missing-key warning per owning component, so a later test would see
    // an already-deduped console.error.
    await waitFor(() => expect(screen.getAllByText('Details').length).toBe(2));

    const keyWarnings = errorSpy.mock.calls.filter((call) =>
      String(call[0]).includes('unique "key" prop'),
    );
    expect(keyWarnings).toHaveLength(0);
    errorSpy.mockRestore();
  });
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

// ---------------------------------------------------------------------------
// F9 (SDD hardening): qoder runs reported totalTokensUsed 0 because the core
// never reported usage. The list must show an explicit "not reported" state
// when the field is absent, and a genuine 0 when the core does report zero.
// ---------------------------------------------------------------------------

describe('RunsPage token usage (F9)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
    runsData = [
      { ...runningRun('r-null', 'COMPLETED'), totalTokensUsed: null },
      { ...runningRun('r-zero', 'COMPLETED'), totalTokensUsed: 0 },
    ];
  });

  it('renders "not reported" for absent usage and a real 0 for a reported zero', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    ui(qc);

    const absentRow = (await screen.findByText('r-null')).closest('tr') as HTMLElement;
    expect(within(absentRow).getByText('not reported')).toBeInTheDocument();
    expect(within(absentRow).queryByText('0')).not.toBeInTheDocument();

    const zeroRow = screen.getByText('r-zero').closest('tr') as HTMLElement;
    expect(within(zeroRow).getByText('0')).toBeInTheDocument();
    expect(within(zeroRow).queryByText('not reported')).not.toBeInTheDocument();
  });

  it('reports absence in the detail view instead of a zero', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    ui(qc);

    const absentRow = (await screen.findByText('r-null')).closest('tr') as HTMLElement;
    fireEvent.click(within(absentRow).getByText('Details'));

    const detail = (await screen.findByText('Tokens Used')).closest('.detail-info-item') as HTMLElement;
    expect(within(detail).getByText('usage not reported')).toBeInTheDocument();
  });
});
