import { useState, useEffect, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listApprovalsByStatus,
  approveApproval,
  rejectApproval,
  listRecentRuns,
  getOpsSummary,
  getOpsActivity,
} from '../api/ops';
import { listAgents } from '../api/agents';
import type { Approval, Run, ActivityEvent, Agent, RunStatus } from '../types';

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                    */
/* -------------------------------------------------------------------------- */

const STATUS_TONE: Record<RunStatus, { pill: string; dot: string; label: string }> = {
  PENDING:      { pill: 'pill',      dot: 'var(--text-mute)', label: 'Pending' },
  INITIALIZING: { pill: 'pill ba',   dot: 'var(--accent)',    label: 'Initializing' },
  RUNNING:      { pill: 'pill warn', dot: 'var(--amber)',     label: 'Running' },
  PAUSED:       { pill: 'pill warn', dot: 'var(--amber)',     label: 'Paused' },
  COMPLETED:    { pill: 'pill ok',   dot: 'var(--green)',     label: 'Completed' },
  FAILED:       { pill: 'pill risk', dot: 'var(--red)',       label: 'Failed' },
  CANCELLED:    { pill: 'pill',      dot: 'var(--text-mute)', label: 'Cancelled' },
  ABORTED:      { pill: 'pill risk', dot: 'var(--red)',       label: 'Aborted' },
};

function relativeTime(iso: string): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '—';
  const diff = Date.now() - t;
  if (diff < 0) return 'in future';
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

function clockHHMM(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '--:--';
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
}

