import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listKnowledge, retireKnowledge, approveKnowledge, createKnowledge, getKnowledgeYaml } from '../api/knowledge';
import { instantiateWorkflowTemplate } from '../api/knowledge';
import { extractTemplateParams } from '../utils/workflowTemplateParams';
import TemplateEditorModal from './TemplateEditorModal';
import RunTemplateModal from './RunTemplateModal';
import type { KnowledgeItem } from '../types';

interface Props {
  onInstantiate?: () => void;
}

export default function TemplatesPanel({ onInstantiate }: Props) {
  const queryClient = useQueryClient();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingItem, setEditingItem] = useState<KnowledgeItem | null>(null);
  const [runTarget, setRunTarget] = useState<KnowledgeItem | null>(null);
  const [actionError, setActionError] = useState('');

  // Same surface convention as KanbanBoard: extract the server-provided message
  // when the API client produced an axios error, else fall back to err.message.
  const describeActionError = (e: unknown): string => {
    const anyE = e as { response?: { data?: { message?: string } }; message?: string };
    return anyE?.response?.data?.message || anyE?.message || 'Request failed';
  };

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
    },
    onError: (e) => setActionError(`Retire failed: ${describeActionError(e)}`),
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
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
        <span style={{ fontSize: 13, color: 'var(--muted, #94a3b8)' }}>
          {visible.length} template{visible.length !== 1 ? 's' : ''}
        </span>
        <button className="btn primary" style={{ marginLeft: 'auto' }} onClick={handleCreate}>
          + New Template
        </button>
      </div>

      {isLoading && <div style={{ color: 'var(--muted, #94a3b8)' }}>Loading templates...</div>}
      {error && <div style={{ color: 'var(--err, #ef4444)' }}>Failed to load templates</div>}
      {actionError && (
        <div role="alert" style={{ color: 'var(--err, #ef4444)', fontSize: 12, margin: '8px 0' }}>
          {actionError}
        </div>
      )}

      {visible.length === 0 && !isLoading && (
        <div style={{
          textAlign: 'center', padding: 40, color: 'var(--muted, #94a3b8)',
          border: '1px dashed var(--border, #334155)', borderRadius: 12,
        }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>📋</div>
          <div>No workflow templates yet.</div>
          <div style={{ fontSize: 13, marginTop: 4 }}>
            Create one to define a reusable multi-agent workflow.
          </div>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {visible.map((t) => (
          <div key={t.id} style={{
            border: '1px solid var(--border, #334155)', borderRadius: 10,
            padding: '14px 18px', background: 'var(--surface2, #1e293b)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
              <span style={{ fontWeight: 600, fontSize: 14, flex: 1 }}>{t.name}</span>
              <span className={`badge ${t.status === 'APPROVED' ? 'badge-success' : 'badge-warning'}`}
                style={{ fontSize: 10, padding: '2px 8px', borderRadius: 8 }}>
                {t.status}
              </span>
              <span style={{ fontSize: 11, color: 'var(--muted, #94a3b8)' }}>{t.currentVersion}</span>
            </div>
            {t.description && (
              <div style={{ fontSize: 12, color: 'var(--muted, #94a3b8)', marginBottom: 10 }}>{t.description}</div>
            )}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button
                className="btn primary"
                disabled={t.status !== 'APPROVED'}
                title={t.status !== 'APPROVED' ? 'Approve first' : 'Run this template'}
                onClick={() => setRunTarget(t)}
              >
                Run
              </button>
              {t.status === 'PENDING' && (
                <button className="btn" onClick={() => approveMutation.mutate(t.id)} disabled={approveMutation.isPending}>
                  Approve
                </button>
              )}
              <button className="btn" onClick={() => handleEdit(t)}>Edit</button>
              <button className="btn" onClick={() => duplicateMutation.mutate(t)} disabled={duplicateMutation.isPending}>
                Duplicate
              </button>
              <button
                className="btn danger"
                onClick={() => { if (confirm('Retire this template? It will be hidden from the list (soft delete).')) retireMutation.mutate(t.id); }}
                disabled={retireMutation.isPending}
              >
                Retire
              </button>
            </div>
          </div>
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
    </div>
  );
}
