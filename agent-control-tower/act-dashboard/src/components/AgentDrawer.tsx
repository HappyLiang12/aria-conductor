import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAgent } from '../api/agents';
import { listRuns, pauseRun, resumeRun, cancelRun, injectRunMessage } from '../api/runs';
import { useDrawerContext } from './DrawerContext';
import { useWebSocketContext } from './Layout';
import { eventLabel } from '../utils/eventLabels';
import type { Agent, AgentHealthStatus, Run } from '../types';

/* -------------------------------------------------------------------------- */
/*  Types & helpers                                                           */
/* -------------------------------------------------------------------------- */

interface StreamLine {
  id: string;
  ts: string;
  tag: 'read' | 'edit' | 'run' | 'ok' | 'warn' | 'err' | 'think' | 'tool_call' | 'tool_result';
  msg: string;
  detail?: {
    toolName?: string;
    toolArgs?: string;
    toolResult?: string;
    thinking?: string;
    skills?: string[];
  };
}

interface FileTouch {
  op: 'read' | 'edit' | 'add';
  path: string;
  delta?: string;
}

const HEALTH_LABEL: Record<AgentHealthStatus, string> = {
  HEALTHY: 'Online',
  DEGRADED: 'Degraded',
  UNHEALTHY: 'Offline',
  RETIRED: 'Retired',
};

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 0 || !parts[0]) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

