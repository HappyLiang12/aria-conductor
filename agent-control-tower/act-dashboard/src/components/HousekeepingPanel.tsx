import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  scanHousekeeping,
  executeHousekeeping,
  type HousekeepingScanResult,
} from '../api/housekeeping';
import { useWebSocketContext } from './Layout';
import { presentError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { Card, CardContent, CardHeader } from './ui/card';

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
  // `executeHousekeeping` is operator-only server-side: a refusal has to read as
  // the operator-session notice, not as a transport message in the receipt line.
  const [executeError, setExecuteError] = useState<unknown | null>(null);

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
    setExecuteError(null);
    try {
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
    } catch (e) {
      // Nothing was confirmed as cleared, so the old receipt would be a claim
      // this call cannot make.
      setReceipt('');
      setExecuteError(e);
    }
  };

  return (
    <Card className="mb-4">
      <CardHeader className="flex flex-row items-center justify-between gap-2 border-b border-border">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-card-foreground">
          Housekeeping
        </h2>
        <button
          className="btn primary inline-flex h-8 items-center justify-center rounded-lg border border-transparent bg-primary px-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/80"
          onClick={() => setScanEnabled(true)}
        >
          ⚡ Scan leftovers
        </button>
      </CardHeader>
      <CardContent className="space-y-2">
        {scanQuery.isLoading && <div className="mini-spinner text-xs text-muted-foreground">Scanning…</div>}
        {scanQuery.isError && (
          <ErrorNotice presented={presentError(scanQuery.error, 'The housekeeping scan failed.')} />
        )}

        {categories.map((c) => (
          <div key={c.key} className="rounded-lg border border-border bg-muted/30 px-3 py-2">
            <label className="flex cursor-pointer items-center gap-2.5 text-sm">
              <input
                type="checkbox"
                className="size-3.5 accent-primary"
                checked={checked[c.key] ?? false}
                onChange={(e) => setChecked((prev) => ({ ...prev, [c.key]: e.target.checked }))}
              />
              <strong className="font-semibold text-foreground">{CATEGORY_LABELS[c.key] ?? c.key}</strong>
              <span className="font-mono text-primary">{c.count}</span>
            </label>
            {c.preview.length > 0 && (
              <details className="mt-1 text-muted-foreground">
                <summary className="cursor-pointer text-xs">sample</summary>
                <ul className="mt-1 list-disc pl-5 font-mono text-[11px]">
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
          <div className="flex flex-wrap items-center gap-2.5 pt-1">
            <span className="text-muted-foreground">
              {selected.length} categories selected
            </span>
            <span className="flex-1" />
            <button
              className="btn inline-flex h-8 items-center justify-center rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:opacity-50"
              disabled={!selected.length}
              onClick={() => setDryRunOpen(true)}
            >
              Dry-run preview
            </button>
            <button
              className="btn danger inline-flex h-8 items-center justify-center rounded-lg border border-destructive/40 bg-background px-2.5 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10 disabled:opacity-50"
              disabled={!selected.length}
              onClick={() => setConfirmOpen(true)}
            >
              Execute cleanup…
            </button>
          </div>
        )}

        {progress && <div className="mt-2 font-mono text-[11px] text-muted-foreground">{progress}</div>}
        {receipt && <div className="mt-2 font-mono text-[11px] text-success">🧹 {receipt}</div>}
        {executeError != null && (
          <ErrorNotice
            presented={presentError(
              executeError,
              'The cleanup batch failed — nothing was confirmed as cleared.',
            )}
            operatorSession
            actionLabel="Running housekeeping"
          />
        )}
      </CardContent>

      {dryRunOpen && (
        <div className="modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm" onClick={() => setDryRunOpen(false)}>
          <div
            className="modal-dialog w-full max-w-3xl rounded-xl bg-card p-4 text-card-foreground ring-1 ring-foreground/10"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-2 text-base font-semibold text-foreground">Dry-run preview — nothing will change</h3>
            <pre className="max-h-[60vh] overflow-auto rounded-lg border border-border bg-muted/40 p-3 font-mono text-[11px] whitespace-pre-wrap">
              {categories
                .filter((c) => checked[c.key])
                .map((c) => `# ${c.key} — ${c.count} item(s)\n${c.preview.map((p) => `  DELETE/RETIRE ${p.id.slice(0, 8)} ${p.title.slice(0, 40)}`).join('\n')}`)
                .join('\n')}
            </pre>
            <div className="mt-3 flex justify-end">
              <button
                className="btn inline-flex h-8 items-center justify-center rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-muted"
                onClick={() => setDryRunOpen(false)}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmOpen && (
        <div className="modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm" onClick={() => setConfirmOpen(false)}>
          <div
            className="modal-dialog w-full max-w-xl rounded-xl bg-card p-4 text-card-foreground ring-1 ring-foreground/10"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-2 text-base font-semibold text-foreground">⚠ Destructive batch — approval required</h3>
            <p className="mb-3 text-sm text-muted-foreground">
              This will permanently purge {selected.join(', ')} leftovers per the scan above.
              Items created after the scan are skipped (idempotent).
            </p>
            <div className="flex justify-end gap-2.5">
              <button
                className="btn inline-flex h-8 items-center justify-center rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-muted"
                onClick={() => setConfirmOpen(false)}
              >
                Cancel
              </button>
              <button
                className="btn danger inline-flex h-8 items-center justify-center rounded-lg border border-destructive/40 bg-background px-2.5 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10"
                onClick={runExecute}
              >
                Approve &amp; execute
              </button>
            </div>
          </div>
        </div>
      )}
    </Card>
  );
}
