import { describe, expect, it } from 'vitest';
import type { TimelineEntry } from '../../api/ariaConversations';
import { emptyState, hasUnansweredCard, panelReducer } from '../ariaPanelState';

const base = emptyState('conv-1');

/** A timeline row with the minimum fields the reducer reads. */
function row(over: Partial<TimelineEntry> & { role: string; content: string }): TimelineEntry {
  return { timestamp: 't', runId: 'r1', ...over };
}

describe('panelReducer', () => {
  it('groups tool calls into one collapsed activity strip per turn', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 't1', name: 'list_agents' });
    s = panelReducer(s, { type: 'toolResult', turnId: 't1', name: 'list_agents', result: '{"ok":true}', ok: true });
    s = panelReducer(s, { type: 'toolCall', turnId: 't1', name: 'get_run' });
    const strips = s.items.filter((i) => i.kind === 'activity');
    expect(strips).toHaveLength(1);
    expect(strips[0].kind === 'activity' && strips[0].tools).toHaveLength(2);
    expect(strips[0].kind === 'activity' && strips[0].collapsed).toBe(true);
  });

  it('renders a restored tool row as activity, never as an assistant bubble', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        { role: 'user', content: 'hi', timestamp: 't', runId: 'r1' },
        { role: 'tool', content: 'tool_call: list_agents {}', timestamp: 't', runId: 'r1' },
        { role: 'assistant', content: 'hello', timestamp: 't', runId: 'r1' },
      ],
      approvals: [], proposals: [],
    });
    expect(s.items.map((i) => i.kind)).toEqual(['user', 'activity', 'assistant']);
  });

  it('is idempotent when the same ask arrives from SSE, WS and REST', () => {
    const ask = { type: 'approvalRequired' as const, approvalId: 'a1', runId: 'r1', toolName: 'remember_fact', riskTier: 'WRITE_LOCAL', toolPolicy: 'WORKER_WRITE' };
    let s = panelReducer(base, ask);
    s = panelReducer(s, ask);
    s = panelReducer(s, { type: 'restore', conversationId: 'conv-1', timeline: [], approvals: [{ id: 'a1', runId: 'r1', toolName: 'remember_fact', status: 'PENDING' } as never], proposals: [] });
    expect(s.items.filter((i) => i.kind === 'approval')).toHaveLength(1);
  });

  it('gates the composer while a card is unanswered and releases it once settled', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'remember_fact' });
    expect(hasUnansweredCard(s)).toBe(true);
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'approved' });
    expect(hasUnansweredCard(s)).toBe(false);
    expect(s.items.find((i) => i.kind === 'approval')).toMatchObject({ state: 'approved' });
  });

  it('keeps a settled card visible with its reason instead of removing it', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'expired', reason: 'Auto-rejected: approval expired' });
    expect(s.items.find((i) => i.kind === 'approval')).toMatchObject({ state: 'expired', reason: 'Auto-rejected: approval expired' });
  });
});

describe('panelReducer tool activity', () => {
  it('updates the last running tool with the same name and records its duration', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 't1', name: 'list_agents' });
    s = panelReducer(s, { type: 'toolCall', turnId: 't1', name: 'list_agents' });
    s = panelReducer(s, { type: 'toolResult', turnId: 't1', name: 'list_agents', result: '{"ok":true}', ok: true });
    const strip = s.items.find((i) => i.kind === 'activity');
    expect(strip).toMatchObject({ kind: 'activity', turnId: 't1' });
    if (strip?.kind !== 'activity') throw new Error('activity strip missing');
    expect(strip.tools).toHaveLength(2);
    // The last running entry wins; the earlier duplicate call is untouched.
    expect(strip.tools[0]).toMatchObject({ name: 'list_agents', status: 'running' });
    expect(strip.tools[1]).toMatchObject({ name: 'list_agents', status: 'ok', result: '{"ok":true}' });
    expect(typeof strip.tools[1].durationMs).toBe('number');
    expect(strip.tools[1].durationMs).toBeGreaterThanOrEqual(0);
  });

  it('marks a failed tool result as error', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 't1', name: 'bash', args: '{"cmd":"rm -rf /"}' });
    s = panelReducer(s, { type: 'toolResult', turnId: 't1', name: 'bash', result: 'denied', ok: false });
    const strip = s.items.find((i) => i.kind === 'activity');
    if (strip?.kind !== 'activity') throw new Error('activity strip missing');
    expect(strip.tools[0]).toMatchObject({ name: 'bash', args: '{"cmd":"rm -rf /"}', status: 'error', result: 'denied' });
  });

  it('ignores a tool result with no matching running tool (positive control above)', () => {
    const s = panelReducer(base, { type: 'toolResult', turnId: 'missing', name: 'list_agents', result: '{}', ok: true });
    expect(s.items).toEqual([]);
  });

  it('keeps strips of different turns apart', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 't1', name: 'list_agents' });
    s = panelReducer(s, { type: 'toolCall', turnId: 't2', name: 'get_run' });
    expect(s.items.filter((i) => i.kind === 'activity')).toHaveLength(2);
  });
});

