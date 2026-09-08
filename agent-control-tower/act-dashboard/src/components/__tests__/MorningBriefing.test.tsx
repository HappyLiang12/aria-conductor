import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MorningBriefing from '../MorningBriefing';
import type { WsEvent } from '../../types';

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({ totalTokensBurned: 0, activeAgents: 0, pendingApprovals: 0, runningRuns: 0 }),
}));
let kanbanData: unknown[] = [];
vi.mock('../../api/kanban', () => ({
  listKanbanItems: vi.fn(() => Promise.resolve(kanbanData)),
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

describe('MorningBriefing briefing signals (single HITL signal, D7)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
  });

  it('counts REVIEW cards waiting on review and never surfaces pendingApprovals', async () => {
    const { getSummary } = await import('../../api/dashboard');
    // Non-zero pendingApprovals proves the second HITL signal is truly gone,
    // not just zero by coincidence.
    vi.mocked(getSummary).mockResolvedValue({
      totalTokensBurned: 0, activeAgents: 0, pendingApprovals: 5, runningRuns: 3,
    });
    kanbanData = [
      { id: 'k-1', title: 'a', priority: 'MEDIUM', status: 'REVIEW' },
      { id: 'k-2', title: 'b', priority: 'MEDIUM', status: 'REVIEW' },
      { id: 'k-3', title: 'c', priority: 'MEDIUM', status: 'TODO' },
    ];
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={qc}><MorningBriefing /></QueryClientProvider>,
    );
    // waitFor (not a bare act flush): under full-suite load the React Query
    // resolution needs more microtask turns than one act gives it.
    await waitFor(() => {
      expect(container.textContent).toContain('2 cards waiting on review');
    });

    // Review cards are the real waiting-on-review signal (BLOCKED was retired
    // in V52, so the old count was always 0).
    expect(container.textContent).toContain('2 cards waiting on review');
    expect(container.textContent).not.toContain('blocker');
    // Second HITL signal removed: D7 wants exactly one "Waiting on you" signal.
    expect(container.textContent).not.toContain('queued for approval');
    expect(container.textContent).not.toContain('approval');
  });
});
