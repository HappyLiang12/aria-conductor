import { describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { listKanbanItems } from '../../api/kanban';
import { listRuns } from '../../api/runs';
import { getAdkProviderHealth, listAdkProviders } from '../../api/adk';
import { useAttentionQueue } from '../useAttentionQueue';

vi.mock('../../api/kanban', () => ({ listKanbanItems: vi.fn() }));
vi.mock('../../api/runs', () => ({ listRuns: vi.fn() }));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
  getAdkProviderHealth: vi.fn(),
}));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

const failedRun = {
  id: 'run-ffffffff',
  agentId: 'agent-1',
  status: 'FAILED',
  promptSeed: '',
  maxIterations: 1,
  totalTokensUsed: 0,
  iterationCount: 0,
  errorMessage: 'port busy',
  finalOutput: null,
  createdAt: '2026-10-27T09:00:00Z',
  completedAt: '2026-10-27T09:01:00Z',
};

describe('useAttentionQueue', () => {
  it('reports a TODO pickup failure with the real retry capability', async () => {
    vi.mocked(listKanbanItems).mockResolvedValue([
      { id: 'k-1', title: 'Fix pickup retry', status: 'TODO', lastError: 'port busy', linkedRunId: null, pendingAskCount: 0 },
    ] as never);
    vi.mocked(listRuns).mockResolvedValue([] as never);
    vi.mocked(listAdkProviders).mockResolvedValue([] as never);

    const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0]).toMatchObject({ kind: 'pickup', taskId: 'k-1', title: 'Fix pickup retry' });
  });

  it('does not hide a failed run behind a REVIEW card with zero asks', async () => {
    vi.mocked(listKanbanItems).mockResolvedValue([
      { id: 'k-1', title: 'Review me', status: 'REVIEW', lastError: null, linkedRunId: 'run-ffffffff', pendingAskCount: 0 },
    ] as never);
    vi.mocked(listRuns).mockResolvedValue([failedRun] as never);
    vi.mocked(listAdkProviders).mockResolvedValue([] as never);

    const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0]).toMatchObject({ kind: 'run', taskId: 'k-1', runId: 'run-ffffffff' });
  });

  it('skips a failed run whose card has a pending ask', async () => {
    vi.mocked(listKanbanItems).mockResolvedValue([
      { id: 'k-1', title: 'Review me', status: 'REVIEW', lastError: null, linkedRunId: 'run-ffffffff', pendingAskCount: 2 },
    ] as never);
    vi.mocked(listRuns).mockResolvedValue([failedRun] as never);
    vi.mocked(listAdkProviders).mockResolvedValue([] as never);

    const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows).toHaveLength(0);
    expect(result.current.count).toBe(0);
  });

  it('reports only unhealthy providers in a mixed fleet', async () => {
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);
    vi.mocked(listRuns).mockResolvedValue([] as never);
    vi.mocked(listAdkProviders).mockResolvedValue([
      { id: 'p-1', displayName: 'Healthy core', supportsTaskExecution: true, isDefault: true },
      { id: 'p-2', displayName: 'Broken core', supportsTaskExecution: true, isDefault: false },
    ] as never);
    vi.mocked(getAdkProviderHealth).mockImplementation(async (id: string) => ({ providerId: id, healthy: id === 'p-1' }));

    const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0]).toMatchObject({ kind: 'provider', title: 'Broken core' });
    expect(result.current.count).toBe(1);
  });

  it('is an error, not an empty success, when every source fails', async () => {
    // A failed provider list must not masquerade as "no providers configured":
    // that pinned probePhase to success and made the error phase unreachable.
    vi.mocked(listKanbanItems).mockRejectedValue(new Error('board down'));
    vi.mocked(listRuns).mockRejectedValue(new Error('runs down'));
    vi.mocked(listAdkProviders).mockRejectedValue(new Error('providers down'));

    const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.count).toBeNull();
    expect(result.current.rows).toHaveLength(0);
  });

  it('withholds the count when one source fails but another succeeds', async () => {
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);
    vi.mocked(listRuns).mockRejectedValue(new Error('runs down'));
    vi.mocked(listAdkProviders).mockResolvedValue([] as never);

    const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.partial).toBe(true));
    expect(result.current.count).toBeNull();
    expect(result.current.rows).toHaveLength(0);
  });
});
