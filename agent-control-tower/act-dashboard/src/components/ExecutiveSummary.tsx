import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { listKanbanItems } from '../api/kanban';
import { listRuns } from '../api/runs';
import { listKnowledge } from '../api/knowledge';
import { listAgents } from '../api/agents';
import { dispatchOpenTaskDrawer } from './DrawerContext';
import { composeRuntimes, effectiveRuntimeOf } from '../utils/runtimeComposition';
import type { KanbanItem, RunStatus } from '../types';

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

export default function ExecutiveSummary() {
  const { data: summary } = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 15000,
  });

  const { data: kanbanItems } = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 15000,
  });

  const { data: runs } = useQuery({
    queryKey: ['runs-all'],
    queryFn: listRuns,
    refetchInterval: 30000,
  });

  const { data: knowledge } = useQuery({
    queryKey: ['knowledge-all'],
    queryFn: () => listKnowledge(),
    refetchInterval: 60000,
  });

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
    refetchInterval: 30000,
  });

  const tasksInProgress = (kanbanItems ?? []).filter(
    (item) => item.status === 'IN_PROGRESS'
  ).length;
  const totalRuns = runs?.length ?? 0;
  const knowledgeCount = knowledge?.length ?? 0;
  const degraded = summary?.degradedAgents ?? 0;

  // Single HITL signal: every ask waiting on the operator, on cards in ANY
  // column (mid-run gate asks included); the click opens the first card
  // that has pending asks.
  const cardsWithAsks = (kanbanItems ?? []).filter((i) => (i.pendingAskCount ?? 0) > 0);
  const waitingAsks = cardsWithAsks.reduce((sum, i) => sum + (i.pendingAskCount ?? 0), 0);
  const firstCardWithAsks: KanbanItem | undefined = cardsWithAsks[0];

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
      runtimeByAgentId.get(r.agentId) !== `${r.coreId}/${r.executionMode}`,
  ).length;
  const runtimeUniform = composition.length <= 1;
  const runtimeLabel =
    liveAgentCount === 0
      ? '—'
      : runtimeUniform
        ? composition[0].runtime
        : 'Mixed';
  const runtimeDetail =
    liveAgentCount === 0
      ? 'No agents'
      : runtimeUniform
        ? `${composition[0].count} agent${composition[0].count === 1 ? '' : 's'}${draining > 0 ? ` · ${draining} draining` : ''}`
        : `${composition.map((c) => `${c.runtime} x${c.count}`).join(', ')}${draining > 0 ? ` · ${draining} draining` : ''}`;


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
          variant={runtimeUniform ? 'green' : 'amber'}
          onClick={() =>
            window.dispatchEvent(
              new CustomEvent('act:open-configure', { detail: { tab: 'runtime' } }),
            )
          }
        />
        <StatCell
          label="Active Agents"
          value={summary?.activeAgents ?? '—'}
          detail={degraded > 0 ? `Healthy · ${degraded} degraded` : 'All healthy'}
          variant={degraded > 0 ? 'amber' : undefined}
        />
        <StatCell
          label="Waiting on you"
          value={waitingAsks}
          detail={waitingAsks > 0 ? 'Cards need a decision' : 'Nothing pending'}
          variant={waitingAsks > 0 ? 'amber' : undefined}
          onClick={firstCardWithAsks ? () => dispatchOpenTaskDrawer(firstCardWithAsks.id) : undefined}
        />
        <StatCell
          label="Tasks In Progress"
          value={tasksInProgress}
          detail="Across kanban board"
          variant="cyan"
        />
        <StatCell
          label="Total Runs"
          value={totalRuns}
          detail={`${summary?.runningRuns ?? 0} running now`}
          variant="purple"
        />
        <StatCell
          label="Tokens Used"
          value={formatNumber(summary?.totalTokensBurned)}
          detail="Cumulative spend"
          variant="green"
        />
        <StatCell
          label="Knowledge Items"
          value={knowledgeCount}
          detail="Indexed assets"
        />
      </div>
    </section>
  );
}
