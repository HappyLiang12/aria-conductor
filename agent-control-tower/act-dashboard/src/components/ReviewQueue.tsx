import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveApproval, listApprovals, rejectApproval } from '../api/approvals';
import { applyOperatorHeaders } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import { shortRunId } from '../utils/shortRunId';
import { ErrorNotice } from './ErrorNotice';
import DiffPreview from './DiffPreview';
import { ApprovalToolDetails } from './ApprovalToolDetails';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader } from './ui/card';
import {
  NativePermissionFacts,
  NativePermissionKindPill,
  nativePermissionOf,
} from './NativePermissionAsk';

function timeAgo(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const diff = Math.max(0, Date.now() - then);
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

export default function ReviewQueue({ runId }: { runId?: string } = {}) {
  const queryClient = useQueryClient();
  // Task 37 / Item 6: the refusal keeps the error itself, not its message, so the
  // shared operator notice can classify it. A 401/403 is a session problem with
  // a remedy; anything else renders the server's text.
  const [actionError, setActionError] = useState<
    { error: unknown; action: string; fallback: string } | null
  >(null);

  const { data: approvals, isLoading, error } = useQuery({
    queryKey: ['approvals', 'PENDING', runId ?? 'all'],
    queryFn: () => listApprovals('PENDING'),
    refetchInterval: 10000,
  });

  const approveMutation = useMutation({
    mutationFn: (id: string) => {
      // /decide is operator-only (Task 12): carry the local session's CSRF token.
      applyOperatorHeaders();
      return approveApproval(id);
    },
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err: unknown) => setActionError({
      error: err,
      action: 'Approving the ask',
      fallback: 'Approve failed.',
    }),
  });

  const rejectMutation = useMutation({
    mutationFn: (id: string) => {
      applyOperatorHeaders();
      return rejectApproval(id);
    },
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err: unknown) => setActionError({
      error: err,
      action: 'Denying the ask',
      fallback: 'Reject failed.',
    }),
  });

  const items = (approvals ?? []).filter((a) => !runId || a.runId === runId);

  return (
    <Card id="panel-queue" className="panel gap-0">
      <CardHeader className="flex-row items-center justify-between gap-2 border-b border-border">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold text-card-foreground">
          <span>Review Queue</span>
          <span className="accent text-xs font-normal text-muted-foreground">
            · {items.length} pending
          </span>
        </h2>
      </CardHeader>
      {actionError && (
        <div className="px-3 pt-2">
          <ErrorNotice
            presented={presentError(actionError.error, actionError.fallback)}
            operatorSession
            actionLabel={actionError.action}
          />
        </div>
      )}
      <CardContent className="queue">
        {isLoading && (
          <div className="px-1 py-2 text-xs text-muted-foreground">
            Loading approvals…
          </div>
        )}
        {error && (
          <ErrorNotice presented={presentError(error, 'Failed to load approvals.')} />
        )}
        {!isLoading && !error && items.length === 0 && (
          <div className="px-1 py-3 text-xs text-muted-foreground">
            No pending approvals — queue is clear.
          </div>
        )}
        {items.map((approval, idx) => {
          const isFirst = idx === 0;
          const pending = approveMutation.isPending || rejectMutation.isPending;
          const permission = nativePermissionOf(approval);
          return (
            <div
              key={approval.id ?? `row-${idx}`}
              className={`qitem rounded-lg border border-border bg-muted/20 p-3${isFirst ? ' highlight' : ''}`}
            >
              <div className="h">
                {permission ? (
                  <NativePermissionKindPill permission={permission} />
                ) : (
                  <span className="pill warn">Review</span>
                )}
                <span className="owner ml-auto text-[11px] text-muted-foreground">
                  {timeAgo(approval.requestedAt)}
                </span>
              </div>
              <div className="ttl text-xs font-semibold text-foreground">
                {approval.toolName ? (
                  <>
                    <span className="cell-mono">{approval.toolName}</span>
                    {' · Run '}
                    <span className="cell-mono">{shortRunId(approval.runId)}</span>
                  </>
                ) : (
                  <>
                    Run · <span className="cell-mono">{shortRunId(approval.runId)}</span>
                  </>
                )}
              </div>
              <div className="desc mb-2 text-[11px] text-muted-foreground">
                {permission
                  ? approval.reason || 'Awaiting human verification before tool execution proceeds.'
                  : approval.content ?? approval.reason ?? 'Review requested — open the run for context.'}
              </div>
              {permission && (
                <div className="ask-meta">
                  <NativePermissionFacts ask={approval} permission={permission} />
                </div>
              )}
              {/* F7: the row title already names the tool; only the arguments
                  (pretty-printed, redacted) are added here, and nothing renders
                  when the payload predates the fields. */}
              <ApprovalToolDetails ask={approval} includeToolName={false} />
              {(approval.toolName === 'git_push' || approval.toolName === 'git_create_pr'
                || approval.riskTier === 'PUSH') && approval.runId && <DiffPreview runId={approval.runId} />}
              {approval.source === 'CLARIFICATION' ? (
                // Waiting-input (2026-10-05): the backend refuses /decide on a
                // CLARIFICATION ask (409 + hint) — it is answered (Answer &
                // continue) or the run is finalized from the kanban card's
                // decision panel, never approved/denied here. Point at that
                // instead of offering buttons that can only fail.
                <div className="ask-ctx rounded-md border border-warning/40 bg-warning/10 px-2 py-1.5 text-[11px] text-warning">
                  This ask is waiting on the run — answer it on the kanban card
                  (Answer &amp; continue) or finalize the run from there.
                </div>
              ) : (
                <div className="row flex gap-1.5">
                  <Button
                    className="btn primary flex-1"
                    variant="default"
                    size="sm"
                    disabled={pending}
                    onClick={() => approveMutation.mutate(approval.id)}
                  >
                    {approveMutation.isPending && approveMutation.variables === approval.id
                      ? 'Approving…'
                      : 'Approve'}
                  </Button>
                  <Button
                    className="btn danger flex-1"
                    variant="destructive"
                    size="sm"
                    disabled={pending}
                    onClick={() => rejectMutation.mutate(approval.id)}
                  >
                    {rejectMutation.isPending && rejectMutation.variables === approval.id
                      ? 'Denying…'
                      : 'Deny'}
                  </Button>
                </div>
              )}
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}
