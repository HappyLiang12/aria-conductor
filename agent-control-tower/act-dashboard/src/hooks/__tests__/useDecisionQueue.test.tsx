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
  askType: 'APPROVAL',
  approvalType: 'TOOL_CALL',
  source: 'LEGACY_GATE',
  content: 'Check output',
  reason: 'Check output',
  requestedAt: '2026-10-27T10:00:00Z',
  riskTier: null,
  toolName: null,
  expiresAt: null,
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
    vi.mocked(listApprovals).mockResolvedValue([
      approval(),
      approval({ id: 'a-review', askType: 'REVIEW_REQUEST' }),
    ] as never);
    vi.mocked(listKanbanItems).mockRejectedValue(new Error('board unavailable'));

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.partial).toBe(true));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0].taskId).toBeNull();
    expect(result.current.count).toBeNull();
    // A partial load cannot render a fresh review count either.
    expect(result.current.reviewCount).toBeNull();
  });

  // T51: the same sparse payload that blanked the Review Queue reaches this
  // queue's row title. It must stay a row with an unknown run, never throw.
  it('keeps a row with an unknown run when the approval carries no runId', async () => {
    vi.mocked(listApprovals).mockResolvedValue([approval({ runId: undefined })] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.rows[0].title).toBe('Run unknown');
  });

  it('reports an error phase when the approvals source fails but the board succeeds', async () => {
    // Approvals own the row identity: a successful board cannot turn a failed
    // approvals fetch into an all-clear or a partial view with zero rows.
    vi.mocked(listApprovals).mockRejectedValue(new Error('approvals unavailable'));
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('error'));
    expect(result.current.count).toBeNull();
    expect(result.current.reviewCount).toBeNull();
    expect(result.current.rows).toHaveLength(0);
  });

  it('counts only blocking asks and reports post-run reviews separately', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({ id: 'a-perm', source: 'ACP_PERMISSION', riskTier: 'DESTRUCTIVE', toolName: 'Bash' }),
      approval({ id: 'a-clar', source: 'CLARIFICATION', askType: 'QUESTION' }),
      approval({ id: 'a-review', askType: 'REVIEW_REQUEST' }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows.map((r) => r.id)).toEqual(['a-perm', 'a-clar']);
    expect(result.current.count).toBe(2);
    expect(result.current.reviewCount).toBe(1);
  });

  it('sorts dangerous asks before pushes, local writes and reads, then by age', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({ id: 'a-read', riskTier: 'READ', requestedAt: '2026-10-27T08:00:00Z' }),
      approval({ id: 'a-local', riskTier: 'WRITE_LOCAL', toolName: 'write_file', requestedAt: '2026-10-27T07:00:00Z' }),
      approval({ id: 'a-push', riskTier: 'PUSH', toolName: 'Edit', requestedAt: '2026-10-27T09:00:00Z' }),
      approval({ id: 'a-danger', riskTier: 'DESTRUCTIVE', toolName: 'Bash', requestedAt: '2026-10-27T10:00:00Z' }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows.map((r) => r.id)).toEqual(['a-danger', 'a-push', 'a-local', 'a-read']);
    expect(result.current.rows[0]).toMatchObject({ riskTier: 'DESTRUCTIVE', toolName: 'Bash' });
  });

  it('summarizes a long markdown reason to one short line', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({
        approvalType: 'SPEC_REVIEW',
        reason: null,
        content: '# Spec\n\n' + 'x'.repeat(400),
      }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows[0].reason).not.toContain('\n');
    expect(result.current.rows[0].reason.length).toBeLessThanOrEqual(160);
  });

  it('falls back to a decision prompt when the content summarizes to nothing', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({ reason: null, content: '```\n\n```' }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows[0].reason).toBe('Decision requested');
  });

  it('marks a clarification as holding a run slot', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      approval({ id: 'a-clar', source: 'CLARIFICATION', askType: 'QUESTION' }),
      approval({ id: 'a-perm', source: 'ACP_PERMISSION' }),
    ] as never);
    vi.mocked(listKanbanItems).mockResolvedValue([] as never);

    const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.rows.find((r) => r.id === 'a-clar')?.blocksRunSlot).toBe(true);
    expect(result.current.rows.find((r) => r.id === 'a-perm')?.blocksRunSlot).toBe(false);
  });
});
