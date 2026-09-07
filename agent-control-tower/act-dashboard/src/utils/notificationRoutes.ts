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
 * Returns null when the type has no meaningful destination (e.g. reminders,
 * monitors, briefs) — callers should treat null as "do not navigate",
 * mirroring the NotificationBell behaviour.
 */
export function routeForNotificationType(type?: string | null): string | null {
  if (!type) return null;
  if (type === 'run.completed' || type === 'run.failed') return '/runs';
  if (type === 'approval.requested') return '/approvals';
  if (type === 'knowledge.submitted') return '/knowledge';
  if (type === 'report.generated') return '/reports';
  return null;
}
