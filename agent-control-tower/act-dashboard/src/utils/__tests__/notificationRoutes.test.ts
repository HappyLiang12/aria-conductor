import { describe, it, expect } from 'vitest';
import { routeForNotificationType } from '../notificationRoutes';

// Backend contract: notifications carry a fine-grained `type` (run.completed,
// approval.requested, ...) and a COARSE `resourceType` (RUN/APPROVAL/KNOWLEDGE/
// REPORT). Only `type` is a valid routing key — see NotificationTriggerListener
// and NotificationService.broadcast on the backend.
describe('routeForNotificationType', () => {
  it.each([
    ['run.completed', '/runs'],
    ['run.failed', '/runs'],
    ['knowledge.submitted', '/knowledge'],
    ['report.generated', '/reports'],
  ])('maps notification type %s to %s', (type, route) => {
    expect(routeForNotificationType(type)).toBe(route);
  });

  // The approvals page is retired (kanban HITL redesign, spec D4): the kanban
  // Review column on the overview is the single HITL surface, so approval
  // notifications route there. The "Waiting on you" summary card opens the
  // first review card; the bell itself does not know kanban ids.
  it('approval.requested routes to the overview review flow', () => {
    expect(routeForNotificationType('approval.requested')).toBe('/');
  });

  it.each(['reminder', 'monitor', 'brief'])('returns null for unmapped type %s', (type) => {
    expect(routeForNotificationType(type)).toBeNull();
  });

  it('returns null for missing or empty type', () => {
    expect(routeForNotificationType(undefined)).toBeNull();
    expect(routeForNotificationType(null)).toBeNull();
    expect(routeForNotificationType('')).toBeNull();
  });

  // Regression guard: the coarse resourceType values must NEVER resolve to a
  // route. Keying this map on resourceType was the original bug — the
  // fine-grained keys never matched, so navigation was a silent no-op.
  it.each(['RUN', 'APPROVAL', 'KNOWLEDGE', 'REPORT'])(
    'returns null for coarse resourceType value %s',
    (coarse) => {
      expect(routeForNotificationType(coarse)).toBeNull();
    },
  );
});
