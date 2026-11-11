import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import { streamMessage } from '../api/aria';
import type { MemoryProposalSignal, StreamError } from '../api/aria';
import { listApprovals, getApproval } from '../api/approvals';
import { apiErrorMessage, isOperatorRejection } from '../api/operatorSession';
import { cancelRun, getRun } from '../api/runs';
import type { Approval } from '../types';
import { getLatestConversation, getConversationTimeline, deleteConversation } from '../api/ariaConversations';
import type { TimelineEntry } from '../api/ariaConversations';
import { listMemoryProposals } from '../api/memory';
import type { MemoryProposalView } from '../api/memory';
import { formatTimestamp } from '../utils/formatTime';
import { useSlashCommands } from '../hooks/useSlashCommands';
import { useWebSocketContext } from './Layout';
import { emptyState, hasUnansweredCard, panelReducer } from './ariaPanelState';
import type { MemoryProposalSummary, PanelItem } from './ariaPanelState';
import AriaActivityStrip from './AriaActivityStrip';
import AriaApprovalCard from './AriaApprovalCard';
import AriaClarificationCard from './AriaClarificationCard';
import AriaProposalCard from './AriaProposalCard';
import SlashCommandMenu from './SlashCommandMenu';

// ponytail: localStorage removed — conversation ownership now backend-backed.
// PANEL_OPEN_KEY retained for UI toggle only (not data).
const PANEL_OPEN_KEY = 'aria-panel-open';

const CLIENT_TIMEOUT_MS = 600_000; // 10 min — matches backend SSE timeout (Issue #12)

const SUGGESTIONS = [
  'My schedule today',
  'Brief me on overnight runs',
  'What needs my approval?',
  'Knowledge status summary',
];

/**
 * The failure reason of a turn the backend marked FAILED — the only error detail
 * allowed to claim a turn failed (A4 enriched SSE payload). Everything else
 * (plain strings, legacy payloads) reports an unknown run state.
 */
function turnFailureReason(errorDetail?: StreamError): string | null {
  if (typeof errorDetail !== 'object' || errorDetail === null || errorDetail.turnFailed !== true) {
    return null;
  }
  return errorDetail.reason || errorDetail.message || 'unknown error';
}

/** Prefix the backend's synthetic failed-turn timeline entry (A1) carries. */
const FAILED_TURN_CONTENT_PREFIX = '回合執行失敗：';

/**
 * `MemoryProposalService.INBOX_LIMIT`: the PENDING proposal inbox is ONE bounded
 * newest-first page across all entities, so a full page cannot prove that a
 * proposal is absent from it.
 */
const PROPOSAL_INBOX_LIMIT = 50;

/**
 * A failed turn's synthetic timeline entry is appended the moment the run turns
 * FAILED, which can trail the engine's own error event by a moment — one short
 * re-check covers that race (never more; the approvals copy is the fallback).
 */
const FAILED_TURN_RECHECK_WAIT_MS = 1500;

/**
 * Panel-side reconcile for a legacy stream error, whose payload carries no
 * failed-turn detail (A4 enrichment covers only the stream service's catch; the
 * engine's own `{"message": ...}` events stay plain). The failed turn still
 * leaves its synthetic entry in the conversation timeline, so re-read it: when
 * the last entry is that failure the panel reports the real reason instead of
 * the misleading approvals copy. Returns null when the timeline is unreadable
 * or the failure entry has not landed — after ONE bounded re-check.
 */
async function readFailedTurnEntry(conversationId: string): Promise<TimelineEntry | null> {
  const readLastEntry = async (): Promise<TimelineEntry | null> => {
    try {
      const timeline = await getConversationTimeline(conversationId);
      const last = timeline[timeline.length - 1];
      return last?.error === true ? last : null;
    } catch {
      return null; // unreadable timeline = no evidence: keep the approvals copy
    }
  };
  const first = await readLastEntry();
  if (first) return first;
  await new Promise((resolve) => setTimeout(resolve, FAILED_TURN_RECHECK_WAIT_MS));
  return readLastEntry();
}

/**
 * A queued turn (spec A3): the run is admitted but still PENDING, and there is
 * no QUEUED engine status, so the indicator is derived from the timeline — a
 * trailing unanswered prompt names the run — and then confirmed against the run
 * itself. The status read is what makes the label true: a trailing prompt whose
 * run is RUNNING is a hung turn, and one that FAILED or was cancelled is dead;
 * neither is admitted-and-waiting, so neither is called Queued. An unreadable
 * status claims nothing. A turn whose stream this panel holds is not queued, it
 * is the turn being watched.
 */
async function queuedRunIds(timeline: TimelineEntry[], activeRunId: string | null): Promise<string[]> {
  const last = timeline[timeline.length - 1];
  if (!last || last.role !== 'user' || !last.runId) return [];
  if (last.runId === activeRunId) return [];
  try {
    const run = await getRun(last.runId);
    return run?.status === 'PENDING' ? [last.runId] : [];
  } catch {
    return []; // no evidence of admission: no claim
  }
}

/** What an ask that left the PENDING list turned into, as its card must say it. */
type AskOutcome = { state: 'approved' | 'denied' | 'expired'; reason?: string };

/** True for the subject types the memory proposals surface can decide. */
function isProposalSubjectType(value: string): value is MemoryProposalSummary['subjectType'] {
  return value === 'FACT' || value === 'ENTITY' || value === 'RELATION';
}

/**
 * A live `memory_proposal` signal as the reducer's summary, or null for a frame
 * the panel cannot classify. A proposal gates the composer (`hasUnansweredCard`
 * counts a pending one), so an unclassifiable subject is dropped rather than
 * drawn as a blank card nobody can act on.
 */
function proposalSummary(signal: MemoryProposalSignal): MemoryProposalSummary | null {
  const { id, subjectType } = signal;
  if (!id) return null;
  if (!isProposalSubjectType(subjectType)) return null;
  return {
    id,
    subjectType,
    ...(signal.entityId !== undefined ? { entityId: signal.entityId } : {}),
    ...(signal.factKey !== undefined ? { factKey: signal.factKey } : {}),
    ...(signal.valueText !== undefined ? { valueText: signal.valueText } : {}),
    ...(signal.sensitivity !== undefined ? { sensitivity: signal.sensitivity } : {}),
  };
}

/**
 * The PENDING proposal inbox as the reducer's restore summaries. `entityId`
 * travels with the row because the card lists an entity's facts from it, and a
 * row the panel cannot classify is dropped for the same reason a live frame is:
 * it would render a card no decision endpoint owns.
 */
