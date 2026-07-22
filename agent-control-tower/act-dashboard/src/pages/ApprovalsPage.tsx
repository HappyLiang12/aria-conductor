import { useState, useEffect, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listApprovals, decideApproval } from '../api/approvals';
import { listAgents } from '../api/agents';
import { useWebSocketContext } from '../components/Layout';
import { StatusBadge } from '../components/StatusBadge';
import type { ApprovalStatus } from '../types';

interface ApprovalWithReason {
  id: string;
  reason: string;
}

// Map a governance risk tier to a human label + badge class (#24). PUSH/DESTRUCTIVE are high
// risk; previously the badge was hardcoded to "Medium" for every approval.
function riskBadge(riskTier?: string): { label: string; cls: string } {
  switch ((riskTier || '').toUpperCase()) {
    case 'DESTRUCTIVE':
    case 'PUSH':
      return { label: 'High', cls: 'risk-high' };
    case 'WRITE_LOCAL':
      return { label: 'Medium', cls: 'risk-medium' };
    case 'READ':
      return { label: 'Low', cls: 'risk-low' };
    default:
      return { label: riskTier || 'Unknown', cls: 'risk-medium' };
  }
}

export function ApprovalsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();
  const [activeTab, setActiveTab] = useState<'pending' | 'history'>('pending');
  const [denyReasons, setDenyReasons] = useState<Record<string, string>>({});
  const [confirmApprove, setConfirmApprove] = useState<ApprovalWithReason | null>(null);

  const { data: approvals, isLoading, error } = useQuery({
    queryKey: ['approvals'],
    queryFn: () => listApprovals(),
    refetchInterval: 5000,
  });

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  const decideMutation = useMutation({
    mutationFn: ({ id, approved, reason }: { id: string; approved: boolean; reason?: string }) =>
      decideApproval(id, { approved, reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      setConfirmApprove(null);
    },
  });

  useEffect(() => {
    if (lastMessage?.type.startsWith('approval.')) {
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
    }
  }, [lastMessage, queryClient]);

  const pending = approvals?.filter((a) => a.status === 'PENDING') ?? [];
  const resolved = approvals?.filter((a) => a.status !== 'PENDING') ?? [];
  const agentMap = new Map(agents?.map((a) => [a.id, a]) ?? []);

  const getCountdown = useCallback((expiresAt: string): { text: string; urgent: boolean } => {
    const now = new Date();
    const expiry = new Date(expiresAt);
    const diffMs = expiry.getTime() - now.getTime();
    if (diffMs <= 0) return { text: 'EXPIRED', urgent: true };
    const urgent = diffMs < 300000; // 5 minutes
    const minutes = Math.floor(diffMs / 60000);
    const seconds = Math.floor((diffMs % 60000) / 1000);
    return { text: `${minutes}m ${seconds}s`, urgent };
  }, []);

  const handleApprove = (id: string, reason: string) => {
    decideMutation.mutate({ id, approved: true, reason });
  };

  const handleDeny = (id: string) => {
    const reason = denyReasons[id] || '';
    decideMutation.mutate({ id, approved: false, reason });
  };

  return (
    <div className="page">
      <div className="page-header">
        <h2>Approvals</h2>
        {pending.length > 0 && <span className="badge badge-warning">{pending.length} pending</span>}
      </div>

      {/* Tabs */}
      <div className="tab-bar">
        <button className={`tab-btn ${activeTab === 'pending' ? 'active' : ''}`} onClick={() => setActiveTab('pending')}>
          Pending ({pending.length})
        </button>
        <button className={`tab-btn ${activeTab === 'history' ? 'active' : ''}`} onClick={() => setActiveTab('history')}>
          History ({resolved.length})
        </button>
      </div>

      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading approvals...</span></div>}
      {error && <div className="error-state">Failed to load approvals.</div>}

      {/* Pending Tab */}
      {activeTab === 'pending' && (
        <>
          {pending.length === 0 && !isLoading && (
            <div className="empty-state">No pending approvals. All clear!</div>
          )}
          <div className="approval-card-grid">
            {pending.map((approval) => {
              const countdown = getCountdown(approval.expiresAt);
              return (
                <div key={approval.id} className={`approval-detail-card ${countdown.urgent ? 'approval-urgent' : ''}`}>
                  <div className="approval-card-header">
                    <span className="cell-mono">Run {approval.runId.slice(0, 8)}</span>
                    <StatusBadge status={approval.status} />
                    {countdown.urgent && <span className="urgency-badge">URGENT</span>}
                  </div>
                  <div className="approval-card-body">
                    <div className="approval-info-row">
                      <span className="approval-label">Tool/Action</span>
                      <span className="cell-mono">{approval.toolName ?? approval.toolCallId.slice(0, 8)}</span>
                    </div>
                    {approval.arguments && (
                      <div className="approval-info-row">
                        <span className="approval-label">Arguments</span>
                        <span className="cell-mono approval-args" title={approval.arguments}>
                          {approval.arguments.length > 160 ? approval.arguments.slice(0, 160) + '…' : approval.arguments}
                        </span>
                      </div>
                    )}
                    <div className="approval-info-row">
                      <span className="approval-label">Risk Level</span>
                      <span className={`risk-badge ${riskBadge(approval.riskTier).cls}`}>
                        {riskBadge(approval.riskTier).label}
                      </span>
                    </div>
                    {approval.reason && (
                      <div className="approval-reason-text">{approval.reason}</div>
                    )}
                    <div className="approval-countdown">
                      <span className="countdown-label">Expires in</span>
                      <span className={`countdown-value ${countdown.urgent ? 'countdown-urgent' : ''}`}>{countdown.text}</span>
                    </div>
                  </div>
                  <div className="approval-card-footer">
                    <div className="approval-reason-input">
                      <input
                        placeholder="Optional reason..."
                        value={denyReasons[approval.id] || ''}
                        onChange={(e) => setDenyReasons({ ...denyReasons, [approval.id]: e.target.value })}
                      />
                    </div>
                    <div className="approval-action-buttons">
                      <button
                        className="btn btn-success btn-approve"
                        onClick={() => setConfirmApprove({ id: approval.id, reason: approval.reason })}
                        disabled={decideMutation.isPending}
                      >
                        Approve
                      </button>
                      <button
                        className="btn btn-danger btn-deny"
                        onClick={() => handleDeny(approval.id)}
                        disabled={decideMutation.isPending}
                      >
                        Deny
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* History Tab */}
      {activeTab === 'history' && (
        <>
          {resolved.length === 0 && !isLoading && (
            <div className="empty-state">No resolved approvals yet.</div>
          )}
          {resolved.length > 0 && (
            <div className="table-wrapper">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Run</th>
                    <th>Status</th>
                    <th>Decided At</th>
                    <th>Expires At</th>
                  </tr>
                </thead>
                <tbody>
                  {resolved.map((a) => (
                    <tr key={a.id}>
                      <td className="cell-mono">{a.id.slice(0, 8)}</td>
                      <td className="cell-mono">{a.runId.slice(0, 8)}</td>
                      <td><StatusBadge status={a.status} size="sm" /></td>
                      <td>{a.decidedAt ? new Date(a.decidedAt).toLocaleString() : '—'}</td>
                      <td>{new Date(a.expiresAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {/* Confirm Approve Dialog */}
      {confirmApprove && (
        <div className="modal-overlay" onClick={() => setConfirmApprove(null)}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>Confirm Approval</h3>
            <p>You are about to <strong>approve</strong> this request. The agent will proceed with the requested action.</p>
            {confirmApprove.reason && <div className="approval-reason-preview">Reason: {confirmApprove.reason}</div>}
            <div className="modal-actions">
              <button className="btn btn-success" onClick={() => handleApprove(confirmApprove.id, denyReasons[confirmApprove.id] || '')} disabled={decideMutation.isPending}>
                {decideMutation.isPending ? 'Processing...' : 'Confirm Approve'}
              </button>
              <button className="btn" onClick={() => setConfirmApprove(null)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}