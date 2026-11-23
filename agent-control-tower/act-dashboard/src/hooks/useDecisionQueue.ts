import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listApprovals } from '../api/approvals';
import { listKanbanItems } from '../api/kanban';
import { nativePermissionOf } from '../components/NativePermissionAsk';
import { mergeStableOrder } from '../utils/stableOrder';
import { shortRunId } from '../utils/shortRunId';
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
  approvalType: string | null;
  source: string;
  reason: string;
  requestedAt: string;
  isPermission: boolean;
  riskTier: string | null;
  toolName: string | null;
  expiresAt: string | null;
  /** A clarification parks the run and keeps its admission slot until answered. */
  blocksRunSlot: boolean;
}

const SUMMARY_MAX = 160;
const RISK_RANK: Record<string, number> = {
  DESTRUCTIVE: 0,
  PUSH: 1,
  WRITE_LOCAL: 2,
  READ: 3,
};

/** One short line for the queue: markdown stripped, whitespace collapsed, bounded. */
function summarize(text: string): string {
  const line = text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#*_`>[\]()]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return line.length > SUMMARY_MAX ? `${line.slice(0, SUMMARY_MAX - 3)}...` : line;
}

function riskRank(riskTier: string | null): number {
  return riskTier != null && riskTier in RISK_RANK ? RISK_RANK[riskTier] : 4;
}

function isReviewRequest(approval: { askType?: string | null }): boolean {
  return approval.askType === 'REVIEW_REQUEST';
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
  /** Post-run REVIEW_REQUEST asks: they block nothing and are surfaced as a board review. */
  reviewCount: number | null;
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

  const approvals = approvalsQuery.data ?? [];
  const blockingApprovals = approvals.filter((approval) => !isReviewRequest(approval));
  // Review counts follow the same authority rule as decision counts: a stale
  // refresh or a failed board join is not a fresh count.
  const reviewCount =
    phase === 'success' && !partial && approvalsQuery.data
      ? approvals.length - blockingApprovals.length
      : null;

  const rows = blockingApprovals
    .map((approval): DecisionRow => {
      const card = joinCard(approval, byId, byRun);
      return {
        id: approval.id,
        approvalId: approval.id,
        runId: approval.runId,
        taskId: card?.id ?? null,
        title: card?.title ?? `Run ${shortRunId(approval.runId)}`,
        state: card?.status ?? 'UNKNOWN',
        askType: approval.askType ?? approval.approvalType ?? 'APPROVAL',
        approvalType: approval.approvalType ?? null,
        source: approval.source ?? '',
        reason: summarize(approval.content ?? approval.reason ?? 'Decision requested')
          || 'Decision requested',
        requestedAt: approval.requestedAt,
        isPermission: nativePermissionOf(approval) !== null,
        riskTier: approval.riskTier ?? null,
        toolName: approval.toolName ?? null,
        expiresAt: approval.expiresAt ?? null,
        blocksRunSlot: approval.source === 'CLARIFICATION',
      };
    })
    // Dangerous first, then pushes, then the rest; oldest first inside a tier so
    // the longest-waiting decision cannot starve behind newer noise.
    .sort(
      (a, b) =>
        riskRank(a.riskTier) - riskRank(b.riskTier)
        || Date.parse(a.requestedAt) - Date.parse(b.requestedAt),
    );

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
    reviewCount,
    retry: () => {
      void approvalsQuery.refetch();
      void kanbanQuery.refetch();
    },
  };
}
