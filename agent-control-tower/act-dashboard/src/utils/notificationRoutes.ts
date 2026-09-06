/**
 * Maps a notification resourceType to the dashboard route that shows the
 * resource. Shared by NotificationBell (dropdown clicks) and Toast (the
 * aria.notification "View" action) so both stay in sync with the route map.
 *
 * Returns null when the type has no meaningful destination (e.g. reminders,
 * monitors, briefs) — callers should treat null as "do not navigate",
 * mirroring the NotificationBell behaviour.
 */
export function routeForNotificationResource(resourceType?: string | null): string | null {
  if (!resourceType) return null;
  if (resourceType === 'run.completed' || resourceType === 'run.failed') return '/runs';
  if (resourceType === 'approval.requested') return '/approvals';
  if (resourceType === 'knowledge.submitted') return '/knowledge';
  if (resourceType === 'report.generated') return '/reports';
  return null;
}
