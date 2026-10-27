/**
 * The honest phase of one TanStack Query read.
 *
 * A failed refetch with cached data is `stale`, never `success`: the operator
 * is looking at last-known data, not at a refreshed answer. A first failure is
 * `error` and must never be rendered as an empty success.
 */
export type QueryPhase = 'loading' | 'success' | 'error' | 'stale';

export function queryPhase(q: {
  data: unknown;
  isPending: boolean;
  isError: boolean;
  isSuccess: boolean;
}): QueryPhase {
  if (q.isSuccess && !q.isError) return 'success';
  if (q.isError) return q.data === undefined || q.data === null ? 'error' : 'stale';
  return 'loading';
}

/** True only when every contributing source is a settled success. */
export function allAuthoritative(phases: QueryPhase[]): boolean {
  return phases.length > 0 && phases.every((p) => p === 'success');
}
