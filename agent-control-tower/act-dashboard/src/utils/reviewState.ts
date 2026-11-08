import type { KanbanItem } from '../types';

/**
 * F24 (SDD hardening): REVIEW must mean "an open ask needs a human".
 *
 * Completed workflow step cards stayed in REVIEW with `pendingAskCount = 0`,
 * so every REVIEW badge read as "waiting on you" with nothing to act on. The
 * workflow lane stops leaving finished cards there; the UI enforces the reading
 * rule on every surface it owns: badge REVIEW only while an ask is open, and
 * let the linked run's outcome (`runOutcome`) describe the rest.
 */
export function openAskCount(item: Pick<KanbanItem, 'pendingAskCount'>): number {
  const count = item.pendingAskCount;
  return typeof count === 'number' && Number.isFinite(count) && count > 0 ? count : 0;
}

/** True only for a REVIEW card that actually has an open ask. */
export function reviewNeedsHuman(
  item: Pick<KanbanItem, 'status' | 'pendingAskCount'>,
): boolean {
  return item.status === 'REVIEW' && openAskCount(item) > 0;
}
