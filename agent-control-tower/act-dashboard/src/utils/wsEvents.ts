/**
 * S1 WS event whitelist: list-level query invalidation must only react to true
 * lifecycle events. High-frequency streaming events (run.progress) are consumed
 * precisely by runId-matched views (AgentDrawer / RunDetailView) and must never
 * trigger board/list refetch storms.
 */
const RUN_LIFECYCLE_TYPES = new Set([
  'run.started',
  'run.completed',
  'run.failed',
  'run.iteration',
  // Waiting-input (2026-10-05): a run parking for operator input is a rare
  // lifecycle transition — lists must refresh so the parked run is visible.
  // (Resume is intentionally event-less: whitelisted run.iteration self-heals
  // the stale WAITING_INPUT badge within seconds.)
  'run.waiting_input',
]);

export function isRunLifecycleEvent(type: string): boolean {
  return RUN_LIFECYCLE_TYPES.has(type);
}

export function isKanbanEvent(type: string): boolean {
  return type.startsWith('kanban.');
}

/** Housekeeping progress + completion audit events (panel-level consumption only). */
export function isHousekeepingEvent(type: string): boolean {
  return type === 'housekeeping.progress' || type === 'audit.HOUSEKEEPING_EXECUTED';
}
