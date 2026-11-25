import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listWorkflows, cancelWorkflow, retryWorkflow, deleteWorkflow, mergeWorkflows, executeYaml, resubmitApproval } from '../api/workflows';
import { listRuns } from '../api/runs';
import { listAgents } from '../api/agents';
import { apiErrorMessage } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import ErrorNotice from '../components/ErrorNotice';
import { formatTimestamp } from '../utils/formatTime';
import { useWebSocketContext } from '../components/Layout';
import TemplatesPanel from '../components/TemplatesPanel';
import { ConfirmDialog } from '../components/ConfirmDialog';
import PageHeader from '../components/layout/PageHeader';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import type { WorkflowChain, WorkflowStepInfo, WorkflowStatus, WorkflowStepStatus } from '../types';

// Tone classes rather than fixed colours: the status chip and the completed-step
// bar read the same tokens in both themes. The chip renders the tone as a dot so
// the token colour never competes with the Badge variant's own text/border.
const statusDot = (s: WorkflowStatus): string => {
  switch (s) {
    case 'COMPLETED': return 'bg-success';
    case 'RUNNING': return 'bg-warning';
    case 'WAITING_APPROVAL': return 'bg-warning';
    case 'FAILED': return 'bg-destructive';
    default: return 'bg-muted-foreground';
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

// Run statuses that mean the step's run will never advance again. A step still
// labelled RUNNING whose run reached one of these is stale — display the run's
// terminal status instead.
const TERMINAL_RUN_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'ABORTED']);

function displayedStepStatus(step: WorkflowStepInfo, runStatusById: Map<string, string>): WorkflowStepStatus {
  if (step.status === 'RUNNING' && step.runId) {
    const runStatus = runStatusById.get(step.runId);
    if (runStatus && TERMINAL_RUN_STATUSES.has(runStatus)) return runStatus as WorkflowStepStatus;
  }
  return step.status;
}

function StepCard({ step, agentName }: { step: WorkflowStepInfo; agentName: string }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div
      className={`rounded-lg border px-3.5 py-2.5 ${
        step.status === 'RUNNING'
          ? 'border-warning/50 bg-warning/5'
          : 'border-border bg-muted/30'
      } ${step.outputPreview ? 'cursor-pointer' : 'cursor-default'}`}
      onClick={() => step.outputPreview && setExpanded(!expanded)}
    >
      <div className="mb-1 flex items-center gap-2">
        <span className="text-base leading-none">{stepStatusIcon(step.status)}</span>
        <span className="flex-1 text-sm font-semibold text-foreground">Step {step.index + 1}: {agentName}</span>
        <span className="text-[11px] text-muted-foreground">{step.status}</span>
      </div>
      <div className="truncate text-xs text-muted-foreground">
        {step.promptTemplate}
      </div>
      {expanded && step.outputPreview && (
        <div className="mt-2 max-h-[300px] overflow-auto rounded-md bg-background p-2.5 font-mono text-xs leading-relaxed whitespace-pre-wrap text-foreground">
          {step.outputPreview}
        </div>
      )}
    </div>
  );
}

