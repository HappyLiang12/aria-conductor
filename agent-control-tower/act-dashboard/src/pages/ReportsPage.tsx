import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  amendReport,
  archiveReport,
  generateReport,
  getReportHtml,
  listReports,
  regenerateReport,
  reportHtmlUrl,
} from '../api/reports';
import { useWebSocketContext } from '../components/Layout';
import { apiErrorMessage } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import { ErrorNotice } from '../components/ErrorNotice';
import { ReportContentViewer } from '../components/ReportContentViewer';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { PageHeader } from '../components/layout/PageHeader';
import { SectionCard } from '../components/layout/SectionCard';
import { Button } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import { formatTimestamp } from '../utils/formatTime';
import type {
  GenerateReportRequest,
  ReportArtifact,
} from '../types';

interface NewReportForm {
  title: string;
  description: string;
  dataScope: string;
  owner: string;
  sensitivity: string;
}

const EMPTY_FORM: NewReportForm = {
  title: '',
  description: '',
  dataScope: '',
  owner: '',
  sensitivity: 'internal',
};

function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

function countWords(html: string): number {
  if (!html) return 0;
  const text = html.replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!text) return 0;
  return text.split(' ').length;
}

function countSections(html: string): number {
  if (!html) return 0;
  const matches = html.match(/<(h1|h2|h3|section)\b/gi);
  return matches ? matches.length : 0;
}

function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  const diff = Math.max(0, Date.now() - t);
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  return `${day}d ago`;
}

const METRIC_TILE = 'rounded-lg bg-muted/50 p-2';
const METRIC_LABEL = 'block text-[10px] font-medium tracking-wider text-muted-foreground uppercase';
const METRIC_VALUE = 'mt-0.5 block text-sm font-semibold text-foreground';

