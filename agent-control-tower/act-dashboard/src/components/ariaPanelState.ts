import type { TimelineEntry } from '../api/ariaConversations';
import type { Approval } from '../types';

/**
 * The Aria panel's conversation state, as a pure reducer.
 *
 * Three planes feed the panel (SSE per turn, WebSocket for background changes,
 * REST for reconciliation) and they overlap: the same ask arrives from all
 * three. The merge, not any single plane, is authoritative, so every card is
 * keyed by its ask id and every strip by its turn id, and re-delivery is a
 * no-op. Restored history and live frames share this state so a pending card
 * survives a reconnect instead of disappearing behind a raw JSON bubble.
 *
 * Pure in the React sense: the state is never mutated, and no event depends on
 * anything but the state and the event. The three events that a live plane has
 * to timestamp (a tool's start, its result, a failed turn) accept an optional
 * `now` clock so a caller — and a test — can supply one; it defaults to
 * `Date.now()` inside the reducer.
 */

export type ToolActivity = { name: string; status: 'running' | 'ok' | 'error'; startedAt: number; durationMs?: number; args?: string; result?: string };
export type PanelItem =
  | { kind: 'user'; id: string; content: string; timestamp: string }
  | { kind: 'assistant'; id: string; content: string; timestamp: string; error?: boolean; retryText?: string; noRetry?: boolean }
  | { kind: 'activity'; id: string; turnId: string; tools: ToolActivity[]; collapsed: boolean }
  | { kind: 'approval'; id: string; approvalId: string; runId: string; toolName: string; riskTier?: string; toolPolicy?: string; args?: string; state: 'pending' | 'approved' | 'denied' | 'expired'; reason?: string }
  | { kind: 'clarification'; id: string; approvalId: string; runId: string; question: string; state: 'pending' | 'answered' | 'expired' }
  | { kind: 'proposal'; id: string; proposalId: string; subjectType: 'FACT' | 'ENTITY' | 'RELATION'; entityId?: string; factKey?: string; valueText?: string; sensitivity?: string; state: 'pending' | 'approved' | 'rejected' }
  | { kind: 'queued'; id: string; runIds: string[] };
export type PanelState = { items: PanelItem[]; conversationId: string };
export type PanelEvent =
  | { type: 'restore'; conversationId: string; timeline: TimelineEntry[]; approvals: Approval[]; proposals: MemoryProposalSummary[] }
  | { type: 'userSent'; id: string; content: string; timestamp: string }
  | { type: 'toolCall'; turnId: string; name: string; args?: string; now?: number }
  | { type: 'toolResult'; turnId: string; name: string; result: string; ok: boolean; now?: number }
  | { type: 'assistantMessage'; id: string; content: string; timestamp: string }
  | { type: 'approvalRequired'; approvalId: string; runId: string; toolName: string; riskTier?: string; toolPolicy?: string; args?: string }
  | { type: 'approvalSettled'; approvalId: string; state: 'approved' | 'denied' | 'expired'; reason?: string }
  | { type: 'waitingInput'; approvalId: string; runId: string; question: string }
  | { type: 'clarificationSettled'; approvalId: string; state: 'answered' | 'expired' }
  | { type: 'proposalCreated'; proposal: MemoryProposalSummary }
  | { type: 'proposalSettled'; proposalId: string; state: 'approved' | 'rejected' }
  | { type: 'queuedRuns'; runIds: string[] }
  | { type: 'turnFailed'; id: string; content: string; retryText?: string; noRetry?: boolean; now?: number };
export type MemoryProposalSummary = { id: string; subjectType: 'FACT' | 'ENTITY' | 'RELATION'; entityId?: string; factKey?: string; valueText?: string; sensitivity?: string };

type RestoreEvent = Extract<PanelEvent, { type: 'restore' }>;
type ActivityItem = Extract<PanelItem, { kind: 'activity' }>;
type ApprovalItem = Extract<PanelItem, { kind: 'approval' }>;
type ClarificationItem = Extract<PanelItem, { kind: 'clarification' }>;
type ProposalItem = Extract<PanelItem, { kind: 'proposal' }>;