function WorkflowCard({ wf, agentMap, runStatusById, isSelected, onToggleSelect, onCancel, onRetry, onResubmit, onDelete }: {
  wf: WorkflowChain;
  agentMap: Map<string, string>;
  runStatusById: Map<string, string>;
  isSelected: boolean;
  onToggleSelect: (id: string) => void;
  onCancel: (id: string) => void;
  onRetry: (id: string, stepIndex: number) => void;
  onResubmit: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const completedSteps = wf.steps.filter(s => displayedStepStatus(s, runStatusById) === 'COMPLETED').length;
  const progress = wf.totalSteps > 0 ? (completedSteps / wf.totalSteps) * 100 : 0;

  return (
    <Card
      className={`mb-4 gap-3 ${
        isSelected ? 'ring-2 ring-primary' : ''
      }`}
    >
      {/* Header: the name stays a bare <span> (E2E selects the card by its exact text). */}
      <div className="flex flex-wrap items-center gap-2.5 px-(--card-spacing)">
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onToggleSelect(wf.id)}
          className="size-4 accent-primary"
          title="Select for merge"
        />
        <Badge
          variant="outline"
          className="gap-1.5 px-2 py-0.5 text-[11px] font-bold tracking-wide uppercase"
        >
          <span className={`size-1.5 rounded-full ${statusDot(wf.status)}`} aria-hidden="true" />
          {wf.status}
        </Badge>
        <span className="min-w-0 flex-1 truncate text-base font-bold text-card-foreground">{wf.name}</span>
        <span className="text-xs text-muted-foreground">
          {completedSteps}/{wf.totalSteps} steps
        </span>
      </div>

      {/* Progress bar */}
      <div className="h-1 overflow-hidden rounded-full bg-muted">
        <div
          className={`h-full rounded-full transition-[width] duration-500 ${
            wf.status === 'FAILED' ? 'bg-destructive' : 'bg-success'
          }`}
          style={{ width: `${progress}%` }}
        />
      </div>

      {/* Steps */}
      <div className="flex flex-col gap-2">
        {wf.steps.map(step => (
          <StepCard
            key={step.index}
            step={{ ...step, status: displayedStepStatus(step, runStatusById) }}
            agentName={agentMap.get(step.agentId) || step.agentId.slice(0, 8)}
          />
        ))}
      </div>

      {/* Footer */}
      <div className="flex flex-wrap gap-4 text-[11px] text-muted-foreground">
        <span>Created: {formatTimestamp(wf.createdAt)}</span>
        {wf.completedAt && <span>Completed: {formatTimestamp(wf.completedAt)}</span>}
        {wf.completedAt && (
          <span>
            Duration: {Math.round((new Date(wf.completedAt).getTime() - new Date(wf.createdAt).getTime()) / 1000)}s
          </span>
        )}
      </div>

      {/* Action buttons */}
      <div className="flex flex-wrap gap-2">
        {['RUNNING', 'PENDING', 'WAITING_APPROVAL'].includes(wf.status) && (
          <Button variant="destructive" size="sm" onClick={() => onCancel(wf.id)}>
            Cancel
          </Button>
        )}
        {wf.status === 'WAITING_APPROVAL' && (
          <Button variant="outline" size="sm" className="text-warning" onClick={() => onResubmit(wf.id)}>
            Resubmit approval
          </Button>
        )}
        {wf.status === 'FAILED' && (
          <Button size="sm" onClick={() => onRetry(wf.id, wf.currentStepIndex)}>
            Retry Step {wf.currentStepIndex}
          </Button>
        )}
        {wf.status !== 'RUNNING' && (
          <Button variant="outline" size="sm" onClick={() => onDelete(wf.id)}>
            Delete
          </Button>
        )}
      </div>
    </Card>
  );
}

