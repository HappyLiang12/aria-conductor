import { describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { listApprovals } from '../../api/approvals';
import { listKanbanItems } from '../../api/kanban';
import { useDecisionQueue } from '../useDecisionQueue';

vi.mock('../../api/approvals', () => ({ listApprovals: vi.fn() }));
vi.mock('../../api/kanban', () => ({ listKanbanItems: vi.fn() }));
vi.mock('../../components/NativePermissionAsk', () => ({ nativePermissionOf: () => null }));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

const approval = (over: Record<string, unknown> = {}) => ({
  id: 'ap-1',
  runId: 'run-abcdef12',
  status: 'PENDING',
  askType: 'REVIEW_REQUEST',
  source: 'LEGACY_GATE',
  content: 'Check output',
  reason: 'Check output',
  requestedAt: '2026-10-27T10:00:00Z',
  ...over,
});

describe('useDecisionQueue', () => {
  it('uses the approval id as row identity and joins the card title', async () => {
    vi.mocked(listApprovals).mockResolvedValue([approval()] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([
      { id: 'k-1', title: 'Fix pickup retry', status: 'REVIEW', linkedRunId: 'run-abcdef12', pendingAskCount: 2, lastError: null },
    ] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0]).toMatchObject({ id: 'ap-1', taskId: 'k-1', title: 'Fix pickup retry', state: 'REVIEW' });
    expect(result.current.count).toBe(1);
  });

  it('prefers kanbanItemId over the run-derived card', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({ kanbanItemId: 'k-direct', runId: 'run-abcdef12' }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([
      { id: 'k-direct', title: 'Direct card', status: 'TODO', linkedRunId: null, pendingAskCount: 0 },
      { id: 'k-run', title: 'Run card', status: 'REVIEW', linkedRunId: 'run-abcdef12', pendingAskCount: 1 },
    ] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows[0]).toMatchObject({ taskId: 'k-direct', title: 'Direct card' });
  });

  it('keeps an unlinked approval actionable with a null task id', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({ id: 'ap-unlinked', runId: 'run-ffffffff', kanbanItemId: null, source: 'CLARIFICATION' }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows[0]).toMatchObject({
      id: 'ap-unlinked',
      taskId: null,
      title: 'Run run-ffff',
      state: 'UNKNOWN',
    });
    expect(result.current.count).toBe(1);
  });

  it('reports partial data and a null count when the board join fails', async () => {
    vi.mocked(listApprovals).mockResolvedValue([approval()] as never);
    vi.mocked(listKanbanItems).mockRejectedValue(new Error('board unavailable'));

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.partial).toBe(true));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0].taskId).toBeNull();
    expect(result.current.count).toBeNull();
  });
});
