import { useState, useEffect, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listRuns } from '../api/runs';
import { getSummary, getRecentActivity } from '../api/dashboard';
import { listApprovals, approveApproval, rejectApproval } from '../api/approvals';
import { listAgents } from '../api/agents';
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import { ErrorNotice } from '../components/ErrorNotice';
import {
  NativePermissionFacts,
  NativePermissionKindPill,
  nativePermissionOf,
} from '../components/NativePermissionAsk';
import HousekeepingPanel from '../components/HousekeepingPanel';
import { PageHeader } from '../components/layout/PageHeader';
import { Badge } from '../components/ui/badge';
import { Card, CardContent, CardHeader } from '../components/ui/card';
import { formatClock, formatTimestamp } from '../utils/formatTime';
import { shortRunId } from '../utils/shortRunId';
import type { Approval, Run, ActivityEvent, Agent, RunStatus } from '../types';

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                    */
/* -------------------------------------------------------------------------- */

const STATUS_TONE: Record<RunStatus, { pill: string; dot: string; label: string }> = {
  PENDING:      { pill: 'pill',      dot: 'var(--text-mute)', label: 'Pending' },
  INITIALIZING: { pill: 'pill ba',   dot: 'var(--accent)',    label: 'Initializing' },
  RUNNING:      { pill: 'pill warn', dot: 'var(--amber)',     label: 'Running' },
  PAUSED:       { pill: 'pill warn', dot: 'var(--amber)',     label: 'Paused' },
  WAITING_INPUT: { pill: 'pill warn', dot: 'var(--amber)',    label: 'Waiting for your answer' },
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
  return formatClock(d);
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

/** Token colours for the legacy `pill` shim classes. */
function pillTone(shim: string): string {
  if (shim.includes('ok')) return 'border-success/30 bg-success/10 text-success';
  if (shim.includes('risk')) return 'border-destructive/30 bg-destructive/10 text-destructive';
  if (shim.includes('warn')) return 'border-warning/30 bg-warning/10 text-warning';
  if (shim.includes('qa')) return 'border-cyan-400/30 bg-cyan-400/10 text-cyan-300';
  if (shim.includes('ba') || shim.includes('dev')) return 'border-primary/30 bg-primary/10 text-primary';
  return 'border-border bg-muted text-muted-foreground';
}

const BTN_CLASS =
  'btn inline-flex h-8 items-center justify-center rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:opacity-50';
const BTN_DANGER_CLASS =
  'btn danger inline-flex h-8 items-center justify-center rounded-lg border border-destructive/40 bg-background px-2.5 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10 disabled:opacity-50';

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
  // Task 37 / Item 6: a refused decision is an authority problem with a remedy,
  // so it renders as the shared notice in the approvals panel instead of a toast
  // repeating whatever the transport said.
  const [approvalRefusal, setApprovalRefusal] = useState<
    { error: unknown; action: string } | null
  >(null);
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
    queryFn: () => listApprovals('PENDING'),
    refetchInterval: 8_000,
  });

  const runsQ = useQuery<Run[]>({
    queryKey: ['ops', 'runs'],
    queryFn: listRuns,
    refetchInterval: 10_000,
  });

  const agentsQ = useQuery<Agent[]>({
    queryKey: ['ops', 'agents'],
    queryFn: listAgents,
  });

  const summaryQ = useQuery({
    queryKey: ['ops', 'summary'],
    queryFn: getSummary,
    refetchInterval: 15_000,
  });

  const activityQ = useQuery<ActivityEvent[]>({
    queryKey: ['ops', 'activity'],
    queryFn: getRecentActivity,
    refetchInterval: 12_000,
  });

  const agentMap = useMemo(() => {
    const m = new Map<string, Agent>();
    (agentsQ.data ?? []).forEach((a) => m.set(a.id, a));
    return m;
  }, [agentsQ.data]);

  /* ---------- Mutations ---------- */
  const approveM = useMutation({
    mutationFn: (id: string) => {
      // /decide is operator-only (Task 12): carry the local session's CSRF token.
      applyOperatorHeaders();
      return approveApproval(id);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ops', 'approvals'] });
      setApprovalRefusal(null);
      setToast({ kind: 'ok', msg: 'Approval granted — agent unblocked.' });
    },
    // Task 37 / Item 6: /decide is operator-only, so a refusal has a remedy and
    // belongs in the panel, not in a toast reading "Approve failed. Retry." —
    // advice that cannot work when the route refused for want of the operator.
    onError: (err: unknown) => {
      if (isOperatorRejection(err)) {
        setApprovalRefusal({ error: err, action: 'Approving the ask' });
        return;
      }
      setToast({ kind: 'err', msg: apiErrorMessage(err, 'Approve failed.') });
    },
  });

  const rejectM = useMutation({
    mutationFn: (id: string) => {
      applyOperatorHeaders();
      return rejectApproval(id);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ops', 'approvals'] });
      setApprovalRefusal(null);
      setToast({ kind: 'ok', msg: 'Approval denied.' });
    },
    onError: (err: unknown) => {
      if (isOperatorRejection(err)) {
        setApprovalRefusal({ error: err, action: 'Denying the ask' });
        return;
      }
      setToast({ kind: 'err', msg: apiErrorMessage(err, 'Deny failed.') });
    },
  });

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
  // Post-run reviews are board work, not blocking approvals: Ops and the KPI
  // agree on the same blocking-only set.
  const pending = (pendingQ.data ?? []).filter((a) => a.askType !== 'REVIEW_REQUEST');
  const runs = (runsQ.data ?? []).slice().sort((a, b) =>
    new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  ).slice(0, 14);
  // Panels that silently degrade to empty must say so instead of showing a
  // healthy-looking surface built from missing data.
  const degradedPanels = [runsQ, agentsQ, summaryQ, activityQ]
    .filter((q) => q.isError)
    .map((q) => presentError(q.error, 'A dashboard query failed.').text);

  return (
    <div className="view-zone mx-auto w-full max-w-[1400px] p-4 sm:p-6" data-view="ops">
      {/* ---------- Header ---------- */}
      <PageHeader
        title="◈ Operations"
        description="Command Surface — approval queue, run history, live activity, and morning briefing in one focused work surface."
        actions={
          <>
            <Badge
              variant="outline"
              className="badge live gap-1.5 border-success/30 bg-success/10 text-success"
              title="Auto-refreshing every 8–15s"
            >
              <span className="dot size-1.5 rounded-full bg-success" />Live
            </Badge>
            <button
              className={BTN_CLASS}
              onClick={() => {
                qc.invalidateQueries({ queryKey: ['ops'] });
                setToast({ kind: 'info', msg: 'Refreshing ops surface…' });
              }}
            >
              ↻ Refresh
            </button>
          </>
        }
      />

      {/* ---------- Degraded panels: name what could not be loaded ---------- */}
      {degradedPanels.length > 0 && (
        <div
          className="error-state mb-3 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          role="alert"
        >
          {Array.from(new Set(degradedPanels)).join(' ')}
        </div>
      )}

      {/* ---------- Housekeeping + 2-column ops grid ---------- */}
      <HousekeepingPanel />
      <div className="ops-grid-2 grid grid-cols-1 gap-4 min-[1100px]:grid-cols-2">
        {/* =================== LEFT COLUMN =================== */}
        <div className="flex flex-col gap-4">
          {/* ----- Approval Queue ----- */}
          <section className="panel" aria-labelledby="ops-approvals-h">
            <Card className="gap-0">
              <CardHeader className="flex flex-row items-center justify-between gap-2 border-b border-border">
                <h2
                  id="ops-approvals-h"
                  className="flex flex-wrap items-center gap-2 text-sm font-semibold text-card-foreground"
                >
                  <span className="text-primary">⚖</span> Pending Approvals
                  <Badge
                    variant="outline"
                    className={
                      pending.length > 0
                        ? 'border-warning/30 bg-warning/10 text-warning'
                        : 'border-success/30 bg-success/10 text-success'
                    }
                  >
                    {pending.length} {pending.length === 1 ? 'item' : 'items'}
                  </Badge>
                </h2>
              </CardHeader>
              <CardContent className="p-0">
                {pendingQ.isLoading && (
                  <div className="px-4 py-3 text-sm text-muted-foreground">Loading queue…</div>
                )}
                {/* The refused decision, in the panel it was made from: the route is
                    operator-only, so the notice names the action and the remedy. */}
                {approvalRefusal != null && (
                  <div className="p-3">
                    <ErrorNotice
                      presented={presentError(approvalRefusal.error, 'The decision was refused.')}
                      operatorSession
                      actionLabel={approvalRefusal.action}
                    />
                  </div>
                )}
                {pendingQ.isError && (
                  <div className="p-3">
                    <ErrorNotice presented={presentError(pendingQ.error, 'Failed to load approvals.')} />
                  </div>
                )}
                {!pendingQ.isLoading && !pendingQ.isError && pending.length === 0 && (
                  <div className="flex items-center gap-2.5 px-4 py-5 text-sm text-muted-foreground">
                    <span className="text-2xl text-success">✓</span>
                    Inbox zero. All requests resolved.
                  </div>
                )}

                {pending.length > 0 && (
                  <div className="queue flex flex-col gap-2 p-3">
                    {pending.map((a, idx) => {
                      const agent = agentMap.get(/* runId is the bridge */ a.runId);
                      const requester = agent ?? null;
                      const kind = approvalKindOf(a.reason);
                      const permission = nativePermissionOf(a);
                      const ageMs = Date.now() - new Date(a.requestedAt).getTime();
                      const stale = ageMs > 5 * 60_000;
                      const busy = approveM.isPending || rejectM.isPending;
                      return (
                        <div
                          key={a.id}
                          data-approval-id={a.id}
                          className={`qitem rounded-xl border border-border bg-card p-3 text-sm ${
                            idx === 0 ? 'highlight border-warning/40' : ''
                          }`}
                        >
                          <div className="h flex items-center gap-1.5">
                            {permission ? (
                              <NativePermissionKindPill permission={permission} />
                            ) : (
                              <span className={`${kind.pill} rounded-full border px-2 py-0.5 text-xs font-medium ${pillTone(kind.pill)}`}>
                                {kind.label}
                              </span>
                            )}
                            <span
                              className={`ml-auto text-[11px] tabular-nums ${stale ? 'text-warning' : 'text-muted-foreground'}`}
                              title={formatTimestamp(a.requestedAt)}
                            >
                              {relativeTime(a.requestedAt)}
                            </span>
                          </div>

                          <div className="ttl mt-1 text-sm font-semibold text-foreground">
                            {a.reason?.trim() || (a.toolCallId ? `Tool call ${a.toolCallId.slice(0, 8)} requires sign-off` : 'Approval requires sign-off')}
                          </div>
                          <div className="desc mt-1 mb-2 flex items-center gap-2 text-[11px] text-muted-foreground">
                            <span
                              className={`avatar ${avatarTone(requester?.role)} flex size-5 items-center justify-center rounded-full bg-primary/20 text-[10px] font-bold text-primary`}
                              aria-hidden
                            >
                              {avatarInitials(requester?.name ?? 'AG')}
                            </span>
                            <span className="text-foreground">{requester?.name ?? 'Unknown agent'}</span>
                            <span className="text-muted-foreground">· run {shortRunId(a.runId)}</span>
                          </div>

                          {permission && (
                            <div className="ask-meta">
                              <NativePermissionFacts ask={a} permission={permission} />
                            </div>
                          )}

                          {a.source === 'CLARIFICATION' ? (
                            <div className="row text-xs text-muted-foreground">
                              This ask is waiting on the run - answer it on the kanban card (Answer &amp;
                              continue) or finalize the run from there.
                            </div>
                          ) : (
                            <div className="row flex gap-1.5">
                              <button
                                className="btn inline-flex h-8 flex-1 items-center justify-center rounded-lg border border-success/40 bg-success/10 px-2.5 text-sm font-medium text-success transition-colors hover:bg-success/20 disabled:opacity-50"
                                disabled={busy}
                                onClick={() => approveM.mutate(a.id)}
                              >
                                ✓ Approve
                              </button>
                              <button
                                className="btn danger inline-flex h-8 flex-1 items-center justify-center rounded-lg border border-destructive/40 bg-background px-2.5 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10 disabled:opacity-50"
                                disabled={busy}
                                onClick={() => rejectM.mutate(a.id)}
                              >
                                ✕ Deny
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </CardContent>
            </Card>
          </section>

          {/* ----- Run History ----- */}
          <section className="panel" aria-labelledby="ops-runs-h">
            <Card className="gap-0">
              <CardHeader className="flex flex-row items-center justify-between gap-2 border-b border-border">
                <h2
                  id="ops-runs-h"
                  className="flex flex-wrap items-center gap-2 text-sm font-semibold text-card-foreground"
                >
                  <span className="text-primary">▣</span> Recent Runs
                  <span className="text-[11px] font-normal text-muted-foreground">{runs.length} latest</span>
                </h2>
              </CardHeader>
              <CardContent className="p-0">
                {runsQ.isLoading && (
                  <div className="px-4 py-3 text-sm text-muted-foreground">Loading runs…</div>
                )}
                {!runsQ.isLoading && runs.length === 0 && (
                  <div className="px-4 py-3 text-sm text-muted-foreground">No runs yet.</div>
                )}

                {runs.length > 0 && (
                  <div role="table" className="flex flex-col text-xs">
                    <div role="row" className="flex border-b border-border bg-muted/50">
                      <div role="columnheader" className="min-w-0 flex-[1.1] px-3 py-2.5 text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase">Agent</div>
                      <div role="columnheader" className="min-w-0 flex-[2.4] px-3 py-2.5 text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase">Prompt</div>
                      <div role="columnheader" className="flex-[0_0_110px] px-3 py-2.5 text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase">Status</div>
                      <div role="columnheader" className="flex-[0_0_80px] px-3 py-2.5 text-right text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase">Duration</div>
                      <div role="columnheader" className="flex-[0_0_90px] px-3 py-2.5 text-right text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase">When</div>
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
                          className={`flex cursor-pointer border-b border-dashed border-border transition-colors hover:bg-muted/40 ${
                            isSel ? 'bg-primary/10' : ''
                          }`}
                        >
                          <div className="flex min-w-0 flex-[1.1] items-center gap-2 px-3 py-2.5">
                            <span
                              className={`avatar ${avatarTone(agent?.role)} flex size-5 shrink-0 items-center justify-center rounded-full bg-primary/20 text-[10px] font-bold text-primary`}
                              aria-hidden
                            >
                              {avatarInitials(agent?.name ?? 'AG')}
                            </span>
                            <span className="truncate">{agent?.name ?? r.agentId.slice(0, 8)}</span>
                          </div>
                          <div
                            className="block min-w-0 flex-[2.4] truncate px-3 py-2.5 leading-[22px] text-muted-foreground"
                            title={r.promptSeed}
                          >
                            {r.promptSeed || <span className="text-muted-foreground">—</span>}
                          </div>
                          <div className="flex flex-[0_0_110px] items-center px-3 py-2.5">
                            <span className={`${tone.pill} inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium ${pillTone(tone.pill)}`}>
                              <span className="size-1.5 rounded-full bg-current" />
                              {tone.label}
                            </span>
                          </div>
                          <div className="flex flex-[0_0_80px] items-center justify-end px-3 py-2.5 text-muted-foreground tabular-nums">
                            {durationOf(r)}
                          </div>
                          <div
                            className="flex flex-[0_0_90px] items-center justify-end px-3 py-2.5 text-[11px] text-muted-foreground"
                            title={formatTimestamp(r.createdAt)}
                          >
                            {relativeTime(r.createdAt)}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </CardContent>
            </Card>
          </section>
        </div>

        {/* =================== RIGHT COLUMN =================== */}
        <div className="flex flex-col gap-4">
          {/* ----- Activity Timeline ----- */}
          <section className="panel" aria-labelledby="ops-activity-h">
            <Card className="gap-0">
              <CardHeader className="border-b border-border">
                <h2 id="ops-activity-h" className="flex items-center gap-2 text-sm font-semibold text-card-foreground">
                  <span className="text-primary">◴</span> Activity Timeline
                </h2>
              </CardHeader>
              <CardContent>
                <div className="timeline">
                  {activityQ.isLoading && timeline.length === 0 ? (
                    <div className="text-sm text-muted-foreground">Listening for events…</div>
                  ) : timeline.length === 0 ? (
                    <div className="text-sm text-muted-foreground">Quiet on the wire.</div>
                  ) : (
                    <div className="tline relative space-y-2 border-l border-border pl-4">
                      {timeline.map((ev, i) => (
                        <div
                          key={`${ev.ts}-${i}`}
                          className={`ev ${ev.tone} relative text-sm`}
                        >
                          <span className="time mr-2 text-[10.5px] text-muted-foreground tabular-nums">{clockHHMM(ev.ts)}</span>
                          <span className="msg text-foreground">{ev.msg}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          </section>

          {/* ----- Morning Briefing ----- */}
          <section className="panel" aria-labelledby="ops-brief-h">
            <Card className="gap-0">
              <CardHeader className="flex flex-row items-center justify-between gap-2 border-b border-border">
                <h2 id="ops-brief-h" className="flex items-center gap-2 text-sm font-semibold text-card-foreground">
                  <span className="text-primary">☀</span> Today&apos;s Briefing
                </h2>
                <span className="text-[10px] tracking-[1.2px] text-muted-foreground tabular-nums">
                  Generated {clockHHMM(now.toISOString())}
                </span>
              </CardHeader>
              <CardContent className="space-y-3.5">
                <p className="m-0 border-l-2 border-primary pl-3 text-[13.5px] leading-relaxed text-foreground">
                  {briefingNarrative}
                </p>

                <div className="grid grid-cols-2 gap-2">
                  <BriefStat label="Active agents" value={briefing.activeAgents} tone="brand" />
                  <BriefStat label="Running" value={briefing.runningRuns} tone="amber" />
                  <BriefStat label="Completed today" value={briefing.completedToday} tone="green" />
                  <BriefStat label="Pending reviews" value={briefing.pendingApprovals} tone="purple" />
                </div>

                {briefing.failedToday > 0 && (
                  <div className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-2.5 py-2 text-xs text-destructive">
                    <span>⚠</span>
                    {briefing.failedToday} failed run{briefing.failedToday === 1 ? '' : 's'} need attention.
                  </div>
                )}
              </CardContent>
            </Card>
          </section>
        </div>
      </div>

      {/* ---------- Toast ---------- */}
      {toast && (
        <div
          role="status"
          className={`fixed right-6 bottom-6 z-[80] rounded-xl border px-3.5 py-2.5 text-xs font-semibold tracking-wide shadow-xl backdrop-blur ${
            toast.kind === 'ok'
              ? 'border-success/40 bg-success/10 text-success'
              : toast.kind === 'err'
                ? 'border-destructive/40 bg-destructive/10 text-destructive'
                : 'border-primary/40 bg-primary/10 text-primary'
          }`}
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
  const accentClass =
    tone === 'amber'  ? 'text-warning' :
    tone === 'green'  ? 'text-success' :
    tone === 'purple' ? 'text-primary' :
                        'text-primary';
  const barClass =
    tone === 'amber'  ? 'bg-warning' :
    tone === 'green'  ? 'bg-success' :
    tone === 'purple' ? 'bg-primary' :
                        'bg-primary';
  return (
    <div className="relative overflow-hidden rounded-lg border border-border bg-muted/40 px-3 py-2.5">
      <span className={`absolute inset-y-0 left-0 w-[3px] ${barClass}`} />
      <div className="pl-1 text-[10px] tracking-[1px] text-muted-foreground uppercase">
        {label}
      </div>
      <div className={`pl-1 text-xl font-bold tabular-nums ${accentClass}`}>
        {value}
      </div>
    </div>
  );
}
