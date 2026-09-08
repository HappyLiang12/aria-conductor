/**
 * Maps a notification `type` to the dashboard route that shows the resource.
 * Shared by NotificationBell (dropdown clicks) and Toast (the aria.notification
 * "View" action) so both stay in sync with the route map.
 *
 * The fine-grained event types (run.completed, approval.requested, ...) live in
 * the notification `type` field — the sibling `resourceType` field only carries
 * coarse categories (RUN/APPROVAL/KNOWLEDGE/REPORT) which this map deliberately
 * does NOT understand (they resolve to null, i.e. "do not navigate").
 *
 * Approvals no longer have a dedicated page (kanban HITL redesign, spec D4):
 * `approval.requested` routes to the overview, where the kanban Review column
 * is the single HITL surface and the "Waiting on you" summary card opens the
 * first review card. The bell does not know kanban ids, so it navigates to the
 * overview only.
 *
 * Returns null when the type has no meaningful destination (e.g. reminders,
 * monitors, briefs) — callers should treat null as "do not navigate",
 * mirroring the NotificationBell behaviour.
 */
export function routeForNotificationType(type?: string | null): string | null {
  if (!type) return null;
  if (type === 'run.completed' || type === 'run.failed') return '/runs';
  // Review-column convention: approval asks live on the kanban board.
  if (type === 'approval.requested') return '/';
  if (type === 'knowledge.submitted') return '/knowledge';
  if (type === 'report.generated') return '/reports';
  return null;
}
