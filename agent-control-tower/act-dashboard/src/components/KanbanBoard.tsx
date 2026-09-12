import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createKanbanItem, listKanbanItems, transitionKanbanItem } from '../api/kanban';
import { executeHousekeeping } from '../api/housekeeping';
import { listAgentTemplates, listAgents } from '../api/agents';
import type {
  CreateKanbanItemRequest,
  KanbanItem,
  KanbanPriority,
  KanbanStatus,
} from '../types';
import { useWebSocketContext } from './Layout';
import { isKanbanEvent, isRunLifecycleEvent } from '../utils/wsEvents';
import { ConfirmDialog } from './ConfirmDialog';
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

function priorityPillClass(priority: KanbanPriority): string {
  switch (priority) {
    case 'CRITICAL':
      return 'pill risk';
    case 'HIGH':
      return 'pill warn';
    case 'MEDIUM':
      return 'pill';
    case 'LOW':
    default:
      return 'pill ok';
  }
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
  // Flash kinds: ok/err = transition result; assign = "Aria assigning…" while
  // the pickup's assign phase runs (spec 4.2).
  const [flash, setFlash] = useState<{ itemId: string; kind: 'ok' | 'err' | 'assign' } | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  // Inline card composer: which REVIEW card is currently asking "what should change?".
  const [feedbackFor, setFeedbackFor] = useState<string | null>(null);
  const [cardFeedback, setCardFeedback] = useState('');
  // Task 10: the card ✕ requests a cancel; the confirmation modal owns it.
  const [confirmCancelId, setConfirmCancelId] = useState<string | null>(null);
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

  const { data: items } = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 12000,
  });

  // React instantly to kanban or run lifecycle events from WebSocket.
  // S1 whitelist: high-frequency streaming events (run.progress) must NOT
  // invalidate the board list — they are consumed precisely elsewhere.
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (isKanbanEvent(t) || isRunLifecycleEvent(t)) {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
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
    },
    onError: (err: unknown) => {
      const anyErr = err as { message?: string };
      setError(anyErr?.message ?? 'Failed to create item');
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
    onError: (err: unknown) => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      // Task 10: the confirmed action is over (it failed) — never leave the
      // confirmation stuck open on a rejection.
      setConfirmCancelId(null);
      // GlobalExceptionHandler puts the rejection reason in `message` (`error`
      // carries only the HTTP reason phrase); same axios shape as TaskDrawer.
      const data = (err as { response?: { data?: { message?: string; error?: string } } } | null)
        ?.response?.data;
      setError(data?.message ?? data?.error ?? 'Move rejected — the card is back in its column.');
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
      setError('Title is required');
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

  // Legal drop targets for the card currently being dragged.
  const legalTargets: KanbanStatus[] = draggingId
    ? LEGAL_DROPS[(items ?? []).find((i) => i.id === draggingId)?.status ?? 'DONE'] ?? []
    : [];

  return (
    <section className="panel" style={{ position: 'relative' }}>
      <h2>
        <span>Kanban Board</span>
        <span className="accent">· Governed Flow</span>
        {finishedCount > 0 && (
          <button
            className="btn"
            style={{ marginLeft: 'auto' }}
            onClick={() => setClearConfirmOpen(true)}
          >
            🧹 Clear Done &amp; Cancelled ({finishedCount})
          </button>
        )}
        <button
          className="btn primary"
          style={{ marginLeft: finishedCount > 0 ? 0 : 'auto' }}
          onClick={() => {
            setShowCreate(true);
            setError(null);
          }}
        >
          + New Item
        </button>
      </h2>

      {/* Board-level transition feedback (snap-back message per spec 5.1):
          the same error slot the modal uses, but visible while it is closed. */}
      {error && !showCreate && (
        <div className="kanban-form-error" role="status" style={{ margin: '0 0 8px 4px' }}>
          {error}
        </div>
      )}

      <div className="kanban">
        <div className="kanban-grid">
          {COLUMNS.map((col) => {
            const columnItems = grouped[col.key] ?? [];
            return (
              <div
                key={col.key}
                className={`col-k${col.isGate ? ' gate' : ''}`}
                data-col={col.key}
                onDragOver={(e) => {
                  if (draggingId && legalTargets.includes(col.key)) e.preventDefault();
                }}
                onDrop={() => handleDrop(col.key)}
              >
                <header>
                  <span>{col.label}</span>
                  <span className="count">{columnItems.length}</span>
                </header>
                <div
                  className={`lane${
                    draggingId && legalTargets.includes(col.key) ? ' drop-legal' : ''
                  }${draggingId && !legalTargets.includes(col.key) ? ' drop-illegal' : ''}`}
                  data-testid={`lane-${col.key}`}
                >
                  {columnItems.map((item) => (
                    <div
                      key={item.id}
                      className={`card${col.isGate ? ' gate' : ''}${
                        item.status === 'DONE' ? ' done' : ''
                      }${
                        flash?.itemId === item.id ? (flash.kind === 'err' ? ' moving-err' : ' moving') : ''
                      }`}
                      data-card={item.id}
                      draggable
                      data-dragging={draggingId === item.id || undefined}
                      onDragStart={(e) => {
                        // Deferred on purpose: mutating the dragged node
                        // synchronously inside dragstart (data-dragging attr
                        // triggers a re-render) makes Chromium cancel the
                        // native drag immediately, so no drop ever fires.
                        // (real browsers always provide dataTransfer; jsdom
                        // synthetic events do not, hence the optional chain)
                        e.dataTransfer?.setData('text/plain', item.id);
                        if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
                        setTimeout(() => setDraggingId(item.id), 0);
                      }}
                      onDragEnd={() => setDraggingId(null)}
                      onClick={() => dispatchOpenTaskDrawer(item.id)}
                    >
                      <div className="gateline" />
                      <div className="id">{item.id.slice(0, 8)}</div>
                      <div className="t">{item.title}</div>
                      {!!item.pendingAskCount && (
                        <span className="pill warn">{item.pendingAskCount} asks</span>
                      )}
                      {flash?.itemId === item.id && flash.kind === 'assign' && (
                        <span className="owner" style={{ color: 'var(--brand-2)' }}>
                          ◐ Aria assigning…
                        </span>
                      )}
                      {item.lastError && (
                        <div className="owner" style={{ color: 'var(--red)' }}>
                          {item.lastError}
                        </div>
                      )}
                      <div className="meta">
                        <span className={priorityPillClass(item.priority)}>
                          {item.priority}
                        </span>
                        {item.linkedAgentId && agentNameById.get(item.linkedAgentId) && (
                          <span className="owner">↪ {agentNameById.get(item.linkedAgentId)}</span>
                        )}
                        {item.assignee && (
                          <span className="owner">@{item.assignee}</span>
                        )}
                      </div>
                      {item.status === 'REVIEW' && (
                        // Full quick decision lives on the card face: approve,
                        // request changes (inline composer) and deny without
                        // ever opening the drawer or the workspace.
                        <div className="card-approve" onClick={(e) => e.stopPropagation()}>
                          {feedbackFor === item.id ? (
                            <div className="cap-compose">
                              <input
                                className="cap-input"
                                autoFocus
                                placeholder="What should change?"
                                aria-label="Request-changes feedback"
                                value={cardFeedback}
                                onChange={(e) => setCardFeedback(e.target.value)}
                                onKeyDown={(e) => {
                                  if (e.key === 'Escape') {
                                    setFeedbackFor(null);
                                    setCardFeedback('');
                                  }
                                }}
                              />
                              <div className="cap-row">
                                <button
                                  className="cap-btn ok"
                                  aria-label="Send request changes"
                                  disabled={transitionMutation.isPending}
                                  onClick={() => {
                                    transitionMutation.mutate({
                                      id: item.id,
                                      status: 'TODO',
                                      feedback: cardFeedback.trim() || undefined,
                                    });
                                    setFeedbackFor(null);
                                    setCardFeedback('');
                                  }}
                                >
                                  Send
                                </button>
                                <button
                                  className="cap-btn"
                                  aria-label="Cancel request changes"
                                  onClick={() => {
                                    setFeedbackFor(null);
                                    setCardFeedback('');
                                  }}
                                >
                                  ✕
                                </button>
                              </div>
                            </div>
                          ) : (
                            <>
                              <button
                                className="cap-btn ok"
                                title="Approve (complete task)"
                                aria-label="Quick approve"
                                disabled={transitionMutation.isPending}
                                onClick={() => transitionMutation.mutate({ id: item.id, status: 'DONE' })}
                              >
                                ✓ Approve
                              </button>
                              <button
                                className="cap-btn"
                                title="Request changes (sent back to the agent)"
                                aria-label="Request changes"
                                onClick={() => {
                                  setFeedbackFor(item.id);
                                  setCardFeedback('');
                                }}
                              >
                                ✎ Changes
                              </button>
                              <button
                                className="cap-btn danger"
                                title="Deny (cancel task)"
                                aria-label="Quick deny"
                                disabled={transitionMutation.isPending}
                                onClick={() => transitionMutation.mutate({ id: item.id, status: 'CANCELLED' })}
                              >
                                ✕ Deny
                              </button>
                              <button
                                className="cap-btn"
                                title="Expand review workspace"
                                aria-label="Expand review workspace"
                                onClick={() => openReviewMode(item.id)}
                              >
                                ⤢
                              </button>
                            </>
                          )}
                        </div>
                      )}
                      {item.status !== 'DONE' && item.status !== 'CANCELLED' && (
                        <button
                          className="card-cancel"
                          title="Cancel task"
                          aria-label="Cancel task"
                          disabled={transitionMutation.isPending}
                          onClick={(e) => {
                            // stopPropagation: asking to cancel must not also
                            // open the card's drawer (Task 10).
                            e.stopPropagation();
                            setConfirmCancelId(item.id);
                          }}
                        >
                          ✕
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {showCreate && (
        <div
          className="modal-overlay"
          onClick={() => {
            setShowCreate(false);
            setError(null);
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
                  {(templates ?? []).map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </label>
              {error && <div className="kanban-form-error">{error}</div>}
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
                  try {
                    await executeHousekeeping({ categories: ['kanban'], confirm: true });
                  } catch {
                    // single-flight conflict or bad request: refresh anyway so the
                    // operator sees the current board state.
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
    </section>
  );
}
