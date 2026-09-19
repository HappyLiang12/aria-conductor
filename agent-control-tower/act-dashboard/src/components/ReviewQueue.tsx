import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveApproval, listApprovals, rejectApproval } from '../api/approvals';
import {
  describeDecisionError,
  hasAllowOnce,
  isAcpAsk,
  isAskExpired,
  isUndecidableAcpAsk,
} from '../utils/acpAsk';
import type { Approval } from '../types';
import DiffPreview from './DiffPreview';

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
  // C5-fix1: the queue is a PENDING-only list across the system, so it carries
  // no outcome strip (the durable strip lives on the card surfaces). But an ACP
  // decide failure must not be swallowed like the legacy one: the last rejected
  // attempt renders under its row and is cleared on the next attempt — row
  // scope, not an outcome record.
  const [rowError, setRowError] = useState<{ askId: string; text: string } | null>(null);

  const { data: approvals, isLoading, error } = useQuery({
    queryKey: ['approvals', 'PENDING', runId ?? 'all'],
    queryFn: () => listApprovals('PENDING'),
    refetchInterval: 10000,
  });

  /** Typed 409 `code: message` for ACP rows; legacy rows keep today's silence. */
  const rowErrorOf = (err: unknown, ask: Approval): { askId: string; text: string } | null => {
    if (!isAcpAsk(ask)) return null;
    const rejection = describeDecisionError(err);
    return {
      askId: ask.id,
      text: rejection
        ? `${rejection.code}: ${rejection.message}`
        : 'Action rejected — please retry.',
    };
  };

  const approveMutation = useMutation({
    mutationFn: (ask: Approval) => approveApproval(ask.id),
    onMutate: () => setRowError(null),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err, ask) => setRowError(rowErrorOf(err, ask)),
  });

  const rejectMutation = useMutation({
    mutationFn: (ask: Approval) => rejectApproval(ask.id),
    onMutate: () => setRowError(null),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err, ask) => setRowError(rowErrorOf(err, ask)),
  });

  const items = (approvals ?? []).filter((a) => !runId || a.runId === runId);

  return (
    <section className="panel" id="panel-queue">
      <h2>
        <span>Review Queue</span>
        <span className="accent">· {items.length} pending</span>
      </h2>
      <div className="queue">
        {isLoading && (
          <div style={{ padding: '8px 4px', color: 'var(--text-mute)', fontSize: 12 }}>
            Loading approvals…
          </div>
        )}
        {error && (
          <div style={{ padding: '8px 4px', color: 'var(--red)', fontSize: 12 }}>
            Failed to load approvals.
          </div>
        )}
        {!isLoading && items.length === 0 && (
          <div style={{ padding: '12px 4px', color: 'var(--text-mute)', fontSize: 12 }}>
            No pending approvals — queue is clear.
          </div>
        )}
        {items.map((approval, idx) => {
          const isFirst = idx === 0;
          const acp = isAcpAsk(approval);
          // UX-6: the expiry gate covers every ask that carries an `expiresAt`, not just ACP
          // asks — a stale legacy row must not offer enabled buttons for a decision the
          // backend would no longer honour.
          const expired = isAskExpired(approval);
          const pending = approveMutation.isPending || rejectMutation.isPending;
          // F3/R4: the backend refuses an approval the bridge truncated (or whose
          // display record cannot prove decidability) with a typed 409
          // UNDECIDABLE_ASK, so Allow once must not be offered enabled here
          // either. Deny still works.
          const undecidable = acp && isUndecidableAcpAsk(approval);
          return (
            <div key={approval.id} className={`qitem${isFirst ? ' highlight' : ''}`}>
              <div className="h">
                {acp ? (
                  <span className="pill acp">ACP permission</span>
                ) : (
                  <span className="pill warn">Approval</span>
                )}
                <span
                  className="owner"
                  style={{ marginLeft: 'auto', color: 'var(--text-mute)', fontSize: 11 }}
                >
                  {timeAgo(approval.requestedAt)}
                </span>
              </div>
              <div className="ttl">
                {approval.toolName ? (
                  <>
                    <span className="cell-mono">{approval.toolName}</span>
                    {' · Run '}
                    <span className="cell-mono">{approval.runId.slice(0, 8)}</span>
                  </>
                ) : (
                  <>
                    Run · <span className="cell-mono">{approval.runId.slice(0, 8)}</span>
                  </>
                )}
              </div>
              <div className="desc">
                {approval.reason || 'Awaiting human verification before tool execution proceeds.'}
              </div>
              {(approval.toolName === 'git_push' || approval.toolName === 'git_create_pr'
                || approval.riskTier === 'PUSH') && <DiffPreview runId={approval.runId} />}
              <div className="row">
                <button
                  className="btn primary"
                  style={{ flex: 1 }}
                  disabled={pending || expired || (acp && !hasAllowOnce(approval)) || undecidable}
                  onClick={() => approveMutation.mutate(approval)}
                >
                  {approveMutation.isPending && approveMutation.variables?.id === approval.id
                    ? 'Approving…'
                    : acp
                      ? 'Allow once'
                      : 'Approve'}
                </button>
                <button
                  className="btn danger"
                  style={{ flex: 1 }}
                  disabled={pending || expired}
                  onClick={() => rejectMutation.mutate(approval)}
                >
                  {rejectMutation.isPending && rejectMutation.variables?.id === approval.id
                    ? 'Denying…'
                    : 'Deny'}
                </button>
              </div>
              {undecidable && (
                <div className="acp-hint">
                  This ask cannot be approved: its input is incomplete or unreadable. Deny still works.
                </div>
              )}
              {rowError?.askId === approval.id && (
                <div className="kanban-form-error">{rowError.text}</div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
