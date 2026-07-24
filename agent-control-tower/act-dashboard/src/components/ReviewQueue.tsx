import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveApproval, listApprovals, rejectApproval } from '../api/approvals';
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

  const { data: approvals, isLoading, error } = useQuery({
    queryKey: ['approvals', 'PENDING', runId ?? 'all'],
    queryFn: () => listApprovals('PENDING'),
    refetchInterval: 10000,
  });

  const approveMutation = useMutation({
    mutationFn: (id: string) => approveApproval(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
  });

  const rejectMutation = useMutation({
    mutationFn: (id: string) => rejectApproval(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
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
          const pending = approveMutation.isPending || rejectMutation.isPending;
          return (
            <div key={approval.id} className={`qitem${isFirst ? ' highlight' : ''}`}>
              <div className="h">
                <span className="pill warn">Approval</span>
                <span
                  className="owner"
                  style={{ marginLeft: 'auto', color: 'var(--text-mute)', fontSize: 11 }}
                >
                  {timeAgo(approval.requestedAt)}
                </span>
              </div>
              <div className="ttl">
                Run · <span className="cell-mono">{approval.runId.slice(0, 8)}</span>
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
                  disabled={pending}
                  onClick={() => approveMutation.mutate(approval.id)}
                >
                  {approveMutation.isPending && approveMutation.variables === approval.id
                    ? 'Approving…'
                    : 'Approve'}
                </button>
                <button
                  className="btn danger"
                  style={{ flex: 1 }}
                  disabled={pending}
                  onClick={() => rejectMutation.mutate(approval.id)}
                >
                  {rejectMutation.isPending && rejectMutation.variables === approval.id
                    ? 'Denying…'
                    : 'Deny'}
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
