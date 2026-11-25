import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getKanbanItem,
  transitionKanbanItem,
} from '../api/kanban';
import { listAsksByKanbanItem } from '../api/approvals';
import { getRun } from '../api/runs';
import { presentError } from '../api/errorPresentation';
import ErrorNotice, { RunErrorNotice } from './ErrorNotice';
import { useDrawerContext, dispatchOpenAgentDrawer } from './DrawerContext';
import { DecisionPanel, RunOutcomeChip, ShortApprovalView, runOutcomeFromStatus } from './ReviewPanels';
import { MarkdownViewer } from './MarkdownViewer';
import { ConfirmDialog } from './ConfirmDialog';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';
import { formatTimestamp } from '../utils/formatTime';
import { tokenUsageText } from '../utils/tokenUsage';
import { reviewNeedsHuman } from '../utils/reviewState';
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
  raw: string[];
}

function parseLabels(item: KanbanItem | undefined): ParsedLabels {
  const empty: ParsedLabels = { acceptance: [], artifacts: [], raw: [] };
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

  const [confirmingReject, setConfirmingReject] = useState(false);

  // Task 7 focus contract: opening moves focus to the card heading (the first
  // thing worth reading), closing returns it to the opener; if the opener is
  // gone, the close control is the fallback. The opener is captured before the
  // programmatic focus so a dialog-like open cannot capture its own heading.
  const headingRef = useRef<HTMLHeadingElement>(null);
  const closeRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (!open) return;
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== document.body) {
      openerRef.current = active;
    }
    const raf = requestAnimationFrame(() => {
      const heading = headingRef.current;
      if (!heading) return;
      // The frame is deferred, so an operator (or a slow CI runner's test) can
      // reach a field inside the open drawer before it lands. Focusing the
      // heading then would pull the caret out mid-draft and send the rest of
      // the typing nowhere, so the frame yields to an already-focused control.
      const drawer = heading.closest('.drawer.open');
      const active = document.activeElement;
      if (
        drawer
        && active instanceof HTMLElement
        && active !== drawer
        && active !== heading
        && drawer.contains(active)
      ) {
        return;
      }
      heading.focus();
    });
    return () => {
      cancelAnimationFrame(raf);
      const opener = openerRef.current;
      openerRef.current = null;
      const target = opener?.isConnected ? opener : closeRef.current;
      // React restores focus to the element that was focused when the commit
      // began (the heading) after running mutation effects, which would undo a
      // synchronous restore here. One microtask later the opener wins.
      queueMicrotask(() => {
        if (target?.isConnected) target.focus();
      });
    };
  }, [open]);

  // The dialog is a sibling of the drawer (fixed positioning needs to escape
  // the drawer's transform). Closing the drawer must therefore drop the gate
  // explicitly, or it would re-open over the next card.
  useEffect(() => {
    if (!open) setConfirmingReject(false);
  }, [open]);

  // Focus starts inside the drawer now, and DrawerContext deliberately ignores
  // Escape that originates inside it. Keep Escape working for the drawer itself
  // without stealing the key from a draft or from a dialog stacked on top.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      const drawer = document.querySelector('.drawer.open');
      const target = e.target instanceof Node ? e.target : null;
      if (!drawer || !target || !drawer.contains(target)) return;
      if (document.querySelector('.modal-overlay, .modal.open')) return;
      if (
        target instanceof Element &&
        target.closest('input, textarea, select, [contenteditable="true"]')
      ) {
        return;
      }
      closeTaskDrawer();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, closeTaskDrawer]);

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

  // Task 7: the linked run's outcome for the REVIEW chip. The single-item
  // payload (GET /kanban/items/{id}) does not carry runOutcome — the T1
  // enrichment is listing-only — so a missing value derives from the run the
  // drawer already fetches.
  const runOutcome = item?.runOutcome ?? runOutcomeFromStatus(linkedRun?.status);

  const transitionMutation = useMutation({
    mutationFn: ({
      id,
      status,
      feedback,
    }: {
      id: string;
      status: KanbanStatus;
      feedback?: string;
    }) => transitionKanbanItem(id, { status, feedback }),
    onSuccess: (data) => {
      queryClient.setQueryData(['kanban', 'item', data.id], data);
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
    },
  });

  const parsed = useMemo(() => parseLabels(item), [item]);

  // Acceptance criteria come from the card's own labels; a card without any
  // gets an empty state, never a fabricated checklist.
  const acceptance = parsed.acceptance.map((text) => ({ text, done: false }));

  const handleTransition = (status: KanbanStatus) => {
    if (!item) return;
    transitionMutation.mutate({ id: item.id, status });
  };

  const handleApprove = () => handleTransition('DONE');
  const handleReject = () => handleTransition('CANCELLED');

  // Only the confirmation's Confirm reaches the reject transition (Task 10).
  const confirmReject = () => {
    setConfirmingReject(false);
    handleReject();
  };

  const validTransitions = item ? TRANSITIONS[item.status] : [];

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
        className={`drawer${open ? ' open' : ''} fixed inset-y-0 right-0 z-[100] flex w-[min(460px,94vw)] flex-col border-l border-border bg-card text-card-foreground shadow-2xl transition-transform`}
        data-slot="sheet-content"
        aria-hidden={!open}
        aria-label="Task details drawer"
      >
        <header
          className="flex items-center gap-2.5 border-b border-border px-4 py-3"
          data-slot="sheet-header"
        >
          <div className="min-w-0 flex-1">
            <div className="id text-[11px] tracking-wider text-muted-foreground uppercase">
              {item ? `TASK · ${item.id.slice(0, 8).toUpperCase()}` : 'TASK'}
            </div>
            <h3 ref={headingRef} tabIndex={-1} className="truncate text-[15px] font-semibold text-foreground">
              {item?.title ?? (taskQuery.isLoading ? 'Loading…' : 'Select a task')}
            </h3>
          </div>
          {/* Defect D7: entry point into the linked agent's Live Activity
              Stream — only meaningful when the card carries an agent. */}
          {item?.linkedAgentId && (
            <Button
              variant="outline"
              size="sm"
              className="btn"
              aria-label="Open live activity"
              onClick={() => dispatchOpenAgentDrawer(item.linkedAgentId as string)}
            >
              ▶ Live activity
            </Button>
          )}
          {onOverview && item?.status === 'REVIEW' && (
            <Button
              variant="outline"
              size="sm"
              className="btn"
              onClick={() => item && openReviewMode(item.id)}
              aria-label="Expand review"
            >
              ⤢ Expand
            </Button>
          )}
          <div
            ref={closeRef}
            className="close ml-auto cursor-pointer rounded-md px-2 py-1 text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={closeTaskDrawer}
            role="button"
            tabIndex={-1}
            aria-label="Close"
          >
            ✕
          </div>
        </header>

        <div className="body min-h-0 flex-1 overflow-y-auto px-4 py-3.5">
          {taskQuery.isLoading && (
            <div className="loading-spinner py-5">
              <div className="spinner" />
              <span>Loading task…</span>
            </div>
          )}

          {taskQuery.isError && !taskQuery.isLoading && (
            <div className="evidence-error rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
              Failed to load task. It may have been deleted.
            </div>
          )}

          {item && (
            <>
              {/* Review decision zone: the first thing an operator sees on a
                  card that is waiting on them — on any column (spec 10.1).
                  While the in-place ReviewWorkspace is open the drawer is
                  closed entirely, so no zone gate is needed here. Keyed by
                  card so answer drafts reset on sibling navigation. */}
              {item.status === 'REVIEW'
                && pendingAsks.length > 0
                && runOutcome != null
                && runOutcome !== 'UNKNOWN' && (
                // Task 7: ask-ful REVIEW cards render the DecisionPanel instead
                // of the ShortApprovalView (which carries its own chip), so the
                // linked run's outcome chip must render here — a failed run
                // must not read as a normal review while asks are decided.
                <div className="dz-title">
                  <RunOutcomeChip outcome={runOutcome} />
                </div>
              )}
              {asksQuery.isError && (
                <ErrorNotice
                  presented={presentError(asksQuery.error, "Failed to load this task's asks.")}
                />
              )}
              {pendingAsks.length > 0 && (
                <DecisionPanel key={item.id} item={item} pendingAsks={pendingAsks} />
              )}
              {item.status === 'REVIEW' && asksQuery.isSuccess && pendingAsks.length === 0 && (
                <ShortApprovalView item={item} runOutcome={runOutcome} />
              )}

              {/* Status row */}
              <div className="section-h text-[11px] tracking-wider text-muted-foreground uppercase">
                Status
              </div>
              <div className="flex flex-wrap items-center gap-2">
                {/* F24: badge REVIEW only while an ask is open. A finished card
                    still parked in REVIEW is waiting for sign-off, not review. */}
                <span
                  className={reviewNeedsHuman(item) ? 'pill warn' : 'pill'}
                >
                  {item.status === 'REVIEW' && !reviewNeedsHuman(item)
                    ? 'Awaiting sign-off'
                    : STATUS_LABEL[item.status]}
                </span>
                <span className={PRIORITY_TONE[item.priority]}>{item.priority}</span>
                {item.assignee && (
                  <span className="text-[11.5px] text-muted-foreground">
                    · @{item.assignee}
                  </span>
                )}
                <span className="ml-auto text-[11px] text-muted-foreground">
                  Updated {formatTimestamp(item.updatedAt)}
                </span>
              </div>

              {/* Description */}
              <div className="section-h text-[11px] tracking-wider text-muted-foreground uppercase">
                Description
              </div>
              <div className="rounded-lg border border-border bg-muted/20 px-2.5 py-2 text-[12.5px] leading-relaxed text-muted-foreground whitespace-pre-wrap">
                {item.description?.trim() ? item.description : 'No description provided.'}
              </div>

              {/* Acceptance Criteria */}
              <div className="section-h text-[11px] tracking-wider text-muted-foreground uppercase">
                Acceptance Criteria
              </div>
              <div className="ac">
                {acceptance.length === 0 && (
                  <div className="px-0.5 py-1 text-[11.5px] text-muted-foreground">
                    No acceptance criteria on this card.
                  </div>
                )}
                {acceptance.map((row, idx) => (
                  <div
                    key={idx}
                    className={`row flex items-start gap-2 rounded-md border border-border bg-muted/20 px-2 py-1.5 text-xs ${row.done ? 'ok' : 'todo'}`}
                  >
                    <span className="check">{row.done ? '✓' : '○'}</span>
                    <div>{row.text}</div>
                  </div>
                ))}
              </div>

              {/* Artifacts */}
              <div className="section-h text-[11px] tracking-wider text-muted-foreground uppercase">
                Artifacts
              </div>
              <div className="artifacts grid gap-1.5">
                {parsed.artifacts.length === 0 && !item.linkedRunId && (
                  <div className="artifact flex items-center gap-2 rounded-lg border border-border bg-muted/20 px-2.5 py-1.5 text-xs text-muted-foreground">
                    <span className="ic">∅</span> No artifacts linked yet.
                  </div>
                )}
                {item.linkedRunId && (
                  <div className="artifact flex items-center gap-2 rounded-lg border border-border bg-muted/20 px-2.5 py-1.5 text-xs">
                    <span className="ic">▶</span>
                    Run · <span className="cell-mono">{item.linkedRunId.slice(0, 12)}</span>
                  </div>
                )}
                {item.linkedAgentId && (
                  <div className="artifact flex items-center gap-2 rounded-lg border border-border bg-muted/20 px-2.5 py-1.5 text-xs">
                    <span className="ic">★</span>
                    Agent · <span className="cell-mono">{item.linkedAgentId.slice(0, 12)}</span>
                  </div>
                )}
                {parsed.artifacts.map((a, idx) => (
                  <div
                    key={idx}
                    className="artifact flex items-center gap-2 rounded-lg border border-border bg-muted/20 px-2.5 py-1.5 text-xs"
                  >
                    <span className="ic">📄</span>
                    {a}
                  </div>
                ))}
              </div>

              {/* Linked run result (D4): the card detail must expose what the
                  run actually produced, not just its id in Artifacts. */}
              {item.linkedRunId && (
                <Card size="sm" className="run-result">
                  <CardContent className="grid gap-1.5">
                    <div className="section-h text-[11px] tracking-wider text-muted-foreground uppercase">
                      Run Result
                    </div>
                    {runQuery.isLoading && (
                      <div className="px-0.5 py-1 text-[11.5px] text-muted-foreground">
                        Loading run…
                      </div>
                    )}
                    {runQuery.isError && !runQuery.isLoading && (
                      <div className="evidence-error rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                        Failed to load run {item.linkedRunId.slice(0, 8)}.
                      </div>
                    )}
                    {linkedRun && (
                      <>
                        <div className="run-meta flex flex-wrap items-center gap-2">
                          <span className="pill">{linkedRun.status}</span>
                          <span className="cell-mono text-[11.5px] text-muted-foreground">
                            Iter {linkedRun.iterationCount}
                          </span>
                          <span className="cell-mono text-[11.5px] text-muted-foreground">
                            {tokenUsageText(linkedRun.totalTokensUsed)}
                          </span>
                          <span className="cell-mono text-[11.5px] text-muted-foreground">
                            {linkedRun.completedAt
                              ? formatTimestamp(linkedRun.completedAt)
                              : 'not finished'}
                          </span>
                        </div>
                        {linkedRun.errorMessage && (
                          <div className="evidence-error mt-2 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2.5 text-xs">
                            <RunErrorNotice
                              message={linkedRun.errorMessage}
                              surface={linkedRun.errorSurface}
                              code={linkedRun.errorCode}
                            />
                          </div>
                        )}
                        {linkedRun.finalOutput && (
                          <div className="artifact-result rounded-lg border border-success/20 bg-success/10 p-2.5 text-xs">
                            <MarkdownViewer content={linkedRun.finalOutput} />
                          </div>
                        )}
                      </>
                    )}
                  </CardContent>
                </Card>
              )}

              {transitionMutation.error && (
                <ErrorNotice
                  className="evidence-error"
                  presented={presentError(transitionMutation.error, 'The transition was refused.')}
                />
              )}

              {validTransitions.length > 1 && (
                <>
                  <div className="section-h text-[11px] tracking-wider text-muted-foreground uppercase">
                    Transition To
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {validTransitions
                      .filter((s) => s !== 'DONE' && s !== 'CANCELLED')
                      .map((s) => (
                        <Button
                          key={s}
                          variant="outline"
                          size="sm"
                          className="btn"
                          disabled={transitionMutation.isPending}
                          onClick={() => handleTransition(s)}
                        >
                          → {STATUS_LABEL[s]}
                        </Button>
                      ))}
                  </div>
                </>
              )}
            </>
          )}
        </div>

        <footer
          className="flex gap-2 border-t border-border bg-muted/20 px-4 py-3.5"
          data-slot="sheet-footer"
        >
          <Button
            variant="default"
            className="btn primary flex-1"
            title={item?.pendingAskCount
              ? `Decide the ${item.pendingAskCount} pending ask${item.pendingAskCount === 1 ? '' : 's'} first`
              : undefined}
            disabled={
              !item
              || transitionMutation.isPending
              || !validTransitions.includes('DONE')
              // Bulk Done is refused while asks are pending (the linked run is still
              // alive): the asks above are what the operator has to settle first.
              || !!item.pendingAskCount
            }
            onClick={handleApprove}
          >
            Approve
          </Button>
          <Button
            variant="destructive"
            className="btn danger flex-1"
            disabled={!item || transitionMutation.isPending || !validTransitions.includes('CANCELLED')}
            onClick={() => setConfirmingReject(true)}
          >
            Reject
          </Button>
          <Button variant="outline" className="btn flex-1" onClick={closeTaskDrawer}>
            Close
          </Button>
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
