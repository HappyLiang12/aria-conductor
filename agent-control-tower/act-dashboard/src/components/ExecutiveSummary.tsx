import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { listKanbanItems } from '../api/kanban';
import { listRuns } from '../api/runs';
import { listKnowledge } from '../api/knowledge';
import { listAgents } from '../api/agents';
import { composeRuntimes, effectiveRuntimeOf } from '../utils/runtimeComposition';
import { queryPhase, type QueryPhase } from '../utils/queryState';
import type { RunStatus } from '../types';

/** The run statuses after which no core/mode migration is ever relevant. */
const TERMINAL_RUN_STATUSES: RunStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED', 'ABORTED'];

interface StatCellProps {
  label: string;
  value: string | number;
  detail: string;
  variant?: 'default' | 'cyan' | 'amber' | 'purple' | 'red' | 'green';
  onClick?: () => void;
}

function StatCell({ label, value, detail, variant = 'default', onClick }: StatCellProps) {
  const variantClass = variant === 'default' ? '' : ` ${variant}`;
  return (
    <div
      className={`stat${variantClass}${onClick ? ' clickable' : ''}`}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={
        onClick
          ? (e) => {
              if (e.key === 'Enter') onClick();
            }
          : undefined
      }
    >
      <div className="l">{label}</div>
      <div className="v">{value}</div>
      <div className="d">{detail}</div>
    </div>
  );
}

function formatNumber(n: number | undefined): string {
  if (n === undefined || n === null) return '—';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}K`;
  return n.toLocaleString();
}

function phaseDetail(phase: QueryPhase): string | null {
  if (phase === 'loading') return 'Loading';
  if (phase === 'error') return 'Unavailable';
  if (phase === 'stale') return 'Last known data; refresh failed';
  return null;
}

export default function ExecutiveSummary() {
  const summaryQuery = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 15000,
  });

  const kanbanQuery = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 15000,
  });

  const runsQuery = useQuery({
    queryKey: ['runs-all'],
    queryFn: listRuns,
    refetchInterval: 30000,
  });

  const knowledgeQuery = useQuery({
    queryKey: ['knowledge-all'],
    queryFn: () => listKnowledge(),
    refetchInterval: 60000,
  });

  const agentsQuery = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
    refetchInterval: 30000,
  });

  const summary = summaryQuery.data;
  const kanbanItems = kanbanQuery.data;
  const runs = runsQuery.data;
  const knowledge = knowledgeQuery.data;
  const agents = agentsQuery.data;

  const summaryPhase = queryPhase(summaryQuery);
  const kanbanPhase = queryPhase(kanbanQuery);
  const runsPhase = queryPhase(runsQuery);
  const knowledgePhase = queryPhase(knowledgeQuery);
  const agentsPhase = queryPhase(agentsQuery);
  const hasAgents = agentsPhase === 'success' || agentsPhase === 'stale';

  const tasksInProgress = (kanbanItems ?? []).filter(
    (item) => item.status === 'IN_PROGRESS'
  ).length;
  const totalRuns = runs?.length ?? 0;
  const knowledgeCount = knowledge?.length ?? 0;
  const degraded = summary?.degradedAgents ?? 0;

  // Fleet runtime governance: the composition over non-retired agents, and the
  // runs still working on a runtime their agent has moved off (they drain to
  // completion or migrate at their next answer — no operator action needed).
  const liveAgentCount = (agents ?? []).filter((a) => a.healthStatus !== 'RETIRED').length;
  const composition = composeRuntimes(agents ?? []);
  const runtimeByAgentId = new Map(
    (agents ?? []).map((a) => [a.id, effectiveRuntimeOf(a)]),
  );
  const draining = (runs ?? []).filter(
    (r) =>
      !TERMINAL_RUN_STATUSES.includes(r.status) &&
      r.coreId != null &&
      r.executionMode != null &&
      // An agent absent from the list (e.g. retired) gives no comparable
      // runtime — a run frozen on a removed agent is not "draining", it has
      // nothing to migrate to; counting it would flag forever.
      runtimeByAgentId.has(r.agentId) &&
      runtimeByAgentId.get(r.agentId) !== `${r.coreId}/${r.executionMode}`,
  ).length;
  const runtimeUniform = composition.length <= 1;
  const runtimeLabel =
    !hasAgents || liveAgentCount === 0
      ? '—'
      : runtimeUniform
        ? composition[0].runtime
        : 'Mixed';
  const runtimeDetail =
    !hasAgents
      ? phaseDetail(agentsPhase) ?? 'No agents'
      : liveAgentCount === 0
        ? 'No agents'
        : runtimeUniform
          ? `${composition[0].count} agent${composition[0].count === 1 ? '' : 's'}${draining > 0 ? ` · ${draining} draining` : ''}`
          : `${composition.map((c) => `${c.runtime} x${c.count}`).join(', ')}${draining > 0 ? ` · ${draining} draining` : ''}`;

  const summaryFresh = summaryPhase === 'success' || summaryPhase === 'stale';
  const kanbanFresh = kanbanPhase === 'success' || kanbanPhase === 'stale';
  const runsFresh = runsPhase === 'success' || runsPhase === 'stale';
  const knowledgeFresh = knowledgePhase === 'success' || knowledgePhase === 'stale';


  return (
    <section className="panel" id="panel-exec">
      <h2>
        <span>Executive Summary</span>
        <span className="accent">· Live</span>
      </h2>
      <div className="summary">
        <StatCell
          label="Runtime"
          value={runtimeLabel}
          detail={runtimeDetail}
          variant={hasAgents && runtimeUniform ? 'green' : hasAgents ? 'amber' : undefined}
          onClick={() =>
            window.dispatchEvent(
              new CustomEvent('act:open-configure', { detail: { tab: 'runtime' } }),
            )
          }
        />
        <StatCell
          label="Active Agents"
          value={summaryFresh ? summary?.activeAgents ?? '—' : '—'}
          detail={phaseDetail(summaryPhase) ?? (degraded > 0 ? `Healthy · ${degraded} degraded` : 'All healthy')}
          variant={summaryFresh && degraded > 0 ? 'amber' : undefined}
        />
        <StatCell
          label="Tasks In Progress"
          value={kanbanFresh ? tasksInProgress : '—'}
          detail={phaseDetail(kanbanPhase) ?? 'Across kanban board'}
          variant="cyan"
        />
        <StatCell
          label="Total Runs"
          value={runsFresh ? totalRuns : '—'}
          detail={summaryFresh ? `${summary?.runningRuns ?? 0} running now` : phaseDetail(summaryPhase) ?? 'Running count unavailable'}
          variant="purple"
        />
        <StatCell
          label="Recorded tokens"
          value={summaryFresh ? formatNumber(summary?.totalTokensBurned) : '—'}
          detail={phaseDetail(summaryPhase) ?? 'Cumulative recorded input + output over retained records'}
          variant="green"
        />
        <StatCell
          label="Knowledge Items"
          value={knowledgeFresh ? knowledgeCount : '—'}
          detail={phaseDetail(knowledgePhase) ?? 'Indexed assets'}
        />
      </div>
    </section>
  );
}
