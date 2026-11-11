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

export type ToolActivity = { name: string; status: 'running' | 'ok' | 'error'; startedAt: number; durationMs?: number; args?: string; result?: string; toolCallId?: string };
export type PanelItem =
  | { kind: 'user'; id: string; content: string; timestamp: string }
  | { kind: 'assistant'; id: string; content: string; timestamp: string; error?: boolean; retryText?: string; noRetry?: boolean }
  | { kind: 'activity'; id: string; turnId: string; tools: ToolActivity[]; collapsed: boolean }
  | { kind: 'approval'; id: string; approvalId: string; runId: string; toolName: string; riskTier?: string; toolPolicy?: string; args?: string; state: 'pending' | 'approved' | 'denied' | 'expired'; reason?: string }
  | { kind: 'clarification'; id: string; approvalId: string; runId: string; question: string; state: 'pending' | 'answered' | 'expired' }
  /**
   * `decided-elsewhere` is the proposal's third settled state: a PENDING inbox
   * read no longer listed it. The inbox is a bounded page with no by-id outcome
   * read, so it cannot say whether the departure was an approval, a rejection,
   * or a later retirement — the card says "decided elsewhere" instead of
   * inventing a verdict. It drops the card's controls.
   *
   * `blocked-on-entity` is the fourth, and it is neither a verdict nor a
   * departure: the server refused this proposal's decision because its parent
   * entity is still PENDING, so the decision that owns it is the ENTITY
   * proposal's. The card's only remaining control could 409 forever, so it is
   * settled, and it never reopens, because a listing that still carries the row
   * would hand the operator the same undecidable ask again.
   *
   * Neither state gates the composer: a proposal never does (see
   * `hasUnansweredCard`).
   */
  | { kind: 'proposal'; id: string; proposalId: string; subjectType: 'FACT' | 'ENTITY' | 'RELATION'; entityId?: string; factKey?: string; valueText?: string; sensitivity?: string; state: 'pending' | 'approved' | 'rejected' | 'decided-elsewhere' | 'blocked-on-entity' }
  | { kind: 'queued'; id: string; runIds: string[] };
export type PanelState = { items: PanelItem[]; conversationId: string };
export type PanelEvent =
  | { type: 'restore'; conversationId: string; timeline: TimelineEntry[]; approvals: Approval[]; proposals: MemoryProposalSummary[] }
  | { type: 'userSent'; id: string; content: string; timestamp: string }
  /**
   * `turnId` IS the run id — the only turn identity a restored timeline carries.
   * A caller must pass the run id (the `thinking` event's active run id), not a
   * counter: anything else draws a second strip for a turn the snapshot already
   * rendered, and the restored tool never settles.
   */
  | { type: 'toolCall'; turnId: string; name: string; args?: string; now?: number }
  /** `turnId` IS the run id — the same value the matching `toolCall` carried. */
  | { type: 'toolResult'; turnId: string; name: string; result: string; ok: boolean; now?: number }
  /**
   * Carries no turn id; the timeline re-delivers it by content. `turnId` on the
   * tool events above IS the run id — the only turn identity a restored timeline
   * carries.
   */
  | { type: 'assistantMessage'; id: string; content: string; timestamp: string }
  | { type: 'approvalRequired'; approvalId: string; runId: string; toolName: string; riskTier?: string; toolPolicy?: string; args?: string }
  | { type: 'approvalSettled'; approvalId: string; state: 'approved' | 'denied' | 'expired'; reason?: string }
  | { type: 'waitingInput'; approvalId: string; runId: string; question: string }
  | { type: 'clarificationSettled'; approvalId: string; state: 'answered' | 'expired' }
  | { type: 'proposalCreated'; proposal: MemoryProposalSummary }
  /**
   * `decided-elsewhere` settles a proposal the PENDING inbox no longer lists.
   * It is NOT a verdict: the bounded inbox cannot tell an approval from a
   * rejection, so a caller that only knows "it left the PENDING page" must use
   * this state and the card will say so.
   *
   * `blocked-on-entity` is the server's parent-still-PENDING refusal: the
   * proposal is still pending, and the decision that owns it belongs to its
   * entity's proposal. It is not a verdict and not a departure either.
   */
  | { type: 'proposalSettled'; proposalId: string; state: 'approved' | 'rejected' | 'decided-elsewhere' | 'blocked-on-entity' }
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
/** A failure is labelled with this separator; the reason follows the last one. */
const FAILURE_REASON_SEPARATOR = '：';
/** A tool result row whose call row is missing still names itself in the strip. */
const ORPHAN_TOOL_NAME = '(tool output)';
/** An orphan strip's turn id when the run already has one: `<runId>#orphan-<n>`. */
const ORPHAN_SUFFIX = '#orphan-';
/** The single queued indicator is keyed by its kind, not by a run. */
const QUEUED_ID = 'queued';

