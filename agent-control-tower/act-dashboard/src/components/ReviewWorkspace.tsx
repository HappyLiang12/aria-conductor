import { useEffect, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getKanbanItem } from '../api/kanban';
import { listAsksByKanbanItem } from '../api/approvals';
import { getRun } from '../api/runs';
import { MarkdownViewer } from './MarkdownViewer';
import { DecisionPanel, ShortApprovalView } from './ReviewPanels';
import { AcpDecidedStrip } from './AcpDecisionOutcomes';
import { useDrawerContext, dispatchOpenAgentDrawer } from './DrawerContext';
import { formatTimestamp } from '../utils/formatTime';
import type { KanbanItem } from '../types';

/**
 * Spec 10.3: the expanded story card, rendered IN-FLOW inside the Overview
 * layout (no fixed overlay) while DrawerContext.reviewTargetId is set.
 * Prev/next walk sibling REVIEW cards in place; Collapse hands back to the
 * drawer on the same card via closeReviewMode.
 */
export function ReviewWorkspace({ itemId }: { itemId: string }) {
  const { openReviewMode, closeReviewMode } = useDrawerContext();
  const queryClient = useQueryClient();

  const itemQuery = useQuery({
    queryKey: ['kanban', 'item', itemId],
    queryFn: () => getKanbanItem(itemId),
    retry: false,
  });
  const item = itemQuery.data;

  const asksQuery = useQuery({
    queryKey: ['kanban', 'asks', itemId],
    queryFn: () => listAsksByKanbanItem(itemId),
    enabled: Boolean(itemId),
  });
  const pendingAsks = (asksQuery.data ?? []).filter((a) => a.status === 'PENDING');

  // Defect D4: the spec rail must show what the linked run produced, not just a
  // truncated run id. Fetched only when the card actually carries a run link.
  const runQuery = useQuery({
    queryKey: ['runs', 'detail', item?.linkedRunId],
    queryFn: () => getRun(item?.linkedRunId as string),
    enabled: Boolean(item?.linkedRunId),
    retry: false,
  });
  const linkedRun = runQuery.data;

  // Auto-exit when the card leaves REVIEW (e.g. the short-view Approve moved
  // it to DONE): a stale workspace must not linger on a card that no longer
  // needs a decision. closeReviewMode also reopens the drawer on the card.
  useEffect(() => {
    if (item && item.status !== 'REVIEW') closeReviewMode();
  }, [item, closeReviewMode]);

  // Escape collapses the workspace back to the drawer (keyboard exit). The
  // component only mounts while a review target is set, so the listener's
  // lifetime is exactly the workspace's. DrawerContext's global Escape handler
  // is a no-op while expanded (both drawers are closed), so the two listeners
  // never fight. Escape fired while the operator is typing in an editable
  // control (ask answers, request-changes feedback) is ignored — it must not
  // silently discard an in-progress draft.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // e.target is only an Element for events dispatched on a DOM node —
      // window-targeted events have no editable target to protect (same
      // narrowing DrawerContext uses for its own Escape handler).
      const t = e.target instanceof Element ? e.target : null;
      if (t && t.closest('textarea, input, select, [contenteditable="true"]')) return;
      closeReviewMode();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [closeReviewMode]);

  // Review siblings come from the board list cache so the operator can walk
  // every card waiting on them without leaving the workspace.
  const siblings = useMemo(
    () =>
      (queryClient.getQueryData<KanbanItem[]>(['kanban-items']) ?? []).filter(
        (i) => i.status === 'REVIEW',
      ),
    // itemQuery.data refreshes the sibling window whenever the card reloads
    // (e.g. after a transition invalidates the cache).
    [queryClient, itemQuery.data],
  );
  const siblingIndex = siblings.findIndex((i) => i.id === itemId);
  const prevSibling = siblingIndex > 0 ? siblings[siblingIndex - 1] : undefined;
  const nextSibling =
    siblingIndex >= 0 && siblingIndex < siblings.length - 1
      ? siblings[siblingIndex + 1]
      : undefined;

  // Load failure: a dead-end spinner is not acceptable — mirror the drawer's
  // error wording and keep the Collapse exit reachable.
  if (itemQuery.isError) {
    return (
      <section
        className="panel review-workspace"
        data-testid="review-workspace"
        aria-label="Review workspace"
      >
        <div className="rw-head">
          <div style={{ minWidth: 0 }} />
          <div className="rw-nav">
            <button
              className="btn"
              onClick={closeReviewMode}
              aria-label="Collapse review"
              autoFocus
            >
              ⤡ Collapse
            </button>
          </div>
        </div>
        <div className="rw-body">
          <div className="evidence-error">Failed to load task. It may have been deleted.</div>
        </div>
      </section>
    );
  }

  if (!item) {
    return (
      <section className="panel review-workspace" data-testid="review-workspace">
        <div className="loading-spinner" style={{ padding: 20 }}>
          <div className="spinner" />
        </div>
      </section>
    );
  }

  return (
    <section
      className="panel review-workspace"
      data-testid="review-workspace"
      aria-label="Review workspace"
    >
      <div className="rw-head">
        <div style={{ minWidth: 0 }}>
          <div className="id">TASK · {item.id.slice(0, 8).toUpperCase()}</div>
          <h3
            style={{
              margin: 0,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {item.title}
          </h3>
        </div>
        <div className="rw-nav">
          {/* Defect D7: entry point into the linked agent's live stream. */}
          {item.linkedAgentId && (
            <button
              className="btn"
              aria-label="Open live activity"
              onClick={() => dispatchOpenAgentDrawer(item.linkedAgentId as string)}
            >
              ▶ Live activity
            </button>
          )}
          <button
            className="btn"
            disabled={!prevSibling}
            aria-label="Previous review card"
            onClick={() => prevSibling && openReviewMode(prevSibling.id)}
          >
            ← prev
          </button>
          <button
            className="btn"
            disabled={!nextSibling}
            aria-label="Next review card"
            onClick={() => nextSibling && openReviewMode(nextSibling.id)}
          >
            next →
          </button>
          <button
            className="btn"
            onClick={closeReviewMode}
            aria-label="Collapse review"
            autoFocus
          >
            ⤡ Collapse
          </button>
        </div>
      </div>
      <div className="rw-body">
        <div className="rf-spec">
          <div className="rf-meta">
            <span className="pill">{item.status}</span>
            <span className={item.priority === 'CRITICAL' || item.priority === 'HIGH' ? 'pill warn' : 'pill'}>
              {item.priority}
            </span>
            <span className="owner">{item.assignee ? `@${item.assignee}` : 'unassigned'}</span>
            {item.linkedRunId && (
              <span className="owner cell-mono">run {item.linkedRunId.slice(0, 8)}</span>
            )}
            <span className="owner">updated {formatTimestamp(item.updatedAt)}</span>
          </div>
          <MarkdownViewer content={pendingAsks[0]?.content ?? item.description ?? ''} />

          {/* Linked run result (D4): reviewers need the run's actual output to
              judge the card, not just the truncated run id in the meta row. */}
          {item.linkedRunId && (
            <div className="run-result">
              <div className="section-h">Run Result</div>
              {runQuery.isLoading && (
                <div style={{ fontSize: 11.5, color: 'var(--text-mute)' }}>Loading run…</div>
              )}
              {runQuery.isError && !runQuery.isLoading && (
                <div className="evidence-error">Failed to load run {item.linkedRunId.slice(0, 8)}.</div>
              )}
              {linkedRun && (
                <>
                  <div className="rf-meta">
                    <span className="pill">{linkedRun.status}</span>
                    <span className="owner cell-mono">Iter {linkedRun.iterationCount}</span>
                    <span className="owner cell-mono">
                      {linkedRun.totalTokensUsed.toLocaleString()} tokens
                    </span>
                    <span className="owner cell-mono">
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
        </div>
        <div className="rf-decisions">
          {pendingAsks.length > 0 ? (
            <DecisionPanel key={item.id} item={item} pendingAsks={pendingAsks} />
          ) : asksQuery.isSuccess ? (
            // Mirrors the drawer's guard: while the asks query is in flight an
            // empty pendingAsks array is NOT evidence that the card has no
            // asks — the rail stays empty instead of flashing the short view.
            <ShortApprovalView item={item} />
          ) : null}
          {/* C5-fix1: decided ACP outcomes are derived from the card's ask list,
              so the strip outlives the panel unmount a decide causes. */}
          <AcpDecidedStrip asks={asksQuery.data ?? []} />
        </div>
      </div>
    </section>
  );
}

export default ReviewWorkspace;
