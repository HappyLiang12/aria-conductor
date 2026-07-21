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

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

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

export function ReportsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<NewReportForm>(EMPTY_FORM);
  const [createError, setCreateError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [amendInstruction, setAmendInstruction] = useState('');
  const [copyToast, setCopyToast] = useState<string | null>(null);

  const { data: reports, isLoading, error } = useQuery({
    queryKey: ['reports'],
    queryFn: listReports,
    refetchInterval: 15000,
  });

  const sortedReports = useMemo(() => reports ?? [], [reports]);

  const selected = useMemo(
    () => sortedReports.find((r) => r.id === selectedId) ?? null,
    [sortedReports, selectedId]
  );

  // Auto-select first report when list loads
  useEffect(() => {
    if (!selectedId && sortedReports.length > 0) {
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
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (id: string) => archiveReport(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports'] });
      setSelectedId(null);
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
    if (confirm(`Delete "${selected.title}"? This archives the report.`)) {
      archiveMutation.mutate(selected.id);
    }
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
    <div className="reports-page" style={{ padding: 16 }}>
      <div className="view-header">
        <div>
          <h1>📊 Generative UI · Agent Report Workspace</h1>
          <div className="sub">
            Agents author sandboxed HTML briefings · review them in tabs · chat to amend or regenerate.
          </div>
        </div>
        <div className="actions">
          <button
            className="btn primary"
            onClick={() => {
              setForm(EMPTY_FORM);
              setCreateError(null);
              setShowCreate(true);
            }}
          >
            + Generate Report
          </button>
        </div>
      </div>

      {isLoading && (
        <section className="panel" style={{ padding: 18, color: 'var(--text-dim)' }}>
          Retrieving archive…
        </section>
      )}
      {error && (
        <section className="panel" style={{ padding: 18, color: 'var(--red, #ff8d99)' }}>
          Failed to load reports.
        </section>
      )}

      {!isLoading && !error && sortedReports.length === 0 && (
        <section className="panel">
          <h2>Reports <span className="accent">· no dossiers yet</span></h2>
          <div style={{ padding: '24px 18px', color: 'var(--text-dim)', fontSize: 13, lineHeight: 1.55 }}>
            The archive is silent. Commission your first dossier — the agent composes structured
            HTML from a prompt and your data scope, then renders it inside a sandboxed iframe.
            <div style={{ marginTop: 14 }}>
              <button className="btn primary" onClick={() => setShowCreate(true)}>
                + Generate Report
              </button>
            </div>
          </div>
        </section>
      )}

      {sortedReports.length > 0 && (
        <section className="panel">
          <h2>
            Reports <span className="accent">· sandboxed render · amend on demand</span>
          </h2>
          <div className="report-grid" style={{ padding: 14 }}>
            {/* ============== LEFT: TABS + METRICS ============== */}
            <aside>
              <div className="report-tabs" style={{ padding: 0 }}>
                {sortedReports.map((r) => {
                  const isActive = r.id === selectedId;
                  return (
                    <button
                      key={r.id}
                      type="button"
                      className={`report-tab ${isActive ? 'active' : ''}`}
                      onClick={() => setSelectedId(r.id)}
                    >
                      <div className="ttl">{r.title}</div>
                      <div className="meta">
                        {formatDate(r.createdAt)}
                        {r.owner ? ` · ${r.owner}` : ''}
                      </div>
                      <div className="badge-row">
                        <span className="mini-badge">v{r.version}</span>
                        <span className="mini-badge">{r.status}</span>
                        {r.sensitivity && (
                          <span className="mini-badge">{r.sensitivity}</span>
                        )}
                      </div>
                    </button>
                  );
                })}
              </div>

              <div className="report-metrics" style={{ marginTop: 12 }}>
                <div>
                  <span>Words</span>
                  <b>{selected ? wordCount.toLocaleString() : '—'}</b>
                </div>
                <div>
                  <span>Sections</span>
                  <b>{selected ? sectionCount : '—'}</b>
                </div>
                <div>
                  <span>Version</span>
                  <b>{selected ? `v${selected.version}` : '—'}</b>
                </div>
                <div>
                  <span>Generated</span>
                  <b>{formatRelative(selected?.createdAt)}</b>
                </div>
                <div>
                  <span>Amended</span>
                  <b>{selected?.amendedAt ? formatRelative(selected.amendedAt) : '—'}</b>
                </div>
                <div>
                  <span>Tabs</span>
                  <b>{sortedReports.length}</b>
                </div>
              </div>

              {selected && wordCount === 0 && (
                <div style={{ marginTop: 8, padding: '6px 10px', background: 'rgba(255,141,153,.1)', borderRadius: 4, fontSize: 12, color: '#ff8d99' }}>
                  ⚠ This report has no readable content. It may be truncated. Try Regenerate.
                </div>
              )}
            </aside>

            {/* ============== RIGHT: TOOLBAR + PREVIEW + CHAT ============== */}
            <div className="report-main">
              <div className="report-toolbar">
                <div style={{ minWidth: 0 }}>
                  <div className="report-title" title={selected?.title ?? ''}>
                    {selected?.title ?? 'No report selected'}
                  </div>
                  <div className="report-sub">
                    {selected
                      ? `№ ${shortId(selected.id)} · ${selected.dataScope ?? 'no scope'} · sandboxed preview`
                      : 'Pick a report from the left, or generate a new one.'}
                  </div>
                </div>
                <div className="actions" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  {copyToast && (
                    <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>{copyToast}</span>
                  )}
                  <button
                    className="btn"
                    onClick={handleCopyLink}
                    disabled={!selected}
                  >
                    Copy Link
                  </button>
                  <button
                    className="btn"
                    onClick={() => selected && regenerateMutation.mutate(selected.id)}
                    disabled={!selected || regenerateMutation.isPending}
                  >
                    {regenerateMutation.isPending ? 'Regenerating…' : '↻ Regenerate'}
                  </button>
                  <button
                    className="btn"
                    onClick={handleDownload}
                    disabled={!selected || !reportHtml}
                  >
                    Download
                  </button>
                  <button
                    className="btn danger"
                    onClick={handleDelete}
                    disabled={!selected || archiveMutation.isPending}
                  >
                    Delete
                  </button>
                </div>
              </div>

              <div className="report-preview" style={{ position: 'relative' }}>
                {selected ? (
                  <>
                    <iframe
                      key={`${selected.id}-${selected.version}-${selected.amendedAt ?? ''}`}
                      className="report-frame"
                      title={selected.title}
                      sandbox=""
                      srcDoc={reportHtml || '<!doctype html><html><body style="font-family:system-ui;color:#666;padding:24px;">Loading report…</body></html>'}
                    />
                    {isBusy && (
                      <div
                        style={{
                          position: 'absolute',
                          inset: 0,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          background: 'rgba(8,12,24,.55)',
                          color: '#fff',
                          fontSize: 13,
                          letterSpacing: '.5px',
                          textTransform: 'uppercase',
                          fontWeight: 700,
                          backdropFilter: 'blur(2px)',
                        }}
                      >
                        {amendMutation.isPending
                          ? '✦ Amending report…'
                          : regenerateMutation.isPending
                          ? '↻ Regenerating…'
                          : 'Loading…'}
                      </div>
                    )}

                    {selected?.status === 'INCOMPLETE' && (
                      <div style={{
                        position: 'absolute', inset: 0,
                        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                        background: 'rgba(20,20,30,.85)', backdropFilter: 'blur(4px)',
                        zIndex: 10,
                      }}>
                        <div style={{ fontSize: 28, marginBottom: 8 }}>⚠</div>
                        <div style={{ color: '#ff8d99', fontSize: 14, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.5px' }}>
                          Report Incomplete
                        </div>
                        <div style={{ color: '#a0aec0', fontSize: 12, marginTop: 6, textAlign: 'center', maxWidth: 280 }}>
                          LLM output was truncated before the report could finish. Click Regenerate to retry.
                        </div>
                        <button
                          className="btn primary"
                          style={{ marginTop: 16 }}
                          onClick={() => selected && regenerateMutation.mutate(selected.id)}
                          disabled={!selected || regenerateMutation.isPending}
                        >
                          {regenerateMutation.isPending ? 'Regenerating…' : '↻ Regenerate'}
                        </button>
                      </div>
                    )}

                    {selected?.status === 'MISSING' && (
                      <div style={{
                        position: 'absolute', inset: 0,
                        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                        background: 'rgba(20,20,30,.85)', backdropFilter: 'blur(4px)',
                        zIndex: 10,
                      }}>
                        <div style={{ color: '#e6e7ea', fontSize: 14, marginBottom: 12 }}>
                          HTML file not found on disk
                        </div>
                        <button
                          className="btn primary"
                          onClick={() => selected && regenerateMutation.mutate(selected.id)}
                          disabled={!selected || regenerateMutation.isPending}
                        >
                          {regenerateMutation.isPending ? 'Regenerating…' : '↻ Regenerate'}
                        </button>
                      </div>
                    )}
                  </>
                ) : (
                  <div
                    style={{
                      height: 560,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      color: '#94a0b8',
                      fontSize: 13,
                    }}
                  >
                    Select a report from the archive to preview.
                  </div>
                )}
              </div>

              <div className="report-chat">
                <div className="row">
                  <textarea
                    aria-label="Ask the agent to amend this report"
                    placeholder='Ask the agent to amend, e.g. "add a risk section", "reorder by impact", "highlight critical blockers"…'
                    value={amendInstruction}
                    onChange={(e) => setAmendInstruction(e.target.value)}
                    disabled={!selected || amendMutation.isPending}
                  />
                  <button
                    className="btn primary"
                    onClick={handleAmend}
                    disabled={
                      !selected ||
                      amendMutation.isPending ||
                      !amendInstruction.trim()
                    }
                  >
                    {amendMutation.isPending ? 'Asking…' : 'Ask Agent'}
                  </button>
                </div>
                <div className="report-sub" style={{ marginTop: 8 }}>
                  Chat amendments become new report versions and stay linked to the original prompt,
                  data scope, and run evidence.
                  {amendMutation.isError && (
                    <span style={{ color: '#ff8d99', marginLeft: 8 }}>
                      · Amendment failed. Check LLM provider configuration.
                    </span>
                  )}
                </div>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* ============== GENERATE REPORT MINI DIALOG ============== */}
      <div
        className={`mini-scrim ${showCreate ? 'open' : ''}`}
        onClick={() => {
          if (!generateMutation.isPending) {
            setShowCreate(false);
            setCreateError(null);
          }
        }}
      />
      <div
        className={`mini-dialog ${showCreate ? 'open' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="generateReportTitle"
      >
        <h3 id="generateReportTitle">Generate New Report</h3>
        <p>
          Describe the briefing you want the agent to author. The output renders in a sandboxed iframe —
          no scripts, no exfiltration.
        </p>

        <label>Title *</label>
        <input
          autoFocus
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
          placeholder="e.g. Q1 Agent Performance Brief"
        />

        <label>Topic / Prompt *</label>
        <input
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          placeholder="What should the report cover? Sections, tone, structure…"
        />

        <label>Data scope</label>
        <input
          value={form.dataScope}
          onChange={(e) => setForm({ ...form, dataScope: e.target.value })}
          placeholder="e.g. runs:Q1, agents:coder*"
        />

        <label>Owner</label>
        <input
          value={form.owner}
          onChange={(e) => setForm({ ...form, owner: e.target.value })}
          placeholder="e.g. alice"
        />

        <label>Sensitivity</label>
        <select
          value={form.sensitivity}
          onChange={(e) => setForm({ ...form, sensitivity: e.target.value })}
        >
          <option value="public">public</option>
          <option value="internal">internal</option>
          <option value="confidential">confidential</option>
          <option value="restricted">restricted</option>
        </select>

        {createError && (
          <div
            style={{
              marginTop: 10,
              fontSize: 11.5,
              color: '#ff8d99',
              border: '1px solid rgba(255,107,122,.4)',
              borderRadius: 8,
              padding: '6px 8px',
              background: 'rgba(255,107,122,.08)',
            }}
          >
            {createError}
          </div>
        )}

        <div className="actions">
          <button
            className="btn"
            onClick={() => {
              setShowCreate(false);
              setCreateError(null);
            }}
            disabled={generateMutation.isPending}
          >
            Cancel
          </button>
          <button
            className="btn primary"
            onClick={handleGenerate}
            disabled={generateMutation.isPending}
          >
            {generateMutation.isPending ? '✦ Composing…' : '✦ Generate'}
          </button>
        </div>
      </div>
    </div>
  );
}

function extractError(err: unknown): string | null {
  if (typeof err === 'object' && err !== null) {
    const anyErr = err as {
      response?: { data?: { message?: string; error?: string } };
      message?: string;
    };
    return (
      anyErr.response?.data?.message ??
      anyErr.response?.data?.error ??
      anyErr.message ??
      null
    );
  }
  return null;
}

export default ReportsPage;
