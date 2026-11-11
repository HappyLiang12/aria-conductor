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

  // T45 Item 1 (spec:229-234, decision 2): the composer gate covers a PARKED
  // TURN — an approval or a clarification — not every undecided card. A memory
  // proposal is decided on its own card and parks nothing, so gating on it let
  // one conversation's undecided proposal disable another conversation's
  // composer, because the PENDING proposal inbox is global.
  it('does not gate the composer on a memory proposal, only on a parked ask', () => {
    const proposal = { id: 'p1', subjectType: 'FACT' as const, factKey: 'timezone', valueText: 'Asia/Shanghai' };
    let s = panelReducer(base, { type: 'proposalCreated', proposal });
    expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'pending' });
    expect(hasUnansweredCard(s)).toBe(false);

    // The parked ask still gates, and only until it is settled.
    s = panelReducer(s, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'remember_fact' });
    expect(hasUnansweredCard(s)).toBe(true);
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'denied' });
    expect(hasUnansweredCard(s)).toBe(false);

    s = panelReducer(s, { type: 'waitingInput', approvalId: 'c1', runId: 'r1', question: 'Which repo?' });
    expect(hasUnansweredCard(s)).toBe(true);
    s = panelReducer(s, { type: 'clarificationSettled', approvalId: 'c1', state: 'answered' });
    expect(hasUnansweredCard(s)).toBe(false);
    // The proposal card survived all of it, still the operator's to decide.
    expect(s.items.filter((i) => i.kind === 'proposal')).toHaveLength(1);
  });

  it('keeps the gate open when the global inbox restores another conversation\'s proposal', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'user', content: 'run my report' }), row({ role: 'assistant', content: 'done' })],
      approvals: [],
      proposals: [{ id: 'p-other', subjectType: 'ENTITY' as const, entityId: 'e-other', valueText: 'Acme' }],
    });
    expect(s.items.filter((i) => i.kind === 'proposal')).toHaveLength(1);
    expect(hasUnansweredCard(s)).toBe(false);
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

  it('renders a tool result row with no call row instead of dropping it', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'tool', content: 'orphaned output', runId: 'r9' })],
      approvals: [], proposals: [],
    });
    expect(s.items).toHaveLength(1);
    const strip = s.items[0];
    if (strip.kind !== 'activity') throw new Error('activity strip missing');
    expect(strip).toMatchObject({ kind: 'activity', turnId: 'r9', collapsed: true });
    expect(strip.tools).toHaveLength(1);
    expect(strip.tools[0]).toMatchObject({ name: '(tool output)', status: 'ok', result: 'orphaned output' });
  });

  it('renders a tool result row with no call row even when the run already has a strip', () => {
    // The run's call row and first result landed; a later result row's call row was
    // lost. Its output is still audited output and must not disappear behind the
    // strip the run already has.
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'tool_call: list_agents {}', runId: 'r1' }),
        row({ role: 'tool', content: '{"ok":true}', runId: 'r1' }),
        row({ role: 'tool', content: 'second output', runId: 'r1' }),
      ],
      approvals: [], proposals: [],
    });
    const strips = s.items.flatMap((item) => (item.kind === 'activity' ? [item] : []));
    expect(strips).toHaveLength(2);
    expect(strips[0].tools.map((t) => [t.name, t.status, t.result])).toEqual([['list_agents', 'ok', '{"ok":true}']]);
    expect(strips[1].tools.map((t) => [t.name, t.status, t.result])).toEqual([['(tool output)', 'ok', 'second output']]);
    expect(new Set(strips.map((strip) => strip.id)).size).toBe(2);
    expect(new Set(strips.map((strip) => strip.turnId)).size).toBe(2);
  });

  it('gives two orphan result rows of one run distinct identities', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'first output', runId: 'r9' }),
        row({ role: 'tool', content: 'second output', runId: 'r9' }),
      ],
      approvals: [], proposals: [],
    });
    const strips = s.items.flatMap((item) => (item.kind === 'activity' ? [item] : []));
    expect(strips).toHaveLength(2);
    expect(strips.map((strip) => strip.tools.map((t) => t.result))).toEqual([['first output'], ['second output']]);
    // Distinct ids: two strips of one run must not collide as React keys.
    expect(new Set(strips.map((strip) => strip.id)).size).toBe(2);
    expect(new Set(strips.map((strip) => strip.turnId)).size).toBe(2);
    // Distinct turn ids: the next restore's turnId dedupe must keep the second one.
    const again = panelReducer(s, { type: 'restore', conversationId: 'conv-1', timeline: [], approvals: [], proposals: [] });
    expect(again.items.flatMap((item) => (item.kind === 'activity' ? [item] : []))).toHaveLength(2);
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