function fmtTime(d: string | Date): string {
  const dt = typeof d === 'string' ? new Date(d) : d;
  return dt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function pickAgentRuns(runs: Run[] | undefined, agentId: string | null): Run[] {
  if (!runs || !agentId) return [];
  return runs.filter((r) => r.agentId === agentId).slice(0, 5);
}

function clampPercent(n: number): number {
  return Math.max(0, Math.min(100, Math.round(n)));
}

/* -------------------------------------------------------------------------- */
/*  AgentDrawer                                                               */
/* -------------------------------------------------------------------------- */

export function AgentDrawer() {
  const { state, closeAgentDrawer } = useDrawerContext();
  const { open, agentId } = state.agentDrawer;
  const { lastMessage } = useWebSocketContext();
  const queryClient = useQueryClient();

  const [order, setOrder] = useState('');
  const [stream, setStream] = useState<StreamLine[]>([]);

  const agentQuery = useQuery<Agent>({
    queryKey: ['agents', 'detail', agentId],
    queryFn: () => getAgent(agentId as string),
    enabled: open && Boolean(agentId),
    retry: false,
  });

  const runsQuery = useQuery({
    queryKey: ['runs', 'forAgent', agentId],
    queryFn: () => listRuns(),
    enabled: open && Boolean(agentId),
    refetchInterval: open ? 8000 : false,
  });

  const agent = agentQuery.data;
  const agentRuns = useMemo(
    () => pickAgentRuns(runsQuery.data, agentId),
    [runsQuery.data, agentId]
  );
  const activeRun = agentRuns.find((r) => r.status === 'RUNNING') ?? agentRuns[0];

  // Seed stream when drawer opens / agent changes.
  useEffect(() => {
    if (!open || !agent) return;
    setStream([
      {
        id: `seed-${agent.id}`,
        ts: fmtTime(new Date()),
        tag: 'think',
        msg: `Connected to ${agent.name} · ${agent.role || agent.agentType}`,
      },
    ]);
  }, [open, agent?.id, agent?.name, agent?.role, agent?.agentType, agent]);

  // Fold WS events into the live stream + invalidate queries for real-time updates.
  useEffect(() => {
    if (!open || !agentId || !lastMessage) return;
    const payload = lastMessage.payload ?? {};
    const matchAgent =
      payload.agentId === agentId ||
      payload['agent_id'] === agentId ||
      payload.resourceId === agentId;
    if (!matchAgent && lastMessage.type !== 'agent.heartbeat') return;

    // Invalidate runs query when run events arrive (to pick up finalOutput, status changes)
    if (lastMessage.type.startsWith('run.')) {
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    }

    const tag: StreamLine['tag'] =
      lastMessage.type.includes('error') || lastMessage.type.includes('fail')
        ? 'err'
        : lastMessage.type.includes('warn')
        ? 'warn'
        : lastMessage.type.includes('tool')
        ? 'run'
        : lastMessage.type.includes('complete')
        ? 'ok'
        : lastMessage.type.includes('iteration')
        ? 'run'
        : 'think';

    // Build a more descriptive message with enhanced observability
    let detail = '';
    const lineDetail: StreamLine['detail'] = {};
    if (lastMessage.type === 'run.iteration') {
      const thinking = payload.thinking as string | undefined;
      const toolCalls = payload.toolCalls as Array<{name:string;arguments:string;result:string}> | undefined;
      const skills = payload.skills as string[] | undefined;
      detail = `iter ${payload.iteration}/${payload.maxIterations}`;
      if (thinking) lineDetail.thinking = thinking.length > 200 ? thinking.slice(0,200)+'...' : thinking;
      if (toolCalls?.length) {
        const tc = toolCalls[0];
        lineDetail.toolName = tc.name;
        lineDetail.toolArgs = tc.arguments?.length > 100 ? tc.arguments.slice(0,100)+'...' : tc.arguments;
        lineDetail.toolResult = tc.result?.length > 200 ? tc.result.slice(0,200)+'...' : tc.result;
        detail += ` · ${tc.name}`;
      }
      if (skills?.length) lineDetail.skills = skills;
    } else if (lastMessage.type === 'run.completed') {
      detail = `status: ${payload.status}`;
      if (payload.finalOutput) {
        const out = String(payload.finalOutput);
        detail += ` — ${out.length > 80 ? out.slice(0, 80) + '...' : out}`;
      }
    } else if (lastMessage.type === 'run.started') {
      detail = 'run started';
    } else if (typeof payload.action === 'string') {
      detail = payload.action;
    }

    const ln: StreamLine = {
      id: `${lastMessage.timestamp}-${Math.random().toString(36).slice(2, 6)}`,
      ts: fmtTime(lastMessage.timestamp || new Date()),
      tag,
      msg: `${eventLabel(lastMessage.type)}${detail ? ' · ' + detail : ''}`,
      detail: lineDetail,
    };
    setStream((prev) => [...prev.slice(-49), ln]);
  }, [lastMessage, open, agentId, queryClient]);

  // Derived resources.
  const totalTokens = agentRuns.reduce((sum, r) => sum + (r.totalTokensUsed || 0), 0);
  const tokenCap = 500_000;
  const tokenPct = clampPercent((totalTokens / tokenCap) * 100);
  const ctxUsed = activeRun ? Math.min(activeRun.iterationCount * 2_500, 200_000) : 0;
  const ctxPct = clampPercent((ctxUsed / 200_000) * 100);
  const runtimeMin = activeRun
    ? Math.max(
        0,
        Math.round((Date.now() - new Date(activeRun.createdAt).getTime()) / 60_000)
      )
    : 0;

  // Agent configuration display (replaces hardcoded mock data)
  const agentConfig = useMemo(() => {
    if (!agent?.config) return null;
    if (typeof agent.config === 'object') return agent.config as Record<string, unknown>;
    return null;
  }, [agent?.config]);

  // Files touched — now shows tool usage from active run

  const submitOrder = async () => {
    if (!order.trim() || !agent || !activeRun) return;
    const instruction = order.trim();
    setOrder('');

    // Optimistic local feedback
    const pendingLn: StreamLine = {
      id: `cmd-${Date.now()}`,
      ts: fmtTime(new Date()),
      tag: 'edit',
      msg: `> Sending order: ${instruction}`,
    };
    setStream((prev) => [...prev.slice(-49), pendingLn]);

    try {
      const result = await injectRunMessage(activeRun.id, instruction);
      const okLn: StreamLine = {
        id: `cmd-ok-${Date.now()}`,
        ts: fmtTime(new Date()),
        tag: 'ok',
        msg: `\u2713 Order accepted (id: ${result.id.slice(0, 8)}, turn: ${result.turnNumber})`,
      };
      setStream((prev) => [...prev.slice(-49), okLn]);
    } catch (err: unknown) {
      const anyErr = err as { message?: string };
      const errLn: StreamLine = {
        id: `cmd-err-${Date.now()}`,
        ts: fmtTime(new Date()),
        tag: 'err',
        msg: `\u2717 Order failed: ${anyErr?.message ?? 'unknown error'}`,
      };
      setStream((prev) => [...prev.slice(-49), errLn]);
    }
  };

  const onOrderKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      submitOrder();
    }
  };

  return (
    <>
      {open && (
        <div
          className="agent-drawer-backdrop"
          onClick={closeAgentDrawer}
          aria-hidden="true"
          role="presentation"
        />
      )}
      <aside
        className={`agent-drawer${open ? ' open' : ''}`}
        aria-hidden={!open}
        aria-label="Agent live drawer"
      >
        <header>
          <div className="avatar" style={{ width: 38, height: 38 }}>
            {agent ? initials(agent.name) : '··'}
          </div>
          <div className="who">
            <div className="role">
              {agent
                ? `${agent.role || agent.agentType} · ${agent.model || '—'}`
                : agentQuery.isLoading
                ? 'Loading…'
                : 'Select an agent'}
            </div>
            <h3>{agent?.name ?? '—'}</h3>
          </div>
          {agent && (
            <span className="live-pill">
              <span className="dot" />
              <span>{HEALTH_LABEL[agent.healthStatus]}</span>
            </span>
          )}
          <div className="close" onClick={closeAgentDrawer} role="button" aria-label="Close">
            ✕
          </div>
        </header>

        <div className="ad-body">
          {agentQuery.isLoading && (
            <div className="loading-spinner" style={{ padding: 20 }}>
              <div className="spinner" />
              <span>Loading agent…</span>
            </div>
          )}

          {agentQuery.isError && !agentQuery.isLoading && (
            <div className="evidence-error">Failed to load agent.</div>
          )}

          {agent && (
            <>
              {/* Now-task / active run */}
              {activeRun ? (
                <div className="now-task">
                  <div className="lbl">Active Run</div>
                  <div className="ttl">
                    {activeRun.promptSeed?.slice(0, 80) || activeRun.id}
                  </div>
                  <div className="meta">
                    <span className="pill dev">{activeRun.status}</span>
                    <span>
                      Iter {activeRun.iterationCount} / {activeRun.maxIterations}
                    </span>
                    <span style={{ marginLeft: 'auto' }}>{runtimeMin}m elapsed</span>
                  </div>
                  <div className="progress shimmer">
                    <i
                      style={{
                        width: `${clampPercent(
                          (activeRun.iterationCount / Math.max(activeRun.maxIterations, 1)) * 100
                        )}%`,
                      }}
                    />
                  </div>
                </div>
              ) : (
                <div className="now-task" style={{ opacity: 0.7 }}>
                  <div className="lbl">Status</div>
                  <div className="ttl">Idle — awaiting work</div>
                </div>
              )}

              {/* Run Result (shown when latest run completed with output) */}
              {activeRun?.status === 'COMPLETED' && activeRun.finalOutput && (
                <>
                  <div className="section-h" style={{ color: '#4dd88a' }}>Run Result</div>
                  <div
                    className="evidence-body"
                    style={{
                      background: 'rgba(77,216,138,.06)',
                      border: '1px solid rgba(77,216,138,.2)',
                      borderRadius: 8,
                      padding: '12px 14px',
                      marginBottom: 12,
                      whiteSpace: 'pre-wrap',
                      fontSize: 13,
                      lineHeight: 1.55,
                      maxHeight: 300,
                      overflowY: 'auto',
                    }}
                  >
                    {activeRun.finalOutput}
                  </div>
                </>
              )}
              {activeRun?.status === 'FAILED' && activeRun.errorMessage && (
                <>
                  <div className="section-h" style={{ color: '#ff6b7a' }}>Run Failed</div>
                  <div
                    className="evidence-error"
                    style={{
                      background: 'rgba(255,107,122,.06)',
                      border: '1px solid rgba(255,107,122,.2)',
                      borderRadius: 8,
                      padding: '12px 14px',
                      marginBottom: 12,
                    }}
                  >
                    {activeRun.errorMessage}
                  </div>
                </>
              )}

              {/* Live activity stream */}
              <div className="section-h">Live Activity Stream</div>
              <div className="stream">
                {stream.length === 0 ? (
                  <div className="ln">
                    <span className="ts">{fmtTime(new Date())}</span>
                    <span className="tag think">idle</span>
                    <span className="msg caret" />
                  </div>
                ) : (
                  stream.map((ln, idx) => (
                    <div
                      key={ln.id}
                      className={`ln${idx === stream.length - 1 ? ' new' : ''}`}
                    >
                      <span className="ts">{ln.ts}</span>
                      <span className={`tag ${ln.tag}`}>{ln.tag.toUpperCase()}</span>
                      <span className="msg">{ln.msg}</span>
                    </div>
                  ))
                )}
              </div>

              {/* Resource grid */}
              <div className="section-h">Resources</div>
              <div className="resgrid">
                <div className={`res${tokenPct > 85 ? ' red' : tokenPct > 60 ? ' amber' : ''}`}>
                  <div className="l">Tokens</div>
                  <div className="v">{totalTokens.toLocaleString()}</div>
                  <div className="sub">cap {tokenCap.toLocaleString()}</div>
                  <div className="bar">
                    <i style={{ width: `${tokenPct}%` }} />
                  </div>
                </div>
                <div className="res">
                  <div className="l">Context</div>
                  <div className="v">{Math.round(ctxUsed / 1000)}k / 200k</div>
                  <div className="sub">{ctxPct}% used</div>
                  <div className="bar">
                    <i style={{ width: `${ctxPct}%` }} />
                  </div>
                </div>
                <div className="res green">
                  <div className="l">Active runs</div>
                  <div className="v">{agentRuns.filter((r) => r.status === 'RUNNING').length}</div>
                  <div className="sub">total {agentRuns.length}</div>
                  <div className="bar">
                    <i style={{ width: `${clampPercent(agentRuns.length * 20)}%` }} />
                  </div>
                </div>
                <div className="res amber">
                  <div className="l">Runtime</div>
                  <div className="v">{runtimeMin}m</div>
                  <div className="sub">since current run start</div>
                  <div className="bar">
                    <i style={{ width: `${clampPercent(runtimeMin * 5)}%` }} />
                  </div>
                </div>
              </div>

              {/* Assigned Skills */}
              <div className="section-h">Assigned Skills</div>
              <div className="files">
                {agent.skills?.length ? agent.skills.map(s => (
                  <div key={s} className="file">
                    <span className="op read">SKILL</span>
                    <span>{s}</span>
                  </div>
                )) : (
                  <div className="file">
                    <span className="op read">--</span>
                    <span>No skills assigned</span>
                  </div>
                )}
              </div>

              {/* Assigned Tools */}
              <div className="section-h">Assigned Tools</div>
              <div className="files">
                {agent.tools?.length ? agent.tools.map(t => (
                  <div key={t} className="file">
                    <span className="op edit">TOOL</span>
                    <span>{t}</span>
                  </div>
                )) : (
                  <div className="file">
                    <span className="op read">--</span>
                    <span>No tools assigned</span>
                  </div>
                )}
              </div>

              {/* Config (collapsed) */}
              <details style={{marginTop: 12}}>
                <summary className="section-h" style={{display:'inline',cursor:'pointer'}}>Config</summary>
                <div className="files" style={{marginTop: 8}}>
                  <div className="file">
                    <span className="op read">CFG</span>
                    <span>maxToolCallRounds</span>
                    <span className="delta">{String(agentConfig?.maxToolCallRounds ?? '—')}</span>
                  </div>
                  {agentConfig && Object.entries(agentConfig)
                    .filter(([k]) => k !== 'maxToolCallRounds' && k !== 'systemPrompt')
                    .map(([k, v]) => (
                      <div key={k} className="file">
                        <span className="op read">CFG</span>
                        <span style={{overflow:'hidden',textOverflow:'ellipsis'}}>{k}</span>
                        <span className="delta">{String(v)}</span>
                      </div>
                    ))}
                </div>
              </details>

              {/* Order console */}
              <div className="section-h">
                Order Console
                <span style={{ float: 'right', color: 'var(--text-mute)', fontSize: 10 }}>
                  ⌘ + ↵ to send
                </span>
              </div>
              <div className="order-console">
                <div className="oc-h">
                  ▶ Send instruction to <span style={{ color: '#fff', marginLeft: 4 }}>{agent.name}</span>
                </div>
                <div className="chips">
                  <span
                    className="chip"
                    onClick={() => setOrder('Focus on the highest-priority task in the queue.')}
                  >
                    🎯 Focus highest priority
                  </span>
                  <span
                    className="chip"
                    onClick={() => setOrder('Submit current evidence and request review.')}
                  >
                    🔁 Submit when ready
                  </span>
                  <span className="chip warn" onClick={() => setOrder('Pause and wait for review.')}>
                    ⏸ Pause &amp; wait
                  </span>
                  <span className="chip danger" onClick={() => setOrder('Stop work immediately and report state.')}>
                    ⛔ Stop run
                  </span>
                </div>
                <div className="order-row">
                  <textarea
                    aria-label="Order instruction"
                    placeholder="Type a clear instruction…"
                    value={order}
                    onChange={(e) => setOrder(e.target.value)}
                    onKeyDown={onOrderKeyDown}
                  />
                  <span className="hint">~{Math.max(8, order.length / 4) | 0} tok</span>
                  <button className="send" onClick={submitOrder} disabled={!order.trim() || !activeRun}>
                    Send ▶
                  </button>
                </div>
              </div>

              {/* Knowledge Space — no entries yet */}
            </>
          )}
        </div>

        <footer>
          <button className="btn warn" disabled={!activeRun || activeRun.status !== 'RUNNING'}
            onClick={() => activeRun && pauseRun(activeRun.id).then(() => queryClient.invalidateQueries({ queryKey: ['runs'] }))}>⏸ Pause</button>
          <button className="btn primary" disabled={!activeRun || activeRun.status !== 'PAUSED'}
            onClick={() => activeRun && resumeRun(activeRun.id).then(() => queryClient.invalidateQueries({ queryKey: ['runs'] }))}>▶ Resume</button>
          <button className="btn danger" disabled={!activeRun || (activeRun.status !== 'RUNNING' && activeRun.status !== 'PAUSED')}
            onClick={() => activeRun && cancelRun(activeRun.id).then(() => queryClient.invalidateQueries({ queryKey: ['runs'] }))}>⛔ Stop</button>
          <button className="btn" onClick={closeAgentDrawer}>Close</button>
        </footer>
      </aside>
    </>
  );
}

export default AgentDrawer;