/** The backend's trajectory rows prefix a tool invocation with this literal. */
const TOOL_CALL_PREFIX = 'tool_call:';
/** A tool result row whose call row is missing still names itself in the strip. */
const ORPHAN_TOOL_NAME = '(tool output)';
/** The single queued indicator is keyed by its kind, not by a run. */
const QUEUED_ID = 'queued';

export function emptyState(conversationId: string): PanelState {
  return { items: [], conversationId };
}

/** True while the conversation holds a decision the operator has not made yet. */
export function hasUnansweredCard(state: PanelState): boolean {
  return state.items.some(
    (item) =>
      (item.kind === 'approval' || item.kind === 'clarification' || item.kind === 'proposal')
      && item.state === 'pending',
  );
}

export function panelReducer(state: PanelState, event: PanelEvent): PanelState {
  switch (event.type) {
    case 'restore':
      return restore(state, event);
    case 'userSent':
      return appendMessage(state, { kind: 'user', id: event.id, content: event.content, timestamp: event.timestamp });
    case 'assistantMessage':
      return appendMessage(state, { kind: 'assistant', id: event.id, content: event.content, timestamp: event.timestamp });
    case 'turnFailed':
      return appendMessage(state, {
        kind: 'assistant',
        id: event.id,
        content: event.content,
        timestamp: new Date(event.now ?? Date.now()).toISOString(),
        error: true,
        ...(event.retryText !== undefined ? { retryText: event.retryText } : {}),
        noRetry: event.noRetry === true,
      });
    case 'toolCall':
      return applyToolCall(state, event);
    case 'toolResult':
      return applyToolResult(state, event);
    case 'approvalRequired':
      return upsertApproval(state, {
        approvalId: event.approvalId,
        runId: event.runId,
        toolName: event.toolName,
        riskTier: event.riskTier,
        toolPolicy: event.toolPolicy,
        args: event.args,
      });
    case 'approvalSettled':
      return settleApproval(state, event.approvalId, event.state, event.reason);
    case 'waitingInput':
      return upsertClarification(state, {
        approvalId: event.approvalId,
        runId: event.runId,
        question: event.question,
      });
    case 'clarificationSettled':
      return settleClarification(state, event.approvalId, event.state);
    case 'proposalCreated':
      return upsertProposal(state, { ...event.proposal, state: 'pending' });
    case 'proposalSettled':
      return settleProposal(state, event.proposalId, event.state);
    case 'queuedRuns':
      return setQueued(state, event.runIds);
    default:
      return state;
  }
}

// === messages ===

function appendMessage(state: PanelState, item: PanelItem): PanelState {
  return { items: [...state.items, item], conversationId: state.conversationId };
}

// === tool activity ===

function applyToolCall(state: PanelState, event: Extract<PanelEvent, { type: 'toolCall' }>): PanelState {
  const index = activityIndex(state.items, event.turnId);
  const tool: ToolActivity = {
    name: event.name,
    status: 'running',
    startedAt: event.now ?? Date.now(),
    ...(event.args !== undefined ? { args: event.args } : {}),
  };
  if (index < 0) {
    const strip: ActivityItem = {
      kind: 'activity',
      id: activityId(event.turnId),
      turnId: event.turnId,
      tools: [tool],
      collapsed: true,
    };
    return { items: [...state.items, strip], conversationId: state.conversationId };
  }
  const strip = state.items[index] as ActivityItem;
  return replaceItem(state, index, { ...strip, tools: [...strip.tools, tool] });
}

function applyToolResult(state: PanelState, event: Extract<PanelEvent, { type: 'toolResult' }>): PanelState {
  const index = activityIndex(state.items, event.turnId);
  if (index < 0) return state;
  const strip = state.items[index] as ActivityItem;
  const target = lastRunningToolIndex(strip.tools, event.name);
  if (target < 0) return state; // a result with no live call is not a tool: keep the strip truthful
  const tool = strip.tools[target];
  const settled: ToolActivity = {
    ...tool,
    status: event.ok ? 'ok' : 'error',
    result: event.result,
    durationMs: Math.max(0, (event.now ?? Date.now()) - tool.startedAt),
  };
  const tools = strip.tools.slice();
  tools[target] = settled;
  return replaceItem(state, index, { ...strip, tools });
}

function lastRunningToolIndex(tools: ToolActivity[], name: string): number {
  for (let i = tools.length - 1; i >= 0; i -= 1) {
    if (tools[i].name === name && tools[i].status === 'running') return i;
  }
  return -1;
}