export function emptyState(conversationId: string): PanelState {
  return { items: [], conversationId };
}

/**
 * True while the conversation holds a card the composer must wait for: an
 * approval or a clarification, the two asks that PARK their turn (spec:229-234).
 *
 * A memory proposal is deliberately NOT here. It is decided on its own card and
 * parks nothing, and the PENDING proposal inbox is global (see the scope note in
 * `restore`), so counting a proposal gated this conversation's composer on an
 * ask another conversation raised — one conversation's undecided proposal
 * disabled another's composer (spec decision 2, T45 Item 1).
 */
export function hasUnansweredCard(state: PanelState): boolean {
  return state.items.some(
    (item) => (item.kind === 'approval' || item.kind === 'clarification') && item.state === 'pending',
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

/**
 * A result row whose call row never landed, as its own one-tool strip. The tool is
 * named after the row, not after a call the timeline does not hold, and carries the
 * status the row reports — an orphan is audited output, so a denied one must not be
 * drawn as a success either.
 *
 * Several orphans can belong to one run, so the strip needs an identity of its own:
 * the bare run id while it is free (a run with no other strip), then
 * `<runId>#orphan-<n>`. Without it two orphans of one run share an `id` (a React key
 * collision) and a `turnId`, and the next restore's turnId dedupe drops the second.
 */
function orphanStrip(
  items: PanelItem[],
  turnId: string,
  startedAt: number,
  content: string,
  status: ToolActivity['status'],
): ActivityItem {
  const identity = freeOrphanTurnId(items, turnId);
  return {
    kind: 'activity',
    id: activityId(identity),
    turnId: identity,
    tools: [{ name: ORPHAN_TOOL_NAME, status, startedAt, result: content }],
    collapsed: true,
  };
}

/** The run id when no activity strip holds it yet, else the first free `#orphan-n`. */
function freeOrphanTurnId(items: PanelItem[], turnId: string): string {
  const taken = new Set<string>();
  for (const item of items) {
    if (item.kind === 'activity') taken.add(item.turnId);
  }
  if (!taken.has(turnId)) return turnId;
  let suffix = 0;
  while (taken.has(`${turnId}${ORPHAN_SUFFIX}${suffix}`)) suffix += 1;
  return `${turnId}${ORPHAN_SUFFIX}${suffix}`;
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

/** The display fields a summary carries onto its card, when it carries them. */
function proposalFields(proposal: MemoryProposalSummary): Omit<ProposalItem, 'kind' | 'id' | 'proposalId' | 'state'> {
  return {
    subjectType: proposal.subjectType,
    ...(proposal.entityId !== undefined ? { entityId: proposal.entityId } : {}),
    ...(proposal.factKey !== undefined ? { factKey: proposal.factKey } : {}),
    ...(proposal.valueText !== undefined ? { valueText: proposal.valueText } : {}),
    ...(proposal.sensitivity !== undefined ? { sensitivity: proposal.sensitivity } : {}),
  };
}

function upsertProposal(state: PanelState, patch: ProposalPatch): PanelState {
  const index = state.items.findIndex((item) => item.kind === 'proposal' && item.proposalId === patch.id);
  const fields = proposalFields(patch);
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
  // The operator's own verdict is closed for good. `decided-elsewhere` is not a
  // verdict — it only reported that the inbox had dropped the row — and a
  // re-proposal returns the same id to PENDING (the server flips the same
  // entity row back to review), so that card becomes the operator's to decide
  // again instead of staying dead next to a live proposal.
  //
  // `blocked-on-entity` DOES stay closed: its parent is still PENDING, so every
  // re-delivery would only hand the operator an ask the server refuses.
  if (existing.state !== 'pending' && existing.state !== 'decided-elsewhere') return state;
  return replaceItem(state, index, { ...existing, ...fields, state: patch.state });
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

  for (const approval of event.approvals) {
    if (approval.status !== 'PENDING' || !runIds.has(approval.runId)) continue;
    if (askIds.has(approval.id)) continue; // already drawn, live or restored: a card is keyed by its ask id
    askIds.add(approval.id);
    items.push(isClarificationAsk(approval) ? clarificationFromApproval(approval) : approvalFromApproval(approval));
  }

  for (const proposal of event.proposals) {
    // CONVERSATION SCOPE — KNOWN GAP (task 20 / ui-surface-review m3). This list
    // is the GLOBAL PENDING inbox: `GET /api/v1/memory/proposals?status=PENDING`
    // is not conversation-scoped and `MemoryProposalView` carries no conversation
    // or run field, so unlike the approvals loop above (`runIds.has(...)`) there
    // is nothing to guard on here: a proposal raised in another conversation is
    // restored into this transcript. The key must come from the server, in this
    // order: persist the raising run on the proposal row (`MemoryTools.java:117-124`
    // already holds `ActorPrincipal.runId()` at the write, `MemoryCommandService`
    // `addFact` is the write path), expose it on
    // `act-memory/.../dto/MemoryProposalView.java`, mirror it in
    // `src/api/memory.ts` (`MemoryProposalView`) and in `MemoryProposalSummary`
    // below, and this loop becomes `if (!runIds.has(proposal.runId)) continue;`.
    const at = items.findIndex((item) => item.kind === 'proposal' && item.proposalId === proposal.id);
    if (at >= 0) {
      const existing = items[at] as ProposalItem;
      // The operator's own verdict stands, whatever the listing says. A
      // `decided-elsewhere` card only ever reported that the inbox had dropped
      // the row, and the server re-proposes a decided id back to PENDING — so a
      // listing that carries the same id again is a live decision, not a
      // re-delivery, and the card must reopen instead of staying dead.
      if (existing.state !== 'decided-elsewhere') continue;
      items[at] = { ...existing, ...proposalFields(proposal), state: 'pending' };
      continue;
    }
    items.push({
      kind: 'proposal',
      id: `proposal:${proposal.id}`,
      proposalId: proposal.id,
      ...proposalFields(proposal),
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
  const restoredFailureReasons = new Set<string>();
  for (const item of base) {
    if (item.kind !== 'assistant' || item.error !== true) continue;
    const reason = failureReasonText(item.content);
    if (reason !== null) restoredFailureReasons.add(reason);
  }
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
      // cannot consume the live notice. Drop it only when the snapshot tells the
      // same failure — a notice whose reason matches a restored one. A different
      // reason (a newer failed turn) or no reason at all (a run-uncertain notice,
      // which the snapshot never holds) still carries: a duplicate is noisy, a
      // deletion claims nothing failed.
      const carriedReason = item.kind === 'assistant' && item.error === true ? failureReasonText(item.content) : null;
      if (item.kind === 'assistant' && item.error === true && item.noRetry !== true
        && carriedReason !== null && restoredFailureReasons.has(carriedReason)) continue;
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
 * The reason a failure notice reports. The live plane labels it `⚠ 上一回合失敗：<reason>`
 * and the snapshot's synthetic failed-turn entry `回合執行失敗：<reason>`, so the text
 * after the last separator is what the two tellings share. A notice with no reason
 * (the run-uncertain copy) yields null and matches nothing: it is not a re-telling
 * of a persisted failure, and the snapshot never re-delivers it.
 */
function failureReasonText(content: string): string | null {
  const at = content.lastIndexOf(FAILURE_REASON_SEPARATOR);
  if (at < 0) return null;
  const reason = content.slice(at + FAILURE_REASON_SEPARATOR.length).trim();
  return reason ? reason : null;
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
 * The status a restored tool row reports, from the raw `ToolCallStatus` name the
 * timeline carries. The name, not a boolean: a tool that was still EXECUTING when
 * the snapshot was read is not a success, and a PENDING one has not run at all. A
 * name this panel does not recognise is not evidence of success either, so the tool
 * stays unsettled. A row with no status at all keeps the reading this panel has
 * always had — `ok`, meaning the timeline does not say otherwise (an older backend).
 */
const RESTORED_TOOL_STATUS: Record<string, ToolActivity['status']> = {
  COMPLETED: 'ok',
  FAILED: 'error',
  DENIED: 'error',
  PENDING: 'running',
  EXECUTING: 'running',
};

function restoredToolStatus(toolStatus: string | null | undefined): ToolActivity['status'] {
  if (!toolStatus) return 'ok';
  return RESTORED_TOOL_STATUS[toolStatus] ?? 'running';
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
    // The row's own identity, when it has one: an older row carries no id.
    const identity = typeof entry.toolCallId === 'string' && entry.toolCallId !== '' ? entry.toolCallId : null;

    if (call) {
      const tool: ToolActivity = {
        name: call.name,
        status: 'running',
        startedAt,
        ...(call.args !== undefined ? { args: call.args } : {}),
        ...(identity !== null ? { toolCallId: identity } : {}),
      };
      if (at < 0) {
        items.push({ kind: 'activity', id: activityId(turnId), turnId, tools: [tool], collapsed: true });
      } else {
        const strip = items[at] as ActivityItem;
        items[at] = { ...strip, tools: [...strip.tools, tool] };
      }
      return;
    }

    // Any other tool row is the result of a tool of this run.
    // A result whose call row never landed is still audited output: render it
    // as its own one-tool strip instead of dropping it — even when the run
    // already has a strip, because it is the call row of this result that is
    // missing. It carries the status the row reports, so a denied orphan is not
    // drawn as a success.
    const status = restoredToolStatus(entry.toolStatus);
    if (at < 0) {
      items.push(orphanStrip(items, turnId, startedAt, entry.content, status));
      return;
    }
    const strip = items[at] as ActivityItem;
    // Attribution is by identity first: the row's tool-call id names its own call,
    // which is what keeps two overlapping calls from crossing their results. Rows
    // written before the id was exposed carry none, so the historical rule stays as
    // the fallback: the last tool of this run still running.
    let target = identity !== null ? strip.tools.findIndex((tool) => tool.toolCallId === identity) : -1;
    if (target < 0) {
      for (let i = strip.tools.length - 1; i >= 0; i -= 1) {
        if (strip.tools[i].status === 'running') {
          target = i;
          break;
        }
      }
    }
    if (target < 0) {
      items.push(orphanStrip(items, turnId, startedAt, entry.content, status));
      return;
    }
    const tool = strip.tools[target];
    const durationMs = tool.startedAt > 0 && startedAt >= tool.startedAt ? startedAt - tool.startedAt : undefined;
    const tools = strip.tools.slice();
    // The status the timeline reports, mapped: FAILED and DENIED are errors, PENDING and
    // EXECUTING are still running, and only an absent field falls back to `ok` — the
    // timeline does not say otherwise.
    tools[target] = {
      ...tool,
      status,
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