export function WorkflowsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();
  const [activeTab, setActiveTab] = useState<'chains' | 'templates'>('chains');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  // M2: replace native window.prompt() with accessible modal dialogs.
  const [mergeModalOpen, setMergeModalOpen] = useState(false);
  const [yamlModalOpen, setYamlModalOpen] = useState(false);
  const [mergeName, setMergeName] = useState('');
  const [yamlContent, setYamlContent] = useState('');
  // Workflow awaiting delete confirmation. Deletion is irreversible, so the
  // card's Delete control only opens the shared dialog (Task 10).
  const [deletingId, setDeletingId] = useState<string | null>(null);
  // Delete failure notice — the page has no toast, so a rejected delete reuses
  // the same inline error banner the load error above renders (no second toast).
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const { data: workflows, isLoading, error } = useQuery({
    queryKey: ['workflows'],
    queryFn: listWorkflows,
    refetchInterval: activeTab === 'chains' ? 5000 : false,
    enabled: activeTab === 'chains',
  });

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  // Run statuses by id — used to derive the display status of steps whose
  // own status went stale (still RUNNING after the run finished).
  const { data: runs } = useQuery({
    queryKey: ['runs'],
    queryFn: listRuns,
    enabled: activeTab === 'chains',
  });

  // Mutations
  const cancelMutation = useMutation({
    mutationFn: (id: string) => cancelWorkflow(id),
    onSuccess: () => {
      setDeleteError(null);
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
    },
    onError: (err: unknown) => setDeleteError(apiErrorMessage(err, 'Cancel failed.')),
  });

  const retryMutation = useMutation({
    mutationFn: ({ id, stepIndex }: { id: string; stepIndex: number }) => retryWorkflow(id, stepIndex),
    onSuccess: () => {
      setDeleteError(null);
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
    },
    onError: (err: unknown) => setDeleteError(apiErrorMessage(err, 'Retry step failed.')),
  });

  const resubmitMutation = useMutation({
    mutationFn: (id: string) => resubmitApproval(id),
    onSuccess: () => {
      setDeleteError(null);
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
    },
    onError: (err: unknown) => setDeleteError(apiErrorMessage(err, 'Resubmit failed.')),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteWorkflow(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      // Task 10: the confirmed delete is done — drop the pending confirmation
      // and any failure notice left by an earlier attempt.
      setDeletingId(null);
      setDeleteError(null);
    },
    // The confirmed action is over (it failed) — never leave the confirmation
    // stuck open, and never let a rejected delete pass silently: the failure is
    // rendered in this page's existing inline error banner.
    onError: (err: unknown) => {
      setDeletingId(null);
      setDeleteError(apiErrorMessage(err, (err as Error)?.message ?? 'Operation failed.'));
    },
  });

  const mergeMutation = useMutation({
    mutationFn: ({ sourceIds, name }: { sourceIds: string[]; name: string }) => mergeWorkflows(sourceIds, name),
    onSuccess: () => {
      setDeleteError(null);
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      setSelectedIds([]);
    },
    onError: (err: unknown) => setDeleteError(apiErrorMessage(err, 'Merge failed.')),
  });

  const executeYamlMutation = useMutation({
    mutationFn: (yamlContent: string) => executeYaml(yamlContent),
    onSuccess: () => {
      setDeleteError(null);
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
    },
    onError: (err: unknown) => setDeleteError(apiErrorMessage(err, 'YAML execution failed.')),
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

  // Build run ID → status map for stale-step derivation
  const runStatusById = new Map<string, string>();
  runs?.forEach(r => runStatusById.set(r.id, r.status));

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
    <div className="mx-auto w-full max-w-5xl space-y-3 p-6">
      <PageHeader
        title="Workflows"
        actions={activeTab === 'chains' ? (
          <>
            {runningCount > 0 && (
              <Badge variant="outline" className="gap-1.5 tabular-nums">
                <span className="size-1.5 rounded-full bg-warning" aria-hidden="true" />
                {runningCount} running
              </Badge>
            )}
            <Badge variant="outline" className="gap-1.5 tabular-nums">
              <span className="size-1.5 rounded-full bg-success" aria-hidden="true" />
              {completedCount} completed
            </Badge>
            {selectedIds.length >= 2 && (
              <Button size="sm" onClick={() => { setMergeName(''); setMergeModalOpen(true); }}>
                Merge {selectedIds.length} Workflows
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={() => { setYamlContent(''); setYamlModalOpen(true); }}>
              Execute YAML
            </Button>
          </>
        ) : undefined}
      />

      {/* Tab bar */}
      <div className="inline-flex items-center gap-1 rounded-lg bg-muted p-1" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'chains'}
          onClick={() => setActiveTab('chains')}
          className="rounded-md px-3 py-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground aria-selected:bg-background aria-selected:text-foreground aria-selected:shadow-sm"
        >
          Chains
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'templates'}
          onClick={() => setActiveTab('templates')}
          className="rounded-md px-3 py-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground aria-selected:bg-background aria-selected:text-foreground aria-selected:shadow-sm"
        >
          Templates
        </button>
      </div>

      {activeTab === 'templates' && <TemplatesPanel onInstantiate={() => setActiveTab('chains')} />}

      {activeTab === 'chains' && (<>
      {isLoading && <p className="text-sm text-muted-foreground">Loading workflows...</p>}
      {error && (
        <div className="mb-3">
          <ErrorNotice presented={presentError(error, 'Failed to load workflows.')} />
        </div>
      )}
      {deleteError && (
        <div role="alert" className="text-sm text-destructive">{deleteError}</div>
      )}

      {!error && sorted.length === 0 && !isLoading && (
        <div className="rounded-xl border border-dashed border-border px-8 py-10 text-center">
          <div className="mb-2 text-3xl">🔗</div>
          <div className="text-sm text-foreground">No workflow chains yet.</div>
          <div className="mt-1 text-xs text-muted-foreground">
            Create one via Aria: <code className="rounded bg-muted px-1 py-0.5 font-mono">start_workflow</code> or the REST API.
          </div>
        </div>
      )}

      <div>
        {sorted.map(wf => (
          <WorkflowCard
            key={wf.id}
            wf={wf}
            agentMap={agentMap}
            runStatusById={runStatusById}
            isSelected={selectedIds.includes(wf.id)}
            onToggleSelect={toggleSelect}
            onCancel={(id) => cancelMutation.mutate(id)}
            onRetry={(id, stepIndex) => retryMutation.mutate({ id, stepIndex })}
            onResubmit={(id) => resubmitMutation.mutate(id)}
            onDelete={(id) => setDeletingId(id)}
          />
        ))}
      </div>

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
            <header className="mb-3 flex items-center justify-between gap-2">
              <h2 id="merge-modal-title" className="text-base font-semibold"><b>Merge</b> {selectedIds.length} Workflows</h2>
              <span className="x cursor-pointer text-muted-foreground hover:text-foreground" role="button" tabIndex={0} aria-label="Close merge dialog" onClick={() => setMergeModalOpen(false)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setMergeModalOpen(false); }}>✕</span>
            </header>
            <div className="flex flex-col gap-3">
              <label className="flex flex-col gap-1.5 text-sm text-muted-foreground">
                Name for merged workflow
                <Input
                  className="gate-row-input"
                  value={mergeName}
                  onChange={(e) => setMergeName(e.target.value)}
                  placeholder="e.g. Combined onboarding flow"
                  autoFocus
                />
              </label>
              <div className="flex justify-end gap-2">
                <Button variant="outline" size="sm" onClick={() => setMergeModalOpen(false)}>Cancel</Button>
                <Button
                  size="sm"
                  disabled={!mergeName.trim()}
                  onClick={() => { mergeMutation.mutate({ sourceIds: selectedIds, name: mergeName.trim() }); setMergeModalOpen(false); }}
                >
                  Confirm
                </Button>
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
            <header className="mb-3 flex items-center justify-between gap-2">
              <h2 id="yaml-modal-title" className="text-base font-semibold"><b>Execute</b> YAML Workflow</h2>
              <span className="x cursor-pointer text-muted-foreground hover:text-foreground" role="button" tabIndex={0} aria-label="Close YAML dialog" onClick={() => setYamlModalOpen(false)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setYamlModalOpen(false); }}>✕</span>
            </header>
            <div className="flex flex-col gap-3">
              <label className="flex flex-col gap-1.5 text-sm text-muted-foreground">
                YAML workflow template
                <Textarea
                  value={yamlContent}
                  onChange={(e) => setYamlContent(e.target.value)}
                  placeholder={'name: my-workflow\nsteps:\n  - agent: dev\n    promptTemplate: implement the feature'}
                  rows={10}
                  autoFocus
                  className="font-mono text-xs"
                />
              </label>
              <div className="flex justify-end gap-2">
                <Button variant="outline" size="sm" onClick={() => setYamlModalOpen(false)}>Cancel</Button>
                <Button
                  size="sm"
                  disabled={!yamlContent.trim()}
                  onClick={() => { executeYamlMutation.mutate(yamlContent); setYamlModalOpen(false); }}
                >
                  Execute
                </Button>
              </div>
            </div>
          </div>
        </>
      )}
      </>)}

      {/* Shared destructive-action confirmation (Task 10): the card's Delete
          control only opens this dialog, and the delete fires from onConfirm. */}
      <ConfirmDialog
        open={deletingId !== null}
        title="Delete this workflow?"
        message={
          <>
            Delete <strong>{workflows?.find(w => w.id === deletingId)?.name}</strong>?
            Its chain history is removed and cannot be restored from here.
          </>
        }
        danger
        onConfirm={() => {
          if (!deletingId) return;
          const id = deletingId;
          // Closed immediately: onSuccess/onError re-clear (idempotent) so a
          // rejection can never strand the dialog open. The previous failure
          // notice is dropped so a retry never shows a stale error.
          setDeletingId(null);
          setDeleteError(null);
          deleteMutation.mutate(id);
        }}
        onCancel={() => setDeletingId(null)}
      />
    </div>
  );
}

export default WorkflowsPage;
