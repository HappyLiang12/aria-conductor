import { describe, it, expect, vi } from 'vitest';
import { render, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from '../DashboardPage';
import type { WsEvent } from '../../types';

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({ totalTokensBurned: 0, activeAgents: 0, pendingApprovals: 0, runningRuns: 0 }),
  getRecentActivity: vi.fn().mockResolvedValue([]),
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
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DashboardPage WS invalidation whitelist (S1)', () => {
  it('does not invalidate dashboard-summary for run.progress', () => {
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
            <MemoryRouter>
              <DashboardPage />
            </MemoryRouter>
          </QueryClientProvider>,
        );
      }
    });

    expect(spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['dashboard-summary'])))
      .toHaveLength(0);
  });

  it('invalidates dashboard-summary for lifecycle and kanban events', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);

    act(() => {
      mockCtx = { lastMessage: { type: 'run.started', payload: { runId: 'r-1' }, timestamp: 't' }, isConnected: true };
      rerender(
        <QueryClientProvider client={qc}>
          <MemoryRouter>
            <DashboardPage />
          </MemoryRouter>
        </QueryClientProvider>,
      );
    });
    act(() => {
      mockCtx = { lastMessage: { type: 'kanban.transitioned', payload: { itemId: 'k-1' }, timestamp: 't2' }, isConnected: true };
      rerender(
        <QueryClientProvider client={qc}>
          <MemoryRouter>
            <DashboardPage />
          </MemoryRouter>
        </QueryClientProvider>,
      );
    });

    const calls = spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['dashboard-summary']));
    expect(calls.length).toBeGreaterThanOrEqual(2);
  });
});
