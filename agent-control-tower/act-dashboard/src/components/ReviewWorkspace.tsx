import { useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getKanbanItem } from '../api/kanban';
import { listAsksByKanbanItem } from '../api/approvals';
import { getRun } from '../api/runs';
import { MarkdownViewer } from './MarkdownViewer';
import { DecisionPanel, ShortApprovalView, runOutcomeFromStatus } from './ReviewPanels';
import { useDrawerContext, dispatchOpenAgentDrawer } from './DrawerContext';
import { RunErrorNotice } from './ErrorNotice';
import { formatTimestamp } from '../utils/formatTime';
import { tokenUsageText } from '../utils/tokenUsage';
import { reviewNeedsHuman } from '../utils/reviewState';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';
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

  // Task 7: the single-item payload carries no runOutcome (the T1 enrichment is
  // listing-only), so the outcome chip derives from the run this rail already
  // fetches instead of claiming nothing (or worse, completion).
  const runOutcome = item?.runOutcome ?? runOutcomeFromStatus(linkedRun?.status);

  // Task 7 focus contract: the workspace replaces the drawer in place, so the
  // opener (Expand) is captured on mount and restored on collapse/unmount; the
  // card heading is the initial focus target once the card is available. The
  // per-card guard keeps a refetch from stealing focus while the operator types.
  const headingRef = useRef<HTMLHeadingElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const focusedForRef = useRef<string | null>(null);
  useLayoutEffect(() => {
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== document.body) {
      openerRef.current = active;
    }
    return () => {
      const opener = openerRef.current;
      openerRef.current = null;
      if (opener?.isConnected) opener.focus();
    };
  }, []);
  useLayoutEffect(() => {
    if (!item || focusedForRef.current === itemId) return;
    focusedForRef.current = itemId;
    // Focus synchronously. A requestAnimationFrame here deferred the focus by
    // a frame, and on a slow runner the frame landed after the operator had
    // already clicked into the feedback textarea, stealing the caret: the
    // draft stayed empty while keystrokes went to the heading (observed on
    // hosted CI, ReviewWorkspace.test.tsx "Escape while typing").
    headingRef.current?.focus();
  }, [item, itemId]);

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
        className="review-workspace flex flex-col"
        data-testid="review-workspace"
        aria-label="Review workspace"
      >
        <Card>
          <div className="flex justify-end p-3">
            <Button
              variant="outline"
              size="sm"
              onClick={closeReviewMode}
              aria-label="Collapse review"
              autoFocus
            >
              ⤡ Collapse
            </Button>
          </div>
          <CardContent className="text-sm text-destructive">
            Failed to load task. It may have been deleted.
          </CardContent>
        </Card>
      </section>
    );
  }

  if (!item) {
    return (
      <section className="review-workspace flex flex-col" data-testid="review-workspace">
        <Card>
          <div className="p-5">
            <div className="spinner" />
          </div>
        </Card>
      </section>
    );
  }

  return (
    <section
      className="review-workspace flex flex-col"
      data-testid="review-workspace"
      aria-label="Review workspace"
    >
      <Card className="gap-0 p-0!">
        <div className="flex flex-wrap items-start justify-between gap-3 border-b p-3">
          <div className="min-w-0">
            <div className="font-mono text-[10.5px] tracking-wide text-muted-foreground">
              TASK · {item.id.slice(0, 8).toUpperCase()}
            </div>
            <h3
              ref={headingRef}
              tabIndex={-1}
              className="mt-0.5 truncate text-base font-semibold text-card-foreground outline-none"
            >
              {item.title}
            </h3>
          </div>
          <div className="flex flex-wrap gap-2">
            {/* Defect D7: entry point into the linked agent's live stream. */}
            {item.linkedAgentId && (
              <Button
                variant="outline"
                size="sm"
                aria-label="Open live activity"
                onClick={() => dispatchOpenAgentDrawer(item.linkedAgentId as string)}
              >
                ▶ Live activity
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              disabled={!prevSibling}
              aria-label="Previous review card"
              onClick={() => prevSibling && openReviewMode(prevSibling.id)}
            >
              ← prev
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={!nextSibling}
              aria-label="Next review card"
              onClick={() => nextSibling && openReviewMode(nextSibling.id)}
            >
              next →
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={closeReviewMode}
              aria-label="Collapse review"
            >
              ⤡ Collapse
            </Button>
          </div>
        </div>
        <div className="grid gap-3 p-3 lg:grid-cols-[1.4fr_1fr]">
          <div className="rf-spec max-h-[56vh] min-w-0 space-y-2 overflow-y-auto rounded-lg border! border-border! bg-muted/20! p-3!">
            <div className="rf-meta flex flex-wrap items-center gap-2">
              {/* F24: REVIEW badges an open ask only; a finished card parked here
                  is awaiting sign-off (the run outcome chip carries the result). */}
              <Badge variant={reviewNeedsHuman(item) ? 'secondary' : 'outline'}>
                {item.status === 'REVIEW' && !reviewNeedsHuman(item)
                  ? 'Awaiting sign-off'
                  : item.status}
              </Badge>
              <Badge variant={item.priority === 'CRITICAL' || item.priority === 'HIGH' ? 'secondary' : 'outline'}>
                {item.priority}
              </Badge>
              <span className="text-xs text-muted-foreground">{item.assignee ? `@${item.assignee}` : 'unassigned'}</span>
              {item.linkedRunId && (
                <span className="font-mono text-xs text-muted-foreground">run {item.linkedRunId.slice(0, 8)}</span>
              )}
              <span className="text-xs text-muted-foreground">updated {formatTimestamp(item.updatedAt)}</span>
            </div>
            <MarkdownViewer content={pendingAsks[0]?.content ?? item.description ?? ''} />

            {/* Linked run result (D4): reviewers need the run's actual output to
                judge the card, not just the truncated run id in the meta row. */}
            {item.linkedRunId && (
              <div className="run-result mt-3 grid gap-1.5">
                <div className="text-[11px] tracking-wide text-muted-foreground uppercase">Run Result</div>
                {runQuery.isLoading && (
                  <div className="text-xs text-muted-foreground">Loading run…</div>
                )}
                {runQuery.isError && !runQuery.isLoading && (
                  <div className="text-xs text-destructive">Failed to load run {item.linkedRunId.slice(0, 8)}.</div>
                )}
                {linkedRun && (
                  <>
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="outline">{linkedRun.status}</Badge>
                      <span className="font-mono text-xs text-muted-foreground">Iter {linkedRun.iterationCount}</span>
                      <span className="font-mono text-xs text-muted-foreground">
                        {tokenUsageText(linkedRun.totalTokensUsed)}
                      </span>
                      <span className="font-mono text-xs text-muted-foreground">
                        {linkedRun.completedAt
                          ? formatTimestamp(linkedRun.completedAt)
                          : 'not finished'}
                      </span>
                    </div>
                    {linkedRun.errorMessage && (
                      <div className="mt-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3">
                        <RunErrorNotice
                          message={linkedRun.errorMessage}
                          surface={linkedRun.errorSurface}
                          code={linkedRun.errorCode}
                        />
                      </div>
                    )}
                    {linkedRun.finalOutput && (
                      <div className="artifact-result rounded-lg border bg-background/40 p-3">
                        <MarkdownViewer content={linkedRun.finalOutput} />
                      </div>
                    )}
                  </>
                )}
              </div>
            )}
          </div>
          <div className="rf-decisions max-h-[56vh] min-w-0! overflow-y-auto">
            {pendingAsks.length > 0 ? (
              <DecisionPanel key={item.id} item={item} pendingAsks={pendingAsks} />
            ) : asksQuery.isSuccess ? (
              // Mirrors the drawer's guard: while the asks query is in flight an
              // empty pendingAsks array is NOT evidence that the card has no
              // asks — the rail stays empty instead of flashing the short view.
              <ShortApprovalView item={item} runOutcome={runOutcome} />
            ) : null}
          </div>
        </div>
      </Card>
    </section>
  );
}

export default ReviewWorkspace;