function activityIndex(items: PanelItem[], turnId: string): number {
  for (let i = items.length - 1; i >= 0; i -= 1) {
    const item = items[i];
    if (item.kind === 'activity' && item.turnId === turnId) return i;
  }
  return -1;
}

function activityId(turnId: string): string {
  return `activity:${turnId}`;
}

// === approval cards ===

type ApprovalPatch = {
  approvalId: string;
  runId: string;
  toolName: string;
  riskTier?: string;
  toolPolicy?: string;
  args?: string;
  reason?: string;
  state?: ApprovalItem['state'];
};

function upsertApproval(state: PanelState, patch: ApprovalPatch): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'approval' && item.approvalId === patch.approvalId);
  if (index < 0) {
    const card: ApprovalItem = {
      kind: 'approval',
      id: `approval:${patch.approvalId}`,
      approvalId: patch.approvalId,
      runId: patch.runId,
      toolName: patch.toolName,
      ...(patch.riskTier !== undefined ? { riskTier: patch.riskTier } : {}),
      ...(patch.toolPolicy !== undefined ? { toolPolicy: patch.toolPolicy } : {}),
      ...(patch.args !== undefined ? { args: patch.args } : {}),
      ...(patch.reason !== undefined ? { reason: patch.reason } : {}),
      state: patch.state ?? 'pending',
    };
    return { items: [...state.items, card], conversationId: state.conversationId };
  }
  const existing = state.items[index] as ApprovalItem;
  // A settled card is the operator's decision; a re-delivered ask must not reopen it.
  if (existing.state !== 'pending') return state;
  const merged: ApprovalItem = {
    ...existing,
    toolName: patch.toolName,
    ...(patch.riskTier !== undefined ? { riskTier: patch.riskTier } : {}),
    ...(patch.toolPolicy !== undefined ? { toolPolicy: patch.toolPolicy } : {}),
    ...(patch.args !== undefined ? { args: patch.args } : {}),
    ...(patch.reason !== undefined ? { reason: patch.reason } : {}),
  };
  return replaceItem(state, index, merged);
}

function settleApproval(
  state: PanelState,
  approvalId: string,
  nextState: ApprovalItem['state'],
  reason?: string,
): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'approval' && item.approvalId === approvalId);
  if (index < 0) return state;
  const existing = state.items[index] as ApprovalItem;
  // A decided card is the operator's decision; a late or racing settle (the expiry
  // sweeper) must not rewrite it. Mirrors the guard in upsertApproval.
  if (existing.state !== 'pending') return state;
  return replaceItem(state, index, {
    ...existing,
    state: nextState,
    ...(reason !== undefined ? { reason } : {}),
  });
}

// === clarification cards ===

function upsertClarification(
  state: PanelState,
  patch: { approvalId: string; runId: string; question: string },
): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'clarification' && item.approvalId === patch.approvalId);
  if (index < 0) {
    const card: ClarificationItem = {
      kind: 'clarification',
      id: `clarification:${patch.approvalId}`,
      approvalId: patch.approvalId,
      runId: patch.runId,
      question: patch.question,
      state: 'pending',
    };
    return { items: [...state.items, card], conversationId: state.conversationId };
  }
  const existing = state.items[index] as ClarificationItem;
  if (existing.state !== 'pending') return state;
  return replaceItem(state, index, { ...existing, question: patch.question });
}

function settleClarification(
  state: PanelState,
  approvalId: string,
  nextState: ClarificationItem['state'],
): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'clarification' && item.approvalId === approvalId);
  if (index < 0) return state;
  return replaceItem(state, index, { ...(state.items[index] as ClarificationItem), state: nextState });
}

// === proposal cards ===

type ProposalPatch = MemoryProposalSummary & { state: ProposalItem['state'] };

