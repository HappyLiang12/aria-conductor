import { useState, useEffect } from 'react';
import { createKnowledge, getKnowledgeYaml, updateKnowledgeContent } from '../api/knowledge';
import { extractTemplateParams } from '../utils/workflowTemplateParams';
import type { KnowledgeItem } from '../types';

interface Props {
  item: KnowledgeItem | null; // null = create mode
  onClose: () => void;
  onSaved: () => void;
}

export default function TemplateEditorModal({ item, onClose, onSaved }: Props) {
  const isEdit = !!item;
  const [name, setName] = useState(item?.name ?? '');
  const [description, setDescription] = useState(item?.description ?? '');
  const [yaml, setYaml] = useState('');
  const [loading, setLoading] = useState(!isEdit ? false : true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (item) {
      getKnowledgeYaml(item.id)
        .then((y) => setYaml(y || ''))
        .catch(() => setYaml(''))
        .finally(() => setLoading(false));
    }
  }, [item]);

  const detectedParams = extractTemplateParams(yaml);

  const handleSave = async () => {
    if (!name.trim() || !yaml.trim()) { setError('Name and YAML are required'); return; }
    setSaving(true);
    setError('');
    try {
      if (isEdit) {
        await updateKnowledgeContent(item!.id, { description, content: yaml, yamlContent: yaml });
      } else {
        await createKnowledge({
          name: name.trim(),
          type: 'WORKFLOW' as any,
          description,
          content: yaml,
          yamlContent: yaml,
        } as any);
      }
      onSaved();
    } catch (e: any) {
      setError(e?.response?.data?.message || e?.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <div className="modal-scrim open" onClick={onClose} />
      <div className="modal open" role="dialog" aria-modal="true" aria-labelledby="tpl-editor-title"
        style={{ width: 'min(640px, 94vw)', height: 'auto', maxHeight: '85vh', overflow: 'auto' }}>
        <header>
          <h2 id="tpl-editor-title"><b>{isEdit ? 'Edit' : 'Create'}</b> Workflow Template</h2>
          <span className="x" role="button" tabIndex={0} aria-label="Close" onClick={onClose}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClose(); }}>✕</span>
        </header>
        <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {loading ? (
            <div style={{ color: 'var(--muted, #94a3b8)' }}>Loading YAML...</div>
          ) : (
            <>
              {!isEdit && (
                <label style={{ fontSize: 13, display: 'flex', flexDirection: 'column', gap: 4 }}>
                  Name
                  <input value={name} onChange={(e) => setName(e.target.value)} placeholder="my-workflow"
                    style={{ padding: '7px 10px', borderRadius: 8, border: '1px solid var(--line, #334155)', background: 'var(--surface, #0f172a)', color: 'var(--text, #e2e8f0)' }} />
                </label>
              )}
              <label style={{ fontSize: 13, display: 'flex', flexDirection: 'column', gap: 4 }}>
                Description
                <input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="What this workflow does"
                  style={{ padding: '7px 10px', borderRadius: 8, border: '1px solid var(--line, #334155)', background: 'var(--surface, #0f172a)', color: 'var(--text, #e2e8f0)' }} />
              </label>
              <label style={{ fontSize: 13, display: 'flex', flexDirection: 'column', gap: 4 }}>
                YAML Template
                <textarea value={yaml} onChange={(e) => setYaml(e.target.value)} rows={14}
                  placeholder={'steps:\n  - kind: ba\n    agent_role: ba\n    prompt_template: "Analyze issue {issueRef}..."\n    max_iterations: 15'}
                  style={{ padding: '8px 10px', borderRadius: 8, border: '1px solid var(--line, #334155)', background: 'var(--surface, #0f172a)', color: 'var(--text, #e2e8f0)', fontFamily: 'monospace', fontSize: 12, resize: 'vertical' }} />
              </label>
              {detectedParams.length > 0 && (
                <div style={{ fontSize: 12, color: 'var(--muted, #94a3b8)' }}>
                  Detected parameters: <code>{detectedParams.join(', ')}</code>
                </div>
              )}
              {error && <div style={{ fontSize: 12, color: 'var(--err, #ef4444)' }}>{error}</div>}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <button className="btn" onClick={onClose}>Cancel</button>
                <button className="btn primary" onClick={handleSave} disabled={saving || loading}>
                  {saving ? 'Saving...' : 'Save'}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}
