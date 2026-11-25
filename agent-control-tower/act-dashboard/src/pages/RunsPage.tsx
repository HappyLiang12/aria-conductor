import { Fragment, useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listRuns, createRun, cancelRun, pauseRun, resumeRun, getRunTrajectory, getRunToolCalls } from '../api/runs';
import { formatTimestamp } from '../utils/formatTime';
import { listAgents } from '../api/agents';
import { useWebSocketContext } from '../components/Layout';
import { isRunLifecycleEvent } from '../utils/wsEvents';
import { StatusBadge } from '../components/StatusBadge';
import { RuntimeBadge } from '../components/RuntimeBadge';
import { RunErrorNotice, ErrorNotice } from '../components/ErrorNotice';
import { SectionCard } from '../components/layout/SectionCard';
import { presentError, type PresentedError } from '../api/errorPresentation';
import { tokenUsageCell, tokenUsageText } from '../utils/tokenUsage';
import type { CreateRunRequest, RunStatus, SessionTrajectory, ToolCall } from '../types';

const TH_CLASS =
  'border-b border-border bg-muted/50 px-3 py-2 text-left text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase';
const TD_CLASS = 'border-b border-border px-3 py-2 align-middle';
const BTN_CLASS =
  'inline-flex h-7 items-center justify-center rounded-lg border border-border bg-background px-2 text-xs font-medium text-foreground transition-colors hover:bg-muted disabled:opacity-50';
const INPUT_CLASS =
  'w-full rounded-lg border border-input bg-background px-2.5 py-1.5 text-sm text-foreground outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40';

