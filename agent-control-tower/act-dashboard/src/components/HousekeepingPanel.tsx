import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  scanHousekeeping,
  executeHousekeeping,
  type HousekeepingScanResult,
} from '../api/housekeeping';
import { useWebSocketContext } from './Layout';

/** Default category selection: stuck + approvals are opt-in (destructive-adjacent). */
const DEFAULT_CHECKED: Record<string, boolean> = {
  runs: true,
  stuck: false,
  kanban: true,
  agents: true,
  approvals: false,
};

const CATEGORY_LABELS: Record<string, string> = {
  runs: 'Terminal runs',
  stuck: 'Stuck / paused runs (opt-in)',
  kanban: 'Finished kanban cards',
  agents: 'Leftover test agents',
  approvals: 'Expired pending approvals',
};

/**
 * Housekeeping H1: scan leftovers, preview a dry-run plan, and execute a
 * confirm-gated cleanup batch. Completion (audit.HOUSEKEEPING_EXECUTED)
 * refreshes the affected lists; housekeeping.progress feeds the status line.
 */
export default function HousekeepingPanel() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();

  const [scanEnabled, setScanEnabled] = useState(false);
  const [checked, setChecked] = useState<Record<string, boolean>>({ ...DEFAULT_CHECKED });
  const [includeStuck] = useState(true);
  const [dryRunOpen, setDryRunOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [progress, setProgress] = useState<string>('');
  const [receipt, setReceipt] = useState<string>('');

  const scanQuery = useQuery<HousekeepingScanResult>({
    queryKey: ['housekeeping', 'scan', includeStuck],
    queryFn: () => scanHousekeeping(includeStuck),
    enabled: scanEnabled,
    staleTime: 10_000,
  });

  // WS: progress line + completion invalidation.
  useEffect(() => {
    if (!lastMessage) return;
    if (lastMessage.type === 'housekeeping.progress') {
      const p = lastMessage.payload ?? {};
      setProgress(
        `cleaning ${p.category ?? ''}… cleared=${p.cleared ?? 0} failed=${p.failed ?? 0}`,
      );
    }
    if (lastMessage.type === 'audit.HOUSEKEEPING_EXECUTED') {
      setProgress('');
      setReceipt(String((lastMessage.payload as Record<string, unknown> | undefined)?.details ?? 'cleanup complete'));
      queryClient.invalidateQueries({ queryKey: ['runs'] });
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['housekeeping'] });
    }
  }, [lastMessage, queryClient]);

  const categories = useMemo(() => scanQuery.data?.categories ?? [], [scanQuery.data]);
  const selected = categories.filter((c) => checked[c.key]).map((c) => c.key);

  const runExecute = async () => {
    setConfirmOpen(false);
    const result = await executeHousekeeping({
      categories: selected,
      includeStuck: checked.stuck,
      confirm: true,
    });
    setReceipt(
      result.categories
        .map((r) => `${r.key}: cleared=${r.cleared} failed=${r.failed} skipped=${r.skipped}`)
        .join(' · '),
    );
  };

  return (
    <section className="panel" style={{ marginBottom: 16 }}>
      <h2 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        Housekeeping
        <span className="grow" style={{ flex: 1 }} />
        <button className="btn primary" onClick={() => setScanEnabled(true)}>
          ⚡ Scan leftovers
        </button>
      </h2>

      {scanQuery.isLoading && <div className="mini-spinner">Scanning…</div>}

      {categories.map((c) => (
        <div key={c.key} style={{ border: '1px solid var(--line)', borderRadius: 10, padding: '8px 12px', marginBottom: 8 }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={checked[c.key] ?? false}
              onChange={(e) => setChecked((prev) => ({ ...prev, [c.key]: e.target.checked }))}
            />
            <strong>{CATEGORY_LABELS[c.key] ?? c.key}</strong>
            <span style={{ fontFamily: 'var(--mono, monospace)', color: 'var(--accent2, #5eead4)' }}>
              {c.count}
            </span>
          </label>
          {c.preview.length > 0 && (
            <details>
              <summary>sample</summary>
              <ul style={{ fontFamily: 'var(--mono, monospace)', fontSize: 11 }}>
                {c.preview.map((p) => (
                  <li key={p.id}>
                    {p.id.slice(0, 8)} {p.status} “{p.title.slice(0, 40)}” {p.age}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      ))}

      {categories.length > 0 && (
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <span style={{ color: 'var(--mute, #8ea0b8)' }}>
            {selected.length} categories selected
          </span>
          <span style={{ flex: 1 }} />
          <button className="btn" disabled={!selected.length} onClick={() => setDryRunOpen(true)}>
            Dry-run preview
          </button>
          <button className="btn danger" disabled={!selected.length} onClick={() => setConfirmOpen(true)}>
            Execute cleanup…
          </button>
        </div>
      )}

      {progress && <div style={{ marginTop: 8, fontFamily: 'var(--mono, monospace)', fontSize: 11 }}>{progress}</div>}
      {receipt && <div style={{ marginTop: 8, fontFamily: 'var(--mono, monospace)', fontSize: 11 }}>🧹 {receipt}</div>}

      {dryRunOpen && (
        <div className="modal-overlay" onClick={() => setDryRunOpen(false)}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>Dry-run preview — nothing will change</h3>
            <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--mono, monospace)', fontSize: 11 }}>
              {categories
                .filter((c) => checked[c.key])
                .map((c) => `# ${c.key} — ${c.count} item(s)\n${c.preview.map((p) => `  DELETE/RETIRE ${p.id.slice(0, 8)} ${p.title.slice(0, 40)}`).join('\n')}`)
                .join('\n')}
            </pre>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button className="btn" onClick={() => setDryRunOpen(false)}>Close</button>
            </div>
          </div>
        </div>
      )}

      {confirmOpen && (
        <div className="modal-overlay" onClick={() => setConfirmOpen(false)}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>⚠ Destructive batch — approval required</h3>
            <p>
              This will permanently purge {selected.join(', ')} leftovers per the scan above.
              Items created after the scan are skipped (idempotent).
            </p>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button className="btn" onClick={() => setConfirmOpen(false)}>Cancel</button>
              <button className="btn danger" onClick={runExecute}>Approve &amp; execute</button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
