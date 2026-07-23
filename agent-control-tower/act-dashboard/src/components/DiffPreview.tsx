import { useState, type CSSProperties } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getWorkspaceDiff } from '../api/workspace';

const preStyle: CSSProperties = {
  background: 'var(--panel-2, #0e1220)',
  border: '1px solid var(--line-2, #26304d)',
  borderRadius: 6,
  padding: 8,
  fontSize: 11,
  fontFamily: 'var(--mono, monospace)',
  whiteSpace: 'pre-wrap',
  color: 'var(--text-dim, #b9c2e0)',
  margin: '4px 0 0',
};

/**
 * Code-diff preview for a run's workspace, shown at push/PR approval gates so an operator can see
 * exactly what the agent is about to push before approving. Lazily fetches on expand.
 */
export default function DiffPreview({ runId }: { runId: string }) {
  const [open, setOpen] = useState(false);
  const { data, isLoading, error } = useQuery({
    queryKey: ['workspace-diff', runId],
    queryFn: () => getWorkspaceDiff(runId),
    enabled: open,
  });

  return (
    <div className="diff-preview" style={{ marginTop: 8 }}>
      <button
        type="button"
        className="btn"
        style={{ fontSize: 11 }}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        {open ? 'Hide code diff' : 'View code diff'}
      </button>
      {open && (
        <div style={{ marginTop: 6 }}>
          {isLoading && (
            <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>Loading diff…</div>
          )}
          {error && (
            <div style={{ fontSize: 11, color: 'var(--red, #ff97a3)' }}>Failed to load diff.</div>
          )}
          {data && !data.hasWorkspace && (
            <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>
              No workspace for this run yet.
            </div>
          )}
          {data && data.hasWorkspace && (
            <>
              {data.summary && <pre style={preStyle}>{data.summary}</pre>}
              <pre style={{ ...preStyle, maxHeight: 320, overflow: 'auto' }}>
                {data.diff || '(no uncommitted changes)'}
              </pre>
              {data.truncated && (
                <div style={{ fontSize: 10, color: 'var(--text-mute)' }}>Diff truncated.</div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