function upsertProposal(state: PanelState, patch: ProposalPatch): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'proposal' && item.proposalId === patch.id);
  const fields = {
    subjectType: patch.subjectType,
    ...(patch.entityId !== undefined ? { entityId: patch.entityId } : {}),
    ...(patch.factKey !== undefined ? { factKey: patch.factKey } : {}),
    ...(patch.valueText !== undefined ? { valueText: patch.valueText } : {}),
    ...(patch.sensitivity !== undefined ? { sensitivity: patch.sensitivity } : {}),
  };
  if (index < 0) {
    const card: ProposalItem = {
      kind: 'proposal',
      id: `proposal:${patch.id}`,
      proposalId: patch.id,
      ...fields,
      state: patch.state,
    };
    return { items: [...state.items, card], conversationId: state.conversationId };
  }
  const existing = state.items[index] as ProposalItem;
  // A decided proposal is never reopened by a re-delivered creation signal.
  if (existing.state !== 'pending') return state;
  return replaceItem(state, index, { ...existing, ...fields });
}

function settleProposal(state: PanelState, proposalId: string, nextState: ProposalItem['state']): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'proposal' && item.proposalId === proposalId);
  if (index < 0) return state;
  return replaceItem(state, index, { ...(state.items[index] as ProposalItem), state: nextState });
}

// === queued indicator ===

function setQueued(state: PanelState, runIds: string[]): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'queued');
  if (runIds.length === 0) {
    if (index < 0) return state;
    return { items: state.items.filter((_, i) => i !== index), conversationId: state.conversationId };
  }
  const item: PanelItem = { kind: 'queued', id: QUEUED_ID, runIds: [...runIds] };
  if (index < 0) return { items: [...state.items, item], conversationId: state.conversationId };
  return replaceItem(state, index, item);
}

// === restore ===

/**
 * Reconcile against a REST snapshot. The snapshot is the durable record, but it
 * is taken at a different instant than the live planes, so it is merged rather
 * than assigned: a frame that arrived while the snapshot was in flight survives
 * it, and nothing the snapshot re-delivers is drawn twice.
 */
function restore(state: PanelState, event: RestoreEvent): PanelState {
  const fromTimeline = itemsFromTimeline(event.timeline);
  const prior = state.conversationId === event.conversationId ? state.items : [];
  const items = mergeCarriedStrips(fromTimeline, carryOver(prior, fromTimeline));
  const runIds = new Set(event.timeline.map((entry) => entry.runId).filter((runId) => !!runId));
  const askIds = new Set(
    items.flatMap((item) => (item.kind === 'approval' || item.kind === 'clarification' ? [item.approvalId] : [])),
  );
  const proposalIds = new Set(
    items.flatMap((item) => (item.kind === 'proposal' ? [item.proposalId] : [])),
  );

  for (const approval of event.approvals) {
    if (approval.status !== 'PENDING' || !runIds.has(approval.runId)) continue;
    if (askIds.has(approval.id)) continue; // already drawn, live or restored: a card is keyed by its ask id
    askIds.add(approval.id);
    items.push(isClarificationAsk(approval) ? clarificationFromApproval(approval) : approvalFromApproval(approval));
  }

  for (const proposal of event.proposals) {
    if (proposalIds.has(proposal.id)) continue;
    proposalIds.add(proposal.id);
    items.push({
      kind: 'proposal',
      id: `proposal:${proposal.id}`,
      proposalId: proposal.id,
      subjectType: proposal.subjectType,
      ...(proposal.entityId !== undefined ? { entityId: proposal.entityId } : {}),
      ...(proposal.factKey !== undefined ? { factKey: proposal.factKey } : {}),
      ...(proposal.valueText !== undefined ? { valueText: proposal.valueText } : {}),
      ...(proposal.sensitivity !== undefined ? { sensitivity: proposal.sensitivity } : {}),
      state: 'pending',
    });
  }

  return { items, conversationId: event.conversationId };
}

/**
 * Prior items the snapshot does not represent. A live frame for a turn the
 * timeline has not persisted yet has no snapshot counterpart, so it is kept.
 * A strip the snapshot does hold is kept too and merged by the caller: the
 * snapshot may have been read before the result row landed, so only its call
 * rows are visible and the live strip still holds the settled result.
 */
