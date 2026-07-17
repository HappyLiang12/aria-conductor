import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listRuns, createRun, cancelRun, pauseRun, resumeRun, getRunTrajectory, getRunToolCalls } from '../api/runs';
import { listAgents } from '../api/agents';
import { useWebSocket } from '../hooks/useWebSocket';
import { StatusBadge } from '../components/StatusBadge';
import type { CreateRunRequest, RunStatus, SessionTrajectory, ToolCall } from '../types';

export function RunsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocket();
  const [showForm, setShowForm] = useState(false);
  const [filterStatus, setFilterStatus] = useState<RunStatus | ''>('');
  const [filterAgent, setFilterAgent] = useState<string>('');
  const [expandedRun, setExpandedRun] = useState<string | null>(null);
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});

  const [form, setForm] = useState<CreateRunRequest>({ agentId: '', promptSeed: '', maxIterations: 10 });

  const { data: runs, isLoading, error } = useQuery({
    queryKey: ['runs'],
    queryFn: listRuns,
  });

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  const createMutation = useMutation({
    mutationFn: createRun,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['runs'] });
      setShowForm(false);
      setForm({ agentId: '', promptSeed: '', maxIterations: 10 });
      setFormErrors({});
    },
  });

  const cancelMutation = useMutation({
    mutationFn: cancelRun,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs'] }),
  });

  const pauseMutation = useMutation({
    mutationFn: pauseRun,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs'] }),
  });

  const resumeMutation = useMutation({
    mutationFn: resumeRun,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs'] }),
  });

  useEffect(() => {
    if (lastMessage?.type.startsWith('run.')) {
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    }
  }, [lastMessage, queryClient]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const errors: Record<string, string> = {};
    if (!form.agentId) errors.agentId = 'Agent selection is required';
    if (!form.promptSeed.trim()) errors.promptSeed = 'Prompt seed is required';
    setFormErrors(errors);
    if (Object.keys(errors).length > 0) return;
    createMutation.mutate(form);
  };

  const healthyAgents = agents?.filter((a) => a.healthStatus === 'HEALTHY' || a.healthStatus === 'DEGRADED') ?? [];
  const agentMap = new Map(agents?.map((a) => [a.id, a]) ?? []);

  const filteredRuns = runs?.filter((run) => {
    if (filterStatus && run.status !== filterStatus) return false;
    if (filterAgent && run.agentId !== filterAgent) return false;
    return true;
  }) ?? [];

  const getDuration = (run: { createdAt: string; completedAt: string | null }): string => {
    const start = new Date(run.createdAt);
    const end = run.completedAt ? new Date(run.completedAt) : new Date();
    const diffMs = end.getTime() - start.getTime();
    if (diffMs < 60000) return `${Math.floor(diffMs / 1000)}s`;
    if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ${Math.floor((diffMs % 60000) / 1000)}s`;
    return `${Math.floor(diffMs / 3600000)}h ${Math.floor((diffMs % 3600000) / 60000)}m`;
  };

  return (
    <div className="page">
      <div className="page-header">
        <h2>Runs</h2>
        <button className="btn btn-primary" onClick={() => setShowForm(!showForm)}>
          {showForm ? 'Cancel' : '+ Start Run'}
        </button>
      </div>

      {/* Filters */}
      <div className="filter-bar">
        <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value as RunStatus | '')}>
          <option value="">All Statuses</option>
          <option value="PENDING">PENDING</option>
          <option value="RUNNING">RUNNING</option>
          <option value="PAUSED">PAUSED</option>
          <option value="COMPLETED">COMPLETED</option>
          <option value="FAILED">FAILED</option>
          <option value="CANCELLED">CANCELLED</option>
        </select>
        <select value={filterAgent} onChange={(e) => setFilterAgent(e.target.value)}>
          <option value="">All Agents</option>
          {agents?.map((a) => (
            <option key={a.id} value={a.id}>{a.name}</option>
          ))}
        </select>
      </div>

      {/* Start Run Form */}
      {showForm && (
        <form className="card form-card" onSubmit={handleSubmit}>
          <h3 className="form-title">Start New Run</h3>
          <div className="form-grid">
            <div className={`form-field ${formErrors.agentId ? 'field-error' : ''}`}>
              <label>Agent *</label>
              <select value={form.agentId} onChange={(e) => setForm({ ...form, agentId: e.target.value })}>
                <option value="">Select healthy agent...</option>
                {healthyAgents.map((a) => (
                  <option key={a.id} value={a.id}>{a.name} ({a.healthStatus})</option>
                ))}
              </select>
              {formErrors.agentId && <span className="error-text">{formErrors.agentId}</span>}
            </div>
            <div className="form-field">
              <label>Max Iterations</label>
              <input
                type="number"
                min={1}
                max={100}
                value={form.maxIterations}
                onChange={(e) => setForm({ ...form, maxIterations: parseInt(e.target.value) || 10 })}
              />
            </div>
            <div className={`form-field full-width ${formErrors.promptSeed ? 'field-error' : ''}`}>
              <label>Prompt Seed *</label>
              <textarea
                value={form.promptSeed}
                onChange={(e) => setForm({ ...form, promptSeed: e.target.value })}
                rows={4}
                placeholder="Describe what the agent should do..."
              />
              {formErrors.promptSeed && <span className="error-text">{formErrors.promptSeed}</span>}
            </div>
          </div>
          <div className="form-actions">
            <button className="btn btn-primary" type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Starting...' : 'Start Run'}
            </button>
            <button className="btn" type="button" onClick={() => { setShowForm(false); setFormErrors({}); }}>Cancel</button>
          </div>
        </form>
      )}

      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading runs...</span></div>}
      {error && <div className="error-state">Failed to load runs. Please retry.</div>}

      {filteredRuns.length === 0 && !isLoading && (
        <div className="empty-state">
          {runs?.length === 0 ? 'No runs yet. Start one above.' : 'No runs match your filter criteria.'}
        </div>
      )}

      {filteredRuns.length > 0 && (
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Agent</th>
                <th>Status</th>
                <th>Iterations</th>
                <th>Tokens</th>
                <th>Created</th>
                <th>Duration</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredRuns.map((run) => (
                <>
                  <tr key={run.id} className={expandedRun === run.id ? 'row-expanded' : ''}>
                    <td className="cell-mono">{run.id.slice(0, 8)}</td>
                    <td>{agentMap.get(run.agentId)?.name ?? run.agentId.slice(0, 8)}</td>
                    <td><StatusBadge status={run.status} /></td>
                    <td>{run.iterationCount}/{run.maxIterations}</td>
                    <td>{run.totalTokensUsed.toLocaleString()}</td>
                    <td>{new Date(run.createdAt).toLocaleDateString()}</td>
                    <td>{getDuration(run)}</td>
                    <td className="action-cell">
                      <button className="btn btn-sm" onClick={() => setExpandedRun(expandedRun === run.id ? null : run.id)}>
                        {expandedRun === run.id ? 'Hide' : 'Details'}
                      </button>
                      {run.status === 'RUNNING' && (
                        <button className="btn btn-sm" onClick={() => pauseMutation.mutate(run.id)}>Pause</button>
                      )}
                      {run.status === 'PAUSED' && (
                        <button className="btn btn-sm" onClick={() => resumeMutation.mutate(run.id)}>Resume</button>
                      )}
                      {['RUNNING', 'PAUSED', 'PENDING', 'INITIALIZING'].includes(run.status) && (
                        <button className="btn btn-sm btn-danger" onClick={() => cancelMutation.mutate(run.id)}>Cancel</button>
                      )}
                    </td>
                  </tr>
                  {expandedRun === run.id && (
                    <tr key={`${run.id}-detail`}>
                      <td colSpan={8} className="expanded-detail">
                        <RunDetailView runId={run.id} run={run} agentName={agentMap.get(run.agentId)?.name ?? 'Unknown'} />
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function RunDetailView({ runId, run, agentName }: { runId: string; run: { status: string; totalTokensUsed: number; errorMessage: string | null }; agentName: string }) {
  const { data: trajectory, isLoading: trajLoading } = useQuery({
    queryKey: ['run-trajectory', runId],
    queryFn: () => getRunTrajectory(runId),
    enabled: true,
  });

  const { data: toolCalls, isLoading: toolsLoading } = useQuery({
    queryKey: ['run-toolcalls', runId],
    queryFn: () => getRunToolCalls(runId),
    enabled: true,
  });

  return (
    <div className="run-detail-panel">
      <div className="detail-columns">
        {/* Status timeline */}
        <div className="detail-col">
          <h4>Status & Info</h4>
          <div className="detail-info-list">
            <div className="detail-info-item"><span>Agent</span><strong>{agentName}</strong></div>
            <div className="detail-info-item"><span>Status</span><StatusBadge status={run.status} /></div>
            <div className="detail-info-item"><span>Tokens Used</span><strong>{run.totalTokensUsed.toLocaleString()}</strong></div>
            {run.errorMessage && (
              <div className="detail-info-item error-detail"><span>Error</span><span className="error-text">{run.errorMessage}</span></div>
            )}
          </div>
        </div>

        {/* Trajectory */}
        <div className="detail-col detail-col-wide">
          <h4>Session Trajectory</h4>
          {trajLoading && <div className="mini-spinner">Loading...</div>}
          {!trajLoading && (!trajectory || trajectory.length === 0) && <div className="empty-mini">No trajectory data yet.</div>}
          {trajectory && trajectory.length > 0 && (
            <div className="trajectory-list">
              {trajectory.map((turn) => (
                <div key={turn.id} className={`trajectory-turn turn-${turn.role}`}>
                  <div className="turn-header">
                    <span className="turn-role">{turn.role}</span>
                    <span className="turn-number">#{turn.turnNumber}</span>
                    <span className="turn-tokens">{turn.inputTokens + turn.outputTokens} tokens</span>
                  </div>
                  <div className="turn-content">{turn.content.slice(0, 200)}{turn.content.length > 200 ? '...' : ''}</div>
                  {turn.toolCalls && <div className="turn-tools cell-mono">{turn.toolCalls.slice(0, 150)}...</div>}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Tool Calls */}
        <div className="detail-col">
          <h4>Tool Calls</h4>
          {toolsLoading && <div className="mini-spinner">Loading...</div>}
          {!toolsLoading && (!toolCalls || toolCalls.length === 0) && <div className="empty-mini">No tool calls yet.</div>}
          {toolCalls && toolCalls.length > 0 && (
            <div className="toolcall-list">
              {toolCalls.map((tc) => (
                <div key={tc.id} className="toolcall-item">
                  <div className="toolcall-header">
                    <span className="toolcall-name">{tc.toolName}</span>
                    <StatusBadge status={tc.status} size="sm" />
                  </div>
                  <div className="toolcall-meta">{tc.latencyMs}ms</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}