function proposalSummaries(rows: MemoryProposalView[]): MemoryProposalSummary[] {
  const out: MemoryProposalSummary[] = [];
  for (const row of rows) {
    if (!row.id || !isProposalSubjectType(row.subjectType)) continue;
    out.push({
      id: row.id,
      subjectType: row.subjectType,
      ...(row.entityId !== null ? { entityId: row.entityId } : {}),
      ...(row.factKey !== null ? { factKey: row.factKey } : {}),
      ...(row.valueText !== null ? { valueText: row.valueText } : {}),
      ...(row.sensitivity !== null ? { sensitivity: row.sensitivity } : {}),
    });
  }
  return out;
}

/**
 * Reads an ask the PENDING snapshot no longer lists (spec A2's by-id endpoint)
 * so its card can say what became of it: decided elsewhere, expired, or gone.
 * A read that fails or that cannot classify the status proves nothing, so it
 * returns null and the card stays the operator's to decide.
 */
async function readAskOutcome(approvalId: string): Promise<AskOutcome | null> {
  let approval: Approval | null;
  try {
    approval = await getApproval(approvalId);
  } catch {
    // 404 (the ask no longer exists) or an unreadable read: either way it is not
    // pending any more — it left the list this reconcile just read.
    return { state: 'expired', reason: 'this ask no longer exists on the server' };
  }
  if (approval === null || typeof approval?.status !== 'string') return null;
  if (approval.status === 'APPROVED') return { state: 'approved' };
  if (approval.status === 'DENIED') return { state: 'denied' };
  if (approval.status === 'PENDING') return null; // the two reads disagree: never decide for the operator
  return { state: 'expired', reason: approval.reason || 'this ask can no longer be decided' };
}

function loadOpenState(): boolean {
  try {
    return localStorage.getItem(PANEL_OPEN_KEY) === '1';
  } catch {
    return false;
  }
}

/** Compact, allocation-light markdown renderer for assistant bubbles (m2: + tables & horizontal rules). */
function renderMarkdown(text: string): string {
  if (!text) return '<p></p>';
  // m2: guard very large payloads — render verbatim to avoid costly regex passes.
  if (text.length > 10000) {
    return `<pre>${text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</pre>`;
  }
  try {
    // Extract fenced code blocks first so their pipes/dashes aren't parsed as tables/rules.
    const codeBlocks: string[] = [];
    let src = text.replace(/```(\w*)\n([\s\S]*?)```/g, (_m, _lang, code) => {
      codeBlocks.push(`<pre class="md-code-block"><code>${String(code).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</code></pre>`);
      return `\u0000CB${codeBlocks.length - 1}\u0000`;
    });
    src = src
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    const inline = (s: string): string =>
      s
        .replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>');
    const lines = src.split('\n');
    const out: string[] = [];
    let inList = false;
    let inTable = false;
    let tableHeaderDone = false;
    const closeList = () => { if (inList) { out.push('</ul>'); inList = false; } };
    const closeTable = () => { if (inTable) { out.push('</tbody></table>'); inTable = false; tableHeaderDone = false; } };
    for (const raw of lines) {
      const line = raw.trimEnd();
      const trimmed = line.trim();
      if (trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.length > 1) {
        const cells = trimmed.slice(1, -1).split('|').map((c) => c.trim());
        const isSeparator = cells.every((c) => /^:?-{2,}:?$/.test(c));
        if (isSeparator) { continue; }
        if (!inTable) {
          closeList();
          out.push('<table class="md-table"><thead><tr>');
          for (const c of cells) out.push(`<th>${inline(c)}</th>`);
          out.push('</tr></thead><tbody>');
          inTable = true;
          tableHeaderDone = true;
        } else {
          out.push('<tr>');
          for (const c of cells) out.push(`<td>${inline(c)}</td>`);
          out.push('</tr>');
        }
        continue;
      }
      closeTable();
      if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
        closeList();
        out.push('<hr/>');
        continue;
      }
      let h = line;
      h = h.replace(/^### (.+)$/, '<h4>$1</h4>');
      h = h.replace(/^## (.+)$/, '<h3>$1</h3>');
      h = h.replace(/^# (.+)$/, '<h2>$1</h2>');
      h = inline(h);
      if (/^[•\-\*] (.+)$/.test(h)) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push(`<li>${h.replace(/^[•\-\*] /, '')}</li>`);
        continue;
      }
      if (/^\d+\. (.+)$/.test(h)) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push(`<li>${h.replace(/^\d+\. /, '')}</li>`);
        continue;
      }
      closeList();
      if (h.trim() === '') { out.push(''); } else { out.push(h); }
    }
    closeList();
    closeTable();
    let html = out.join('\n');
    html = html.replace(/\u0000CB(\d+)\u0000/g, (_m, i) => codeBlocks[Number(i)] ?? '');
    html = html.replace(/\n\n/g, '</p><p>');
    html = html.replace(/\n/g, '<br/>');
    if (!html.startsWith('<')) html = `<p>${html}</p>`;
    return html;
  } catch {
    return `<pre>${text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</pre>`;
  }
}

/**
 * Floating Aria assistant panel — FAB + slide-out chat surface.
 *
 * Wired into the global app shell so the operator can summon Aria from any page
 * without losing route context. The conversation itself lives in
 * `ariaPanelState`; this component is wiring and layout only. Three planes feed
 * that state and all three merge by ask id — the turn's ordered SSE stream, the
 * `/topic/events` WebSocket for what happened elsewhere, and the REST snapshot
 * used to reconcile on mount, focus and reconnect.
 *
 * A parked turn announces its ask on its own stream (`approval_required`,
 * `waiting_input`), so an approval or a question renders as a card in the
 * conversation: deciding it resumes the same turn, which still holds this
 * panel's stream open. Tool activity collapses into one strip per turn instead
 * of a bubble per row.
 */
