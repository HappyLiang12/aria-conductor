import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MorningBriefing from '../MorningBriefing';
import type { WsEvent } from '../../types';

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({ totalTokensBurned: 0, activeAgents: 0, pendingApprovals: 0, runningRuns: 0 }),
}));
vi.mock('../../api/kanban', () => ({
  listKanbanItems: vi.fn().mockResolvedValue([]),
}));

let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));

describe('MorningBriefing WS invalidation (S5)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
  });

  it('invalidates kanban and summary on lifecycle/kanban events', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = render(
      <QueryClientProvider client={qc}><MorningBriefing /></QueryClientProvider>,
    );
    await act(async () => {});

    act(() => {
      mockCtx = { lastMessage: { type: 'kanban.transitioned', payload: { itemId: 'k-1' }, timestamp: 't' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><MorningBriefing /></QueryClientProvider>);
    });
    act(() => {
      mockCtx = { lastMessage: { type: 'run.started', payload: { runId: 'r-1' }, timestamp: 't2' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><MorningBriefing /></QueryClientProvider>);
    });

    expect(spy.mock.calls.some((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['kanban-items']))).toBe(true);
    expect(spy.mock.calls.some((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['dashboard-summary']))).toBe(true);
  });

  it('ignores high-frequency run.progress', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = render(
      <QueryClientProvider client={qc}><MorningBriefing /></QueryClientProvider>,
    );
    await act(async () => {});

    act(() => {
      for (let i = 0; i < 10; i++) {
        mockCtx = { lastMessage: { type: 'run.progress', payload: { runId: 'r-1', kind: 'thinking', seq: i }, timestamp: 't' }, isConnected: true };
        rerender(<QueryClientProvider client={qc}><MorningBriefing /></QueryClientProvider>);
      }
    });

    expect(spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['kanban-items']))).toHaveLength(0);
    expect(spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['dashboard-summary']))).toHaveLength(0);
  });
});