export function ReportsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();
  const [showCreate, setShowCreate] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<NewReportForm>(EMPTY_FORM);
  const [createError, setCreateError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [amendInstruction, setAmendInstruction] = useState('');
  const [copyToast, setCopyToast] = useState<string | null>(null);
  // A regenerate/archive failure kept as the API error so the shared notice can
  // decide: an operator-only refusal reads as the operator-session notice, a
  // fault keeps the ordinary error path. A toast cannot make that distinction.
  const [actionFailure, setActionFailure] = useState<{
    error: unknown;
    action: string;
    fallback: string;
  } | null>(null);
  // Delete awaiting confirmation. Archiving is irreversible from the UI, so the
  // Delete control only opens the shared dialog (Task 10).
  const [confirmingArchive, setConfirmingArchive] = useState(false);

  const { data: reports, isLoading, error } = useQuery({
    queryKey: ['reports'],
    queryFn: listReports,
    refetchInterval: 15000,
  });

  const totalReports = reports?.length ?? 0;

  // Delete soft-archives (status ARCHIVED). Archived rows stay out of the list
  // until the operator opts in via the "Show archived" toggle.
  const sortedReports = useMemo(
    () => (reports ?? []).filter((r) => showArchived || r.status !== 'ARCHIVED'),
    [reports, showArchived]
  );

  const selected = useMemo(
    () => sortedReports.find((r) => r.id === selectedId) ?? null,
    [sortedReports, selectedId]
  );

  // Auto-select the first VISIBLE report. When the current selection is hidden
  // (archived, or filtered out by the toggle), fall back to the first visible
  // report — or clear the selection when nothing is visible.
  useEffect(() => {
    if (sortedReports.length === 0) {
      if (selectedId !== null) setSelectedId(null);
      return;
    }
    if (!sortedReports.some((r) => r.id === selectedId)) {
      setSelectedId(sortedReports[0].id);
    }
  }, [sortedReports, selectedId]);

  // Realtime: invalidate reports when a report event arrives
  useEffect(() => {
    if (lastMessage?.type?.startsWith('report.')) {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      queryClient.invalidateQueries({ queryKey: ['report-html'] });
    }
  }, [lastMessage, queryClient]);

  // Fetch HTML content for selected report (used for srcDoc + metrics)
  const htmlQuery = useQuery({
    queryKey: ['report-html', selected?.id, selected?.version, selected?.amendedAt],
    queryFn: () => getReportHtml(selected!.id),
    enabled: !!selected,
  });

  const generateMutation = useMutation({
    mutationFn: (request: GenerateReportRequest) => generateReport(request),
    onSuccess: (created: ReportArtifact) => {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      setShowCreate(false);
      setForm(EMPTY_FORM);
      setCreateError(null);
      setSelectedId(created.id);
    },
    onError: (err: unknown) => {
      setCreateError(extractError(err) ?? 'Failed to generate report');
    },
  });

  const amendMutation = useMutation({
    mutationFn: ({ id, instruction }: { id: string; instruction: string }) =>
      amendReport(id, { instruction }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      queryClient.invalidateQueries({ queryKey: ['report-html'] });
      setAmendInstruction('');
    },
  });

  const regenerateMutation = useMutation({
    mutationFn: (id: string) => regenerateReport(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      queryClient.invalidateQueries({ queryKey: ['report-html'] });
      setActionFailure(null);
    },
    onError: (err: unknown) => {
      // A gated route: keep the error itself so a refusal can say who may act.
      setActionFailure({
        error: err,
        action: 'Regenerating a report',
        fallback: 'Regeneration failed.',
      });
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (id: string) => archiveReport(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      setSelectedId(null);
      setActionFailure(null);
      // Task 10: the confirmed archive is done — drop the pending confirmation.
      setConfirmingArchive(false);
    },
    onError: (err: unknown) => {
      // The confirmed action is over (it failed) — never leave the confirmation
      // stuck open. The failure then renders in front of the operator, not
      // behind the dialog that would have hidden it.
      setConfirmingArchive(false);
      setActionFailure({
        error: err,
        action: 'Archiving a report',
        fallback: 'Archive failed. Please retry.',
      });
    },
  });

  const handleGenerate = () => {
    const title = form.title.trim();
    const description = form.description.trim();
    if (!title || !description) {
      setCreateError('Title and topic/prompt are required');
      return;
    }
    generateMutation.mutate({
      title,
      description,
      dataScope: form.dataScope.trim() || undefined,
      owner: form.owner.trim() || undefined,
      sensitivity: form.sensitivity || undefined,
    });
  };

  const handleAmend = () => {
    if (!selected) return;
    const text = amendInstruction.trim();
    if (!text) return;
    amendMutation.mutate({ id: selected.id, instruction: text });
  };

  const handleCopyLink = async () => {
    if (!selected) return;
    const link = window.location.origin + reportHtmlUrl(selected.id);
    try {
      await navigator.clipboard.writeText(link);
      setCopyToast('Link copied');
    } catch {
      setCopyToast('Copy failed');
    }
    setTimeout(() => setCopyToast(null), 1800);
  };

  const handleDownload = () => {
    if (!selected || !htmlQuery.data) return;
    const blob = new Blob([htmlQuery.data], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${selected.title.replace(/[^a-z0-9]+/gi, '_').toLowerCase()}_v${selected.version}.html`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleDelete = () => {
    if (!selected) return;
    // Opens the shared confirmation only — the archive fires from onConfirm.
    setConfirmingArchive(true);
  };

  const reportHtml = htmlQuery.data ?? '';
  const isBusy =
    htmlQuery.isLoading ||
    htmlQuery.isFetching ||
    amendMutation.isPending ||
    regenerateMutation.isPending;

  const wordCount = useMemo(() => countWords(reportHtml), [reportHtml]);
  const sectionCount = useMemo(() => countSections(reportHtml), [reportHtml]);

  return (
    <div className="reports-page space-y-4 p-4">
      <PageHeader
        title="📊 Generative UI · Agent Report Workspace"
        description="Agents author sandboxed HTML briefings · review them in tabs · chat to amend or regenerate."
        actions={
          <Button
            onClick={() => {
              setForm(EMPTY_FORM);
              setCreateError(null);
              setShowCreate(true);
            }}
          >
            + Generate Report
          </Button>
        }
      />

      {actionFailure && (
        <ErrorNotice
          presented={presentError(actionFailure.error, actionFailure.fallback)}
          operatorSession
          actionLabel={actionFailure.action}
        />
      )}

      {isLoading && (
        <Card>
          <CardContent className="text-sm text-muted-foreground">Retrieving archive…</CardContent>
        </Card>
      )}
      {error && (
        <Card>
          <CardContent className="text-sm text-destructive">Failed to load reports.</CardContent>
        </Card>
      )}

      {!isLoading && !error && totalReports === 0 && (
        <section className="panel">
          <SectionCard title="Reports · no dossiers yet">
            <div className="max-w-3xl space-y-3 text-sm leading-relaxed text-muted-foreground">
              <p>
                The archive is silent. Commission your first dossier — the agent composes structured
                HTML from a prompt and your data scope, then renders it inside a sandboxed iframe.
              </p>
              <Button onClick={() => setShowCreate(true)}>+ Generate Report</Button>
            </div>
          </SectionCard>
        </section>
      )}

      {totalReports > 0 && (
        <section className="panel">
          <SectionCard title="Reports · sandboxed render · amend on demand">
            {/* Delete soft-archives: archived dossiers are hidden unless revealed. */}
            <div className="mb-3 flex justify-end">
              <label className="flex cursor-pointer items-center gap-1.5 text-xs text-muted-foreground">
                <input
                  type="checkbox"
                  className="accent-primary"
                  checked={showArchived}
                  onChange={(e) => setShowArchived(e.target.checked)}
                />
                Show archived
              </label>
            </div>
            <div className="report-grid grid gap-4 lg:grid-cols-[minmax(240px,0.72fr)_minmax(0,1.28fr)]">
              {/* ============== LEFT: TABS + METRICS ============== */}
              <aside>
                {sortedReports.length === 0 && (
                  <div className="mb-2 text-xs leading-relaxed text-muted-foreground">
                    No active dossiers — archived reports are hidden.
                  </div>
                )}
                <div className="report-tabs grid gap-2">
                  {sortedReports.map((r) => {
                    const isActive = r.id === selectedId;
                    return (
                      <button
                        key={r.id}
                        type="button"
                        className={`report-tab rounded-xl border p-3 text-left transition-colors ${
                          isActive
                            ? 'active border-primary/50 bg-primary/10'
                            : 'border-border bg-card hover:border-ring/50 hover:bg-muted/40'
                        } ${r.status === 'ARCHIVED' ? 'opacity-70' : ''}`}
                        onClick={() => setSelectedId(r.id)}
                      >
                        <div className="ttl truncate text-sm font-semibold text-foreground">{r.title}</div>
                        <div className="meta mt-1 text-xs text-muted-foreground">
                          {formatTimestamp(r.createdAt)}
                          {r.owner ? ` · ${r.owner}` : ''}
                        </div>
                        <div className="badge-row mt-2 flex flex-wrap gap-1.5">
                          <span className="mini-badge inline-flex rounded-full bg-secondary px-2 py-0.5 text-[10px] font-semibold text-secondary-foreground">
                            v{r.version}
                          </span>
                          <span className="mini-badge inline-flex rounded-full bg-secondary px-2 py-0.5 text-[10px] font-semibold text-secondary-foreground">
                            {r.status}
                          </span>
                          {r.sensitivity && (
                            <span className="mini-badge inline-flex rounded-full bg-secondary px-2 py-0.5 text-[10px] font-semibold text-secondary-foreground">
                              {r.sensitivity}
                            </span>
                          )}
                        </div>
                      </button>
                    );
                  })}
                </div>

                <div className="report-metrics mt-3 grid grid-cols-3 gap-2">
                  <div className={METRIC_TILE}>
                    <span className={METRIC_LABEL}>Words</span>
                    <b className={METRIC_VALUE}>{selected ? wordCount.toLocaleString() : '—'}</b>
                  </div>
                  <div className={METRIC_TILE}>
                    <span className={METRIC_LABEL}>Sections</span>
                    <b className={METRIC_VALUE}>{selected ? sectionCount : '—'}</b>
                  </div>
                  <div className={METRIC_TILE}>
                    <span className={METRIC_LABEL}>Version</span>
                    <b className={METRIC_VALUE}>{selected ? `v${selected.version}` : '—'}</b>
                  </div>
                  <div className={METRIC_TILE}>
                    <span className={METRIC_LABEL}>Generated</span>
                    <b className={METRIC_VALUE}>{formatRelative(selected?.createdAt)}</b>
                  </div>
                  <div className={METRIC_TILE}>
                    <span className={METRIC_LABEL}>Amended</span>
                    <b className={METRIC_VALUE}>{selected?.amendedAt ? formatRelative(selected.amendedAt) : '—'}</b>
                  </div>
                  <div className={METRIC_TILE}>
                    <span className={METRIC_LABEL}>Tabs</span>
                    <b className={METRIC_VALUE}>{sortedReports.length}</b>
                  </div>
                </div>

                {selected && wordCount === 0 && (
                  <div className="mt-2 rounded-lg bg-destructive/10 px-3 py-1.5 text-xs text-destructive">
                    ⚠ This report has no readable content. It may be truncated. Try Regenerate.
                  </div>
                )}
              </aside>

              {/* ============== RIGHT: TOOLBAR + PREVIEW + CHAT ============== */}
              <div className="report-main grid gap-3">
                <div className="report-toolbar flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-card/60 p-3">
                  <div className="min-w-0">
                    <div className="report-title truncate text-sm font-semibold text-foreground" title={selected?.title ?? ''}>
                      {selected?.title ?? 'No report selected'}
                    </div>
                    <div className="report-sub mt-1 text-xs text-muted-foreground">
                      {selected
                        ? `№ ${shortId(selected.id)} · ${selected.dataScope ?? 'no scope'} · sandboxed preview`
                        : 'Pick a report from the left, or generate a new one.'}
                    </div>
                  </div>
                  <div className="actions flex flex-wrap items-center gap-2">
                    {copyToast && (
                      <span className="text-xs text-muted-foreground">{copyToast}</span>
                    )}
                    <Button
                      variant="outline"
                      onClick={handleCopyLink}
                      disabled={!selected}
                    >
                      Copy Link
                    </Button>
                    <Button
                      variant="outline"
                      onClick={() => selected && regenerateMutation.mutate(selected.id)}
                      disabled={!selected || regenerateMutation.isPending}
                    >
                      {regenerateMutation.isPending ? 'Regenerating…' : '↻ Regenerate'}
                    </Button>
                    <Button
                      variant="outline"
                      onClick={handleDownload}
                      disabled={!selected || !reportHtml}
                    >
                      Download
                    </Button>
                    <Button
                      variant="destructive"
                      onClick={handleDelete}
                      disabled={!selected || archiveMutation.isPending}
                    >
                      Delete
                    </Button>
                  </div>
                </div>

                <Card className="report-preview relative overflow-hidden">
                  <CardContent className="relative">
                    {selected ? (
                      <>
                        <ReportContentViewer
                          key={`${selected.id}-${selected.version}-${selected.amendedAt ?? ''}`}
                          content={reportHtml}
                          title={selected.title}
                        />
                        {isBusy && (
                          <div className="absolute inset-0 flex items-center justify-center bg-background/60 text-sm font-semibold tracking-wide text-foreground uppercase backdrop-blur-sm">
                            {amendMutation.isPending
                              ? '✦ Amending report…'
                              : regenerateMutation.isPending
                              ? '↻ Regenerating…'
                              : 'Loading…'}
                          </div>
                        )}

                        {selected?.status === 'INCOMPLETE' && (
                          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 bg-background/85 p-4 text-center backdrop-blur-sm">
                            <div className="text-2xl">⚠</div>
                            <div className="text-sm font-semibold tracking-wide text-destructive uppercase">
                              Report Incomplete
                            </div>
                            <div className="max-w-xs text-xs text-muted-foreground">
                              LLM output was truncated before the report could finish. Click Regenerate to retry.
                            </div>
                            <Button
                              className="mt-1"
                              onClick={() => selected && regenerateMutation.mutate(selected.id)}
                              disabled={!selected || regenerateMutation.isPending}
                            >
                              {regenerateMutation.isPending ? 'Regenerating…' : '↻ Regenerate'}
                            </Button>
                          </div>
                        )}

                        {selected?.status === 'MISSING' && (
                          <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 bg-background/85 p-4 text-center backdrop-blur-sm">
                            <div className="text-sm text-foreground">
                              HTML file not found on disk
                            </div>
                            <Button
                              onClick={() => selected && regenerateMutation.mutate(selected.id)}
                              disabled={!selected || regenerateMutation.isPending}
                            >
                              {regenerateMutation.isPending ? 'Regenerating…' : '↻ Regenerate'}
                            </Button>
                          </div>
                        )}
                      </>
                    ) : (
                      <div className="flex h-[560px] items-center justify-center text-sm text-muted-foreground">
                        Select a report from the archive to preview.
                      </div>
                    )}
                  </CardContent>
                </Card>

                <div className="report-chat rounded-xl border border-border bg-card/60 p-3">
                  <div className="row flex items-end gap-2">
                    <Textarea
                      aria-label="Ask the agent to amend this report"
                      placeholder='Ask the agent to amend, e.g. "add a risk section", "reorder by impact", "highlight critical blockers"…'
                      className="min-h-11 flex-1"
                      value={amendInstruction}
                      onChange={(e) => setAmendInstruction(e.target.value)}
                      disabled={!selected || amendMutation.isPending}
                    />
                    <Button
                      onClick={handleAmend}
                      disabled={
                        !selected ||
                        amendMutation.isPending ||
                        !amendInstruction.trim()
                      }
                    >
                      {amendMutation.isPending ? 'Asking…' : 'Ask Agent'}
                    </Button>
                  </div>
                  <div className="report-sub mt-2 text-xs text-muted-foreground">
                    Chat amendments become new report versions and stay linked to the original prompt,
                    data scope, and run evidence.
                    {amendMutation.isError && (
                      <span className="ml-2 text-destructive">
                        · {apiErrorMessage(amendMutation.error,
                          'Amendment failed. Check LLM provider configuration.')}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </SectionCard>
        </section>
      )}

      {/* ============== GENERATE REPORT MINI DIALOG ============== */}
      <div
        className={`mini-scrim ${showCreate ? 'open' : ''} fixed inset-0 z-40 bg-black/50 backdrop-blur-sm`}
        onClick={() => {
          if (!generateMutation.isPending) {
            setShowCreate(false);
            setCreateError(null);
          }
        }}
      />
      <div
        className={`mini-dialog ${showCreate ? 'open' : ''} fixed top-1/2 left-1/2 z-50 w-[min(520px,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-xl bg-card p-5 text-card-foreground ring-1 ring-foreground/10`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="generateReportTitle"
      >
        <h3 id="generateReportTitle" className="text-base font-semibold text-foreground">Generate New Report</h3>
        <p className="mt-1 mb-3 text-sm text-muted-foreground">
          Describe the briefing you want the agent to author. The output renders in a sandboxed iframe —
          no scripts, no exfiltration.
        </p>

        <label htmlFor="report-title" className="mb-1 block text-xs font-medium text-muted-foreground">Title *</label>
        <Input
          id="report-title"
          autoFocus
          className="mb-3"
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
          placeholder="e.g. Q1 Agent Performance Brief"
        />

        <label htmlFor="report-topic" className="mb-1 block text-xs font-medium text-muted-foreground">Topic / Prompt *</label>
        <Input
          id="report-topic"
          className="mb-3"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          placeholder="What should the report cover? Sections, tone, structure…"
        />

        <label htmlFor="report-scope" className="mb-1 block text-xs font-medium text-muted-foreground">Data scope</label>
        <Input
          id="report-scope"
          className="mb-3"
          value={form.dataScope}
          onChange={(e) => setForm({ ...form, dataScope: e.target.value })}
          placeholder="e.g. runs:Q1, agents:coder*"
        />

        <label htmlFor="report-owner" className="mb-1 block text-xs font-medium text-muted-foreground">Owner</label>
        <Input
          id="report-owner"
          className="mb-3"
          value={form.owner}
          onChange={(e) => setForm({ ...form, owner: e.target.value })}
          placeholder="e.g. alice"
        />

        <label htmlFor="report-sensitivity" className="mb-1 block text-xs font-medium text-muted-foreground">Sensitivity</label>
        <select
          id="report-sensitivity"
          className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          value={form.sensitivity}
          onChange={(e) => setForm({ ...form, sensitivity: e.target.value })}
        >
          <option value="public">public</option>
          <option value="internal">internal</option>
          <option value="confidential">confidential</option>
          <option value="restricted">restricted</option>
        </select>

        {createError && (
          <div className="mt-2.5 rounded-lg border border-destructive/40 bg-destructive/10 px-2 py-1.5 text-xs text-destructive">
            {createError}
          </div>
        )}

        <div className="actions mt-4 flex justify-end gap-2">
          <Button
            variant="outline"
            onClick={() => {
              setShowCreate(false);
              setCreateError(null);
            }}
            disabled={generateMutation.isPending}
          >
            Cancel
          </Button>
          <Button
            onClick={handleGenerate}
            disabled={generateMutation.isPending}
          >
            {generateMutation.isPending ? '✦ Composing…' : '✦ Generate'}
          </Button>
        </div>
      </div>

      {/* Shared destructive-action confirmation (Task 10): the Delete control
          only opens this dialog, and the archive fires from onConfirm. */}
      <ConfirmDialog
        open={confirmingArchive && selected !== null}
        title="Delete this report?"
        message={
          <>
            Delete <strong>{selected?.title}</strong>? This archives the report — it leaves
            the workspace list and cannot be restored from here.
          </>
        }
        danger
        onConfirm={() => {
          if (!selected) return;
          const id = selected.id;
          // Closed immediately: onSuccess/onError re-clear (idempotent) so a
          // rejection can never strand the dialog open.
          setConfirmingArchive(false);
          archiveMutation.mutate(id);
        }}
        onCancel={() => setConfirmingArchive(false)}
      />
    </div>
  );
}

function extractError(err: unknown): string | null {
  if (err === null || err === undefined) {
    return null;
  }
  return apiErrorMessage(err, (err as Error)?.message ?? '') || null;
}

export default ReportsPage;
