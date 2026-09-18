import { useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getKanbanItem,
  transitionKanbanItem,
} from '../api/kanban';
import { listAsksByKanbanItem } from '../api/approvals';
import { getRun } from '../api/runs';
import { useDrawerContext, dispatchOpenAgentDrawer } from './DrawerContext';
import { DecisionPanel, ShortApprovalView } from './ReviewPanels';
import { AcpDecidedStrip } from './AcpDecisionOutcomes';
import { MarkdownViewer } from './MarkdownViewer';
import { ConfirmDialog } from './ConfirmDialog';
import { formatTimestamp } from '../utils/formatTime';
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
  // Defect D3: a finished card can be sent back for redo (Backlog or Todo).
  DONE: ['BACKLOG', 'TODO'],
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
  const { state, closeTaskDrawer, openReviewMode } = useDrawerContext();
  const { open, itemId } = state.taskDrawer;
  const queryClient = useQueryClient();

  // Spec 10.3: Expand swaps the drawer for the in-place ReviewWorkspace, which
  // only exists inside the Overview layout ('/'). The drawer itself mounts on
  // every route, so on any other route the affordance must not render —
  // expanding there would create a workspace with nowhere to land.
  const onOverview = useLocation().pathname === '/';

  const [comment, setComment] = useState('');
  // Task 10: the footer Reject only opens the shared confirmation; the
  // transition fires from its Confirm handler. Approve is untouched.
  const [confirmingReject, setConfirmingReject] = useState(false);

  // The dialog is a sibling of the drawer (fixed positioning needs to escape
  // the drawer's transform). Closing the drawer must therefore drop the gate
  // explicitly, or it would re-open over the next card.
  useEffect(() => {
    if (!open) setConfirmingReject(false);
  }, [open]);

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

  // Defect D4: the card's linked run is fetched on demand so the drawer can show
  // the run's actual work output (status, effort, tokens, result) instead of an
  // inert truncated id. Disabled when the card has no run link.
  const runQuery = useQuery({
    queryKey: ['runs', 'detail', item?.linkedRunId],
    queryFn: () => getRun(item?.linkedRunId as string),
    enabled: open && Boolean(item?.linkedRunId),
    retry: false,
  });
  const linkedRun = runQuery.data;

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

  // Only the confirmation's Confirm reaches the reject transition (Task 10).
  const confirmReject = () => {
    setConfirmingReject(false);
    handleReject();
  };

  const validTransitions = item ? TRANSITIONS[item.status] : [];

  // GlobalExceptionHandler puts the human reason in `message` and the HTTP
  // phrase in `error` (e.g. "Bad Request"); prefer the reason when present.
  const errData = (transitionMutation.error as { response?: { data?: { message?: string; error?: string } } } | null)
    ?.response?.data;
  const errMsg = errData?.message ?? errData?.error;

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
          {/* Defect D7: entry point into the linked agent's Live Activity
              Stream — only meaningful when the card carries an agent. */}
          {item?.linkedAgentId && (
            <button
              className="btn"
              aria-label="Open live activity"
              onClick={() => dispatchOpenAgentDrawer(item.linkedAgentId as string)}
            >
              ▶ Live activity
            </button>
          )}
          {onOverview && item?.status === 'REVIEW' && (
            <button
              className="btn"
              onClick={() => item && openReviewMode(item.id)}
              aria-label="Expand review"
            >
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
                  While the in-place ReviewWorkspace is open the drawer is
                  closed entirely, so no zone gate is needed here. Keyed by
                  card so answer drafts reset on sibling navigation. */}
              {pendingAsks.length > 0 && (
                <DecisionPanel key={item.id} item={item} pendingAsks={pendingAsks} />
              )}
              {item.status === 'REVIEW' && asksQuery.isSuccess && pendingAsks.length === 0 && (
                <ShortApprovalView item={item} />
              )}
              {/* C5-fix1: decided ACP outcomes come from the card's ask list, so
                  the strip outlives the panel unmount a decide causes. */}
              <AcpDecidedStrip asks={asksQuery.data ?? []} />

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
                  Updated {formatTimestamp(item.updatedAt)}
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

              {/* Linked run result (D4): the card detail must expose what the
                  run actually produced, not just its id in Artifacts. */}
              {item.linkedRunId && (
                <div className="run-result">
                  <div className="section-h">Run Result</div>
                  {runQuery.isLoading && (
                    <div
                      style={{ fontSize: 11.5, color: 'var(--text-mute)', padding: '4px 2px' }}
                    >
                      Loading run…
                    </div>
                  )}
                  {runQuery.isError && !runQuery.isLoading && (
                    <div className="evidence-error">Failed to load run {item.linkedRunId.slice(0, 8)}.</div>
                  )}
                  {linkedRun && (
                    <>
                      <div className="run-meta">
                        <span className="pill">{linkedRun.status}</span>
                        <span className="cell-mono">Iter {linkedRun.iterationCount}</span>
                        <span className="cell-mono">
                          {linkedRun.totalTokensUsed.toLocaleString()} tokens
                        </span>
                        <span className="cell-mono">
                          {linkedRun.completedAt
                            ? formatTimestamp(linkedRun.completedAt)
                            : 'not finished'}
                        </span>
                      </div>
                      {linkedRun.errorMessage && (
                        <div
                          className="evidence-error"
                          style={{
                            background: 'rgba(255,107,122,.06)',
                            border: '1px solid rgba(255,107,122,.2)',
                            borderRadius: 8,
                            padding: '10px 12px',
                            marginTop: 8,
                          }}
                        >
                          {linkedRun.errorMessage}
                        </div>
                      )}
                      {linkedRun.finalOutput && (
                        <div className="artifact-result">
                          <MarkdownViewer content={linkedRun.finalOutput} />
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}

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
            onClick={() => setConfirmingReject(true)}
          >
            Reject
          </button>
          <button className="btn" onClick={closeTaskDrawer}>
            Close
          </button>
        </footer>
      </aside>

      {/* Shared destructive-action confirmation (Task 10). Rendered outside the
          transformed drawer so the fixed overlay covers the viewport. */}
      <ConfirmDialog
        open={open && confirmingReject}
        title="Reject task — confirmation required"
        message={
          <>
            This rejects card {item ? item.id.slice(0, 8) : ''} and cancels the work in progress on
            it. It leaves the board as cancelled; only the housekeeping sweep removes it.
          </>
        }
        danger
        onConfirm={confirmReject}
        onCancel={() => setConfirmingReject(false)}
      />
    </>
  );
}

export default TaskDrawer;