function durationOf(run: Run): string {
  const start = new Date(run.createdAt).getTime();
  const end = run.completedAt ? new Date(run.completedAt).getTime() : Date.now();
  const ms = Math.max(0, end - start);
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  if (m < 60) return `${m}m ${rs}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function approvalKindOf(reason: string | null | undefined): { label: string; pill: string } {
  const r = (reason ?? '').toLowerCase();
  if (r.includes('deploy') || r.includes('release')) return { label: 'Deployment', pill: 'pill ba' };
  if (r.includes('knowledge') || r.includes('skill') || r.includes('script'))
    return { label: 'Knowledge', pill: 'pill qa' };
  if (r.includes('write') || r.includes('delete') || r.includes('exec')) return { label: 'Action', pill: 'pill warn' };
  return { label: 'Action', pill: 'pill dev' };
}

function eventToneOf(eventType: string): string {
  const t = eventType.toLowerCase();
  if (t.includes('approval')) return 'app';
  if (t.includes('error') || t.includes('fail')) return 'blk';
  if (t.includes('agent') || t.includes('run')) return 'qa';
  if (t.includes('night') || t.includes('schedule')) return 'night';
  return '';
}

function avatarInitials(name: string): string {
  if (!name) return '··';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function avatarTone(role: string | undefined): string {
  switch ((role ?? '').toLowerCase()) {
    case 'qa':           return 'qa';
    case 'ba':           return 'ba';
    case 'orchestrator': return 'ba';
    case 'verifier':     return 'ver';
    case 'sm':           return 'sm';
    default:             return 'dev';
  }
}

/* -------------------------------------------------------------------------- */
/*  Component                                                                  */
/* -------------------------------------------------------------------------- */

export default function OpsPage() {
  const qc = useQueryClient();
  const [now, setNow] = useState<Date>(new Date());
  const [toast, setToast] = useState<{ kind: 'ok' | 'info' | 'err'; msg: string } | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  // Heartbeat for "generated at" + relative timestamps.
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(id);
  }, []);

  // Auto-dismiss toast.
  useEffect(() => {
    if (!toast) return;
    const id = setTimeout(() => setToast(null), 2_800);
    return () => clearTimeout(id);
  }, [toast]);

  /* ---------- Data ---------- */
  const pendingQ = useQuery<Approval[]>({
    queryKey: ['ops', 'approvals', 'pending'],
    queryFn: () => listApprovalsByStatus('PENDING'),
    refetchInterval: 8_000,
  });

  const runsQ = useQuery<Run[]>({
    queryKey: ['ops', 'runs'],
    queryFn: listRecentRuns,
    refetchInterval: 10_000,
  });

  const agentsQ = useQuery<Agent[]>({
    queryKey: ['ops', 'agents'],
    queryFn: listAgents,
  });

  const summaryQ = useQuery({
    queryKey: ['ops', 'summary'],
    queryFn: getOpsSummary,
    refetchInterval: 15_000,
  });

  const activityQ = useQuery<ActivityEvent[]>({
    queryKey: ['ops', 'activity'],
    queryFn: getOpsActivity,
    refetchInterval: 12_000,
  });

  const agentMap = useMemo(() => {
    const m = new Map<string, Agent>();
    (agentsQ.data ?? []).forEach((a) => m.set(a.id, a));
    return m;
  }, [agentsQ.data]);

  /* ---------- Mutations ---------- */
  const approveM = useMutation({
    mutationFn: (id: string) => approveApproval(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ops', 'approvals'] });
      setToast({ kind: 'ok', msg: 'Approval granted — agent unblocked.' });
    },
    onError: () => setToast({ kind: 'err', msg: 'Approve failed. Retry.' }),
  });

  const rejectM = useMutation({
    mutationFn: (id: string) => rejectApproval(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ops', 'approvals'] });
      setToast({ kind: 'ok', msg: 'Approval denied.' });
    },
    onError: () => setToast({ kind: 'err', msg: 'Deny failed. Retry.' }),
  });

  const onEscalate = (a: Approval) => {
    setToast({ kind: 'info', msg: `Escalated ${a.id.slice(0, 8)} — paged on-call (mock).` });
  };

  /* ---------- Derived: Activity Timeline ---------- */
  const timeline = useMemo(() => {
    const native = (activityQ.data ?? []).map((ev) => ({
      ts: ev.timestamp,
      tone: eventToneOf(ev.eventType),
      msg: `${ev.action} · ${ev.resourceType.toLowerCase()} ${ev.resourceId.slice(0, 8)}`,
      type: ev.eventType,
      details: ev.details,
      conversationId: ev.conversationId,
    }));

    if (native.length > 0) return native.slice(0, 12);

    // Fallback: derive from runs when activity feed is empty.
    return (runsQ.data ?? [])
      .slice(0, 12)
      .map((r) => ({
        ts: r.completedAt ?? r.createdAt,
        tone:
          r.status === 'COMPLETED' ? 'app' :
          r.status === 'FAILED'    ? 'blk' :
          r.status === 'RUNNING'   ? 'qa' : '',
        msg: `${agentMap.get(r.agentId)?.name ?? 'Agent'} · ${r.status.toLowerCase()}`,
        type: r.status,
      }));
  }, [activityQ.data, runsQ.data, agentMap]);

  /* ---------- Derived: Briefing ---------- */
  const briefing = useMemo(() => {
    const s = summaryQ.data;
    const runs = runsQ.data ?? [];
    const startOfDay = new Date();
    startOfDay.setHours(0, 0, 0, 0);
    const completedToday = runs.filter(
      (r) => r.status === 'COMPLETED' && r.completedAt && new Date(r.completedAt) >= startOfDay,
    ).length;
    const failedToday = runs.filter(
      (r) => r.status === 'FAILED' && r.completedAt && new Date(r.completedAt) >= startOfDay,
    ).length;
    return {
      activeAgents: s?.activeAgents ?? 0,
      runningRuns: s?.runningRuns ?? 0,
      pendingApprovals: s?.pendingApprovals ?? (pendingQ.data?.length ?? 0),
      tokens: s?.totalTokensBurned ?? 0,
      completedToday,
      failedToday,
    };
  }, [summaryQ.data, runsQ.data, pendingQ.data]);

  const briefingNarrative = useMemo(() => {
    const hr = now.getHours();
    const greeting = hr < 5 ? 'Night watch' : hr < 12 ? 'Morning' : hr < 18 ? 'Afternoon' : 'Evening';
    const tail =
      briefing.failedToday > 0
        ? `${briefing.failedToday} run${briefing.failedToday === 1 ? '' : 's'} failed — investigate.`
        : briefing.pendingApprovals > 0
          ? `${briefing.pendingApprovals} approval${briefing.pendingApprovals === 1 ? '' : 's'} awaiting your call.`
          : 'All systems nominal.';
    return `${greeting}. ${briefing.activeAgents} agent${briefing.activeAgents === 1 ? '' : 's'} on deck, ${briefing.runningRuns} active run${briefing.runningRuns === 1 ? '' : 's'}. ${tail}`;
  }, [briefing, now]);

  /* ---------- Render ---------- */
  const pending = pendingQ.data ?? [];
  const runs = (runsQ.data ?? []).slice().sort((a, b) =>
    new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  ).slice(0, 14);

  return (
    <div className="view-zone" data-view="ops" style={{ display: 'block', padding: '16px' }}>
      {/* ---------- Header ---------- */}
      <div className="view-header">
        <div>
          <h1>
            <span style={{ color: 'var(--brand-2)', marginRight: 8 }}>◈</span>
            Operations
            <span
              style={{
                marginLeft: 10, fontSize: 11, color: 'var(--text-mute)',
                letterSpacing: 1.4, textTransform: 'uppercase', fontWeight: 600,
              }}
            >
              · Command Surface
            </span>
          </h1>
          <div className="sub">
            Approval queue, run history, live activity, and morning briefing — one focused work surface.
          </div>
        </div>
        <div className="actions">
          <span
            className="badge live"
            title="Auto-refreshing every 8–15s"
            style={{ alignSelf: 'center' }}
          >
            <span className="dot" />Live
          </span>
          <button
            className="btn"
            onClick={() => {
              qc.invalidateQueries({ queryKey: ['ops'] });
              setToast({ kind: 'info', msg: 'Refreshing ops surface…' });
            }}
          >
            ↻ Refresh
          </button>
        </div>
      </div>

      {/* ---------- 2-column ops grid (wide left, narrow right) ---------- */}
      <div
        className="ops-grid-2"
        style={{ gridTemplateColumns: 'minmax(0, 1.55fr) minmax(0, 1fr)' }}
      >
        {/* =================== LEFT COLUMN =================== */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* ----- Approval Queue ----- */}
          <section className="panel" aria-labelledby="ops-approvals-h">
            <h2 id="ops-approvals-h">
              <span className="accent">⚖</span> Pending Approvals
              <span
                style={{
                  marginLeft: 'auto',
                  display: 'inline-flex', alignItems: 'center', gap: 6,
                  padding: '3px 10px', borderRadius: 999,
                  fontSize: 11, fontWeight: 700, letterSpacing: '.4px',
                  background: pending.length > 0 ? 'rgba(246,196,83,.12)' : 'rgba(54,211,153,.10)',
                  color: pending.length > 0 ? '#ffd884' : '#6fe2b6',
                  border: `1px solid ${pending.length > 0 ? 'rgba(246,196,83,.30)' : 'rgba(54,211,153,.30)'}`,
                  textTransform: 'none',
                }}
              >
                {pending.length} {pending.length === 1 ? 'item' : 'items'}
              </span>
            </h2>

            {pendingQ.isLoading && (
              <div className="body" style={{ color: 'var(--text-dim)' }}>Loading queue…</div>
            )}
            {pendingQ.isError && (
              <div className="body" style={{ color: '#ff97a3' }}>Failed to load approvals.</div>
            )}
            {!pendingQ.isLoading && pending.length === 0 && (
              <div
                className="body"
                style={{
                  color: 'var(--text-dim)', display: 'flex',
                  alignItems: 'center', gap: 10, padding: '22px 16px',
                }}
              >
                <span style={{ fontSize: 22, color: 'var(--green)' }}>✓</span>
                Inbox zero. All requests resolved.
              </div>
            )}

            {pending.length > 0 && (
              <div className="queue">
                {pending.map((a, idx) => {
                  const agent = agentMap.get(/* runId is the bridge */ a.runId);
                  const requester = agent ?? null;
                  const kind = approvalKindOf(a.reason);
                  const ageMs = Date.now() - new Date(a.requestedAt).getTime();
                  const stale = ageMs > 5 * 60_000;
                  const busy = approveM.isPending || rejectM.isPending;
                  return (
                    <div key={a.id} className={`qitem ${idx === 0 ? 'highlight' : ''}`}>
                      <div className="h">
                        <span className={kind.pill}>{kind.label}</span>
                        <span
                          style={{
                            color: stale ? '#ffd884' : 'var(--text-mute)',
                            fontSize: 11, marginLeft: 'auto',
                            fontVariantNumeric: 'tabular-nums',
                          }}
                          title={new Date(a.requestedAt).toLocaleString()}
                        >
                          {relativeTime(a.requestedAt)}
                        </span>
                      </div>

                      <div className="ttl">
                        {a.reason?.trim() || `Tool call ${a.toolCallId.slice(0, 8)} requires sign-off`}
                      </div>
                      <div className="desc" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span
                          className={`avatar ${avatarTone(requester?.role)}`}
                          style={{
                            width: 22, height: 22, borderRadius: '50%', fontSize: 10,
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            color: '#fff', fontWeight: 700,
                          }}
                          aria-hidden
                        >
                          {avatarInitials(requester?.name ?? 'AG')}
                        </span>
                        <span style={{ color: 'var(--text)' }}>{requester?.name ?? 'Unknown agent'}</span>
                        <span style={{ color: 'var(--text-mute)' }}>· run {a.runId.slice(0, 8)}</span>
                      </div>

                      <div className="row">
                        <button
                          className="btn"
                          style={{
                            flex: 1,
                            background: 'rgba(54,211,153,.10)',
                            borderColor: 'rgba(54,211,153,.45)',
                            color: '#6fe2b6',
                          }}
                          disabled={busy}
                          onClick={() => approveM.mutate(a.id)}
                        >
                          ✓ Approve
                        </button>
                        <button
                          className="btn danger"
                          style={{ flex: 1 }}
                          disabled={busy}
                          onClick={() => rejectM.mutate(a.id)}
                        >
                          ✕ Deny
                        </button>
                        <button
                          className="btn warn"
                          style={{ flex: '0 0 auto' }}
                          disabled={busy}
                          onClick={() => onEscalate(a)}
                          title="Escalate to on-call"
                        >
                          ⤴ Escalate
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          {/* ----- Run History ----- */}
          <section className="panel" aria-labelledby="ops-runs-h">
            <h2 id="ops-runs-h">
              <span className="accent">▣</span> Recent Runs
              <span
                style={{
                  marginLeft: 'auto', fontSize: 11, color: 'var(--text-mute)',
                  letterSpacing: 1, textTransform: 'none',
                }}
              >
                {runs.length} latest
              </span>
            </h2>
            {runsQ.isLoading && (
              <div className="body" style={{ color: 'var(--text-dim)' }}>Loading runs…</div>
            )}
            {!runsQ.isLoading && runs.length === 0 && (
              <div className="body" style={{ color: 'var(--text-dim)' }}>No runs yet.</div>
            )}

            {runs.length > 0 && (
              <div className="body" style={{ padding: 0 }}>
                <div role="table" style={{ display: 'flex', flexDirection: 'column', fontSize: 12 }}>
                  <div role="row" style={{ display: 'flex', ...headerCellShared }}>
                    <div role="columnheader" style={{ ...hdrCell, flex: '1.1 1 0', minWidth: 0 }}>Agent</div>
                    <div role="columnheader" style={{ ...hdrCell, flex: '2.4 1 0', minWidth: 0 }}>Prompt</div>
                    <div role="columnheader" style={{ ...hdrCell, flex: '0 0 110px' }}>Status</div>
                    <div role="columnheader" style={{ ...hdrCell, flex: '0 0 80px', textAlign: 'right' }}>Duration</div>
                    <div role="columnheader" style={{ ...hdrCell, flex: '0 0 90px', textAlign: 'right' }}>When</div>
                  </div>
                  {runs.map((r) => {
                    const agent = agentMap.get(r.agentId);
                    const tone = STATUS_TONE[r.status];
                    const isSel = selectedRunId === r.id;
                    return (
                      <div
                        role="row"
                        key={r.id}
                        onClick={() => setSelectedRunId(isSel ? null : r.id)}
                        style={{
                          display: 'flex',
                          cursor: 'pointer',
                          background: isSel ? 'rgba(91,140,255,.10)' : 'transparent',
                          borderLeft: isSel ? '2px solid var(--brand-2)' : '2px solid transparent',
                          borderBottom: '1px dashed var(--line)',
                          transition: 'background .15s ease',
                        }}
                      >
                        <div style={{ ...cellStyle, flex: '1.1 1 0', minWidth: 0 }}>
                          <span
                            className={`avatar ${avatarTone(agent?.role)}`}
                            style={avatarInlineStyle}
                            aria-hidden
                          >
                            {avatarInitials(agent?.name ?? 'AG')}
                          </span>
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {agent?.name ?? r.agentId.slice(0, 8)}
                          </span>
                        </div>
                        <div
                          style={{
                            ...cellStyle,
                            flex: '2.4 1 0',
                            minWidth: 0,
                            color: 'var(--text-dim)',
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            display: 'block',
                            lineHeight: '22px',
                          }}
                          title={r.promptSeed}
                        >
                          {r.promptSeed || <span style={{ color: 'var(--text-mute)' }}>—</span>}
                        </div>
                        <div style={{ ...cellStyle, flex: '0 0 110px' }}>
                          <span className={tone.pill} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                            <span
                              style={{
                                width: 6, height: 6, borderRadius: '50%',
                                background: tone.dot,
                                boxShadow: `0 0 8px ${tone.dot}`,
                              }}
                            />
                            {tone.label}
                          </span>
                        </div>
                        <div
                          style={{
                            ...cellStyle, flex: '0 0 80px', justifyContent: 'flex-end',
                            fontVariantNumeric: 'tabular-nums', color: 'var(--text-dim)',
                          }}
                        >
                          {durationOf(r)}
                        </div>
                        <div
                          style={{
                            ...cellStyle, flex: '0 0 90px', justifyContent: 'flex-end',
                            color: 'var(--text-mute)', fontSize: 11,
                          }}
                          title={new Date(r.createdAt).toLocaleString()}
                        >
                          {relativeTime(r.createdAt)}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </section>
        </div>

        {/* =================== RIGHT COLUMN =================== */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* ----- Activity Timeline ----- */}
          <section className="panel" aria-labelledby="ops-activity-h">
            <h2 id="ops-activity-h">
              <span className="accent">◴</span> Activity Timeline
            </h2>
            <div className="timeline">
              {activityQ.isLoading && timeline.length === 0 ? (
                <div style={{ color: 'var(--text-dim)' }}>Listening for events…</div>
              ) : timeline.length === 0 ? (
                <div style={{ color: 'var(--text-dim)' }}>Quiet on the wire.</div>
              ) : (
                <div className="tline">
                  {timeline.map((ev, i) => (
                    <div
                      key={`${ev.ts}-${i}`}
                      className={`ev ${ev.tone}`}
                    >
                      <span className="time">{clockHHMM(ev.ts)}</span>
                      <span className="msg">{ev.msg}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>

          {/* ----- Morning Briefing ----- */}
          <section className="panel" aria-labelledby="ops-brief-h" style={{ position: 'relative' }}>
            <h2 id="ops-brief-h">
              <span className="accent">☀</span> Today&apos;s Briefing
              <span
                style={{
                  marginLeft: 'auto', fontSize: 10, color: 'var(--text-mute)',
                  letterSpacing: 1.2, fontVariantNumeric: 'tabular-nums',
                }}
              >
                Generated {clockHHMM(now.toISOString())}
              </span>
            </h2>
            <div className="body" style={{ padding: '14px 16px 18px' }}>
              {/* narrative */}
              <p
                style={{
                  margin: 0, marginBottom: 14,
                  color: 'var(--text)',
                  fontSize: 13.5, lineHeight: 1.55,
                  paddingLeft: 12,
                  borderLeft: '2px solid var(--brand-2)',
                }}
              >
                {briefingNarrative}
              </p>

              {/* stat chips */}
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                  gap: 8,
                }}
              >
                <BriefStat label="Active agents" value={briefing.activeAgents} tone="brand" />
                <BriefStat label="Running" value={briefing.runningRuns} tone="amber" />
                <BriefStat label="Completed today" value={briefing.completedToday} tone="green" />
                <BriefStat label="Pending reviews" value={briefing.pendingApprovals} tone="purple" />
              </div>

              {briefing.failedToday > 0 && (
                <div
                  style={{
                    marginTop: 12, padding: '8px 10px',
                    borderRadius: 8,
                    background: 'rgba(255,107,122,.08)',
                    border: '1px solid rgba(255,107,122,.30)',
                    color: '#ff97a3', fontSize: 12,
                    display: 'flex', alignItems: 'center', gap: 8,
                  }}
                >
                  <span>⚠</span>
                  {briefing.failedToday} failed run{briefing.failedToday === 1 ? '' : 's'} need attention.
                </div>
              )}
            </div>
          </section>
        </div>
      </div>

      {/* ---------- Toast ---------- */}
      {toast && (
        <div
          role="status"
          style={{
            position: 'fixed', bottom: 24, right: 24, zIndex: 80,
            padding: '10px 14px', borderRadius: 10, fontSize: 12.5, fontWeight: 600,
            letterSpacing: '.2px',
            background:
              toast.kind === 'ok'  ? 'rgba(54,211,153,.12)' :
              toast.kind === 'err' ? 'rgba(255,107,122,.12)' :
                                     'rgba(91,140,255,.12)',
            color:
              toast.kind === 'ok'  ? '#6fe2b6' :
              toast.kind === 'err' ? '#ff97a3' :
                                     'var(--brand-2)',
            border: `1px solid ${
              toast.kind === 'ok'  ? 'rgba(54,211,153,.40)' :
              toast.kind === 'err' ? 'rgba(255,107,122,.40)' :
                                     'rgba(91,140,255,.40)'
            }`,
            boxShadow: '0 12px 28px rgba(0,0,0,.45)',
            backdropFilter: 'blur(6px)',
          }}
        >
          {toast.msg}
        </div>
      )}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/*  Small inline subcomponents                                                 */
/* -------------------------------------------------------------------------- */

function BriefStat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: 'brand' | 'amber' | 'green' | 'purple';
}) {
  const accent =
    tone === 'amber'  ? 'var(--amber)' :
    tone === 'green'  ? 'var(--green)' :
    tone === 'purple' ? '#b9a4ff' :
                        'var(--brand-2)';
  return (
    <div
      style={{
        position: 'relative', overflow: 'hidden',
        padding: '10px 12px', borderRadius: 10,
        background: 'rgba(255,255,255,.025)',
        border: '1px solid var(--line)',
      }}
    >
      <span
        style={{
          position: 'absolute', left: 0, top: 0, bottom: 0,
          width: 3, background: accent,
        }}
      />
      <div
        style={{
          fontSize: 10, color: 'var(--text-mute)',
          letterSpacing: 1, textTransform: 'uppercase',
        }}
      >
        {label}
      </div>
      <div
        style={{
          fontSize: 20, fontWeight: 700, marginTop: 2,
          color: accent, fontVariantNumeric: 'tabular-nums',
        }}
      >
        {value}
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/*  Inline shared style objects (typed)                                        */
/* -------------------------------------------------------------------------- */

const headerCellShared: React.CSSProperties = {
  borderBottom: '1px solid var(--line)',
  background: 'rgba(255,255,255,.02)',
};

const hdrCell: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 10,
  letterSpacing: 1.2,
  textTransform: 'uppercase',
  color: 'var(--text-mute)',
};

const cellStyle: React.CSSProperties = {
  padding: '10px 12px',
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  minWidth: 0,
};

const avatarInlineStyle: React.CSSProperties = {
  width: 22,
  height: 22,
  borderRadius: '50%',
  fontSize: 10,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontWeight: 700,
  flex: '0 0 auto',
};
