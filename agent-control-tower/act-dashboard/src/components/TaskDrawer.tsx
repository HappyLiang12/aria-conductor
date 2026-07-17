import { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getKanbanItem,
  transitionKanbanItem,
} from '../api/kanban';
import { useDrawerContext } from './DrawerContext';
import type { KanbanItem, KanbanPriority, KanbanStatus } from '../types';

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                   */
/* -------------------------------------------------------------------------- */

const STATUS_LABEL: Record<KanbanStatus, string> = {
  TODO: 'To Do',
  IN_PROGRESS: 'In Progress',
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

const TRANSITIONS: Record<KanbanStatus, KanbanStatus[]> = {
  TODO: ['IN_PROGRESS', 'BLOCKED', 'CANCELLED'],
  IN_PROGRESS: ['DONE', 'BLOCKED', 'TODO', 'CANCELLED'],
  BLOCKED: ['IN_PROGRESS', 'CANCELLED'],
  DONE: ['IN_PROGRESS'],
  CANCELLED: ['TODO'],
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
  const { state, closeTaskDrawer } = useDrawerContext();
  const { open, itemId } = state.taskDrawer;
  const queryClient = useQueryClient();

  const [comment, setComment] = useState('');

  const taskQuery = useQuery({
    queryKey: ['kanban', 'item', itemId],
    queryFn: () => getKanbanItem(itemId as string),
    enabled: open && Boolean(itemId),
    retry: false,
  });

  const transitionMutation = useMutation({
    mutationFn: ({ id, status, comment: c }: { id: string; status: KanbanStatus; comment?: string }) =>
      transitionKanbanItem(id, { status, comment: c }),
    onSuccess: (data) => {
      queryClient.setQueryData(['kanban', 'item', data.id], data);
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      setComment('');
    },
  });

  const item = taskQuery.data;
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
    </>
  );
}

export default TaskDrawer;
