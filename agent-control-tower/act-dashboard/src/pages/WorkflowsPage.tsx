import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listWorkflows, cancelWorkflow, retryWorkflow, deleteWorkflow, mergeWorkflows, executeYaml, resubmitApproval } from '../api/workflows';
import { listAgents } from '../api/agents';
import { useWebSocketContext } from '../components/Layout';
import type { WorkflowChain, WorkflowStepInfo, WorkflowStatus, WorkflowStepStatus } from '../types';

const statusColor = (s: WorkflowStatus): string => {
  switch (s) {
    case 'COMPLETED': return 'var(--ok, #22c55e)';
    case 'RUNNING': return 'var(--warn, #f59e0b)';
    case 'WAITING_APPROVAL': return 'var(--warn, #f59e0b)';
    case 'FAILED': return 'var(--err, #ef4444)';
    case 'CANCELLED': return 'var(--muted, #94a3b8)';
    default: return 'var(--muted, #94a3b8)';
  }
};

const stepStatusIcon = (s: WorkflowStepStatus): string => {
  switch (s) {
    case 'COMPLETED': return '✅';
    case 'RUNNING': return '⏳';
    case 'FAILED': return '❌';
    case 'SKIPPED': return '⏭️';
    default: return '⬜';
  }
};

function StepCard({ step, agentName }: { step: WorkflowStepInfo; agentName: string }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div
      style={{
        border: `1px solid ${step.status === 'RUNNING' ? 'var(--warn, #f59e0b)' : 'var(--border, #334155)'}`,
        borderRadius: 8,
        padding: '10px 14px',
        background: step.status === 'RUNNING' ? 'rgba(245,158,11,0.06)' : 'var(--surface2, #1e293b)',
        cursor: step.outputPreview ? 'pointer' : 'default',
      }}
      onClick={() => step.outputPreview && setExpanded(!expanded)}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
        <span style={{ fontSize: 16 }}>{stepStatusIcon(step.status)}</span>
        <span style={{ fontWeight: 600, fontSize: 13, flex: 1 }}>Step {step.index + 1}: {agentName}</span>
        <span style={{ fontSize: 11, color: 'var(--muted, #94a3b8)' }}>{step.status}</span>
      </div>
      <div style={{ fontSize: 12, color: 'var(--muted, #94a3b8)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {step.promptTemplate}
      </div>
      {expanded && step.outputPreview && (
        <div style={{
          marginTop: 8,
          padding: '8px 10px',
          background: 'var(--surface, #0f172a)',
          borderRadius: 6,
          fontSize: 12,
          whiteSpace: 'pre-wrap',
          maxHeight: 300,
          overflow: 'auto',
          lineHeight: 1.5,
        }}>
          {step.outputPreview}
        </div>
      )}
    </div>
  );
}

function WorkflowCard({ wf, agentMap, isSelected, onToggleSelect, onCancel, onRetry, onResubmit, onDelete }: {
  wf: WorkflowChain;
  agentMap: Map<string, string>;
  isSelected: boolean;
  onToggleSelect: (id: string) => void;
  onCancel: (id: string) => void;
  onRetry: (id: string, stepIndex: number) => void;
  onResubmit: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const completedSteps = wf.steps.filter(s => s.status === 'COMPLETED').length;
  const progress = wf.totalSteps > 0 ? (completedSteps / wf.totalSteps) * 100 : 0;

  return (
    <div style={{
      border: isSelected ? '2px solid var(--brand, #5b8cff)' : '1px solid var(--border, #334155)',
      borderRadius: 12,
      padding: '16px 20px',
      background: 'var(--surface, #0f172a)',
      marginBottom: 16,
    }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onToggleSelect(wf.id)}
          style={{ accentColor: 'var(--brand, #5b8cff)' }}
          title="Select for merge"
        />
        <span style={{
          fontSize: 11,
          fontWeight: 700,
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
          padding: '3px 8px',
          borderRadius: 4,
          color: '#fff',
          background: statusColor(wf.status),
        }}>
          {wf.status}
        </span>
        <span style={{ fontWeight: 700, fontSize: 16, flex: 1 }}>{wf.name}</span>
        <span style={{ fontSize: 12, color: 'var(--muted, #94a3b8)' }}>
          {completedSteps}/{wf.totalSteps} steps
        </span>
      </div>

      {/* Progress bar */}
      <div style={{
        height: 4,
        background: 'var(--surface2, #1e293b)',
        borderRadius: 2,
        marginBottom: 14,
        overflow: 'hidden',
      }}>
        <div style={{
          height: '100%',
          width: `${progress}%`,
          background: wf.status === 'FAILED' ? 'var(--err, #ef4444)' : 'var(--ok, #22c55e)',
          borderRadius: 2,
          transition: 'width 0.5s ease',
        }} />
      </div>

      {/* Steps */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {wf.steps.map(step => (
          <StepCard key={step.index} step={step} agentName={agentMap.get(step.agentId) || step.agentId.slice(0, 8)} />
        ))}
      </div>

      {/* Footer */}
      <div style={{ marginTop: 10, fontSize: 11, color: 'var(--muted, #94a3b8)', display: 'flex', gap: 16 }}>
        <span>Created: {new Date(wf.createdAt).toLocaleTimeString()}</span>
        {wf.completedAt && <span>Completed: {new Date(wf.completedAt).toLocaleTimeString()}</span>}
        {wf.completedAt && (
          <span>
            Duration: {Math.round((new Date(wf.completedAt).getTime() - new Date(wf.createdAt).getTime()) / 1000)}s
          </span>
        )}
      </div>

      {/* Action buttons */}
      <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {['RUNNING', 'PENDING', 'WAITING_APPROVAL'].includes(wf.status) && (
          <button className="btn danger" onClick={() => onCancel(wf.id)}>
            Cancel
          </button>
        )}
        {wf.status === 'WAITING_APPROVAL' && (
          <button className="btn btn-warning" onClick={() => onResubmit(wf.id)}>
            Resubmit approval
          </button>
        )}
        {wf.status === 'FAILED' && (
          <button className="btn primary" onClick={() => onRetry(wf.id, wf.currentStepIndex)}>
            Retry Step {wf.currentStepIndex}
          </button>
        )}
        {wf.status !== 'RUNNING' && (
          <button className="btn" onClick={() => { if (confirm('Delete this workflow?')) onDelete(wf.id); }}>
            Delete
          </button>
        )}
      </div>
    </div>
  );
}

export function WorkflowsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  // M2: replace native window.prompt() with accessible modal dialogs.
  const [mergeModalOpen, setMergeModalOpen] = useState(false);
  const [yamlModalOpen, setYamlModalOpen] = useState(false);
  const [mergeName, setMergeName] = useState('');
  const [yamlContent, setYamlContent] = useState('');

  const { data: workflows, isLoading, error } = useQuery({
    queryKey: ['workflows'],
    queryFn: listWorkflows,
    refetchInterval: 5000,
  });

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  // Mutations
  const cancelMutation = useMutation({
    mutationFn: (id: string) => cancelWorkflow(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });

  const retryMutation = useMutation({
    mutationFn: ({ id, stepIndex }: { id: string; stepIndex: number }) => retryWorkflow(id, stepIndex),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });

  const resubmitMutation = useMutation({
    mutationFn: (id: string) => resubmitApproval(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteWorkflow(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });

  const mergeMutation = useMutation({
    mutationFn: ({ sourceIds, name }: { sourceIds: string[]; name: string }) => mergeWorkflows(sourceIds, name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      setSelectedIds([]);
    },
  });

  const executeYamlMutation = useMutation({
    mutationFn: (yamlContent: string) => executeYaml(yamlContent),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });

  // Real-time invalidation on workflow.* or run.* WS events
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (t.startsWith('workflow.') || t.startsWith('run.')) {
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
    }
  }, [lastMessage, queryClient]);

  // Build agent ID → name map
  const agentMap = new Map<string, string>();
  agents?.forEach(a => agentMap.set(a.id, a.name));

  // Sort: running first, then by createdAt desc
  const sorted = [...(workflows || [])].sort((a, b) => {
    if (a.status === 'RUNNING' && b.status !== 'RUNNING') return -1;
    if (b.status === 'RUNNING' && a.status !== 'RUNNING') return 1;
    return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
  });

  const runningCount = workflows?.filter(w => w.status === 'RUNNING').length || 0;
  const completedCount = workflows?.filter(w => w.status === 'COMPLETED').length || 0;

  const toggleSelect = (id: string) => {
    setSelectedIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  // M2: close the merge/YAML modals on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMergeModalOpen(false);
        setYamlModalOpen(false);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div style={{ padding: '24px 28px', maxWidth: 960, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0 }}>Workflows</h1>
        {runningCount > 0 && (
          <span style={{
            fontSize: 11,
            fontWeight: 600,
            padding: '2px 8px',
            borderRadius: 10,
            background: 'var(--warn, #f59e0b)',
            color: '#000',
          }}>
            {runningCount} running
          </span>
        )}
        <span style={{
          fontSize: 11,
          fontWeight: 600,
          padding: '2px 8px',
          borderRadius: 10,
          background: 'var(--ok, #22c55e)',
          color: '#000',
        }}>
          {completedCount} completed
        </span>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {selectedIds.length >= 2 && (
            <button className="btn primary" onClick={() => { setMergeName(''); setMergeModalOpen(true); }}>
              Merge {selectedIds.length} Workflows
            </button>
          )}
          <button className="btn" onClick={() => { setYamlContent(''); setYamlModalOpen(true); }}>
            Execute YAML
          </button>
        </div>
      </div>

      {isLoading && <div style={{ color: 'var(--muted, #94a3b8)' }}>Loading workflows...</div>}
      {error && <div style={{ color: 'var(--err, #ef4444)' }}>Failed to load workflows</div>}

      {sorted.length === 0 && !isLoading && (
        <div style={{
          textAlign: 'center',
          padding: 40,
          color: 'var(--muted, #94a3b8)',
          border: '1px dashed var(--border, #334155)',
          borderRadius: 12,
        }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>🔗</div>
          <div>No workflow chains yet.</div>
          <div style={{ fontSize: 13, marginTop: 4 }}>
            Create one via Aria: <code>start_workflow</code> or the REST API.
          </div>
        </div>
      )}

      {sorted.map(wf => (
        <WorkflowCard
          key={wf.id}
          wf={wf}
          agentMap={agentMap}
          isSelected={selectedIds.includes(wf.id)}
          onToggleSelect={toggleSelect}
          onCancel={(id) => cancelMutation.mutate(id)}
          onRetry={(id, stepIndex) => retryMutation.mutate({ id, stepIndex })}
          onResubmit={(id) => resubmitMutation.mutate(id)}
          onDelete={(id) => deleteMutation.mutate(id)}
        />
      ))}

      {/* M2: Merge workflow modal (replaces window.prompt). Conditionally rendered so a closed
          modal is unmounted — no autoFocus steal, no Tab into hidden controls — and uses
          height:auto instead of the .modal class's fixed 720px (code-review warnings 1 & 2). */}
      {mergeModalOpen && (
        <>
          <div className="modal-scrim open" onClick={() => setMergeModalOpen(false)} />
          <div
            className="modal open"
            role="dialog"
            aria-modal="true"
            aria-labelledby="merge-modal-title"
            style={{ width: 'min(480px, 94vw)', height: 'auto' }}
          >
            <header>
              <h2 id="merge-modal-title"><b>Merge</b> {selectedIds.length} Workflows</h2>
              <span className="x" role="button" tabIndex={0} aria-label="Close merge dialog" onClick={() => setMergeModalOpen(false)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setMergeModalOpen(false); }}>✕</span>
            </header>
            <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
              <label style={{ fontSize: 13, display: 'flex', flexDirection: 'column', gap: 6 }}>
                Name for merged workflow
                <input
                  className="gate-row-input"
                  value={mergeName}
                  onChange={(e) => setMergeName(e.target.value)}
                  placeholder="e.g. Combined onboarding flow"
                  autoFocus
                  style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid var(--line, #334155)', background: 'var(--surface, #0f172a)', color: 'var(--text, #e2e8f0)' }}
                />
              </label>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <button className="btn" onClick={() => setMergeModalOpen(false)}>Cancel</button>
                <button
                  className="btn primary"
                  disabled={!mergeName.trim()}
                  onClick={() => { mergeMutation.mutate({ sourceIds: selectedIds, name: mergeName.trim() }); setMergeModalOpen(false); }}
                >
                  Confirm
                </button>
              </div>
            </div>
          </div>
        </>
      )}

      {/* M2: Execute YAML modal (replaces window.prompt). Conditionally rendered (see merge modal note). */}
      {yamlModalOpen && (
        <>
          <div className="modal-scrim open" onClick={() => setYamlModalOpen(false)} />
          <div
            className="modal open"
            role="dialog"
            aria-modal="true"
            aria-labelledby="yaml-modal-title"
            style={{ width: 'min(560px, 94vw)', height: 'auto' }}
          >
            <header>
              <h2 id="yaml-modal-title"><b>Execute</b> YAML Workflow</h2>
              <span className="x" role="button" tabIndex={0} aria-label="Close YAML dialog" onClick={() => setYamlModalOpen(false)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setYamlModalOpen(false); }}>✕</span>
            </header>
            <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
              <label style={{ fontSize: 13, display: 'flex', flexDirection: 'column', gap: 6 }}>
                YAML workflow template
                <textarea
                  value={yamlContent}
                  onChange={(e) => setYamlContent(e.target.value)}
                  placeholder={'name: my-workflow\nsteps:\n  - agent: dev\n    promptTemplate: implement the feature'}
                  rows={10}
                  autoFocus
                  style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid var(--line, #334155)', background: 'var(--surface, #0f172a)', color: 'var(--text, #e2e8f0)', fontFamily: 'monospace', resize: 'vertical' }}
                />
              </label>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <button className="btn" onClick={() => setYamlModalOpen(false)}>Cancel</button>
                <button
                  className="btn primary"
                  disabled={!yamlContent.trim()}
                  onClick={() => { executeYamlMutation.mutate(yamlContent); setYamlModalOpen(false); }}
                >
                  Execute
                </button>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default WorkflowsPage;