export function AriaPanel() {
  const [open, setOpen] = useState<boolean>(() => loadOpenState());
  const [state, dispatch] = useReducer(panelReducer, emptyState(''));
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState('');
  const [busy, setBusy] = useState(false);
  const [lastSentMessage, setLastSentMessage] = useState<string | null>(null);
  const lastSentSkillIdRef = useRef<string | undefined>(undefined); // retry must re-attach the skill directive
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(true);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const stackRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelledRef = useRef(false); // M1: suppress the false "connection closed" error after a user cancel
  const activeRunIdRef = useRef<string | null>(null); // M1: run id for server-side cancellation
  const turnIdRef = useRef(''); // the id the live turn's activity strip is keyed by (the run id once known)
  const conversationIdRef = useRef('');
  // The runs this conversation's timeline holds: a WebSocket ask is only this
  // panel's business when its run is one of them (a single-conversation panel
  // must not render another conversation's ask).
  const conversationRunIdsRef = useRef<Set<string>>(new Set());
  // Proposal ids a PENDING inbox read has actually listed: only those may be
  // settled, the rule the approval settle below applies to a run. A live frame
  // can precede its rows, so an empty read must not settle what it never saw.
  // The settled card itself lives in the reducer (`decided-elsewhere`), keyed by
  // conversation, so Clear reopens nothing stale.
  const confirmedProposalIdsRef = useRef<{ conversationId: string; ids: Set<string> }>(
    { conversationId: '', ids: new Set() },
  );

  const items = state.items;
  // A proposal is settled as `decided-elsewhere` inside the state, so it is no
  // longer `pending` and cannot lock the composer.
  const gated = hasUnansweredCard(state);
  const isEmpty = items.length === 0;
  const slash = useSlashCommands(open);
  // The latest transcript for the reconcile's settle step: a reconcile resolves
  // after awaits, so it reads the cards as they are now, not as they were when
  // the callback was created.
  const itemsRef = useRef<PanelItem[]>(items);
  itemsRef.current = items;
  // Hoisted primitive so sendStreamed's closure can depend on the id itself —
  // the `slash` object is render-scoped and must not be a hook dep (fix 1).
  const pendingSkillId = slash.pendingSkill?.id;

  const adoptConversationId = useCallback((id: string) => {
    conversationIdRef.current = id;
    setConversationId(id);
  }, []);

  /**
   * Settles every carried pending card whose ask the PENDING snapshot no longer
   * lists — a lost `approval.expired` frame, a Cancel while the turn is parked,
   * a decision taken in another window. Left pending, such a card can only 409
   * and keeps the composer locked behind a false "still pending" copy, so it is
   * settled with what the ask actually became. Only a run the snapshot speaks
   * for is settled: a live turn's ask may simply predate its timeline rows.
   */
  const settleDepartedAsks = useCallback(
    async (targetConversationId: string, listed: Approval[], runIds: Set<string>) => {
      const listedIds = new Set(listed.map((approval) => approval.id));
      const departed = itemsRef.current.filter(
        (item) =>
          (item.kind === 'approval' || item.kind === 'clarification')
          && item.state === 'pending'
          && runIds.has(item.runId)
          && !listedIds.has(item.approvalId),
      );
      for (const card of departed) {
        if (card.kind !== 'approval' && card.kind !== 'clarification') continue;
        const outcome = await readAskOutcome(card.approvalId);
        if (outcome === null) continue;
        if (conversationIdRef.current !== targetConversationId) return;
        dispatch({
          type: 'approvalSettled',
          approvalId: card.approvalId,
          state: outcome.state,
          ...(outcome.reason !== undefined ? { reason: outcome.reason } : {}),
        });
        dispatch({
          type: 'clarificationSettled',
          approvalId: card.approvalId,
          state: outcome.state === 'approved' ? 'answered' : 'expired',
        });
      }
    },
    [],
  );

  /**
   * Settles a local pending proposal a PENDING inbox read no longer lists: it
   * was decided elsewhere (the Memory page, another operator). Left pending,
   * the card could only 409 and would keep the composer locked behind a false
   * "still pending" copy. The inbox has no by-id outcome read and its bounded
   * ACTIVE/RETIRED pages cannot tell an approval from a later retirement, so the
   * panel does not claim a verdict — the reducer's `decided-elsewhere` state
   * makes the card say exactly that.
   *
   * Only a proposal a PENDING read has actually listed is settle-able ("the
   * snapshot speaks for it", the rule the approval settle applies to a run): a
   * live frame can arrive before its row, so an empty read must not settle what
   * it has never seen. A read that failed or was refused never reaches here.
   *
   * And only a read whose page was NOT capped can prove a departure: the inbox
   * is one bounded page (`PROPOSAL_INBOX_LIMIT`, newest first across all
   * entities), so a still-pending proposal can be pushed below it and read as
   * gone. A capped page therefore confirms what it lists and settles nothing —
   * a false "decided elsewhere" would take the operator's decision away from a
   * proposal that is still pending.
   *
   * Residual, NOT fixable on this side (task 16, disclosed): a live proposal
   * whose row commits after the last PENDING read and which is then decided
   * elsewhere before any later read lists it is never "spoken for", so it stays
   * pending — the same race the approval path has, and a bounded read cannot
   * distinguish it from a row that has not landed yet.
   */
  const settleDepartedProposals = useCallback(
    (targetConversationId: string, listed: MemoryProposalSummary[], pageProvablyComplete: boolean) => {
      if (conversationIdRef.current !== targetConversationId) return;
      const confirmed = confirmedProposalIdsRef.current.conversationId === targetConversationId
        ? confirmedProposalIdsRef.current.ids
        : new Set<string>();
      const listedIds = new Set(listed.map((proposal) => proposal.id));
      const departed = pageProvablyComplete
        ? itemsRef.current.flatMap((item) =>
          item.kind === 'proposal'
            && item.state === 'pending'
            && confirmed.has(item.proposalId)
            && !listedIds.has(item.proposalId)
            ? [item.proposalId]
            : [],
        )
        : [];
      confirmedProposalIdsRef.current = {
        conversationId: targetConversationId,
        ids: new Set([...confirmed, ...listedIds]),
      };
      if (departed.length === 0) return;
      for (const proposalId of departed) {
        // The state, not a local set: the card says why it settled and the item
        // stops gating because it is no longer `pending`.
        dispatch({ type: 'proposalSettled', proposalId, state: 'decided-elsewhere' });
      }
    },
    [],
  );

  /**
   * REST reconciliation (spec A3): re-read the durable conversation and merge it
   * into the panel state. Pending asks are intersected client-side with the
   * timeline's run ids — there is no conversation-scoped approvals endpoint —
   * and an operator-only refusal of that read leaves the timeline view intact
   * instead of failing the whole reconcile.
   *
   * Pending memory proposals come from their own inbox (`GET /proposals?status=PENDING`),
   * which is server-filtered: a proposal the operator already decided is never
   * re-delivered, and the reducer's merge never reopens a settled card. A read
   * that answers also settles a local pending proposal it no longer lists (a
   * decision taken elsewhere); a refused or failed read proves nothing and
   * settles none of them, and neither does a page that hit the inbox cap.
   */
  const reconcile = useCallback(async (targetConversationId: string) => {
    const timeline = await getConversationTimeline(targetConversationId);
    const runIds = new Set(timeline.map((entry) => entry.runId).filter((runId) => !!runId));
    let approvals: Approval[] = [];
    let pendingListed = false; // only a PENDING read that actually answered may settle a card
    if (runIds.size > 0) {
      try {
        approvals = (await listApprovals('PENDING')).filter((approval) => runIds.has(approval.runId));
        pendingListed = true;
      } catch (err) {
        if (!isOperatorRejection(err)) console.warn('[Aria] pending-approval reconcile failed:', err);
        approvals = [];
      }
    }
    let proposals: MemoryProposalSummary[] = [];
    let proposalsListed = false; // only a PENDING read that actually answered may settle a proposal
    let proposalPageComplete = false; // only a page below the cap is the whole inbox
    try {
      const rows = await listMemoryProposals('PENDING');
      proposals = proposalSummaries(rows);
      proposalsListed = true;
      proposalPageComplete = rows.length < PROPOSAL_INBOX_LIMIT;
    } catch (err) {
      if (!isOperatorRejection(err)) console.warn('[Aria] pending-proposal reconcile failed:', err);
      proposals = [];
    }
    // A snapshot that resolves after the operator cleared this conversation must
    // not write the cleared transcript back, nor revert the reducer's id.
    if (conversationIdRef.current !== targetConversationId) return;
    conversationRunIdsRef.current = runIds;
    dispatch({ type: 'restore', conversationId: targetConversationId, timeline, approvals, proposals });
    const queued = await queuedRunIds(timeline, activeRunIdRef.current);
    if (conversationIdRef.current !== targetConversationId) return;
    dispatch({ type: 'queuedRuns', runIds: queued });
    if (proposalsListed) settleDepartedProposals(targetConversationId, proposals, proposalPageComplete);
    if (!pendingListed) return;
    await settleDepartedAsks(targetConversationId, approvals, runIds);
  }, [settleDepartedAsks, settleDepartedProposals]);

  // Load conversation from backend on mount
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const summary = await getLatestConversation();
        if (cancelled) return;
        // A Clear while this read was in flight already picked this tab's
        // conversation: this snapshot is stale, so it must not adopt its id or
        // its transcript.
        if (conversationIdRef.current !== '') return;
        if (summary) {
          adoptConversationId(summary.conversationId);
          await reconcile(summary.conversationId);
          if (cancelled) return;
        } else {
          const fresh = crypto.randomUUID();
          adoptConversationId(fresh);
          dispatch({ type: 'restore', conversationId: fresh, timeline: [], approvals: [], proposals: [] });
          console.log(`[Aria] New conversation: ${fresh}`);
        }
      } catch (err) {
        // Backend unavailable — start fresh locally, but say the history was not loaded.
        if (!cancelled) {
          setHistoryError(apiErrorMessage(err,
            'Conversation history unavailable — starting a new conversation.'));
          adoptConversationId(crypto.randomUUID());
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [adoptConversationId, reconcile]);

  // Persist open state only (UI toggle, not data)
  useEffect(() => {
    try {
      localStorage.setItem(PANEL_OPEN_KEY, open ? '1' : '0');
    } catch {
      /* ignore */
    }
  }, [open]);

  // Auto-scroll on transcript growth
  useEffect(() => {
    if (!open) return;
    const el = stackRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [items, busy, open]);

  // Focus textarea when panel opens
  useEffect(() => {
    if (open) {
      const t = setTimeout(() => textareaRef.current?.focus(), 60);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [open]);

  // Cancel in-flight stream when component unmounts
  useEffect(() => () => {
    abortRef.current?.abort();
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
  }, []);

  /**
   * The WebSocket plane — the panel's first subscription (spec A3). A decision
   * taken in the Review Queue or in another tab settles the card here, and a
   * completed run re-reads this conversation's snapshot. Both are merged by ask
   * id, so a decision delivered twice is a no-op.
   *
   * The subscription rides the app-wide connection from `WebSocketProvider`
   * (Layout.tsx), the same one ConnectionBanner reports: a per-instance
   * `useWebSocket()` would open a second server session and a second
   * `/topic/events` SUBSCRIBE per tab, and its reconnect flag could disagree
   * with the banner about whether the feed came back.
   */
  const { subscribe, isConnected } = useWebSocketContext();
  /**
   * True when a run is this conversation's own — one the timeline holds, or the
   * turn this panel is watching right now.
   */
  const isThisConversationsRun = useCallback(
    (runId: string) =>
      runId !== ''
      && (runId === activeRunIdRef.current || conversationRunIdsRef.current.has(runId)),
    [],
  );

  /**
   * A WS-announced ask, read by id (spec A2). The `approval.requested` frame
   * carries no tool name, tier, policy or question, so the panel reads the ask
   * itself; the frame is the only signal for an ask raised after this panel's
   * own stream died (client timeout, cancel, a dropped SSE connection), which is
   * the plane the three-plane design exists for. Only asks of a run this
   * conversation holds are adopted — the cross-conversation half of the review's
   * concern is correct: one conversation's panel does not render another's.
   */
  const adoptAskById = useCallback(async (approvalId: string) => {
    const targetConversationId = conversationIdRef.current;
    let approval: Approval | null;
    try {
      approval = await getApproval(approvalId);
    } catch (err) {
      console.warn('[Aria] announced ask could not be read:', err);
      return;
    }
    if (!approval || approval.status !== 'PENDING') return;
    if (conversationIdRef.current !== targetConversationId) return;
    if (!isThisConversationsRun(approval.runId)) return;
    if (approval.source === 'CLARIFICATION') {
      dispatch({
        type: 'waitingInput',
        approvalId: approval.id,
        runId: approval.runId,
        question: approval.content?.trim() || approval.reason || '',
      });
      return;
    }
    dispatch({
      type: 'approvalRequired',
      approvalId: approval.id,
      runId: approval.runId,
      toolName: approval.toolName ?? 'unknown tool',
      riskTier: approval.riskTier,
      toolPolicy: approval.toolPolicy,
      args: approval.arguments,
    });
  }, [isThisConversationsRun]);

  useEffect(() => {
    const subscription = subscribe((event) => {
      switch (event.type) {
        case 'approval.requested': {
          const approvalId = String(event.payload?.approvalId ?? '');
          if (!approvalId) return;
          // The frame names the run: a foreign conversation's ask is not read at
          // all. An absent run id falls through to the by-id read, which decides.
          const runId = String(event.payload?.runId ?? '');
          if (runId !== '' && !isThisConversationsRun(runId)) return;
          void adoptAskById(approvalId);
          return;
        }
        case 'approval.decided': {
          const approvalId = String(event.payload?.approvalId ?? '');
          if (!approvalId) return;
          const decision = String(event.payload?.decision ?? '').toUpperCase();
          dispatch({
            type: 'approvalSettled',
            approvalId,
            state: decision === 'APPROVED' ? 'approved' : decision === 'DENIED' ? 'denied' : 'expired',
          });
          dispatch({ type: 'clarificationSettled', approvalId, state: decision === 'APPROVED' ? 'answered' : 'expired' });
          return;
        }
        case 'approval.expired': {
          const approvalId = String(event.payload?.approvalId ?? '');
          if (!approvalId) return;
          // The frame carries the backend's own reason ("Auto-rejected: approval
          // expired"), which the Review panel shows; the card says the same.
          const reason = String(event.payload?.reason ?? '');
          dispatch({
            type: 'approvalSettled',
            approvalId,
            state: 'expired',
            ...(reason !== '' ? { reason } : {}),
          });
          dispatch({ type: 'clarificationSettled', approvalId, state: 'expired' });
          return;
        }
        case 'run.waiting_input': {
          // The frame carries no approvalId, so it cannot key a card itself; it
          // does mean a run just parked, and the snapshot holds the ask (A2).
          const id = conversationIdRef.current;
          if (id) void reconcile(id).catch(() => { /* a background reconcile is best-effort */ });
          return;
        }
        case 'run.completed': {
          // Only this conversation's live turn justifies a re-read; another
          // run's completion is not part of this transcript.
          const runId = String(event.payload?.runId ?? '');
          if (!runId || runId !== activeRunIdRef.current) return;
          const id = conversationIdRef.current;
          if (id) void reconcile(id).catch(() => { /* a background reconcile is best-effort */ });
          return;
        }
        default:
          return;
      }
    });
    return () => subscription.unsubscribe();
  }, [subscribe, reconcile, adoptAskById, isThisConversationsRun]);

  // Reconcile when the socket comes back: frames missed while it was down are
  // recovered from the durable snapshot rather than from a replay.
  const wasConnectedRef = useRef(isConnected);
  useEffect(() => {
    if (isConnected && !wasConnectedRef.current) {
      const id = conversationIdRef.current;
      if (id) void reconcile(id).catch(() => { /* reconnect reconcile is best-effort */ });
    }
    wasConnectedRef.current = isConnected;
  }, [isConnected, reconcile]);

  // …and when the operator returns to the tab (an ask may have been decided elsewhere).
  useEffect(() => {
    const onFocus = () => {
      const id = conversationIdRef.current;
      if (id) void reconcile(id).catch(() => { /* a focus read is best-effort */ });
    };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [reconcile]);

  const togglePanel = useCallback(() => setOpen((v) => !v), []);
  const closePanel = useCallback(() => setOpen(false), []);

  /**
   * Replaces the old "the request may have timed out. Please try again." text
   * after the client gave up or the stream died before `done`. A turn the backend
   * marked FAILED is the known case: its enriched error payload (A4) pinpoints
   * the failure, so the panel reports that actual reason and re-offers Retry
   * (the run is provably dead). A legacy stream error (no enrichment) is first
   * reconciled against the conversation timeline: the failed turn's synthetic
   * entry (A1) carries the same truth, so it too resolves to the failed-turn
   * bubble whenever it is present. Only for a run whose state stays unknown —
   * and always for a timeout — the panel asks the review queue once and reports
   * what it actually knows: a pending-ask count, "no approval pending", or —
   * when the operator-only queue refuses the read (401/403) or the probe fails —
   * that it could not verify. In that unknown case never a retry prompt:
   * resending would pile a second prompt onto a possibly live run.
   */
  const reportRunUncertain = useCallback(
    async (eventConversationId: string, origin: 'timeout' | 'stream-error', errorDetail?: StreamError) => {
      if (errorDetail) console.warn('[Aria] stream ended before done:', errorDetail);

      const failureReason = turnFailureReason(errorDetail);
      if (failureReason !== null) {
        dispatch({
          type: 'turnFailed',
          id: crypto.randomUUID(),
          content: `⚠ 上一回合失敗：${failureReason}`,
          // The turn is provably dead — a resend of its prompt is safe to offer.
          noRetry: false,
        });
        return;
      }

      // Legacy path (no enriched turnFailed payload — e.g. the engine's own
      // error events): the timeline's failed-turn entry is the truthful source,
      // so consult it before falling back to the approvals copy. A timeout is
      // deliberately excluded — it never proves the run died, so it keeps
      // today's copy without this read.
      if (origin === 'stream-error') {
        const failedEntry = await readFailedTurnEntry(eventConversationId);
        if (failedEntry) {
          const reason = failedEntry.content.startsWith(FAILED_TURN_CONTENT_PREFIX)
            ? failedEntry.content.slice(FAILED_TURN_CONTENT_PREFIX.length)
            : failedEntry.content;
          dispatch({
            type: 'turnFailed',
            id: crypto.randomUUID(),
            content: `⚠ 上一回合失敗：${reason}`,
            // The failed turn is provably dead — Retry resends its own prompt.
            noRetry: false,
            retryText: failedEntry.retryPrompt,
          });
          return;
        }
      }

      let pendingCount: number | null = null; // null until the queue actually answers
      let checkRefused = false;
      try {
        pendingCount = (await listApprovals('PENDING')).length;
      } catch (err) {
        // 401/403 = the operator-only route refused this tab (no operator
        // session); anything else is a real failure. Either way the run state
        // stays unknown and is reported as such, never as a failure.
        checkRefused = isOperatorRejection(err);
        console.warn(`[Aria] pending-approval check ${checkRefused ? 'refused' : 'failed'}:`, err);
      }

      const paragraphs: string[] = [];
      if (pendingCount !== null && pendingCount > 0) {
        paragraphs.push(
          `Aria may be waiting for your approval — ${pendingCount} pending ask${pendingCount === 1 ? '' : 's'} in the Review Queue. ` +
            'The request continues in the background; approve or deny there to resume it.',
        );
      } else if (pendingCount === 0) {
        paragraphs.push(
          'Aria has not answered and no approval is pending — the run may still be alive in the background; check the Runs page.',
        );
      } else {
        paragraphs.push(
          'Aria may be waiting for your approval — open the Review Queue to check; the request continues in the background.',
        );
        if (checkRefused) {
          paragraphs.push('The pending-approval check was refused (operator-only), so this tab could not verify the queue itself.');
        }
      }
      paragraphs.push(`Conversation ID: \`${eventConversationId}\` (include this when reporting issues)`);

      dispatch({
        type: 'turnFailed',
        id: crypto.randomUUID(),
        content: `⚠ ${paragraphs.join('\n\n')}`,
        // A client timeout never proves the run died, and a pending ask proves
        // it is alive and waiting — neither may be answered with a resend.
        noRetry: origin === 'timeout' || (pendingCount !== null && pendingCount > 0),
      });
    },
    [],
  );

  const sendStreamed = useCallback(
    (rawText: string, skillIdOverride?: string | undefined) => {
      const text = rawText.trim();
      // An unanswered decision owns the turn: a second prompt would pile another
      // turn onto a parked conversation.
      if (!text || busy || gated) return;

      const skillId = skillIdOverride ?? pendingSkillId;

      // Abort any prior in-flight stream defensively.
      abortRef.current?.abort();      cancelledRef.current = false; // M1: reset per-request cancel state
      activeRunIdRef.current = null;
      turnIdRef.current = '';
      const ctrl = new AbortController();
      abortRef.current = ctrl;

      // Snapshot prior history BEFORE the new message goes on screen.
      const history = items.flatMap((item) => (item.kind === 'user' || item.kind === 'assistant'
        ? [{ role: item.kind, content: item.content }]
        : []));
      dispatch({
        type: 'userSent',
        id: crypto.randomUUID(),
        content: text,
        timestamp: new Date().toISOString(),
      });
      // The previous turn is no longer the transcript's trailing one, so a
      // queued pill naming its run would be stale until the next reconcile.
      dispatch({ type: 'queuedRuns', runIds: [] });
      setInput('');
      setBusy(true);
      setLastSentMessage(text);
      lastSentSkillIdRef.current = skillId;
      slash.clearSkill();

      // A turn belongs to the conversation it was sent in. Frames from a turn
      // whose conversation the operator has since cleared or replaced must not
      // write into the new transcript — the parked run keeps living on the
      // server, it just stops owning this panel.
      const turnConversationId = conversationId;
      const staleTurn = (): boolean => conversationIdRef.current !== turnConversationId;

      // The live turn's activity strip is keyed by the run id: Task 4's reducer
      // groups restored strips by runId, so a live strip must share that key to be
      // replaced — never duplicated — by the durable rows after a reconcile.
      const turnId = (): string => {
        if (!turnIdRef.current) {
          turnIdRef.current = activeRunIdRef.current ?? `turn:${crypto.randomUUID()}`;
        }
        return turnIdRef.current;
      };

      void streamMessage(
        conversationId,
        text,
        history,
        {
          onThinking: (runId) => {
            if (staleTurn()) return;
            if (runId) {
              activeRunIdRef.current = runId; // M1: capture run id for cancel
              turnIdRef.current = runId;
              // This turn is this conversation's: a WS ask carrying this run id
              // belongs in this transcript even though its rows may not have
              // landed yet.
              conversationRunIdsRef.current.add(runId);
            }
          },
          onToolCall: (name) => {
            if (staleTurn()) return;
            dispatch({ type: 'toolCall', turnId: turnId(), name });
          },
          onToolResult: (name, result, ok) => {
            if (staleTurn()) return;
            dispatch({ type: 'toolResult', turnId: turnId(), name, result, ok });
          },
          onMessage: (content) => {
            if (staleTurn()) return;
            dispatch({
              type: 'assistantMessage',
              id: crypto.randomUUID(),
              content: content || 'No response received.',
              timestamp: new Date().toISOString(),
            });
          },
          onApprovalRequired: (ask) => {
            if (staleTurn()) return;
            dispatch({
              type: 'approvalRequired',
              approvalId: ask.approvalId,
              runId: ask.runId || activeRunIdRef.current || '',
              toolName: ask.toolName,
              riskTier: ask.riskTier,
              toolPolicy: ask.toolPolicy,
            });
          },
          onWaitingInput: (ask) => {
            if (staleTurn()) return;
            dispatch({
              type: 'waitingInput',
              approvalId: ask.approvalId,
              runId: ask.runId || activeRunIdRef.current || '',
              question: ask.question,
            });
          },
          onMemoryProposal: (signal) => {
            if (staleTurn()) return;
            const proposal = proposalSummary(signal);
            if (proposal === null) return;
            dispatch({ type: 'proposalCreated', proposal });
          },
          onDone: (data) => {
            if (staleTurn()) return;
            if (timeoutRef.current) clearTimeout(timeoutRef.current);
            // Adopt the server-issued conversationId so multi-turn context persists (#36).
            if (data?.conversationId) {
              setConversationId((prev) => prev || data.conversationId);
            }
            setBusy(false);
          },
          onError: (errorDetail) => {
            if (staleTurn()) return;
            if (cancelledRef.current) return; // M1: user cancelled — don't show an error bubble
            if (timeoutRef.current) clearTimeout(timeoutRef.current);
            setBusy(false);
            void reportRunUncertain(conversationId, 'stream-error', errorDetail);
          },
        },
        ctrl.signal,
        { isCancelled: () => cancelledRef.current, skillId },
      );

      // Client-side timeout: if no response in CLIENT_TIMEOUT_MS, abort the stream
      // but report the run as unverified (it may be waiting on an approval).
      timeoutRef.current = setTimeout(() => {
        ctrl.abort();
        setBusy(false);
        void reportRunUncertain(conversationId, 'timeout');
      }, CLIENT_TIMEOUT_MS);
    },
    [busy, gated, items, conversationId, pendingSkillId, reportRunUncertain],
  );

  // One-click synthesis (Feature B2): the batch-completion notification composes
  // the prompt and hands it over as an `aria:compose` window event; the panel
  // owns the SSE path, so the event goes through sendStreamed like a typed
  // message. Removing the listener on unmount keeps a stale panel from sending.
  useEffect(() => {
    const handler = (e: Event) => {
      const prompt = (e as CustomEvent<{ prompt?: string }>).detail?.prompt;
      if (typeof prompt === 'string' && prompt.trim()) {
        // The 彙整 button lives outside the panel: make the panel visible before
        // the turn runs, otherwise the composed prompt streams with no feedback.
        setOpen(true);
        // Residual (accepted, no queue): while a turn is already in flight
        // sendStreamed drops the composed prompt — the now-visible panel shows
        // the busy state instead of the click doing nothing on a closed one.
        sendStreamed(prompt);
      }
    };
    window.addEventListener('aria:compose', handler);
    return () => window.removeEventListener('aria:compose', handler);
  }, [sendStreamed]);

  const handleSend = useCallback(() => {
    sendStreamed(input);
  }, [input, sendStreamed]);

  const tail = items[items.length - 1];

  const handleRetry = useCallback(() => {
    if (busy || gated) return;
    // A failed-turn timeline entry knows its own prompt; a stream-sourced failure
    // resends the prompt this tab last sent.
    const retryText = (tail?.kind === 'assistant' ? tail.retryText : undefined) ?? lastSentMessage;
    if (!retryText) return;
    sendStreamed(retryText, lastSentSkillIdRef.current);
  }, [busy, gated, tail, lastSentMessage, sendStreamed]);

  /** Settles the asks of a run that can no longer decide them (a cancelled run). */
  const settleRunAsks = useCallback((runId: string, reason: string) => {
    for (const item of itemsRef.current) {
      if (item.kind !== 'approval' && item.kind !== 'clarification') continue;
      if (item.state !== 'pending' || item.runId !== runId) continue;
      dispatch({ type: 'approvalSettled', approvalId: item.approvalId, state: 'expired', reason });
      dispatch({ type: 'clarificationSettled', approvalId: item.approvalId, state: 'expired' });
    }
  }, []);

  const handleCancel = useCallback(() => {
    cancelledRef.current = true; // M1: suppress the false close-error bubble
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    abortRef.current?.abort();
    // M1: cancel the server-side run so it stops consuming tokens (best-effort; runId
    // is known once the first `thinking` event arrives).
    const cancelledRunId = activeRunIdRef.current;
    if (cancelledRunId) {
      cancelRun(cancelledRunId).then(() => {
        // The run is cancelled, so an ask it raised can no longer be decided:
        // the card says why instead of staying actionable behind a locked
        // composer (a decision on it would only 409).
        settleRunAsks(cancelledRunId, 'the run was cancelled before this ask was decided');
      }).catch((err) => {
        dispatch({
          type: 'turnFailed',
          id: crypto.randomUUID(),
          content: 'The server-side cancel could not be confirmed: '
            + apiErrorMessage(err, 'unknown error'),
        });
      });
    }
    setBusy(false);
    dispatch({
      type: 'assistantMessage',
      id: crypto.randomUUID(),
      content: 'Request cancelled.',
      timestamp: new Date().toISOString(),
    });
  }, [settleRunAsks]);

  const handleCopyConversationId = useCallback(() => {
    navigator.clipboard.writeText(conversationId).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }).catch(() => {
      /* ignore clipboard errors */
    });
  }, [conversationId]);

  const handleNewConversation = useCallback(() => {
    if (conversationId) {
      deleteConversation(conversationId).catch(() => {
        /* ignore backend errors — clear locally regardless */
      });
    }
    const newConversationId = crypto.randomUUID();
    // Stop reading the cleared conversation's turn here; the run itself stays
    // alive on the server and is reconcilable later. Without this, a parked
    // turn's frames would be drawn into the new transcript.
    cancelledRef.current = true;
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    abortRef.current?.abort();
    activeRunIdRef.current = null;
    // The cleared conversation's runs are no longer this panel's: a late WS ask
    // for one of them must not become a card in the new transcript.
    conversationRunIdsRef.current = new Set();
    adoptConversationId(newConversationId);
    // A new conversation is a new transcript: the reducer drops the prior items
    // because the conversation id changed.
    dispatch({ type: 'restore', conversationId: newConversationId, timeline: [], approvals: [], proposals: [] });
    console.log(`[Aria] New conversation: ${newConversationId}`);
    setInput('');
    setBusy(false);
    setLastSentMessage(null);
    lastSentSkillIdRef.current = undefined;
    turnIdRef.current = '';
    slash.clearSkill();
  }, [conversationId, adoptConversationId, slash]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Slash-command menu keyboard navigation takes priority
    if (slash.open) {
      if (e.key === 'ArrowDown') { e.preventDefault(); slash.move(1); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); slash.move(-1); return; }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        if (slash.items.length > 0) {
          slash.selectCurrent();
          setInput(''); // mirror the mouse path — leave no `/xxx` residue (fix 2)
          return;
        }
        // Zero matches: fall through so Enter sends the raw text as a normal message (fix 2).
      }
      if (e.key === 'Escape') { e.preventDefault(); slash.dismiss(); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    } else if (e.key === 'Escape') {
      closePanel();
    }
  };

  // Only the trailing bubble may offer a retry; a run-uncertain bubble (noRetry)
  // must never re-expose a retry control.
  const lastIsRetryable = tail?.kind === 'assistant' && tail.error === true && !tail.noRetry;

  // m2: memoize rendered markdown so it only recomputes when the transcript changes.
  const assistantHtml = useMemo(() => {
    const rendered = new Map<string, string>();
    for (const item of items) {
      if (item.kind === 'assistant') rendered.set(item.id, renderMarkdown(item.content));
    }
    return rendered;
  }, [items]);

  return (
    <>
      <button
        type="button"
        className={`ai-fab ${open ? 'ai-fab-active' : ''}`}
        onClick={togglePanel}
        aria-label={open ? 'Close Aria panel' : 'Open Aria panel'}
        aria-expanded={open}
      >
        <span className="ai-fab-halo" aria-hidden="true" />
        <span className="ai-fab-glyph" aria-hidden="true">✦</span>
        <span className="ai-fab-dot" aria-hidden="true" />
      </button>

      {open && (
        <section className="ai-panel" role="dialog" aria-label="Aria assistant">
          <header className="ai-header">
            <div className="ai-header-stripe" aria-hidden="true" />
            <div className="ai-avatar" aria-hidden="true">
              <span>A</span>
            </div>
            <div className="ai-header-text">
              <div className="ai-header-title">Aria</div>
              <div className="ai-header-sub">
                <span className="ai-pulse-dot" aria-hidden="true" />
                {loading ? 'Connecting…' : 'Personal Assistant · always on'}
              </div>
            </div>
            <div className="ai-session-info">
              <code className="ai-conversation-id" title={conversationId}>
                {conversationId.slice(0, 8)}...
              </code>
              <button
                type="button"
                className="ai-copy-btn"
                onClick={handleCopyConversationId}
                aria-label="Copy conversation ID"
                title="Copy conversation ID to clipboard"
              >
                {copied ? '✓' : '📋'}
              </button>
              <button
                type="button"
                className="ai-clear-btn"
                onClick={handleNewConversation}
                aria-label="Clear and start new conversation"
                title="Clear and start new conversation"
              >
                Clear
              </button>
            </div>
            <button
              type="button"
              className="ai-close"
              onClick={closePanel}
              aria-label="Close Aria"
            >
              ×
            </button>
          </header>

          <div className="ai-stack" ref={stackRef} aria-live="polite" aria-relevant="additions">
            {isEmpty && (
              <div className="ai-card ai-empty">
                <div className="ai-empty-eyebrow">— operator copilot</div>
                <div className="ai-empty-title">
                  Good to see you. <em>How can I help?</em>
                </div>
                <div className="ai-empty-sub">
                  I can brief you on agents, runs, approvals, and the knowledge
                  base — and I can act on what you ask.
                </div>
              </div>
            )}

            {historyError && (
              <div role="alert" style={{ margin: '8px 12px', color: '#ff8d99', fontSize: 11.5 }}>
                {historyError}
              </div>
            )}

            {items.map((item) => {
              if (item.kind === 'activity') {
                return <AriaActivityStrip key={item.id} item={item} />;
              }
              if (item.kind === 'approval') {
                return (
                  <AriaApprovalCard
                    key={item.id}
                    item={item}
                    onSettled={(settled, reason) => dispatch({
                      type: 'approvalSettled',
                      approvalId: item.approvalId,
                      state: settled,
                      reason,
                    })}
                  />
                );
              }
              if (item.kind === 'clarification') {
                return (
                  <AriaClarificationCard
                    key={item.id}
                    item={item}
                    onSettled={(settled) => dispatch({
                      type: 'clarificationSettled',
                      approvalId: item.approvalId,
                      state: settled,
                    })}
                  />
                );
              }
              if (item.kind === 'queued') {
                return (
                  <div key={item.id} className="ai-queued-pill">
                    <span className="pill dim">
                      Queued · {item.runIds.map((runId) => runId.slice(0, 8)).join(', ')}
                    </span>
                  </div>
                );
              }
              if (item.kind === 'proposal') {
                // The card owns the decide call (Task 12a): the panel only mounts
                // it and settles the item, so one decision renders one settlement.
                // A pending proposal counts toward `hasUnansweredCard`, so it must
                // be drawn with its own decision controls — gating the composer on
                // a card the operator cannot act on is an invisible lock. A
                // `decided-elsewhere` item renders the same card, which says why it
                // settled and drops the controls that could only 409.
                return (
                  <AriaProposalCard
                    key={item.id}
                    item={item}
                    onSettled={(proposalId, settled) => dispatch({
                      type: 'proposalSettled',
                      proposalId,
                      state: settled,
                    })}
                  />
                );
              }
              return (
                <div key={item.id} className={`ai-msg ai-msg-${item.kind}`}>
                  <div className="ai-msg-avatar" aria-hidden="true">
                    {item.kind === 'user' ? 'You' : 'A'}
                  </div>
                  <div className="ai-msg-body">
                    <div className={`ai-msg-bubble ${item.kind === 'assistant' && item.error ? 'ai-msg-bubble-error' : ''}`}>
                      {item.kind === 'assistant' ? (
                        <div
                          className="ai-msg-md markdown-content"
                          dangerouslySetInnerHTML={{ __html: assistantHtml.get(item.id) ?? '' }}
                        />
                      ) : (
                        <div className="ai-msg-text">{item.content}</div>
                      )}
                    </div>
                    <div className="ai-msg-meta">
                      {item.kind === 'user' ? 'You' : 'Aria'} ·{' '}
                      {formatTimestamp(item.timestamp)}
                      {item.kind === 'assistant' && item.error === true && lastIsRetryable && item.id === tail?.id && (
                        <button
                          type="button"
                          className="ai-retry"
                          onClick={handleRetry}
                          disabled={busy}
                        >
                          Retry
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}

            {busy && (
              <div className="ai-msg ai-msg-assistant">
                <div className="ai-msg-avatar" aria-hidden="true">
                  A
                </div>
                <div className="ai-msg-body">
                  <div className="ai-msg-bubble ai-msg-bubble-thinking">
                    <div className="typing-indicator" aria-label="Aria is thinking">
                      <span className="typing-dot" />
                      <span className="typing-dot" />
                      <span className="typing-dot" />
                    </div>
                  </div>
                </div>
              </div>
            )}

            {isEmpty && (
              <div className="ai-suggestions" role="group" aria-label="Suggested prompts">
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s}
                    type="button"
                    className="ai-sg"
                    onClick={() => sendStreamed(s)}
                    disabled={busy || gated}
                  >
                    {s}
                  </button>
                ))}
              </div>
            )}
          </div>

          {gated && (
            <div className="ai-gate-hint" role="status">
              {/* Name only what the cards actually offer: Allow/Deny on an
                  approval, Answer on a question. There is no cancel affordance
                  in this panel, so the copy must not promise one. */}
              Answer the pending decision first.
            </div>
          )}

          <div className="ai-compose">
            {slash.open && (
              <SlashCommandMenu
                items={slash.items}
                activeIndex={slash.activeIndex}
                onHover={(i) => slash.move(i - slash.activeIndex)}
                onSelect={(item) => { slash.choose(item); setInput(''); }}
              />
            )}
            {slash.pendingSkill && !slash.open && (
              <div className="ai-skill-chip">
                <span>/{slash.pendingSkill.name}</span>
                <button type="button" onClick={slash.clearSkill} aria-label="Remove skill">✕</button>
              </div>
            )}
            <textarea
              ref={textareaRef}
              className="ai-compose-input"
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                slash.onInputChange(e.target.value, e.target.selectionStart ?? e.target.value.length);
              }}
              onKeyDown={handleKeyDown}
              placeholder="Ask Aria anything…  (type / for skills)"
              rows={2}
              disabled={busy || gated}
              aria-controls={slash.open ? 'slash-command-listbox' : undefined}
              aria-activedescendant={slash.open && slash.items[slash.activeIndex] ? `slash-opt-${slash.items[slash.activeIndex].id}` : undefined}
            />
            {busy ? (
              <button
                type="button"
                className="ai-cancel-btn"
                onClick={handleCancel}
                aria-label="Cancel request"
              >
                <span aria-hidden="true">✕</span>
                <span>Cancel</span>
              </button>
            ) : (
              <button
                type="button"
                className="ai-action-btn"
                onClick={handleSend}
                disabled={!input.trim() || gated}
                aria-label="Send message"
              >
                <span className="ai-action-glyph" aria-hidden="true">↗</span>
                <span className="ai-action-label">Send</span>
              </button>
            )}
          </div>
          <div className="ai-hint">
            Aria streams live · acts on the live system · review approvals before destructive moves.
          </div>
        </section>
      )}
    </>
  );
}

export default AriaPanel;