describe('panelReducer restore parsing', () => {
  it('parses a call row into a tool and the following JSON row into its result', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'user', content: 'list the agents', timestamp: '2026-11-11T00:00:00.000Z' }),
        row({ role: 'tool', content: 'tool_call: list_agents {"limit":5}', timestamp: '2026-11-11T00:00:01.000Z' }),
        row({ role: 'tool', content: '{"ok":true,"agents":[]}', timestamp: '2026-11-11T00:00:03.000Z' }),
        row({ role: 'assistant', content: 'No agents are registered.', timestamp: '2026-11-11T00:00:04.000Z' }),
      ],
      approvals: [], proposals: [],
    });
    expect(s.items.map((i) => i.kind)).toEqual(['user', 'activity', 'assistant']);
    const strip = s.items[1];
    if (strip.kind !== 'activity') throw new Error('activity strip missing');
    expect(strip.tools).toHaveLength(1);
    expect(strip.tools[0]).toMatchObject({
      name: 'list_agents',
      args: '{"limit":5}',
      status: 'ok',
      result: '{"ok":true,"agents":[]}',
      durationMs: 2000,
    });
  });

  it('groups every tool row of one run into a single strip', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'tool_call: list_agents {}' }),
        row({ role: 'tool', content: '{"ok":true}' }),
        row({ role: 'tool', content: 'tool_call: get_run {"id":"r1"}' }),
        row({ role: 'tool', content: '{"ok":true,"status":"COMPLETED"}' }),
      ],
      approvals: [], proposals: [],
    });
    expect(s.items).toHaveLength(1);
    const strip = s.items[0];
    if (strip.kind !== 'activity') throw new Error('activity strip missing');
    expect(strip.tools.map((t) => [t.name, t.status])).toEqual([
      ['list_agents', 'ok'],
      ['get_run', 'ok'],
    ]);
  });

  it('restores a failed turn as an assistant bubble with its retry prompt', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'assistant', content: '回合執行失敗：boom', error: true, retryPrompt: 'retry me' })],
      approvals: [], proposals: [],
    });
    expect(s.items).toHaveLength(1);
    expect(s.items[0]).toMatchObject({
      kind: 'assistant',
      content: '回合執行失敗：boom',
      error: true,
      retryText: 'retry me',
      noRetry: false,
    });
  });

  it('replaces the items when a restore belongs to another conversation', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-2',
      timeline: [row({ role: 'user', content: 'other conversation' })],
      approvals: [], proposals: [],
    });
    expect(s.conversationId).toBe('conv-2');
    expect(s.items.map((i) => i.kind)).toEqual(['user']);
  });
});

