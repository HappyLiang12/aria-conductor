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
]);

export function isRunLifecycleEvent(type: string): boolean {
  return RUN_LIFECYCLE_TYPES.has(type);
}

export function isKanbanEvent(type: string): boolean {
  return type.startsWith('kanban.');
}
