import { useState, useEffect, useMemo } from 'react';
import { getKnowledgeYaml, instantiateWorkflowTemplate } from '../api/knowledge';
import { extractTemplateParams } from '../utils/workflowTemplateParams';
import type { KnowledgeItem } from '../types';

interface Props {
  item: KnowledgeItem;
  onClose: () => void;
  onSuccess: () => void;
}

export default function RunTemplateModal({ item, onClose, onSuccess }: Props) {
  const [yaml, setYaml] = useState('');
  const [loading, setLoading] = useState(true);
  const [params, setParams] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    getKnowledgeYaml(item.id)
      .then((y) => {
        setYaml(y || '');
        const extracted = extractTemplateParams(y || '');
        const initial: Record<string, string> = {};
        extracted.forEach((p) => { initial[p] = ''; });
        setParams(initial);
      })
      .catch(() => setYaml(''))
      .finally(() => setLoading(false));
  }, [item.id]);

  const paramNames = useMemo(() => Object.keys(params).sort(), [params]);

  const handleSubmit = async () => {
    setSubmitting(true);
    setError('');
    try {
      // Filter out empty optional params (like repoUrl which has a system fallback)
      const filtered: Record<string, string> = {};
      for (const [k, v] of Object.entries(params)) {
        if (v.trim()) filtered[k] = v.trim();
      }
      await instantiateWorkflowTemplate(item.id, filtered);
      onSuccess();
    } catch (e: any) {
      const msg = typeof e?.response?.data === 'string'
        ? e.response.data
        : (e?.response?.data?.message || e?.message || 'Failed to instantiate');
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <div className="modal-scrim open" onClick={onClose} />
      <div className="modal open" role="dialog" aria-modal="true" aria-labelledby="run-tpl-title"
        style={{ width: 'min(480px, 94vw)', height: 'auto' }}>
        <header>
          <h2 id="run-tpl-title"><b>Run</b> {item.name}</h2>
          <span className="x" role="button" tabIndex={0} aria-label="Close" onClick={onClose}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClose(); }}>✕</span>
        </header>
        <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {loading ? (
            <div style={{ color: 'var(--muted, #94a3b8)' }}>Loading parameters...</div>
          ) : paramNames.length === 0 ? (
            <div style={{ fontSize: 13, color: 'var(--muted, #94a3b8)' }}>
              This template has no parameters. Click Run to start it immediately.
            </div>
          ) : (
            paramNames.map((name) => (
              <label key={name} style={{ fontSize: 13, display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span>
                  {name}
                  {name === 'repoUrl' && (
                    <span style={{ fontSize: 11, color: 'var(--muted, #94a3b8)', marginLeft: 6 }}>
                      (leave empty to use system default)
                    </span>
                  )}
                </span>
                <input
                  value={params[name] ?? ''}
                  onChange={(e) => setParams((prev) => ({ ...prev, [name]: e.target.value }))}
                  placeholder={name === 'issueRef' ? '#42' : name === 'issueRepo' ? 'owner/repo' : ''}
                  style={{ padding: '7px 10px', borderRadius: 8, border: '1px solid var(--line, #334155)', background: 'var(--surface, #0f172a)', color: 'var(--text, #e2e8f0)' }}
                />
              </label>
            ))
          )}
          {error && (
            <div style={{ fontSize: 12, color: 'var(--err, #ef4444)', whiteSpace: 'pre-wrap' }}>{error}</div>
          )}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <button className="btn" onClick={onClose}>Cancel</button>
            <button className="btn primary" onClick={handleSubmit} disabled={submitting || loading}>
              {submitting ? 'Starting...' : 'Run'}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
