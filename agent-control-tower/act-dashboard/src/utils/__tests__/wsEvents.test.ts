import { describe, it, expect } from 'vitest';
import { isRunLifecycleEvent, isKanbanEvent, isHousekeepingEvent } from '../wsEvents';

/**
 * S1 whitelist: high-frequency streaming events (run.progress) must NOT trigger
 * list-level query invalidation storms; only true lifecycle events do.
 */
describe('ws event whitelist (S1)', () => {
  it('treats only lifecycle run types as list-relevant', () => {
    expect(isRunLifecycleEvent('run.started')).toBe(true);
    expect(isRunLifecycleEvent('run.completed')).toBe(true);
    expect(isRunLifecycleEvent('run.failed')).toBe(true);
    expect(isRunLifecycleEvent('run.iteration')).toBe(true);
    // streaming / high-frequency types are excluded
    expect(isRunLifecycleEvent('run.progress')).toBe(false);
    expect(isRunLifecycleEvent('kanban.transitioned')).toBe(false);
    expect(isRunLifecycleEvent('agent.created')).toBe(false);
  });

  it('matches kanban-prefixed events', () => {
    expect(isKanbanEvent('kanban.created')).toBe(true);
    expect(isKanbanEvent('kanban.transitioned')).toBe(true);
    expect(isKanbanEvent('run.progress')).toBe(false);
    expect(isKanbanEvent('run.started')).toBe(false);
  });

  it('matches housekeeping events and keeps them out of lifecycle whitelist', () => {
    expect(isHousekeepingEvent('housekeeping.progress')).toBe(true);
    expect(isHousekeepingEvent('audit.HOUSEKEEPING_EXECUTED')).toBe(true);
    expect(isHousekeepingEvent('run.started')).toBe(false);
    // housekeeping events must never trigger list-level invalidation storms
    expect(isRunLifecycleEvent('housekeeping.progress')).toBe(false);
    expect(isRunLifecycleEvent('audit.HOUSEKEEPING_EXECUTED')).toBe(false);
  });
});