describe('panelReducer restored tool status', () => {
  function onlyStrip(s: ReturnType<typeof panelReducer>) {
    const strip = s.items.find((i) => i.kind === 'activity');
    if (strip?.kind !== 'activity') throw new Error('activity strip missing');
    return strip;
  }

  it.each([
    ['COMPLETED', 'ok'],
    ['FAILED', 'error'],
    ['DENIED', 'error'],
    ['PENDING', 'running'],
    ['EXECUTING', 'running'],
  ])('restores a %s tool result as %s instead of claiming success', (toolStatus, expected) => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'tool_call: bash {"cmd":"ls"}' }),
        row({ role: 'tool', content: 'output', toolStatus }),
      ],
      approvals: [], proposals: [],
    });
    expect(onlyStrip(s).tools[0]).toMatchObject({ name: 'bash', status: expected, result: 'output' });
  });

  it('keeps ok for a result row the timeline says nothing about (an older backend)', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'tool_call: list_agents {}' }),
        row({ role: 'tool', content: '{"ok":true}' }),
      ],
      approvals: [], proposals: [],
    });
    expect(onlyStrip(s).tools[0]).toMatchObject({ name: 'list_agents', status: 'ok', result: '{"ok":true}' });
  });

  it('does not claim success for a status it does not recognise', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'tool_call: bash {}' }),
        row({ role: 'tool', content: 'output', toolStatus: 'CANCELLED' }),
      ],
      approvals: [], proposals: [],
    });
    // An unknown name is not evidence of success: leave the tool unsettled.
    expect(onlyStrip(s).tools[0]).toMatchObject({ name: 'bash', status: 'running', result: 'output' });
  });

  it('attributes each restored result by tool-call id, not by position', () => {
    // callA and callB overlap; callA finishes first. The positional rule would hand
    // callA's output to the last running tool (callB) and report it as a success.
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'tool', content: 'tool_call: read_file {"path":"a"}', runId: 'r1', toolCallId: 'callA' }),
        row({ role: 'tool', content: 'tool_call: write_file {"path":"b"}', runId: 'r1', toolCallId: 'callB' }),
        row({ role: 'tool', content: 'a-out', runId: 'r1', toolCallId: 'callA', toolStatus: 'COMPLETED' }),
        row({ role: 'tool', content: 'b-out', runId: 'r1', toolCallId: 'callB', toolStatus: 'FAILED' }),
      ],
      approvals: [], proposals: [],
    });
    expect(onlyStrip(s).tools.map((t) => [t.name, t.status, t.result])).toEqual([
      ['read_file', 'ok', 'a-out'],
      ['write_file', 'error', 'b-out'],
    ]);
  });

  it('restores an orphan result with a failing status as an error', () => {
    const s = panelReducer(base, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'tool', content: 'denied by policy', runId: 'r9', toolStatus: 'DENIED' })],
      approvals: [], proposals: [],
    });
    expect(onlyStrip(s).tools[0]).toMatchObject({ name: '(tool output)', status: 'error', result: 'denied by policy' });
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

  it('keeps a newer failure notice when the snapshot only shows an older failed turn', () => {
    // The older turn failed and is persisted; a second turn then fails live and the
    // restore lands before that turn's rows are written. The snapshot's only error
    // is the older turn, so the new notice must survive: deleting it would claim
    // nothing failed.
    let s = panelReducer(base, { type: 'turnFailed', id: 'live-fail-2', content: '⚠ 上一回合失敗：second', retryText: 'retry' });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [
        row({ role: 'user', content: 'first' }),
        row({ role: 'assistant', content: '回合執行失敗：first', error: true }),
      ],
      approvals: [], proposals: [],
    });
    const failures = s.items.filter((i) => i.kind === 'assistant' && i.error === true);
    expect(failures).toHaveLength(2);
    expect(failures.map((i) => (i.kind === 'assistant' ? i.content : ''))).toEqual([
      '回合執行失敗：first',
      '⚠ 上一回合失敗：second',
    ]);
  });

  it('keeps a live run-uncertain notice that offers no retry', () => {
    // AriaPanel's stream-error fallback with zero pending asks: a notice with
    // `noRetry: false` that the snapshot never holds (there is no failed-turn entry
    // for a run whose state is unknown). It must not be mistaken for the restored
    // failure.
    const uncertain = '⚠ Aria has not answered and no approval is pending — the run may still be alive in the background; check the Runs page.'
      + '\n\nConversation ID: `conv-1` (include this when reporting issues)';
    let s = panelReducer(base, { type: 'turnFailed', id: 'uncertain-live', content: uncertain, noRetry: false });
    s = panelReducer(s, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [row({ role: 'assistant', content: '回合執行失敗：boom', error: true })],
      approvals: [], proposals: [],
    });
    const failures = s.items.filter((i) => i.kind === 'assistant' && i.error === true);
    expect(failures).toHaveLength(2);
    expect(failures.map((i) => (i.kind === 'assistant' ? i.content : ''))).toEqual(['回合執行失敗：boom', uncertain]);
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

  // T45 Item 1 changed this pin: a pending proposal used to gate the composer,
  // and the PENDING proposal inbox is GLOBAL, so one conversation's undecided
  // proposal disabled another conversation's composer. The spec's gate is a
  // parked turn (spec:229-234); the card is still the operator's to decide.
  it('keeps a pending proposal decidable without gating the composer', () => {
    const proposal = { id: 'p1', subjectType: 'FACT' as const, factKey: 'timezone', valueText: 'Asia/Shanghai' };
    let s = panelReducer(base, { type: 'proposalCreated', proposal });
    expect(hasUnansweredCard(s)).toBe(false);
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

  it('settles a proposal decided elsewhere without claiming a verdict', () => {
    const proposal = { id: 'p1', subjectType: 'FACT' as const, factKey: 'timezone', valueText: 'Asia/Shanghai' };
    let s = panelReducer(base, { type: 'proposalCreated', proposal });
    // Never a gate, pending or settled (T45 Item 1).
    expect(hasUnansweredCard(s)).toBe(false);
    // The bounded PENDING inbox cannot tell an approval from a rejection, so the
    // item settles into a state of its own rather than into either verdict.
    s = panelReducer(s, { type: 'proposalSettled', proposalId: 'p1', state: 'decided-elsewhere' });
    const card = s.items.find((i) => i.kind === 'proposal');
    expect(card).toMatchObject({ state: 'decided-elsewhere' });
    expect(card).not.toMatchObject({ state: 'approved' });
    expect(card).not.toMatchObject({ state: 'rejected' });
    // The card's decision controls could only 409: the card is settled.
    expect(hasUnansweredCard(s)).toBe(false);
  });

  // The server re-proposes a decided entity id (a re-proposal returns the SAME
  // id to PENDING), so a card that only ever said "decided elsewhere" is a live
  // decision again. The operator's own verdict is not: an approved or rejected
  // card is theirs, and no listing may hand it back.
  describe('a re-proposed id reopens only what the operator did not decide', () => {
    const reproposed = { id: 'ent-1', subjectType: 'ENTITY' as const, entityId: 'ent-1', factKey: 'person:happy' };

    function settledAs(state: 'approved' | 'rejected' | 'decided-elsewhere') {
      const created = panelReducer(base, { type: 'proposalCreated', proposal: reproposed });
      return panelReducer(created, { type: 'proposalSettled', proposalId: 'ent-1', state });
    }

    it('reopens a decided-elsewhere card the PENDING listing lists again', () => {
      let s = settledAs('decided-elsewhere');
      expect(hasUnansweredCard(s)).toBe(false);

      s = panelReducer(s, {
        type: 'restore', conversationId: 'conv-1',
        timeline: [], approvals: [], proposals: [reproposed],
      });

      expect(s.items.filter((i) => i.kind === 'proposal')).toHaveLength(1);
      expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({
        proposalId: 'ent-1', subjectType: 'ENTITY', state: 'pending',
      });
      // Actionable again: the row is PENDING, so the card carries its controls
      // again — and, as always for a proposal, it does not gate the composer.
      expect(hasUnansweredCard(s)).toBe(false);
    });

    it('reopens a decided-elsewhere card on the live re-proposal signal', () => {
      let s = settledAs('decided-elsewhere');

      s = panelReducer(s, { type: 'proposalCreated', proposal: reproposed });

      expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'pending' });
      expect(hasUnansweredCard(s)).toBe(false);
    });

    it('never reopens an approved card, nor downgrades it to a verdict it is not', () => {
      let s = settledAs('approved');

      s = panelReducer(s, { type: 'proposalCreated', proposal: reproposed });
      s = panelReducer(s, {
        type: 'restore', conversationId: 'conv-1',
        timeline: [], approvals: [], proposals: [reproposed],
      });

      expect(s.items.filter((i) => i.kind === 'proposal')).toHaveLength(1);
      expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'approved' });
      expect(hasUnansweredCard(s)).toBe(false);
    });

    it('never reopens a rejected card', () => {
      let s = settledAs('rejected');

      s = panelReducer(s, { type: 'proposalCreated', proposal: reproposed });
      s = panelReducer(s, {
        type: 'restore', conversationId: 'conv-1',
        timeline: [], approvals: [], proposals: [reproposed],
      });

      expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'rejected' });
      expect(hasUnansweredCard(s)).toBe(false);
    });
  });

  // Task 37 Item 1: a FACT proposal whose parent entity is still PENDING cannot
  // be decided from its own card — the decision that owns it is the entity's. That
  // refusal is settled and non-retryable, so it needs a settled reason of its own:
  // "blocked on the entity decision" is neither a verdict (approved/rejected) nor
  // "decided elsewhere" (which would claim the proposal left the PENDING queue
  // while it is still there).
  it('settles a proposal blocked on its pending entity without inventing a verdict', () => {
    const proposal = { id: 'f1', subjectType: 'FACT' as const, entityId: 'ent-7', factKey: 'timezone', valueText: 'Asia/Shanghai' };
    let s = panelReducer(base, { type: 'proposalCreated', proposal });
    expect(hasUnansweredCard(s)).toBe(false);

    s = panelReducer(s, { type: 'proposalSettled', proposalId: 'f1', state: 'blocked-on-entity' });

    const card = s.items.find((i) => i.kind === 'proposal');
    expect(card).toMatchObject({ state: 'blocked-on-entity', entityId: 'ent-7' });
    expect(card).not.toMatchObject({ state: 'approved' });
    expect(card).not.toMatchObject({ state: 'rejected' });
    expect(card).not.toMatchObject({ state: 'decided-elsewhere' });
    // Settled for the card: its only remaining control could 409 forever. A
    // proposal never gates, so the composer was never its hostage either way.
    expect(hasUnansweredCard(s)).toBe(false);
  });

  // Reachability: this reason comes from ONE event — the card's own classification
  // of the parent-PENDING 409. No listing, re-delivery or other settle event may
  // produce it, and a listing that still carries the row must not reopen it: the
  // parent is still pending, so reopening would re-gate the composer on a decision
  // that can only be refused again.
  it('reaches blocked-on-entity only through its settle event, and never reopens it', () => {
    const proposal = { id: 'f1', subjectType: 'FACT' as const, entityId: 'ent-7', factKey: 'timezone', valueText: 'Asia/Shanghai' };
    let s = panelReducer(base, { type: 'proposalCreated', proposal });
    const settled = panelReducer(s, { type: 'proposalSettled', proposalId: 'f1', state: 'blocked-on-entity' });

    // Every other event leaves the pending card pending — only the settle event
    // above can name this reason.
    for (const event of [
      { type: 'proposalCreated' as const, proposal },
      { type: 'restore' as const, conversationId: 'conv-1', timeline: [], approvals: [], proposals: [proposal] },
    ]) {
      expect(panelReducer(s, event).items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'pending' });
    }
    for (const state of ['approved', 'rejected', 'decided-elsewhere'] as const) {
      const other = panelReducer(s, { type: 'proposalSettled', proposalId: 'f1', state });
      expect(other.items.find((i) => i.kind === 'proposal')).toMatchObject({ state });
    }

    // A listing that still carries the pending row keeps the blocked card blocked.
    s = panelReducer(settled, {
      type: 'restore', conversationId: 'conv-1',
      timeline: [], approvals: [], proposals: [proposal],
    });
    expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'blocked-on-entity' });
    expect(hasUnansweredCard(s)).toBe(false);

    // Nor may a live re-delivery of the same id reopen it.
    s = panelReducer(settled, { type: 'proposalCreated', proposal });
    expect(s.items.find((i) => i.kind === 'proposal')).toMatchObject({ state: 'blocked-on-entity' });
    expect(hasUnansweredCard(s)).toBe(false);
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

  it('never overwrites an already-decided approval with a later settle', () => {
    let s = panelReducer(base, { type: 'approvalRequired', approvalId: 'a1', runId: 'r1', toolName: 'bash' });
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'denied', reason: 'not on main' });
    s = panelReducer(s, { type: 'approvalSettled', approvalId: 'a1', state: 'expired', reason: 'Auto-rejected: approval expired' });
    expect(s.items.find((i) => i.kind === 'approval')).toMatchObject({ state: 'denied', reason: 'not on main' });
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
