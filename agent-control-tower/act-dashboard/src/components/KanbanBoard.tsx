import { useEffect, useMemo, useState } from 'react';
import type { DragEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createKanbanItem, listKanbanItems, transitionKanbanItem } from '../api/kanban';
import type { KanbanRejection } from '../api/kanban';
import { executeHousekeeping } from '../api/housekeeping';
import { apiErrorMessage } from '../api/operatorSession';
import { presentError, type PresentedError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { listAgentTemplates, listAgents } from '../api/agents';
import type {
  CreateKanbanItemRequest,
  KanbanItem,
  KanbanPriority,
  KanbanStatus,
} from '../types';
import { useWebSocketContext } from './Layout';
import { isKanbanEvent, isRunLifecycleEvent } from '../utils/wsEvents';
import { queryPhase } from '../utils/queryState';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { StateNotice } from './StateNotice';
import { ConfirmDialog } from './ConfirmDialog';
import { BoardColumn } from './kanban/BoardColumn';
import { KanbanCard } from './kanban/KanbanCard';
import { Button } from './ui/button';
// Canonical dispatcher — DrawerContext reads detail.itemId; a local variant
// that sent { id } silently swallowed every card click (TaskDrawer never opened).
import { dispatchOpenTaskDrawer, useDrawerContext } from './DrawerContext';

interface ColumnDef {
  key: KanbanStatus;
  label: string;
  isGate?: boolean;
  filter: (item: KanbanItem) => boolean;
}

const COLUMNS: ColumnDef[] = [
  { key: 'BACKLOG', label: 'Backlog', filter: (it) => it.status === 'BACKLOG' },
  { key: 'TODO', label: 'Todo', filter: (it) => it.status === 'TODO' },
  { key: 'IN_PROGRESS', label: 'In Progress', filter: (it) => it.status === 'IN_PROGRESS' },
  { key: 'REVIEW', label: 'Review', isGate: true, filter: (it) => it.status === 'REVIEW' },
  { key: 'DONE', label: 'Done', filter: (it) => it.status === 'DONE' },
];

// Mirror of the backend ALLOWED_TRANSITIONS — drives drop-target legality only.
// BLOCKED is retired; CANCELLED is reachable only via the card ✕ / deny actions.
// D3: BACKLOG is queued and never dispatches — a Backlog card must go through TODO first.
// Defect D3: DONE is re-doable — redo re-enters the flow at BACKLOG/TODO only.
const LEGAL_DROPS: Record<KanbanStatus, KanbanStatus[]> = {
  BACKLOG: ['TODO', 'CANCELLED'],
  TODO: ['IN_PROGRESS', 'BACKLOG', 'CANCELLED'],
  IN_PROGRESS: ['TODO', 'BACKLOG', 'REVIEW', 'DONE', 'CANCELLED'],
  REVIEW: ['IN_PROGRESS', 'TODO', 'DONE', 'CANCELLED'],
  DONE: ['BACKLOG', 'TODO'],
  CANCELLED: [],
  BLOCKED: [],
};

/* Task 8: REVIEW-column batch decisions (spec D2/D3/D4). The header buttons
   capture the affected card set from the runOutcome filters; the executor is a
   sequential client loop over the existing per-card transition endpoint — one
   refusal never aborts the batch. */
type BatchKind = 'ACCEPT' | 'REWORK' | 'CANCEL';

const BATCH_STATUS: Record<BatchKind, KanbanStatus> = {
  ACCEPT: 'DONE',
  REWORK: 'TODO',
  CANCEL: 'CANCELLED',
};

const BATCH_VERB: Record<BatchKind, string> = {
  ACCEPT: 'Accepted',
  REWORK: 'Reworked',
  CANCEL: 'Cancelled',
};

function batchConfirmTitle(kind: BatchKind): string {
  switch (kind) {
    case 'ACCEPT':
      return 'Accept all completed — confirmation required';
    case 'REWORK':
      return 'Rework all failed — confirmation required';
    case 'CANCEL':
      return 'Cancel all review cards — confirmation required';
  }
}

/** Confirm copy: the affected card count, plus the dispatch for Rework (D3). */
function batchConfirmMessage(kind: BatchKind, n: number): string {
  const cards = `${n} card${n === 1 ? '' : 's'}`;
  switch (kind) {
    case 'ACCEPT':
      return `This accepts ${cards}. Each card moves to Done and its pending review asks are settled.`;
    case 'REWORK':
      return `This reworks ${cards} — this will dispatch ${n} new run${n === 1 ? '' : 's'}. `
        + 'Each card moves to Todo and its pending review asks are settled.';
    case 'CANCEL':
      return `This cancels ${cards}. Each card leaves the board (CANCELLED) and its pending review asks are denied.`;
  }
}

/** One-line failure reason for the batch summary (backend body when present). */
function batchFailureReason(err: unknown): string {
  return apiErrorMessage(err, (err as Error)?.message ?? 'transition refused');
}

interface NewItemDraft {
  title: string;
  description: string;
  priority: KanbanPriority;
  agentTemplateId: string;
}

const EMPTY_DRAFT: NewItemDraft = { title: '', description: '', priority: 'MEDIUM', agentTemplateId: '' };

export default function KanbanBoard() {
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [draft, setDraft] = useState<NewItemDraft>(EMPTY_DRAFT);
  const [error, setError] = useState<string | null>(null);
  // The quick-clear goes through the operator-gated housekeeping batch, so its
  // failure is an API error, not a board message: a refusal must name the fix.
  const [clearError, setClearError] = useState<unknown | null>(null);
  const [createError, setCreateError] = useState<{
    presented: PresentedError;
    details?: KanbanRejection['details'];
  } | null>(null);
  // Flash kinds: ok/err = transition result; assign = "Aria assigning…" while
  // the pickup's assign phase runs (spec 4.2).
  const [flash, setFlash] = useState<{ itemId: string; kind: 'ok' | 'err' | 'assign' } | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  // Inline card composer: which REVIEW card is currently asking "what should change?".
  const [feedbackFor, setFeedbackFor] = useState<string | null>(null);
  const [cardFeedback, setCardFeedback] = useState('');
  // Task 10: the card ✕ requests a cancel; the confirmation modal owns it.
  const [confirmCancelId, setConfirmCancelId] = useState<string | null>(null);
  // Task 8: a REVIEW-column batch button captures its action + affected cards;
  // the confirm dialog runs it. batchRunning disables the buttons mid-loop.
  const [batchAction, setBatchAction] = useState<{ kind: BatchKind; items: KanbanItem[] } | null>(
    null
  );
  const [batchRunning, setBatchRunning] = useState(false);
  const { lastMessage } = useWebSocketContext();
  const { openReviewMode } = useDrawerContext();

  // S6: resolve linkedAgentId → agent name for attribution badges.
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: () => listAgents() });
  const agentNameById = useMemo(() => {
    const m = new Map<string, string>();
    (agents ?? []).forEach((a) => m.set(a.id, a.name));
    return m;
  }, [agents]);

  // New Task modal: agent templates for the Assign-to picker.
  const { data: templates } = useQuery({
    queryKey: ['agent-templates'],
    queryFn: listAgentTemplates,
  });

  const { data: items, isPending, isError, isSuccess, refetch } = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 12000,
  });
  const phase = queryPhase({ data: items, isPending, isError, isSuccess });
  const narrow = useMediaQuery('(max-width: 900px)');
  const [selectedLane, setSelectedLane] = useState<KanbanStatus>('TODO');

  // React instantly to kanban or run lifecycle events from WebSocket.
  // S1 whitelist: high-frequency streaming events (run.progress) must NOT
  // invalidate the board list — they are consumed precisely elsewhere.
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (isKanbanEvent(t) || isRunLifecycleEvent(t)) {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    }
    // Waiting-input (2026-10-05): the parked run's CLARIFICATION ask must reach
    // the approvals surfaces (ReviewQueue shares this page) and the decision
    // panels (TaskDrawer / ReviewWorkspace key their asks ['kanban', 'asks',
    // itemId]) without a poll.
    if (t === 'run.waiting_input') {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['kanban', 'asks'] });
    }
    // S6: flash the moved card so live agent moves are visible.
    if (t === 'kanban.transitioned') {
      const payload = lastMessage.payload ?? {};
      const itemId = payload.itemId as string | undefined;
      if (itemId) {
        setFlash({ itemId, kind: payload.toStatus === 'CANCELLED' ? 'err' : 'ok' });
      }
    }
    // Spec 4.2: "Aria assigning…" indicator during the pickup's assign phase.
    // Broadcast while the transition request is in flight, so the initiating
    // client has already moved the card optimistically — the indicator mainly
    // serves concurrent viewers.
    if (t === 'kanban.assigning') {
      const payload = lastMessage.payload ?? {};
      const itemId = payload.itemId as string | undefined;
      if (itemId) {
        setFlash({ itemId, kind: 'assign' });
      }
    }
  }, [lastMessage, queryClient]);

  // S6: clear the flash after the animation window.
  useEffect(() => {
    if (!flash) return;
    const h = setTimeout(() => setFlash(null), 1200);
    return () => clearTimeout(h);
  }, [flash]);

  // Escape closes the new-task modal (mirrors ConfigureModal's window handler).
  useEffect(() => {
    if (!showCreate) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowCreate(false);
        setError(null);
        setCreateError(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [showCreate]);

  const createMutation = useMutation({
    mutationFn: (request: CreateKanbanItemRequest) => createKanbanItem(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      setShowCreate(false);
      setDraft(EMPTY_DRAFT);
      setError(null);
      setCreateError(null);
    },
    onError: (err: unknown) => {
      const rejection = (err as { response?: { data?: KanbanRejection } })?.response?.data;
      setCreateError({
        presented: presentError(err, 'Create failed.'),
        details: rejection?.details,
      });
    },
  });

  // Optimistic move with snap-back: the drop updates the cache immediately and
  // the transition call confirms it; on failure the invalidation refetch
  // restores the server state and the operator gets a rejection message.
  const transitionMutation = useMutation({
    mutationFn: ({ id, status, feedback }: { id: string; status: KanbanStatus; feedback?: string }) =>
      transitionKanbanItem(id, { status, feedback }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      // Task 10: a confirmed cancel is done — drop the pending confirmation.
      setConfirmCancelId(null);
      setError(null);
    },
    onError: (err: unknown, variables: { id: string; status: KanbanStatus; feedback?: string }) => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      // Task 10: the confirmed action is over (it failed) — never leave the
      // confirmation stuck open on a rejection.
      setConfirmCancelId(null);
      // A refused move answers 409 with {code, message, details}; malformed input
      // stays a 400 whose body carries only `message` (or the HTTP reason phrase
      // in `error`), so a missing code must degrade to the message.
      const data = (err as { response?: { data?: KanbanRejection & { error?: string } } } | null)
        ?.response?.data;
      const excluded = data?.details?.excluded
        ?.map((e) => `${e.name} (${e.reasons.join(', ')})`)
        .join('; ');
      // A Done refused because the linked run is still active is not fixed by
      // retrying the move: the card's pending asks are what keep that run alive,
      // so name them instead of leaving the operator with "complete or cancel".
      const pendingAsks = (items ?? []).find((i) => i.id === variables.id)?.pendingAskCount;
      const askHint = data?.code === 'LINKED_RUN_ACTIVE' && pendingAsks
        ? `Decide the ${pendingAsks} pending ask${pendingAsks === 1 ? '' : 's'} first`
          + " — open the card's decision panel."
        : null;
      const reason = [data?.code, data?.message ?? data?.error, excluded, askHint]
        .filter(Boolean)
        .join(' — ');
      setError(reason || 'Move rejected — the card is back in its column.');
    },
  });

  const handleDrop = (target: KanbanStatus) => {
    const id = draggingId;
    setDraggingId(null);
    if (!id) return;
    const item = (items ?? []).find((i) => i.id === id);
    if (!item || item.status === target) return;
    if (!LEGAL_DROPS[item.status]?.includes(target)) return;
    queryClient.setQueryData<KanbanItem[]>(['kanban-items'], (old) =>
      (old ?? []).map((i) => (i.id === item.id ? { ...i, status: target } : i))
    );
    transitionMutation.mutate({ id: item.id, status: target });
  };

  const grouped = useMemo(() => {
    const map: Record<string, KanbanItem[]> = {};
    COLUMNS.forEach((c) => (map[c.key] = []));
    (items ?? []).forEach((item) => {
      const matched = COLUMNS.find((c) => c.filter(item));
      if (matched) map[matched.key].push(item);
    });
    return map;
  }, [items]);

  const visibleColumns = narrow
    ? COLUMNS.filter((col) => col.key === selectedLane)
    : COLUMNS;

  // H2: quick-clear finished cards (DONE + CANCELLED) via the housekeeping batch.
  // CANCELLED has no column by design (cancel is an action, not a state column);
  // the housekeeping sweep is its only exit, so count both from the list.
  const [clearConfirmOpen, setClearConfirmOpen] = useState(false);
  const finishedCount = (items ?? []).filter(
    (i) => i.status === 'DONE' || i.status === 'CANCELLED'
  ).length;

  const handleCreate = (target: 'TODO' | 'BACKLOG') => {
    const title = draft.title.trim();
    if (!title) {
      setCreateError({ presented: { text: 'Title is required' } });
      return;
    }
    createMutation.mutate({
      title,
      description: draft.description.trim() || undefined,
      priority: draft.priority,
      agentTemplateId: draft.agentTemplateId || undefined,
      status: target,
    });
  };

  /**
   * Task 8 batch executor (spec error handling): sequential — one refusal never
   * aborts the rest — with a one-line per-batch summary on the board's status
   * surface. The list refetches after, regardless of outcome.
   */
  const runBatch = async () => {
    if (!batchAction || batchRunning) return;
    const { kind, items: batchItems } = batchAction;
    const status = BATCH_STATUS[kind];
    setBatchAction(null);
    setBatchRunning(true);
    setError(null);
    let accepted = 0;
    const failures: string[] = [];
    for (const item of batchItems) {
      try {
        await transitionKanbanItem(item.id, { status });
        accepted += 1;
      } catch (err) {
        failures.push(batchFailureReason(err));
      }
    }
    queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    queryClient.invalidateQueries({ queryKey: ['kanban'] });
    setBatchRunning(false);
    setError(
      failures.length
        ? `${BATCH_VERB[kind]} ${accepted}, ${failures.length} failed: ${failures.join('; ')}`
        : `${BATCH_VERB[kind]} ${accepted}.`
    );
  };

  // Legal drop targets for the card currently being dragged.
  const legalTargets: KanbanStatus[] = draggingId
    ? LEGAL_DROPS[(items ?? []).find((i) => i.id === draggingId)?.status ?? 'DONE'] ?? []
    : [];

  const handleCardDragStart = (event: DragEvent<HTMLDivElement>, id: string) => {
    // Deferred on purpose: mutating the dragged node synchronously inside
    // dragstart (data-dragging attr triggers a re-render) makes Chromium cancel
    // the native drag immediately, so no drop ever fires. (real browsers always
    // provide dataTransfer; jsdom synthetic events do not, hence the optional
    // chain)
    event.dataTransfer?.setData('text/plain', id);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
    setTimeout(() => setDraggingId(id), 0);
  };

  const handleSendRequestChanges = (id: string) => {
    transitionMutation.mutate({
      id,
      status: 'TODO',
      feedback: cardFeedback.trim() || undefined,
    });
    setFeedbackFor(null);
    setCardFeedback('');
  };

  return (
    <section className="panel relative flex flex-col gap-3" data-testid="kanban-board">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-sm font-semibold text-foreground">
          <span>Kanban Board</span>
          <span className="font-normal text-muted-foreground"> · Governed Flow</span>
        </h2>
        {finishedCount > 0 && (
          <Button
            variant="outline"
            size="sm"
            className="ml-auto"
            onClick={() => setClearConfirmOpen(true)}
          >
            🧹 Clear Done &amp; Cancelled ({finishedCount})
          </Button>
        )}
        <Button
          size="sm"
          className={finishedCount > 0 ? '' : 'ml-auto'}
          onClick={() => {
            setShowCreate(true);
            setError(null);
            setCreateError(null);
          }}
        >
          + New Item
        </Button>
      </div>

      {/* Board-level transition feedback (snap-back message per spec 5.1):
          the same error slot the modal uses, but visible while it is closed. */}
      {error && !showCreate && (
        <div className="kanban-form-error" role="status" style={{ margin: '0 0 8px 4px' }}>
          {error}
        </div>
      )}

      {clearError != null && (
        <ErrorNotice
          presented={presentError(clearError, 'Clearing the finished cards failed.')}
          operatorSession
          actionLabel="Clearing finished kanban cards"
        />
      )}

      {phase === 'loading' && <StateNotice kind="loading" title="Loading board…" />}
      {phase === 'error' && (
        <StateNotice
          kind="error"
          title="Board unavailable"
          message="The kanban items could not be loaded."
          actionLabel="Retry"
          onAction={() => { void refetch(); }}
        />
      )}
      {phase === 'stale' && (
        <StateNotice
          kind="stale"
          title="Refresh failed — showing last known board"
          actionLabel="Retry"
          onAction={() => { void refetch(); }}
        />
      )}
      {phase === 'success' && (items ?? []).length === 0 && (
        <StateNotice kind="empty" title="No tasks on the board" />
      )}

      {items !== undefined && (
        <div
          className={narrow ? 'kanban-mobile' : 'kanban'}
          role={narrow ? 'region' : undefined}
          aria-label={narrow ? 'Kanban board' : undefined}
        >
          {narrow && (
            <label className="kanban-lane-select">
              Lane
              <select
                aria-label="Select lane"
                value={selectedLane}
                onChange={(e) => setSelectedLane(e.target.value as KanbanStatus)}
              >
                {COLUMNS.map((col) => (
                  <option key={col.key} value={col.key}>{col.label}</option>
                ))}
              </select>
            </label>
          )}
          <div className={narrow ? 'kanban-mobile-list' : 'grid min-w-0 grid-cols-1 gap-3 md:grid-cols-5'}>
            {visibleColumns.map((col) => {
              const columnItems = grouped[col.key] ?? [];
              // A card with a pending native ask needs an individual decision, so
              // no batch preset may include it. Counts state only the eligible set.
              const batchItems = columnItems.filter((it) => !it.pendingAskCount);
              const heldByAsk = columnItems.length - batchItems.length;
              // Task 8: batch sets come from the same runOutcome filters the
              // buttons display.
              const completedItems = batchItems.filter((it) => it.runOutcome === 'COMPLETED');
              const failedItems = batchItems.filter((it) => it.runOutcome === 'FAILED');
              return (
                <BoardColumn
                  key={col.key}
                  column={col}
                  items={columnItems}
                  draggingId={draggingId}
                  legalTargets={legalTargets}
                  onDragOver={(event, key) => {
                    if (draggingId && legalTargets.includes(key)) event.preventDefault();
                  }}
                  onDrop={handleDrop}
                  batch={col.isGate ? {
                    running: batchRunning,
                    heldByAsk,
                    completedCount: completedItems.length,
                    failedCount: failedItems.length,
                    batchCount: batchItems.length,
                    onAcceptAll: () => setBatchAction({ kind: 'ACCEPT', items: completedItems }),
                    onReworkAll: () => setBatchAction({ kind: 'REWORK', items: failedItems }),
                    onCancelAll: () => setBatchAction({ kind: 'CANCEL', items: batchItems }),
                  } : undefined}
                  renderCard={(item) => (
                    <KanbanCard
                      key={item.id}
                      item={item}
                      isGate={Boolean(col.isGate)}
                      dragging={draggingId === item.id}
                      flash={flash?.itemId === item.id ? flash.kind : null}
                      agentName={item.linkedAgentId ? agentNameById.get(item.linkedAgentId) : undefined}
                      transitionPending={transitionMutation.isPending}
                      feedbackOpen={feedbackFor === item.id}
                      feedbackValue={cardFeedback}
                      onDragStart={handleCardDragStart}
                      onDragEnd={() => setDraggingId(null)}
                      onOpen={dispatchOpenTaskDrawer}
                      onQuickApprove={(id) => transitionMutation.mutate({ id, status: 'DONE' })}
                      onRequestChanges={(id) => {
                        setFeedbackFor(id);
                        setCardFeedback('');
                      }}
                      onFeedbackChange={setCardFeedback}
                      onCancelRequestChanges={() => {
                        setFeedbackFor(null);
                        setCardFeedback('');
                      }}
                      onSendRequestChanges={handleSendRequestChanges}
                      onQuickDeny={(id) => transitionMutation.mutate({ id, status: 'CANCELLED' })}
                      onExpandReview={openReviewMode}
                      onRequestCancel={setConfirmCancelId}
                    />
                  )}
                />
              );
            })}
          </div>
        </div>
      )}

      {showCreate && (
        <div
          className="modal-overlay"
          onClick={() => {
            setShowCreate(false);
            setError(null);
            setCreateError(null);
          }}
        >
          <div
            className="modal-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="New task"
            onClick={(e) => e.stopPropagation()}
          >
            <h3>New Task</h3>
            <div className="kanban-form">
              <label className="kanban-form-row">
                <span>Title *</span>
                <input
                  autoFocus
                  value={draft.title}
                  onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                />
              </label>
              <label className="kanban-form-row">
                <span>Description</span>
                <textarea
                  rows={7}
                  placeholder="Background, goal, acceptance criteria — Aria reads this first."
                  value={draft.description}
                  onChange={(e) => setDraft({ ...draft, description: e.target.value })}
                />
              </label>
              <div className="kanban-form-row">
                <span>Priority</span>
                <div className="priority-seg">
                  {(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as KanbanPriority[]).map((p) => (
                    <button
                      key={p}
                      className={draft.priority === p ? 'on' : ''}
                      onClick={() => setDraft({ ...draft, priority: p })}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </div>
              <label className="kanban-form-row">
                <span>Assign to</span>
                <select
                  value={draft.agentTemplateId}
                  onChange={(e) => setDraft({ ...draft, agentTemplateId: e.target.value })}
                >
                  <option value="">Aria auto-assign</option>
                  {/* UX-7: catalog roles and hired agents in distinguishable
                      groups. An agent option's VALUE is the agent's NAME and it
                      flows through the same agentTemplateId field — the
                      dispatcher's picker resolves exact agent names first
                      (AgentPickerService.pick). */}
                  {(templates ?? []).length > 0 && (
                    <optgroup label="Catalog roles">
                      {(templates ?? []).map((t) => (
                        <option key={t.id} value={t.id}>
                          {t.label}
                        </option>
                      ))}
                    </optgroup>
                  )}
                  {(agents ?? []).filter((a) => a.healthStatus !== 'RETIRED').length > 0 && (
                    <optgroup label="Live agents">
                      {(agents ?? [])
                        .filter((a) => a.healthStatus !== 'RETIRED')
                        .map((a) => (
                          <option key={a.id} value={a.name}>
                            {a.name}
                          </option>
                        ))}
                    </optgroup>
                  )}
                </select>
              </label>
              {createError && (
                <div className="kanban-create-error">
                  {/* kanban-form-error is the form-error contract the board's
                      E2E spec (and the red styling) already depends on; the
                      structured details below keep the served reasons. */}
                  <ErrorNotice className="kanban-form-error" presented={createError.presented} />
                  {!!createError.details?.reasons?.length && (
                    <ul className="create-error-details">
                      {createError.details.reasons.map((reason) => (
                        <li key={reason}>{reason}</li>
                      ))}
                    </ul>
                  )}
                  {!!createError.details?.excluded?.length && (
                    <ul className="create-error-details">
                      {createError.details.excluded.map((entry) => (
                        <li key={entry.name}>
                          <strong>{entry.name}</strong>
                          {entry.reasons.length > 0 ? `: ${entry.reasons.join(', ')}` : ''}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
              <div className="modal-actions">
                <button
                  className="btn primary"
                  disabled={createMutation.isPending}
                  onClick={() => handleCreate('TODO')}
                >
                  {createMutation.isPending ? 'Creating…' : 'Create in Todo'}
                </button>
                <button
                  className="btn"
                  disabled={createMutation.isPending}
                  onClick={() => handleCreate('BACKLOG')}
                >
                  Backlog
                </button>
                <button
                  className="btn"
                  onClick={() => {
                    setShowCreate(false);
                    setError(null);
                    setCreateError(null);
                  }}
                >
                  Close
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {clearConfirmOpen && (
        <div className="modal-overlay" onClick={() => setClearConfirmOpen(false)}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>⚠ Clear finished cards — approval required</h3>
            <p>
              This permanently deletes {finishedCount} DONE/CANCELLED cards via the
              housekeeping batch. In-flight columns are never touched.
            </p>
            <div className="modal-actions">
              <button
                className="btn danger"
                onClick={async () => {
                  setClearConfirmOpen(false);
                  setClearError(null);
                  try {
                    await executeHousekeeping({ categories: ['kanban'], confirm: true });
                  } catch (err) {
                    // Single-flight conflict, bad request, or an operator refusal:
                    // say what happened — and, on a refusal, what to do about it —
                    // then refresh anyway so the operator sees the current board.
                    setClearError(err);
                  } finally {
                    queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
                  }
                }}
              >
                Approve &amp; execute
              </button>
              <button className="btn" onClick={() => setClearConfirmOpen(false)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmCancelId !== null}
        title="Cancel task — confirmation required"
        message={
          <>
            This cancels card {confirmCancelId?.slice(0, 8)} and stops the work in progress on
            it. The card leaves the board; only the housekeeping sweep removes it.
          </>
        }
        danger
        onConfirm={() => {
          if (!confirmCancelId) return;
          const id = confirmCancelId;
          // Closed immediately: the mutation's onSuccess/onError clear the
          // pending state again so a rejection can never strand the dialog.
          setConfirmCancelId(null);
          // The only place the cancel transition is fired (Task 10).
          transitionMutation.mutate({ id, status: 'CANCELLED' });
        }}
        onCancel={() => setConfirmCancelId(null)}
      />

      {/* Task 8: one dialog for the three batch presets; the count is frozen
          when the button opened it, and CANCEL reads as destructive. */}
      <ConfirmDialog
        open={batchAction !== null}
        title={batchAction ? batchConfirmTitle(batchAction.kind) : ''}
        message={
          batchAction ? batchConfirmMessage(batchAction.kind, batchAction.items.length) : ''
        }
        danger={batchAction?.kind === 'CANCEL'}
        onConfirm={() => {
          void runBatch();
        }}
        onCancel={() => setBatchAction(null)}
      />
    </section>
  );
}