function carryOver(prior: PanelItem[], base: PanelItem[]): PanelItem[] {
  const askIds = new Set(
    base.flatMap((item) => (item.kind === 'approval' || item.kind === 'clarification' ? [item.approvalId] : [])),
  );
  const proposalIds = new Set(base.flatMap((item) => (item.kind === 'proposal' ? [item.proposalId] : [])));
  const messageBudget = new Map<string, number>();
  for (const item of base) {
    if (item.kind === 'user' || item.kind === 'assistant') {
      const key = messageKey(item);
      messageBudget.set(key, (messageBudget.get(key) ?? 0) + 1);
    }
  }
  const carriedTurnIds = new Set<string>();
  const baseHasError = base.some((item) => item.kind === 'assistant' && item.error === true);
  let queuedDrawn = base.some((item) => item.kind === 'queued');

  const carried: PanelItem[] = [];
  for (const item of prior) {
    if (item.kind === 'activity') {
      if (carriedTurnIds.has(item.turnId)) continue;
      carriedTurnIds.add(item.turnId);
    } else if (item.kind === 'approval' || item.kind === 'clarification') {
      if (askIds.has(item.approvalId)) continue;
      askIds.add(item.approvalId);
    } else if (item.kind === 'proposal') {
      if (proposalIds.has(item.proposalId)) continue;
      proposalIds.add(item.proposalId);
    } else if (item.kind === 'queued') {
      if (queuedDrawn) continue;
      queuedDrawn = true;
    } else {
      // The timeline re-delivers messages by content; identical text sent twice
      // keeps both, because the budget counts occurrences rather than presence.
      const key = messageKey(item);
      const budget = messageBudget.get(key) ?? 0;
      if (budget > 0) {
        messageBudget.set(key, budget - 1);
        continue;
      }
      // A failed turn is re-told in different words by the snapshot, so the budget
      // cannot consume the live notice; once the snapshot shows a failure, the live
      // duplicate is dropped. A run-uncertain notice (noRetry) is not in the
      // snapshot and still carries.
      if (item.kind === 'assistant' && item.error === true && item.noRetry !== true && baseHasError) continue;
    }
    carried.push(item);
  }
  return carried;
}

function messageKey(item: Extract<PanelItem, { kind: 'user' | 'assistant' }>): string {
  if (item.kind === 'assistant') return `assistant\u0000${item.error === true ? 'error' : 'ok'}\u0000${item.content}`;
  return `user\u0000${item.content}`;
}

/**
 * Fold a carried strip into the restored strip for the same turn. The snapshot
 * cannot show a result written after it was read, so per tool the live plane's
 * settled state wins over a restored `running` one; restored-only tools are
 * kept, and a carried tool the snapshot lacks is kept too. The carried strip is
 * dropped once its tools are merged, never whole.
 */
function mergeCarriedStrips(base: PanelItem[], carried: PanelItem[]): PanelItem[] {
  const merged = base.slice();
  const rest: PanelItem[] = [];
  for (const item of carried) {
    if (item.kind === 'activity') {
      const at = merged.findIndex((candidate) => candidate.kind === 'activity' && candidate.turnId === item.turnId);
      if (at >= 0) {
        const target = merged[at] as ActivityItem;
        merged[at] = { ...target, tools: mergeTools(target.tools, item.tools) };
        continue;
      }
    }
    rest.push(item);
  }
  return [...merged, ...rest];
}

/** Per-name, occurrence-ordered merge of two strips' tools. */
function mergeTools(restored: ToolActivity[], carried: ToolActivity[]): ToolActivity[] {
  const used = new Array<boolean>(carried.length).fill(false);
  const merged = restored.map((tool) => {
    const match = carried.findIndex((candidate, index) => !used[index] && candidate.name === tool.name);
    if (match < 0) return tool;
    used[match] = true;
    const prior = carried[match];
    // A settled prior tool knows the outcome; a running prior one does not.
    return prior.status === 'running' ? tool : prior;
  });
  carried.forEach((tool, index) => {
    if (!used[index]) merged.push(tool);
  });
  return merged;
}

function isClarificationAsk(approval: Approval): boolean {
  return approval.source === 'CLARIFICATION';
}

function approvalFromApproval(approval: Approval): ApprovalItem {
  // `toolPolicy` is added to Approval by the governance lane; read it structurally so
  // this module compiles and carries the value without depending on that field existing.
  const toolPolicy = (approval as { toolPolicy?: string }).toolPolicy;
  return {
    kind: 'approval',
    id: `approval:${approval.id}`,
    approvalId: approval.id,
    runId: approval.runId,
    toolName: approval.toolName ?? 'unknown tool',
    ...(approval.riskTier !== undefined ? { riskTier: approval.riskTier } : {}),
    ...(toolPolicy !== undefined ? { toolPolicy } : {}),
    ...(approval.arguments !== undefined ? { args: approval.arguments } : {}),
    state: 'pending',
  };
}

