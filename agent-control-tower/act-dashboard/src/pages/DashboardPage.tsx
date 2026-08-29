import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { getSummary, getRecentActivity } from '../api/dashboard';
import { formatTimestamp } from '../utils/formatTime';
import { useWebSocketContext } from '../components/Layout';
import { isKanbanEvent, isRunLifecycleEvent } from '../utils/wsEvents';
import type { ActivityEvent } from '../types';

export function DashboardPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { lastMessage, isConnected } = useWebSocketContext();
  const [activityFeed, setActivityFeed] = useState<ActivityEvent[]>([]);

  const { data: summary, isLoading, error } = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 10000,
  });

  const { data: initialActivity } = useQuery({
    queryKey: ['dashboard-activity'],
    queryFn: getRecentActivity,
  });

  useEffect(() => {
    if (initialActivity) {
      setActivityFeed(initialActivity.slice(0, 10));
    }
  }, [initialActivity]);

  useEffect(() => {
    if (!lastMessage) return;

    const eventType = lastMessage.type;
    // S1 whitelist: kanban events now refresh the summary instantly; the
    // high-frequency run.progress stream is excluded from list invalidation.
    if (eventType.startsWith('agent.') || eventType.startsWith('approval.') || eventType.startsWith('knowledge.')
        || isRunLifecycleEvent(eventType) || isKanbanEvent(eventType)) {
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    }

    const payload = lastMessage.payload;
    const newEvent: ActivityEvent = {
      eventType: eventType,
      resourceType: (payload.resourceType as string) || eventType.split('.')[0],
      resourceId: (payload.resourceId as string) || (payload.agentId as string) || (payload.runId as string) || (payload.approvalId as string) || (payload.itemId as string) || '',
      action: eventType.split('.')[1] || 'update',
      timestamp: lastMessage.timestamp || new Date().toISOString(),
    };

    setActivityFeed((prev) => [newEvent, ...prev].slice(0, 10));
  }, [lastMessage, queryClient]);

  const tokenCost = summary ? (summary.totalTokensBurned * 0.00003).toFixed(2) : '0.00';

  return (
    <div className="page">
      <div className="page-header">
        <h2>Dashboard</h2>
        <div className="connection-status">
          <span className={`status-dot ${isConnected ? 'connected' : 'disconnected'}`} />
          <span className="status-label">{isConnected ? 'Connected' : 'Offline'}</span>
        </div>
      </div>

      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading dashboard...</span></div>}
      {error && <div className="error-state">Unable to load dashboard data. Please check backend connection.</div>}

      <div className="kpi-grid">
        <div className="kpi-card kpi-agents" onClick={() => navigate('/agents')}>
          <div className="kpi-icon">👤</div>
          <div className="kpi-content">
            <div className="kpi-value">{summary?.activeAgents ?? '—'}</div>
            <div className="kpi-label">Active Agents</div>
          </div>
          <div className={`kpi-indicator ${(summary?.activeAgents ?? 0) > 0 ? 'positive' : 'neutral'}`} />
        </div>
        <div className="kpi-card kpi-runs" onClick={() => navigate('/runs')}>
          <div className="kpi-icon">▶</div>
          <div className="kpi-content">
            <div className="kpi-value kpi-animated">{summary?.runningRuns ?? '—'}</div>
            <div className="kpi-label">Running Runs</div>
          </div>
          {(summary?.runningRuns ?? 0) > 0 && <div className="kpi-pulse" />}
        </div>
        <div className="kpi-card kpi-approvals" onClick={() => navigate('/approvals')}>
          <div className="kpi-icon">✓</div>
          <div className="kpi-content">
            <div className="kpi-value">{summary?.pendingApprovals ?? '—'}</div>
            <div className="kpi-label">Pending Approvals</div>
          </div>
          {(summary?.pendingApprovals ?? 0) > 0 && <div className="kpi-indicator urgent" />}
        </div>
        <div className="kpi-card kpi-tokens">
          <div className="kpi-icon">⚡</div>
          <div className="kpi-content">
            <div className="kpi-value">{summary?.totalTokensBurned?.toLocaleString() ?? '—'}</div>
            <div className="kpi-label">Tokens Burned</div>
          </div>
          {summary && <div className="kpi-cost">~${tokenCost} est.</div>}
        </div>
      </div>

      <div className="dashboard-actions">
        <button className="btn btn-primary action-btn" onClick={() => navigate('/runs')}>
          <span className="action-icon">▶</span> Start Run
        </button>
        <button className="btn btn-warning action-btn" onClick={() => navigate('/approvals')}>
          <span className="action-icon">✓</span> View Approvals
        </button>
      </div>

      <div className="card" style={{ marginTop: '2rem' }}>
        <h3>Activity Feed</h3>
        {activityFeed.length === 0 ? (
          <div className="empty-state">No recent activity. Events will appear here as they occur.</div>
        ) : (
          <div className="activity-feed">
            {activityFeed.map((event, i) => (
              <div key={`${event.timestamp}-${i}`} className="activity-item">
                <div className={`activity-dot activity-${event.resourceType}`} />
                <div className="activity-content">
                  <div className="activity-label">
                    <span className="activity-type">{event.resourceType}</span>
                    <span className="activity-action">{event.action}</span>
                  </div>
                  <div className="activity-id cell-mono">{event.resourceId?.slice(0, 8) ?? ''}</div>
                </div>
                <div className="activity-time">{formatTimestamp(event.timestamp)}</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}