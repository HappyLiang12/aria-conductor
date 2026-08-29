import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ChatPage from '../ChatPage';
import { getRunTrajectory } from '../../api/runs';
import type { WsEvent } from '../../types';

let runsData: unknown[] = [];
vi.mock('../../api/runs', () => ({
  listRuns: vi.fn().mockImplementation(() => Promise.resolve(runsData)),
  getRunTrajectory: vi.fn().mockResolvedValue([]),
  injectRunMessage: vi.fn().mockResolvedValue({ id: 'inj-1', turnNumber: 1 }),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([{ id: 'a-1', name: 'Aria', role: 'assistant', agentType: 'NATIVE', healthStatus: 'HEALTHY', description: '', model: '', provider: 'langchain', createdAt: '2026-01-01T00:00:00Z' }]),
}));

let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));

const run = (id: string, status: string) => ({
  id, agentId: 'a-1', status, promptSeed: 'hi', maxIterations: 10,
  iterationCount: 1, totalTokensUsed: 0, errorMessage: null, finalOutput: null,
  createdAt: '2026-08-29T06:00:00Z', completedAt: null, conversationId: null,
});

function ui(qc: QueryClient) {
  return render(
    <QueryClientProvider client={qc}>
      <ChatPage />
    </QueryClientProvider>,
  );
}

describe('ChatPage WS live updates (S4)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
    runsData = [run('r-9', 'RUNNING')];
    (getRunTrajectory as ReturnType<typeof vi.fn>).mockClear();
  });

  it('refreshes the active thread trajectory on run.iteration for that run', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);
    // auto-select first thread (r-9)
    await waitFor(() => expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));

    act(() => {
      mockCtx = { lastMessage: { type: 'run.iteration', payload: { runId: 'r-9', iteration: 2 }, timestamp: 't' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><ChatPage /></QueryClientProvider>);
    });
    await waitFor(() =>
      expect(spy.mock.calls.some((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['run-trajectory', 'r-9'])))
        .toBe(true),
    );
  });

  it('ignores iterations of other runs', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);
    await waitFor(() => expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));

    act(() => {
      mockCtx = { lastMessage: { type: 'run.iteration', payload: { runId: 'r-other', iteration: 1 }, timestamp: 't' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><ChatPage /></QueryClientProvider>);
    });
    await act(async () => { await new Promise((r) => setTimeout(r, 50)); });
    expect(spy.mock.calls.some((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['run-trajectory', 'r-9'])))
      .toBe(false);
  });

  it('does not invalidate the runs list for high-frequency run.progress', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);
    await waitFor(() => expect((getRunTrajectory as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));

    act(() => {
      for (let i = 0; i < 10; i++) {
        mockCtx = { lastMessage: { type: 'run.progress', payload: { runId: 'r-9', kind: 'thinking', seq: i }, timestamp: 't' }, isConnected: true };
        rerender(<QueryClientProvider client={qc}><ChatPage /></QueryClientProvider>);
      }
    });
    expect(spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['runs'])))
      .toHaveLength(0);
  });
});
