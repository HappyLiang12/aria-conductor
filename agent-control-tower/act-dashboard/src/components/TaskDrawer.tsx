import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getKanbanItem,
  transitionKanbanItem,
} from '../api/kanban';
import { listAsksByKanbanItem } from '../api/approvals';
import { useDrawerContext } from './DrawerContext';
import { MarkdownViewer } from './MarkdownViewer';
import { DecisionPanel, ShortApprovalView } from './ReviewPanels';
import type { KanbanItem, KanbanPriority, KanbanStatus } from '../types';

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                   */
/* -------------------------------------------------------------------------- */

const STATUS_LABEL: Record<KanbanStatus, string> = {
  BACKLOG: 'Backlog',
  TODO: 'To Do',
  IN_PROGRESS: 'In Progress',
  REVIEW: 'Review',
  DONE: 'Done',
  BLOCKED: 'Blocked',
  CANCELLED: 'Cancelled',
};

const PRIORITY_TONE: Record<KanbanPriority, string> = {
  LOW: 'pill ok',
  MEDIUM: 'pill',
  HIGH: 'pill warn',
  CRITICAL: 'pill danger',
};

// Mirror of the backend ALLOWED_TRANSITIONS — drives the drawer's transition
// buttons only (drop-target legality lives in KanbanBoard's LEGAL_DROPS).
// BLOCKED is retired; CANCELLED/DONE are reachable only via footer actions.
const TRANSITIONS: Record<KanbanStatus, KanbanStatus[]> = {
  BACKLOG: ['TODO', 'CANCELLED'],
  TODO: ['IN_PROGRESS', 'BACKLOG', 'CANCELLED'],
  IN_PROGRESS: ['TODO', 'BACKLOG', 'REVIEW', 'DONE', 'CANCELLED'],
  REVIEW: ['IN_PROGRESS', 'TODO', 'DONE', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
  BLOCKED: [],
};

interface ParsedLabels {
  acceptance: string[];
  artifacts: string[];
  comments: { who: string; when: string; text: string }[];
  raw: string[];
}

function parseLabels(item: KanbanItem | undefined): ParsedLabels {
  const empty: ParsedLabels = { acceptance: [], artifacts: [], comments: [], raw: [] };
  if (!item?.labels) return empty;

  // Best-effort parser: labels may be JSON, comma-list, or plain text.
  const labels = item.labels.trim();
  if (labels.startsWith('{') || labels.startsWith('[')) {
    try {
      const parsed = JSON.parse(labels);
      if (Array.isArray(parsed)) {
        return { ...empty, raw: parsed.map(String) };
      }
      return {
        acceptance: Array.isArray(parsed.acceptance) ? parsed.acceptance.map(String) : [],
        artifacts: Array.isArray(parsed.artifacts) ? parsed.artifacts.map(String) : [],
        comments: Array.isArray(parsed.comments) ? parsed.comments : [],
        raw: Array.isArray(parsed.raw) ? parsed.raw : [],
      };
    } catch {
      /* fall through */
    }
  }
  return { ...empty, raw: labels.split(',').map((s) => s.trim()).filter(Boolean) };
}

/* -------------------------------------------------------------------------- */
/*  TaskDrawer                                                                */
/* -------------------------------------------------------------------------- */

export function TaskDrawer() {
  const { state, closeTaskDrawer, openTaskDrawer, openReviewMode, closeReviewMode } =
    useDrawerContext();
  const { open, itemId } = state.taskDrawer;
  const queryClient = useQueryClient();

  const [comment, setComment] = useState('');

  const taskQuery = useQuery({
    queryKey: ['kanban', 'item', itemId],
    queryFn: () => getKanbanItem(itemId as string),
    enabled: open && Boolean(itemId),
    retry: false,
  });

  const item = taskQuery.data;

  // HITL asks attached to this card — fetched whenever the drawer is open,
  // because asks may sit on any column (mid-run gate approvals included).
  const asksQuery = useQuery({
    queryKey: ['kanban', 'asks', itemId],
    queryFn: () => listAsksByKanbanItem(itemId as string),
    enabled: open && Boolean(itemId),
  });
  const pendingAsks = (asksQuery.data ?? []).filter((a) => a.status === 'PENDING');

  const transitionMutation = useMutation({
    mutationFn: ({
      id,
      status,
      comment: c,
      feedback,
    }: {
      id: string;
      status: KanbanStatus;
      comment?: string;
      feedback?: string;
    }) => transitionKanbanItem(id, { status, comment: c, feedback }),
    onSuccess: (data) => {
      queryClient.setQueryData(['kanban', 'item', data.id], data);
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      setComment('');
    },
  });

  // Review siblings come from the board list cache so the operator can walk
  // every card waiting on them without closing the drawer.
  const reviewSiblings = (queryClient.getQueryData<KanbanItem[]>(['kanban-items']) ?? []).filter(
    (it) => it.status === 'REVIEW',
  );
  const siblingIndex = reviewSiblings.findIndex((it) => it.id === itemId);
  const prevSibling = siblingIndex > 0 ? reviewSiblings[siblingIndex - 1] : null;
  const nextSibling =
    siblingIndex >= 0 && siblingIndex < reviewSiblings.length - 1
      ? reviewSiblings[siblingIndex + 1]
      : null;

  const parsed = useMemo(() => parseLabels(item), [item]);

  // Acceptance criteria: derive simple states from item labels OR a default checklist.
  const acceptance = parsed.acceptance.length
    ? parsed.acceptance.map((text) => ({ text, done: false }))
    : item
    ? [
        { text: 'Title and description captured', done: Boolean(item.title) },
        { text: 'Owner / assignee set', done: Boolean(item.assignee) },
        { text: 'Linked to run or agent', done: Boolean(item.linkedRunId || item.linkedAgentId) },
        { text: 'Reached terminal state', done: item.status === 'DONE' },
      ]
    : [];

  const handleTransition = (status: KanbanStatus) => {
    if (!item) return;
    transitionMutation.mutate({ id: item.id, status, comment: comment.trim() || undefined });
  };

  const handleApprove = () => handleTransition('DONE');
  const handleReject = () => handleTransition('CANCELLED');

  const validTransitions = item ? TRANSITIONS[item.status] : [];

  const errMsg = (transitionMutation.error as { response?: { data?: { error?: string } } } | null)
    ?.response?.data?.error;

  // Shared between the collapsed drawer body and the full-page review rail so
  // both surfaces always offer the exact same decisions. Delegates to the
  // shared DecisionPanel (Task 4 removes this wrapper with the fullpage JSX).
  const renderDecisionZone = () => <DecisionPanel item={item!} pendingAsks={pendingAsks} />;

  const goToSibling = (sibling: KanbanItem | null) => {
    if (!sibling) return;
    openTaskDrawer(sibling.id);
  };

  // While the full-page review workspace is expanded, Escape exits the
  // expanded mode only (back to the drawer) — it must NOT close the whole
  // drawer. DrawerContext's global Escape guard already skips events whose
  // target sits inside `.review-fullpage`, so the two listeners don't fight.
  useEffect(() => {
    if (!open || !state.reviewExpanded) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      closeReviewMode();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, state.reviewExpanded, closeReviewMode]);

  // Leaving REVIEW (e.g. "Approve all" resolved the asks) exits review mode so
  // the fullpage workspace and OverviewPage's bottom widget strip don't linger
  // on a card that no longer needs a decision.
  useEffect(() => {
    if (open && state.reviewExpanded && item && item.status !== 'REVIEW') {
      closeReviewMode();
    }
  }, [open, state.reviewExpanded, item, closeReviewMode]);

  return (
    <>
      {open && (
        <div
          className="task-drawer-backdrop"
          onClick={closeTaskDrawer}
          aria-hidden="true"
          role="presentation"
        />
      )}
      <aside
        className={`drawer${open ? ' open' : ''}`}
        aria-hidden={!open}
        aria-label="Task details drawer"
      >
        <header>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="id">
              {item ? `TASK · ${item.id.slice(0, 8).toUpperCase()}` : 'TASK'}
            </div>
            <h3 style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {item?.title ?? (taskQuery.isLoading ? 'Loading…' : 'Select a task')}
            </h3>
          </div>
          {item?.status === 'REVIEW' && (prevSibling || nextSibling) && (
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                className="btn"
                disabled={!prevSibling}
                aria-label="Previous review card"
                onClick={() => goToSibling(prevSibling)}
              >
                ← prev
              </button>
              <button
                className="btn"
                disabled={!nextSibling}
                aria-label="Next review card"
                onClick={() => goToSibling(nextSibling)}
              >
                next →
              </button>
            </div>
          )}
          {item?.status === 'REVIEW' && (
            <button className="btn" onClick={openReviewMode} aria-label="Expand review">
              ⤢ Expand
            </button>
          )}
          <div className="close" onClick={closeTaskDrawer} role="button" aria-label="Close">
            ✕
          </div>
        </header>

        <div className="body">
          {taskQuery.isLoading && (
            <div className="loading-spinner" style={{ padding: 20 }}>
              <div className="spinner" />
              <span>Loading task…</span>
            </div>
          )}

          {taskQuery.isError && !taskQuery.isLoading && (
            <div className="evidence-error">Failed to load task. It may have been deleted.</div>
          )}

          {item && (
            <>
              {/* Review decision zone: the first thing an operator sees on a
                  card that is waiting on them — on any column (spec 10.1).
                  While the fullpage workspace is expanded the rail is the only
                  decision surface (interim until Task 4 deletes the fullpage):
                  rendering both would hold separate answer drafts. Keyed by
                  card so answer drafts reset on sibling navigation. */}
              {!state.reviewExpanded && pendingAsks.length > 0 && (
                <DecisionPanel key={item.id} item={item} pendingAsks={pendingAsks} />
              )}
              {item.status === 'REVIEW' && asksQuery.isSuccess && pendingAsks.length === 0 && (
                <ShortApprovalView item={item} />
              )}

              {/* Status row */}
              <div className="section-h">Status</div>
              <div
                style={{
                  display: 'flex',
                  gap: 8,
                  alignItems: 'center',
                  flexWrap: 'wrap',
                }}
              >
                <span className="pill">{STATUS_LABEL[item.status]}</span>
                <span className={PRIORITY_TONE[item.priority]}>{item.priority}</span>
                {item.assignee && (
                  <span style={{ fontSize: 11.5, color: 'var(--text-dim)' }}>
                    · @{item.assignee}
                  </span>
                )}
                <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--text-mute)' }}>
                  Updated {new Date(item.updatedAt).toLocaleString()}
                </span>
              </div>

              {/* Description */}
              <div className="section-h">Description</div>
              <div
                style={{
                  fontSize: 12.5,
                  lineHeight: 1.55,
                  color: 'var(--text-dim)',
                  padding: '8px 10px',
                  background: 'rgba(255,255,255,.025)',
                  border: '1px solid var(--line)',
                  borderRadius: 8,
                  whiteSpace: 'pre-wrap',
                }}
              >
                {item.description?.trim() ? item.description : 'No description provided.'}
              </div>

              {/* Acceptance Criteria */}
              <div className="section-h">Acceptance Criteria</div>
              <div className="ac">
                {acceptance.map((row, idx) => (
                  <div key={idx} className={`row ${row.done ? 'ok' : 'todo'}`}>
                    <span className="check">{row.done ? '✓' : '○'}</span>
                    <div>{row.text}</div>
                  </div>
                ))}
              </div>

              {/* Artifacts */}
              <div className="section-h">Artifacts</div>
              <div className="artifacts">
                {parsed.artifacts.length === 0 && !item.linkedRunId && (
                  <div className="artifact" style={{ color: 'var(--text-mute)' }}>
                    <span className="ic">∅</span> No artifacts linked yet.
                  </div>
                )}
                {item.linkedRunId && (
                  <div className="artifact">
                    <span className="ic">▶</span>
                    Run · <span className="cell-mono">{item.linkedRunId.slice(0, 12)}</span>
                  </div>
                )}
                {item.linkedAgentId && (
                  <div className="artifact">
                    <span className="ic">★</span>
                    Agent · <span className="cell-mono">{item.linkedAgentId.slice(0, 12)}</span>
                  </div>
                )}
                {parsed.artifacts.map((a, idx) => (
                  <div key={idx} className="artifact">
                    <span className="ic">📄</span>
                    {a}
                  </div>
                ))}
              </div>

              {/* Comments */}
              <div className="section-h">Comments</div>
              <div className="comments">
                {parsed.comments.length === 0 && (
                  <div
                    style={{
                      fontSize: 11.5,
                      color: 'var(--text-mute)',
                      padding: '4px 2px',
                    }}
                  >
                    No comments yet — add one below.
                  </div>
                )}
                {parsed.comments.map((c, idx) => (
                  <div key={idx} className="comment">
                    <div>
                      <span className="who">{c.who}</span>
                      <span className="when">{c.when}</span>
                    </div>
                    {c.text}
                  </div>
                ))}
                <textarea
                  className="dod-textarea"
                  placeholder="Add a comment / transition note…"
                  rows={2}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                />
                {errMsg && <div className="evidence-error">{errMsg}</div>}
              </div>

              {validTransitions.length > 1 && (
                <>
                  <div className="section-h">Transition To</div>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {validTransitions
                      .filter((s) => s !== 'DONE' && s !== 'CANCELLED')
                      .map((s) => (
                        <button
                          key={s}
                          className="btn"
                          disabled={transitionMutation.isPending}
                          onClick={() => handleTransition(s)}
                        >
                          → {STATUS_LABEL[s]}
                        </button>
                      ))}
                  </div>
                </>
              )}
            </>
          )}
        </div>

        <footer>
          <button
            className="btn primary"
            disabled={!item || transitionMutation.isPending || !validTransitions.includes('DONE')}
            onClick={handleApprove}
          >
            Approve
          </button>
          <button
            className="btn danger"
            disabled={!item || transitionMutation.isPending || !validTransitions.includes('CANCELLED')}
            onClick={handleReject}
          >
            Reject
          </button>
          <button className="btn" onClick={closeTaskDrawer}>
            Close
          </button>
        </footer>
      </aside>

      {/* Full-page review workspace: expanded spec + the same decision rail.
          Gated on the card still being in REVIEW so the stale workspace cannot
          linger after an ask is resolved (e.g. "Approve all" moves the card to
          DONE and the refetched item/asks close the workspace). */}
      {open && state.reviewExpanded && item?.status === 'REVIEW' && (
        <div
          className="review-fullpage"
          role="dialog"
          aria-modal="true"
          aria-label="Review workspace"
        >
          <div className="rf-head">
            <h3>{item.title}</h3>
            <button className="btn" onClick={closeReviewMode}>
              ⤡ Collapse
            </button>
          </div>
          <div className="rf-body">
            <div className="rf-spec">
              <MarkdownViewer content={pendingAsks[0]?.content ?? item.description ?? ''} />
            </div>
            <div className="rf-decisions">
              {pendingAsks.length > 0 ? (
                renderDecisionZone()
              ) : (
                <div className="empty-state">No pending asks on this card.</div>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export default TaskDrawer;
