import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listApprovals } from '../api/approvals';
import { listKanbanItems } from '../api/kanban';
import { nativePermissionOf } from '../components/NativePermissionAsk';
import { mergeStableOrder } from '../utils/stableOrder';
import { queryPhase, type QueryPhase } from '../utils/queryState';
import type { KanbanItem } from '../types';

export interface DecisionRow {
  id: string;
  approvalId: string;
  runId: string;
  taskId: string | null;
  title: string;
  state: string;
  askType: string;
  source: string;
  reason: string;
  requestedAt: string;
  isPermission: boolean;
}

function joinCard(
  approval: { kanbanItemId?: string | null; runId: string },
  byId: Map<string, KanbanItem>,
  byRun: Map<string, KanbanItem>,
): KanbanItem | null {
  if (approval.kanbanItemId) {
    const direct = byId.get(approval.kanbanItemId);
    if (direct) return direct;
  }
  return byRun.get(approval.runId) ?? null;
}

/**
 * The operator's decision queue: one row per pending approval.
 *
 * The approval is the row identity. The card join is only display context, so
 * an approval with no card still renders and stays decidable. Counts are only
 * authoritative when both sources succeeded; a failed join never becomes a
 * zero count or an all-clear.
 */
export function useDecisionQueue(options: { locked: boolean }): {
  rows: DecisionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  retry: () => void;
} {
  const approvalsQuery = useQuery({
    queryKey: ['approvals', 'PENDING', 'all'],
    queryFn: () => listApprovals('PENDING'),
    refetchInterval: 10000,
  });
  const kanbanQuery = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 12000,
  });

  const approvalPhase = queryPhase(approvalsQuery);
  const kanbanPhase = queryPhase(kanbanQuery);
  const phases = [approvalPhase, kanbanPhase];
  const partial =
    (approvalPhase === 'success' && (kanbanPhase === 'error' || kanbanPhase === 'stale'))
    || (kanbanPhase === 'success' && (approvalPhase === 'error' || approvalPhase === 'stale'));
  const phase: QueryPhase =
    phases.includes('loading')
      ? 'loading'
      // Approvals are the row identity: without them the queue is unknown, not
      // partially known. A failed board join alone only withholds card context.
      : approvalPhase === 'error'
        ? 'error'
        : phases.includes('stale')
          ? 'stale'
          : 'success';

  const byId = new Map((kanbanQuery.data ?? []).map((item) => [item.id, item]));
  const byRun = new Map(
    (kanbanQuery.data ?? [])
      .filter((item) => item.linkedRunId)
      .map((item) => [item.linkedRunId as string, item]),
  );

  const rows = (approvalsQuery.data ?? [])
    .map((approval): DecisionRow => {
      const card = joinCard(approval, byId, byRun);
      return {
        id: approval.id,
        approvalId: approval.id,
        runId: approval.runId,
        taskId: card?.id ?? null,
        title: card?.title ?? `Run ${approval.runId.slice(0, 8)}`,
        state: card?.status ?? 'UNKNOWN',
        askType: approval.askType ?? approval.approvalType ?? 'APPROVAL',
        source: approval.source ?? '',
        reason: approval.content ?? approval.reason ?? 'Decision requested',
        requestedAt: approval.requestedAt,
        isPermission: nativePermissionOf(approval) !== null,
      };
    })
    .sort((a, b) => Date.parse(a.requestedAt) - Date.parse(b.requestedAt));

  const previousRef = useRef<DecisionRow[] | null>(null);
  const merged = mergeStableOrder(previousRef.current, rows, options.locked);

  useEffect(() => {
    previousRef.current = merged;
  }, [merged]);

  const count = phase === 'success' && !partial ? merged.length : null;

  return {
    rows: merged,
    phase,
    partial,
    count,
    retry: () => {
      void approvalsQuery.refetch();
      void kanbanQuery.refetch();
    },
  };
}
