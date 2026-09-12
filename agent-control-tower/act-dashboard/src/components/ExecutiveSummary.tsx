import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { listKanbanItems } from '../api/kanban';
import { listRuns } from '../api/runs';
import { listKnowledge } from '../api/knowledge';
import { dispatchOpenTaskDrawer } from './DrawerContext';
import type { KanbanItem } from '../types';

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

  const tasksInProgress = (kanbanItems ?? []).filter(
    (item) => item.status === 'IN_PROGRESS'
  ).length;
  const totalRuns = runs?.length ?? 0;
  const knowledgeCount = knowledge?.length ?? 0;

  // Single HITL signal: every ask waiting on the operator, on cards in ANY
  // column (mid-run gate asks included); the click opens the first card
  // that has pending asks.
  const cardsWithAsks = (kanbanItems ?? []).filter((i) => (i.pendingAskCount ?? 0) > 0);
  const waitingAsks = cardsWithAsks.reduce((sum, i) => sum + (i.pendingAskCount ?? 0), 0);
  const firstCardWithAsks: KanbanItem | undefined = cardsWithAsks[0];

  return (
    <section className="panel" id="panel-exec">
      <h2>
        <span>Executive Summary</span>
        <span className="accent">· Live</span>
      </h2>
      <div className="summary">
        <StatCell
          label="Active Agents"
          value={summary?.activeAgents ?? '—'}
          detail="Healthy & responsive"
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