export function RunsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();
  const [showForm, setShowForm] = useState(false);
  const [filterStatus, setFilterStatus] = useState<RunStatus | ''>('');
  const [filterAgent, setFilterAgent] = useState<string>('');
  const [expandedRun, setExpandedRun] = useState<string | null>(null);
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState<PresentedError | null>(null);

  const [form, setForm] = useState<CreateRunRequest>({ agentId: '', promptSeed: '' });

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
      setForm({ agentId: '', promptSeed: '' });
      setFormErrors({});
      setActionError(null);
    },
    onError: (err: unknown) => setActionError(presentError(err, 'The run could not be started.')),
  });

  const cancelMutation = useMutation({
    mutationFn: cancelRun,
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: unknown) => setActionError(presentError(err, 'The cancel request was refused.')),
  });

  const pauseMutation = useMutation({
    mutationFn: pauseRun,
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: unknown) => setActionError(presentError(err, 'The pause request was refused.')),
  });

  const resumeMutation = useMutation({
    mutationFn: resumeRun,
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: unknown) => setActionError(presentError(err, 'The resume request was refused.')),
  });

  useEffect(() => {
    // S1 whitelist: only lifecycle events refresh the list; run.progress is
    // consumed precisely by RunDetailView via runId-matched invalidation.
    if (isRunLifecycleEvent(lastMessage?.type ?? '')) {
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    }
  }, [lastMessage, queryClient]);

  // S3: precise invalidation — streaming/lifecycle events for the expanded run
  // refresh its trajectory/tool-calls only (never the whole list).
  useEffect(() => {
    const t = lastMessage?.type ?? '';
    const runId = (lastMessage?.payload?.runId as string | undefined) ?? '';
    if (!runId || runId !== expandedRun) return;
    if (t === 'run.iteration' || t === 'run.progress' || t === 'run.completed') {
      queryClient.invalidateQueries({ queryKey: ['run-trajectory', runId] });
      queryClient.invalidateQueries({ queryKey: ['run-toolcalls', runId] });
    }
  }, [lastMessage, expandedRun, queryClient]);

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
    <div className="mx-auto w-full max-w-[1400px] p-4 sm:p-6">
      {/* E2E/CSS contract: the page leads with `.page-header h2` (the legacy runs
          chrome styles that selector directly), so the token type scale is set on
          an inner span it cannot override. */}
      <div className="page-header mb-5 flex flex-wrap items-start justify-between gap-3">
        <h2 className="min-w-0">
          <span className="text-2xl leading-8 font-semibold tracking-tight text-foreground normal-case">Runs</span>
        </h2>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <button
            className="btn btn-primary inline-flex h-8 items-center justify-center rounded-lg border border-transparent bg-primary px-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/80"
            onClick={() => setShowForm(!showForm)}
          >
            {showForm ? 'Cancel' : '+ Start Run'}
          </button>
        </div>
      </div>

      {actionError && (
        <div className="mb-3">
          <ErrorNotice presented={actionError} />
        </div>
      )}

      {/* Filters */}
      <div className="filter-bar mb-4 flex flex-wrap items-center gap-2">
        <select
          className={INPUT_CLASS}
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value as RunStatus | '')}
        >
          <option value="">All Statuses</option>
          <option value="PENDING">PENDING</option>
          <option value="RUNNING">RUNNING</option>
          <option value="PAUSED">PAUSED</option>
          <option value="COMPLETED">COMPLETED</option>
          <option value="FAILED">FAILED</option>
          <option value="CANCELLED">CANCELLED</option>
          <option value="ABORTED">ABORTED</option>
        </select>
        <select
          className={INPUT_CLASS}
          value={filterAgent}
          onChange={(e) => setFilterAgent(e.target.value)}
        >
          <option value="">All Agents</option>
          {agents?.map((a) => (
            <option key={a.id} value={a.id}>{a.name}</option>
          ))}
        </select>
      </div>

      {/* Start Run Form */}
      {showForm && (
        <form className="card form-card mb-4 rounded-xl border border-border bg-card p-4 text-card-foreground" onSubmit={handleSubmit}>
          <h3 className="form-title mb-4 text-sm font-bold text-foreground">Start New Run</h3>
          <div className="form-grid grid grid-cols-1 gap-3.5 sm:grid-cols-2">
            <div className={`form-field flex flex-col gap-1.5 ${formErrors.agentId ? 'field-error' : ''}`}>
              <label className="text-[10.5px] font-semibold tracking-[.8px] text-muted-foreground uppercase">
                Agent *
              </label>
              <select
                className={`${INPUT_CLASS} ${formErrors.agentId ? 'border-destructive' : ''}`}
                value={form.agentId}
                onChange={(e) => setForm({ ...form, agentId: e.target.value })}
              >
                <option value="">Select healthy agent...</option>
                {healthyAgents.map((a) => (
                  <option key={a.id} value={a.id}>{a.name} ({a.healthStatus})</option>
                ))}
              </select>
              {formErrors.agentId && <span className="error-text text-xs text-destructive">{formErrors.agentId}</span>}
            </div>
            <div className={`form-field full-width flex flex-col gap-1.5 sm:col-span-2 ${formErrors.promptSeed ? 'field-error' : ''}`}>
              <label className="text-[10.5px] font-semibold tracking-[.8px] text-muted-foreground uppercase">
                Prompt Seed *
              </label>
              <textarea
                className={`${INPUT_CLASS} min-h-20 resize-y ${formErrors.promptSeed ? 'border-destructive' : ''}`}
                value={form.promptSeed}
                onChange={(e) => setForm({ ...form, promptSeed: e.target.value })}
                rows={4}
                placeholder="Describe what the agent should do..."
              />
              {formErrors.promptSeed && <span className="error-text text-xs text-destructive">{formErrors.promptSeed}</span>}
            </div>
          </div>
          <div className="form-actions mt-4 flex gap-2.5">
            <button
              className="btn btn-primary inline-flex h-8 items-center justify-center rounded-lg border border-transparent bg-primary px-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/80 disabled:opacity-50"
              type="submit"
              disabled={createMutation.isPending}
            >
              {createMutation.isPending ? 'Starting...' : 'Start Run'}
            </button>
            <button
              className="btn inline-flex h-8 items-center justify-center rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-muted"
              type="button"
              onClick={() => { setShowForm(false); setFormErrors({}); }}
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      {isLoading && (
        <div className="loading-spinner flex items-center gap-2 py-6 text-sm text-muted-foreground">
          <div className="spinner size-4 animate-spin rounded-full border-2 border-muted border-t-primary" />
          <span>Loading runs...</span>
        </div>
      )}
      {error && <ErrorNotice presented={presentError(error, 'Failed to load runs.')} />}

      {!error && filteredRuns.length === 0 && !isLoading && (
        <div className="empty-state rounded-lg border border-dashed border-border px-3 py-6 text-center text-sm text-muted-foreground">
          {runs?.length === 0 ? 'No runs yet. Start one above.' : 'No runs match your filter criteria.'}
        </div>
      )}

      {filteredRuns.length > 0 && (
        <SectionCard title="Run History" count={filteredRuns.length}>
          <div className="table-wrapper overflow-x-auto rounded-xl border border-border">
            <table className="data-table w-full border-collapse text-sm">
              <thead>
                <tr>
                  <th className={TH_CLASS}>ID</th>
                  <th className={TH_CLASS}>Agent</th>
                  <th className={TH_CLASS}>Runtime</th>
                  <th className={TH_CLASS}>Status</th>
                  <th className={TH_CLASS}>Iterations</th>
                  <th className={TH_CLASS}>Tokens</th>
                  <th className={TH_CLASS}>Created</th>
                  <th className={TH_CLASS}>Duration</th>
                  <th className={TH_CLASS}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredRuns.map((run) => (
                  <Fragment key={run.id}>
                    <tr className={expandedRun === run.id ? 'row-expanded bg-primary/10' : 'hover:bg-muted/40'}>
                      <td className={`${TD_CLASS} cell-mono font-mono text-[11.5px] text-muted-foreground`}>{run.id.slice(0, 8)}</td>
                      <td className={TD_CLASS}>{agentMap.get(run.agentId)?.name ?? run.agentId.slice(0, 8)}</td>
                      <td className={TD_CLASS}><RuntimeBadge core={run.coreId} mode={run.executionMode} /></td>
                      <td className={TD_CLASS}><StatusBadge status={run.status} /></td>
                      <td className={`${TD_CLASS} tabular-nums`}>{run.iterationCount}</td>
                      <td className={`${TD_CLASS} tabular-nums`}>{tokenUsageCell(run.totalTokensUsed)}</td>
                      <td className={`${TD_CLASS} text-muted-foreground`}>{formatTimestamp(run.createdAt)}</td>
                      <td className={`${TD_CLASS} text-muted-foreground`}>{getDuration(run)}</td>
                      <td className={`${TD_CLASS} action-cell`}>
                        <div className="flex flex-wrap gap-1.5">
                          <button className={`btn btn-sm ${BTN_CLASS}`} onClick={() => setExpandedRun(expandedRun === run.id ? null : run.id)}>
                            {expandedRun === run.id ? 'Hide' : 'Details'}
                          </button>
                          {run.status === 'RUNNING' && (
                            <button className={`btn btn-sm ${BTN_CLASS}`} onClick={() => pauseMutation.mutate(run.id)}>Pause</button>
                          )}
                          {run.status === 'PAUSED' && (
                            <button className={`btn btn-sm ${BTN_CLASS}`} onClick={() => resumeMutation.mutate(run.id)}>Resume</button>
                          )}
                          {['RUNNING', 'PAUSED', 'PENDING', 'INITIALIZING'].includes(run.status) && (
                            <button
                              className="btn btn-sm btn-danger inline-flex h-7 items-center justify-center rounded-lg border border-destructive/40 bg-background px-2 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10"
                              onClick={() => cancelMutation.mutate(run.id)}
                            >
                              Cancel
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                    {expandedRun === run.id && (
                      <tr key={`${run.id}-detail`}>
                        <td colSpan={9} className="expanded-detail">
                          <RunDetailView runId={run.id} run={run} agentName={agentMap.get(run.agentId)?.name ?? 'Unknown'} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}
    </div>
  );
}

function RunDetailView({ runId, run, agentName }: { runId: string; run: { status: string; totalTokensUsed: number | null; errorMessage: string | null; errorSurface?: string | null; errorCode?: string | null }; agentName: string }) {
  // S3: live while RUNNING (5s), stops at terminal status.
  const live = run.status === 'RUNNING';
  const [trajCollapsed, setTrajCollapsed] = useState(false);
  const [toolsCollapsed, setToolsCollapsed] = useState(false);
  const { data: trajectory, isLoading: trajLoading, error: trajError } = useQuery({
    queryKey: ['run-trajectory', runId],
    queryFn: () => getRunTrajectory(runId),
    enabled: true,
    refetchInterval: live ? 5000 : false,
  });

  const { data: toolCalls, isLoading: toolsLoading, error: toolsError } = useQuery({
    queryKey: ['run-toolcalls', runId],
    queryFn: () => getRunToolCalls(runId),
    enabled: true,
    refetchInterval: live ? 5000 : false,
  });

  return (
    <div className="run-detail-panel space-y-4 p-3">
      <div className="detail-columns grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_minmax(0,1fr)]">
        {/* Status timeline */}
        <div className="detail-col rounded-xl border border-border bg-muted/30 p-3">
          <h4 className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Status &amp; Info</h4>
          <div className="detail-info-list space-y-1.5 text-sm">
            <div className="detail-info-item flex items-center justify-between gap-2">
              <span className="text-muted-foreground">Agent</span>
              <strong className="font-semibold text-foreground">{agentName}</strong>
            </div>
            <div className="detail-info-item flex items-center justify-between gap-2">
              <span className="text-muted-foreground">Status</span>
              <StatusBadge status={run.status} />
            </div>
            <div className="detail-info-item flex items-center justify-between gap-2">
              <span className="text-muted-foreground">Tokens Used</span>
              <strong className="font-semibold text-foreground">{tokenUsageText(run.totalTokensUsed)}</strong>
            </div>
            {run.errorMessage && (
              <div className="detail-info-item error-detail block space-y-1">
                <span className="text-muted-foreground">Error</span>
                <RunErrorNotice
                  message={run.errorMessage}
                  surface={run.errorSurface}
                  code={run.errorCode}
                />
              </div>
            )}
          </div>
        </div>

        {/* Trajectory */}
        <div className="detail-col detail-col-wide rounded-xl border border-border bg-muted/30 p-3">
          <h4 className="mb-2 flex items-center gap-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            Session Trajectory
            {live && (
              <span className="winbadge rounded border border-primary/30 bg-primary/10 px-1.5 py-0.5 text-[10px] font-semibold text-primary normal-case">
                live · 5s
              </span>
            )}
            <span className="flex-1" />
            <button
              type="button"
              className="tgl rounded border border-border bg-background px-1.5 py-0.5 text-[10px] text-muted-foreground normal-case hover:text-foreground"
              onClick={() => setTrajCollapsed((v) => !v)}
            >
              {trajCollapsed ? '▸ expand' : '▾ collapse'}
            </button>
          </h4>
          {!trajCollapsed && (<>
          {trajLoading && <div className="mini-spinner text-xs text-muted-foreground">Loading...</div>}
          {trajError && (
            <ErrorNotice presented={presentError(trajError, 'Failed to load the session trajectory.')} />
          )}
          {!trajLoading && !trajError && (!trajectory || trajectory.length === 0) && <div className="empty-mini text-xs text-muted-foreground">No trajectory data yet.</div>}
          {trajectory && trajectory.length > 0 && (
            <div className="trajectory-list space-y-2">
              {trajectory.map((turn) => (
                <div key={turn.id} className={`trajectory-turn turn-${turn.role} rounded-lg border border-border bg-card p-2.5 text-sm`}>
                  <div className="turn-header flex items-center gap-2 text-[10.5px] text-muted-foreground">
                    <span className="turn-role font-semibold tracking-wide uppercase">{turn.role}</span>
                    <span className="turn-number tabular-nums">#{turn.turnNumber}</span>
                    <span className="turn-tokens ml-auto tabular-nums">{turn.inputTokens + turn.outputTokens} tokens</span>
                  </div>
                  <div className="turn-content mt-1 text-foreground">{turn.content.slice(0, 200)}{turn.content.length > 200 ? '...' : ''}</div>
                  {turn.toolCalls && <div className="turn-tools cell-mono mt-1 font-mono text-[11px] text-muted-foreground">{turn.toolCalls.slice(0, 150)}...</div>}
                </div>
              ))}
            </div>
          )}
          </>)}
        </div>

        {/* Tool Calls */}
        <div className="detail-col rounded-xl border border-border bg-muted/30 p-3">
          <h4 className="mb-2 flex items-center gap-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            Tool Calls
            <span className="flex-1" />
            <button
              type="button"
              className="tgl rounded border border-border bg-background px-1.5 py-0.5 text-[10px] text-muted-foreground normal-case hover:text-foreground"
              onClick={() => setToolsCollapsed((v) => !v)}
            >
              {toolsCollapsed ? '▸ expand' : '▾ collapse'}
            </button>
          </h4>
          {!toolsCollapsed && (<>
          {toolsLoading && <div className="mini-spinner text-xs text-muted-foreground">Loading...</div>}
          {toolsError && (
            <ErrorNotice presented={presentError(toolsError, 'Failed to load the tool calls.')} />
          )}
          {!toolsLoading && !toolsError && (!toolCalls || toolCalls.length === 0) && <div className="empty-mini text-xs text-muted-foreground">No tool calls yet.</div>}
          {toolCalls && toolCalls.length > 0 && (
            <div className="toolcall-list space-y-1.5">
              {toolCalls.map((tc) => (
                <div key={tc.id} className="toolcall-item rounded-lg border border-border bg-card px-2.5 py-1.5 text-xs">
                  <div className="toolcall-header flex items-center justify-between gap-2">
                    <span className="toolcall-name font-mono text-foreground">{tc.toolName}</span>
                    <StatusBadge status={tc.status} size="sm" />
                  </div>
                  <div className="toolcall-meta mt-0.5 text-[10.5px] text-muted-foreground">{tc.latencyMs}ms</div>
                </div>
              ))}
            </div>
          )}
          </>)}
        </div>
      </div>
    </div>
  );
}