describe('panelReducer restore merge', () => {
  it('keeps a live frame that arrived during a restore, exactly once', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'remember_fact', riskTier: 'WRITE_LOCAL' });
    // The REST snapshot predates the approval row: the timeline knows the run, the approvals list is empty.
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'remember it' })],
      approvals: [], proposals: [],
    });
    const cards = s.items.filter((i) => i.kind === 'approval');
    expect(cards).toHaveLength(1);
    expect(cards[0]).toMatchObject({ approvalId: 'a1', toolName: 'remember_fact', riskTier: 'WRITE_LOCAL', state: 'pending' });
    expect(s.items.map((i) => i.kind)).toEqual(['user', 'approval']);
  });

  it('does not duplicate an ask when the restore arrives after the live frame and carries it too', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'remember_fact' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'remember it' })],
      approvals: [{ id: 'a1', runId: 'r1', toolName: 'remember_fact', status: 'PENDING' } as never],
      proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'approval')).toHaveLength(1);
    expect(hasUnansweredCard(s)).toBe(true);
  });

  it('merges a pending approval only when its run is in the timeline', () => {
    const pending = { id: 'a1', runId: 'r-somewhere-else', toolName: 'bash', status: 'PENDING' } as never;
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'hi' })],
      approvals: [pending], proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'approval')).toHaveLength(0);
    expect(hasUnansweredCard(s)).toBe(false);
  });

  it('restores a settled card as settled and never resurrects it to pending', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'denied', reason: 'not on main' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'hi' })],
      approvals: [{ id: 'a1', runId: 'r1', toolName: 'bash', status: 'PENDING' } as never],
      proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'approval')).toHaveLength(1);
    expect(s.items.find((i) => i.kind === 'approval')).toMatchObject({ state: 'denied', reason: 'not on main' });
    expect(hasUnansweredCard(s)).toBe(false);
  });

  it('does not re-open a settled card when a duplicate live ask arrives late', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'denied', reason: 'no' });
    s = panelReducer(s, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    expect(s.items.find((i) => i.kind === 'approval')).toMatchObject({ state: 'denied' });
    expect(hasUnansweredCard(s)).toBe(false);
  });

  it('carries the tool policy through a live ask and a restored ask', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash', toolPolicy: 'WORKER_WRITE' });
    expect(s.items.find((i) => i.kind === 'approval')).toMatchObject({ toolPolicy: 'WORKER_WRITE' });
    const restored = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'hi' })],
      approvals: [{ id: 'a1', runId: 'r1', toolName: 'bash', status: 'PENDING', toolPolicy: 'WORKER_WRITE' } as never],
      proposals: [],
    });
    expect(restored.items.find((i) => i.kind === 'approval')).toMatchObject({ toolPolicy: 'WORKER_WRITE' });
  });

  it('keeps a live activity strip of a turn the timeline has not persisted yet', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 'live-turn', name: 'recall_memory' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'recall it' })],
      approvals: [], proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'activity')).toHaveLength(1);
    expect(s.items.map((i) => i.kind)).toEqual(['user', 'activity']);
  });

  it('does not duplicate an activity strip the timeline already holds', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 'r1', name: 'recall_memory' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'tool', content: 'tool_call: recall_memory {"q":"x"}', runId: 'r1' })],
      approvals: [], proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'activity')).toHaveLength(1);
  });

  it('does not duplicate the user bubble a restore re-delivers', () => {
    let s = panelReducer(base, { type: 'userSent', id: 'm1', content: 'hi', timestamp: '2026-11-11T00:00:00.000Z' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'hi' })],
      approvals: [], proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'user')).toHaveLength(1);
  });

  it('keeps a live settled tool when the snapshot only holds its call row', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 'r1', name: 'recall_memory', now: 1_000 });
    s = panelReducer(s, { type: 'toolResult', turnId: 'r1', name: 'recall_memory', result: '{"hits":1}', ok: true, now: 1_250 });
    // The snapshot was read before the result row landed: it has the call row only.
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'user', content: 'recall it' }),
        row({ role: 'tool', content: 'tool_call: recall_memory {"q":"x"}', timestamp: '2026-11-11T00:00:00.000Z' }),
      ],
      approvals: [], proposals: [],
    });
    const strips = s.items.filter((i) => i.kind === 'activity');
    expect(strips).toHaveLength(1);
    const strip = strips[0];
    if (strip.kind !== 'activity') throw new Error('activity strip missing');
    expect(strip.tools).toHaveLength(1);
    expect(strip.tools[0]).toMatchObject({
      name: 'recall_memory',
      status: 'ok',
      result: '{"hits":1}',
      durationMs: 250,
    });
  });

  it('shows one failure bubble when the live notice and the restored entry meet', () => {
    let s = panelReducer(base, { type: 'turnFailed', id: 'live-fail', content: '⚠ 上一回合失敗：boom', retryText: 'retry' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'user', content: 'do it' }),
        row({ role: 'assistant', content: '回合執行失敗：boom', error: true, retryPrompt: 'retry' }),
      ],
      approvals: [], proposals: [],
    });
    const failures = s.items.filter((i) => i.kind === 'assistant' && i.error === true);
    expect(failures).toHaveLength(1);
    expect(failures[0]).toMatchObject({ content: '回合執行失敗：boom' });
  });

  it('still carries a run-uncertain notice beside a restored failed turn', () => {
    let s = panelReducer(base, { type: 'turnFailed', id: 'uncertain', content: 'run uncertain', noRetry: true });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'assistant', content: '回合執行失敗：boom', error: true })],
      approvals: [], proposals: [],
    });
    const failures = s.items.filter((i) => i.kind === 'assistant' && i.error === true);
    expect(failures).toHaveLength(2);
    expect(failures.map((i) => (i.kind === 'assistant' ? i.content : ''))).toEqual(['回合執行失敗：boom', 'run uncertain']);
  });
});

