import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listKnowledge, retireKnowledge, approveKnowledge, createKnowledge, getKnowledgeYaml } from '../api/knowledge';
import { instantiateWorkflowTemplate } from '../api/knowledge';
import { extractTemplateParams } from '../utils/workflowTemplateParams';
import { apiErrorMessage } from '../api/operatorSession';
import TemplateEditorModal from './TemplateEditorModal';
import RunTemplateModal from './RunTemplateModal';
import { ConfirmDialog } from './ConfirmDialog';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';
import type { KnowledgeItem } from '../types';

interface Props {
  onInstantiate?: () => void;
}

export default function TemplatesPanel({ onInstantiate }: Props) {
  const queryClient = useQueryClient();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<KnowledgeItem | null>(null);
  const [runTarget, setRunTarget] = useState<KnowledgeItem | null>(null);
  // Template awaiting retire confirmation. Retiring is irreversible from the
  // UI, so the control only opens the shared dialog (Task 10).
  const [confirmingRetire, setConfirmingRetire] = useState<KnowledgeItem | null>(null);
  const [actionError, setActionError] = useState('');

  // Same surface convention as KanbanBoard: extract the server-provided message
  // when the API client produced an axios error, else fall back to err.message.
  const describeActionError = (e: unknown): string =>
    apiErrorMessage(e, (e as { message?: string } | undefined)?.message || 'Request failed');

  const { data: templates, isLoading, error } = useQuery({
    queryKey: ['knowledge', 'WORKFLOW'],
    queryFn: () => listKnowledge('WORKFLOW'),
  });

  const visible = (templates ?? []).filter((t) => t.status !== 'RETIRED');

  const approveMutation = useMutation({
    mutationFn: (id: string) => approveKnowledge(id),
    onSuccess: () => {
      setActionError('');
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
    },
    onError: (e) => setActionError(`Approve failed: ${describeActionError(e)}`),
  });

  const retireMutation = useMutation({
    mutationFn: (id: string) => retireKnowledge(id),
    onSuccess: () => {
      setActionError('');
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
      // Task 10: the confirmed retire is done — drop the pending confirmation.
      setConfirmingRetire(null);
    },
    onError: (e) => {
      setActionError(`Retire failed: ${describeActionError(e)}`);
      // The confirmed action is over (it failed) — never leave it stuck open.
      setConfirmingRetire(null);
    },
  });

  const duplicateMutation = useMutation({
    mutationFn: async (item: KnowledgeItem) => {
      const yaml = await getKnowledgeYaml(item.id);
      return createKnowledge({
        name: item.name + '-copy',
        type: 'WORKFLOW' as any,
        description: item.description ?? '',
        content: yaml || item.description || '',
        yamlContent: yaml,
      } as any);
    },
    onSuccess: () => {
      setActionError('');
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
    },
    onError: (e) => setActionError(`Duplicate failed: ${describeActionError(e)}`),
  });

  const handleCreate = () => { setEditingItem(null); setEditorOpen(true); };
  const handleEdit = (item: KnowledgeItem) => { setEditingItem(item); setEditorOpen(true); };

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2.5">
        <span className="text-sm text-muted-foreground">
          {visible.length} template{visible.length !== 1 ? 's' : ''}
        </span>
        <Button size="sm" className="ml-auto" onClick={handleCreate}>
          + New Template
        </Button>
      </div>

      {isLoading && <p className="text-sm text-muted-foreground">Loading templates...</p>}
      {error && <p className="text-sm text-destructive">Failed to load templates</p>}
      {actionError && (
        <p role="alert" className="my-2 text-xs text-destructive">
          {actionError}
        </p>
      )}

      {visible.length === 0 && !isLoading && (
        <div className="rounded-xl border border-dashed border-border px-8 py-10 text-center">
          <div className="mb-2 text-3xl">📋</div>
          <div className="text-sm text-foreground">No workflow templates yet.</div>
          <div className="mt-1 text-xs text-muted-foreground">
            Create one to define a reusable multi-agent workflow.
          </div>
        </div>
      )}

      <div className="flex flex-col gap-2.5">
        {visible.map((t) => (
          <Card key={t.id} className="gap-0 py-(--card-spacing)">
            <div className="space-y-1.5 px-(--card-spacing)">
              <div className="flex items-center gap-2.5">
                <span className="min-w-0 flex-1 truncate text-sm font-semibold text-card-foreground">{t.name}</span>
                <Badge
                  variant="outline"
                  className="gap-1.5 px-2 py-0.5 text-[10px] tracking-wide uppercase"
                >
                  <span
                    className={`size-1.5 rounded-full ${t.status === 'APPROVED' ? 'bg-success' : 'bg-warning'}`}
                    aria-hidden="true"
                  />
                  {t.status}
                </Badge>
                <span className="text-[11px] text-muted-foreground">{t.currentVersion}</span>
              </div>
              {t.description && (
                <p className="text-xs text-muted-foreground">{t.description}</p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  disabled={t.status !== 'APPROVED'}
                  title={t.status !== 'APPROVED' ? 'Approve first' : 'Run this template'}
                  onClick={() => setRunTarget(t)}
                >
                  Run
                </Button>
                {t.status === 'PENDING' && (
                  <Button variant="outline" size="sm" onClick={() => approveMutation.mutate(t.id)} disabled={approveMutation.isPending}>
                    Approve
                  </Button>
                )}
                <Button variant="outline" size="sm" onClick={() => handleEdit(t)}>Edit</Button>
                <Button variant="outline" size="sm" onClick={() => duplicateMutation.mutate(t)} disabled={duplicateMutation.isPending}>
                  Duplicate
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => setConfirmingRetire(t)}
                  disabled={retireMutation.isPending}
                >
                  Retire
                </Button>
              </div>
            </div>
          </Card>
        ))}
      </div>

      {editorOpen && (
        <TemplateEditorModal
          item={editingItem}
          onClose={() => setEditorOpen(false)}
          onSaved={() => { setEditorOpen(false); queryClient.invalidateQueries({ queryKey: ['knowledge'] }); }}
        />
      )}

      {runTarget && (
        <RunTemplateModal
          item={runTarget}
          onClose={() => setRunTarget(null)}
          onSuccess={() => {
            setRunTarget(null);
            queryClient.invalidateQueries({ queryKey: ['workflows'] });
            onInstantiate?.();
          }}
        />
      )}

      {/* Shared destructive-action confirmation (Task 10): the Retire control
          only opens this dialog, and the retire fires from onConfirm. */}
      <ConfirmDialog
        open={confirmingRetire !== null}
        title="Retire template?"
        message={
          <>
            Retire <strong>{confirmingRetire?.name}</strong>? It will be hidden from the
            list (soft delete).
          </>
        }
        danger
        onConfirm={() => {
          if (!confirmingRetire) return;
          const id = confirmingRetire.id;
          // Closed immediately: onSuccess/onError re-clear (idempotent) so a
          // rejection can never strand the dialog open.
          setConfirmingRetire(null);
          retireMutation.mutate(id);
        }}
        onCancel={() => setConfirmingRetire(null)}
      />
    </div>
  );
}
