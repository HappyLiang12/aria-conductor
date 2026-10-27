import { useEffect, useRef } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { listKanbanItems } from '../api/kanban';
import { listRuns } from '../api/runs';
import { getAdkProviderHealth, listAdkProviders } from '../api/adk';
import { mergeStableOrder } from '../utils/stableOrder';
import { queryPhase, type QueryPhase } from '../utils/queryState';

export type AttentionKind = 'pickup' | 'run' | 'provider';

export interface AttentionRow {
  id: string;
  kind: AttentionKind;
  title: string;
  reason: string;
  taskId?: string;
  runId?: string;
  cardStatus?: string;
}

/**
 * "Needs attention": pickup failures, failed runs with no real ask, and
 * unhealthy providers. A REVIEW card only owns a failure when it actually has
 * a pending ask; otherwise its failed run still needs the operator.
 */
export function useAttentionQueue(options: { locked: boolean }): {
  rows: AttentionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  retry: () => void;
} {
  const kanbanQuery = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 15000,
  });
  const runsQuery = useQuery({
    queryKey: ['runs-all'],
    queryFn: listRuns,
    refetchInterval: 30000,
  });
  const providersQuery = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
    refetchInterval: 30000,
  });
  const healthResults = useQueries({
    queries: (providersQuery.data ?? []).map((provider) => ({
      queryKey: ['adk-provider-health', provider.id],
      queryFn: () => getAdkProviderHealth(provider.id),
      refetchInterval: 30000,
    })),
  });

  const kanbanPhase = queryPhase(kanbanQuery);
  const runsPhase = queryPhase(runsQuery);
  const providersPhase = queryPhase(providersQuery);
  const providerCount = providersQuery.data?.length ?? 0;
  const probePhase: QueryPhase =
    providerCount === 0
      ? 'success'
      : healthResults.some((result) => result.isPending)
        ? 'loading'
        : healthResults.some((result) => result.isError)
          ? healthResults.every((result) => result.data === undefined) ? 'error' : 'stale'
          : 'success';

  const phases = [kanbanPhase, runsPhase, providersPhase, probePhase];
  const partial =
    phases.includes('success') && phases.some((p) => p === 'error' || p === 'stale');
  const phase: QueryPhase = phases.includes('loading')
    ? 'loading'
    : phases.every((p) => p === 'error')
      ? 'error'
      : phases.includes('stale')
        ? 'stale'
        : 'success';

  const cards = kanbanQuery.data ?? [];
  const cardsByRun = new Map(
    cards.filter((card) => card.linkedRunId).map((card) => [card.linkedRunId as string, card]),
  );

  const pickupRows: AttentionRow[] = cards
    .filter((card) => card.status === 'TODO' && card.lastError)
    .map((card) => ({
      id: `pickup-${card.id}`,
      kind: 'pickup' as const,
      title: card.title,
      reason: card.lastError as string,
      taskId: card.id,
      cardStatus: card.status,
    }));
  const pickupCardIds = new Set(pickupRows.map((row) => row.taskId));

  const runRows: AttentionRow[] = (runsQuery.data ?? [])
    .filter((run) => run.status === 'FAILED')
    .filter((run) => {
      const card = cardsByRun.get(run.id);
      if (card && pickupCardIds.has(card.id)) return false;
      if (card && (card.pendingAskCount ?? 0) > 0) return false;
      return true;
    })
    .map((run) => {
      const card = cardsByRun.get(run.id);
      return {
        id: `run-${run.id}`,
        kind: 'run' as const,
        title: card?.title ?? `Run ${run.id.slice(0, 8)}`,
        reason: run.errorMessage ?? 'Run failed',
        taskId: card?.id,
        runId: run.id,
        cardStatus: card?.status,
      };
    });

  const providerRows: AttentionRow[] = (providersQuery.data ?? [])
    .map((provider, index) => ({ provider, health: healthResults[index]?.data }))
    .filter(({ health }) => health?.healthy === false)
    .map(({ provider }) => ({
      id: `provider-${provider.id}`,
      kind: 'provider' as const,
      title: provider.displayName,
      reason: 'Provider health check reported unhealthy',
    }));

  const rows = [...pickupRows, ...runRows, ...providerRows];
  const previousRef = useRef<AttentionRow[] | null>(null);
  const merged = mergeStableOrder(previousRef.current, rows, options.locked);

  useEffect(() => {
    previousRef.current = merged;
  }, [merged]);

  return {
    rows: merged,
    phase,
    partial,
    count: phase === 'success' && !partial ? merged.length : null,
    retry: () => {
      void kanbanQuery.refetch();
      void runsQuery.refetch();
      void providersQuery.refetch();
      healthResults.forEach((result) => { void result.refetch(); });
    },
  };
}