describe('panelReducer clarifications, proposals and queue', () => {
  it('restores an unanswered clarification as a clarification card, not an approval', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'deploy it' })],
      approvals: [{ id: 'c1', runId: 'r1', status: 'PENDING', source: 'CLARIFICATION', content: 'Which environment?' } as never],
      proposals: [],
    });
    expect(s.items.filter((i) => i.kind === 'clarification')).toHaveLength(1);
    expect(s.items.filter((i) => i.kind === 'approval')).toHaveLength(0);
    expect(s.items.find((i) => i.kind === 'clarification')).toMatchObject({ approvalId: 'c1', question: 'Which environment?', state: 'pending' });
    expect(hasUnansweredCard(s)).toBe(true);
  });

  it('gates on a live clarification and releases it when answered', () => {
    let s = panelReducer(base, { type: 'waitingInput', approvalId: 'c1', runId: 'r1', question: 'Which repo?' });
    expect(hasUnansweredCard(s)).toBe(true);
    s = panelReducer(s, { type: 'clarificationSettled', approvalId: 'c1', state: 'answered' });
    expect(hasUnansweredCard(s)).toBe(false);
    expect(s.items.find((i) => i.kind === 'clarification')).toMatchObject({ state: 'answered' });
  });

  it('gates on a pending proposal and keeps it after approval', () => {
    const proposal = { id: 'p1', subjectType: 'FACT' as const, factKey: 'timezone', valueText: 'Asia/Shanghai' };
    let s = panelReducer(base, { type: 'proposalCreated', proposal });
    expect(hasUnansweredCard(s)).toBe(true);
    expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({
      proposalId: 'p1', subjectType: 'FACT', factKey: 'timezone', valueText: 'Asia/Shanghai', state: 'pending',
    });
    s = panelReducer(s, { type: 'proposalSettled', proposalId: 'p1', state: 'approved' });
    expect(hasUnansweredCard(s)).toBe(false);
    expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'approved' });
  });

  it('restores a pending proposal once and never downgrades a settled one', () => {
    const proposal = { id: 'p1', subjectType: 'ENTITY' as const, entityId: 'e1', valueText: 'Acme' };
    let s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [], approvals: [], proposals: [proposal],
    });
    expect(s.items.filter((i) => i.kind === 'proposal')).toHaveLength(1);
    s = panelReducer(s, { type: 'proposalSettled', proposalId: 'p1', state: 'rejected' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [], approvals: [], proposals: [proposal],
    });
    expect(s.items.filter((i) => i.kind === 'proposal')).toHaveLength(1);
    expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'rejected' });
  });

  it('replaces the single queued item and removes it on an empty list', () => {
    let s = panelReducer(base, { type: 'queuedRuns', runIds: ['r1', 'r2'] });
    expect(s.items.filter((i) => i.kind === 'queued')).toHaveLength(1);
    expect(s.items.find((i) => i.kind === 'queued')).toMatchObject({ runIds: ['r1', 'r2'] });
    s = panelReducer(s, { type: 'queuedRuns', runIds: ['r3'] });
    expect(s.items.filter((i) => i.kind === 'queued')).toHaveLength(1);
    expect(s.items.find((i) => i.kind === 'queued')).toMatchObject({ runIds: ['r3'] });
    s = panelReducer(s, { type: 'queuedRuns', runIds: [] });
    expect(s.items.filter((i) => i.kind === 'queued')).toHaveLength(0);
    expect(hasUnansweredCard(s)).toBe(false);
  });

  it('is a no-op when a settle event names an unknown approval', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    const after = panelReducer(s, { type: 'approvalSettled', approvalId: 'unknown', state: 'approved' });
    expect(after.items).toEqual(s.items);
    expect(hasUnansweredCard(after)).toBe(true);
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'approved' });
    expect(hasUnansweredCard(s)).toBe(false);
  });

  it('records a live failed turn as an assistant bubble with a retry prompt', () => {
    const s = panelReducer(base, { type: 'turnFailed', id: 'm1', content: '回合執行失敗：boom', retryText: 'redo it' });
    expect(s.items).toHaveLength(1);
    expect(s.items[0]).toMatchObject({ kind: 'assistant', content: '回合執行失敗：boom', error: true, retryText: 'redo it', noRetry: false });
  });
});

describe('panelReducer live clock', () => {
  it('reads the supplied clock for live timestamps instead of the wall clock', () => {
    let s = panelReducer(base, { type: 'toolCall', turnId: 't1', name: 'list_agents', now: 1_000 });
    const started = s.items.find((i) => i.kind === 'activity');
    if (started?.kind !== 'activity') throw new Error('activity strip missing');
    expect(started.tools[0].startedAt).toBe(1_000);

    s = panelReducer(s, { type: 'toolResult', turnId: 't1', name: 'list_agents', result: '{}', ok: true, now: 1_750 });
    const settled = s.items.find((i) => i.kind === 'activity');
    if (settled?.kind !== 'activity') throw new Error('activity strip missing');
    expect(settled.tools[0].durationMs).toBe(750);

    const failed = panelReducer(base, { type: 'turnFailed', id: 'm1', content: 'boom', now: 0 });
    expect(failed.items[0]).toMatchObject({ timestamp: '1970-01-01T00:00:00.000Z' });
  });
});