function clarificationFromApproval(approval: Approval): ClarificationItem {
  return {
    kind: 'clarification',
    id: `clarification:${approval.id}`,
    approvalId: approval.id,
    runId: approval.runId,
    question: approval.content?.trim() || approval.reason || '',
    state: 'pending',
  };
}

/**
 * The timeline half of a restore. A failed-turn entry is a synthetic assistant
 * bubble carrying its retry prompt; user and assistant rows are messages; every
 * other role is a tool row, which must become activity rather than a JSON bubble.
 * A run is the only turn identity the timeline carries, so tool rows group by run.
 */
function itemsFromTimeline(timeline: TimelineEntry[]): PanelItem[] {
  const items: PanelItem[] = [];
  timeline.forEach((entry, index) => {
    const timestamp = entry.timestamp ?? '';
    if (entry.error) {
      items.push({
        kind: 'assistant',
        id: `assistant:${entry.runId}:${index}`,
        content: entry.content,
        timestamp,
        error: true,
        ...(entry.retryPrompt !== undefined ? { retryText: entry.retryPrompt } : {}),
        noRetry: false,
      });
      return;
    }
    if (entry.role === 'user') {
      items.push({ kind: 'user', id: `user:${entry.runId}:${index}`, content: entry.content, timestamp });
      return;
    }
    if (entry.role === 'assistant') {
      items.push({ kind: 'assistant', id: `assistant:${entry.runId}:${index}`, content: entry.content, timestamp });
      return;
    }

    const turnId = entry.runId || `row-${index}`;
    const startedAt = parseTime(timestamp);
    const call = parseToolCall(entry.content);
    const at = activityIndex(items, turnId);

    if (call) {
      const tool: ToolActivity = {
        name: call.name,
        status: 'running',
        startedAt,
        ...(call.args !== undefined ? { args: call.args } : {}),
      };
      if (at < 0) {
        items.push({ kind: 'activity', id: activityId(turnId), turnId, tools: [tool], collapsed: true });
      } else {
        const strip = items[at] as ActivityItem;
        items[at] = { ...strip, tools: [...strip.tools, tool] };
      }
      return;
    }

    // Any other tool row is the result of the tool still running in this run.
    // A result whose call row never landed is still audited output: render it
    // as its own one-tool strip instead of dropping it.
    if (at < 0) {
      items.push({
        kind: 'activity',
        id: activityId(turnId),
        turnId,
        tools: [{ name: ORPHAN_TOOL_NAME, status: 'ok', startedAt, result: entry.content }],
        collapsed: true,
      });
      return;
    }
    const strip = items[at] as ActivityItem;
    let target = -1;
    for (let i = strip.tools.length - 1; i >= 0; i -= 1) {
      if (strip.tools[i].status === 'running') {
        target = i;
        break;
      }
    }
    if (target < 0) return;
    const tool = strip.tools[target];
    const durationMs = tool.startedAt > 0 && startedAt >= tool.startedAt ? startedAt - tool.startedAt : undefined;
    const tools = strip.tools.slice();
    tools[target] = {
      ...tool,
      status: 'ok',
      result: entry.content,
      ...(durationMs !== undefined ? { durationMs } : {}),
    };
    items[at] = { ...strip, tools };
  });
  return items;
}

function parseToolCall(content: string): { name: string; args?: string } | null {
  const text = content.trim();
  if (!text.startsWith(TOOL_CALL_PREFIX)) return null;
  const rest = text.slice(TOOL_CALL_PREFIX.length).trim();
  if (!rest) return null;
  const separator = rest.search(/\s/);
  if (separator < 0) return { name: rest };
  const name = rest.slice(0, separator);
  const args = rest.slice(separator + 1).trim();
  return args ? { name, args } : { name };
}

function parseTime(value: string): number {
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : 0;
}

// === small helpers ===

function replaceItem(state: PanelState, index: number, item: PanelItem): PanelState {
  const items = state.items.slice();
  items[index] = item;
  return { items, conversationId: state.conversationId };
}
