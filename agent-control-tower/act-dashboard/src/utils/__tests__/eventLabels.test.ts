import { describe, it, expect } from 'vitest';
import { eventLabel } from '../eventLabels';

describe('eventLabel', () => {
  it('maps run lifecycle event types to human-readable labels', () => {
    expect(eventLabel('run.started')).toBe('Run Started');
    expect(eventLabel('run.completed')).toBe('Run Completed');
    expect(eventLabel('run.iteration')).toBe('Agent Iteration');
    expect(eventLabel('run.failed')).toBe('Run Failed');
  });

  it('maps approval, agent and tool event types', () => {
    expect(eventLabel('approval.requested')).toBe('Approval Needed');
    expect(eventLabel('approval.decided')).toBe('Approval Decided');
    expect(eventLabel('agent.created')).toBe('Agent Created');
    expect(eventLabel('agent.retired')).toBe('Agent Retired');
    expect(eventLabel('tool_call')).toBe('Tool Call');
    expect(eventLabel('tool_result')).toBe('Tool Result');
    expect(eventLabel('thinking')).toBe('Thinking');
  });

  it('maps kanban, knowledge, report and workflow event types', () => {
    expect(eventLabel('kanban.created')).toBe('Kanban Item Created');
    expect(eventLabel('kanban.transitioned')).toBe('Kanban Item Transitioned');
    expect(eventLabel('knowledge.submitted')).toBe('Knowledge Submitted');
    expect(eventLabel('knowledge.approved')).toBe('Knowledge Approved');
    expect(eventLabel('knowledge.retired')).toBe('Knowledge Retired');
    expect(eventLabel('report.generated')).toBe('Report Generated');
    expect(eventLabel('report.amended')).toBe('Report Amended');
    expect(eventLabel('workflow.started')).toBe('Workflow Started');
    expect(eventLabel('workflow.completed')).toBe('Workflow Completed');
    expect(eventLabel('workflow.advanced')).toBe('Workflow Advanced');
  });

  it('falls back to the raw type for unknown events', () => {
    expect(eventLabel('some.unknown.event')).toBe('some.unknown.event');
  });

  it('is case-sensitive: non-exact keys are returned as-is', () => {
    expect(eventLabel('Run.Started')).toBe('Run.Started');
    expect(eventLabel('RUN.STARTED')).toBe('RUN.STARTED');
  });

  it('returns an empty string unchanged', () => {
    expect(eventLabel('')).toBe('');
  });
});
