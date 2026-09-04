import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { listKanbanItems } from '../api/kanban';
import { listRuns } from '../api/runs';
import { listKnowledge } from '../api/knowledge';

interface StatCellProps {
  label: string;
  value: string | number;
  detail: string;
  variant?: 'default' | 'cyan' | 'amber' | 'purple' | 'red' | 'green';
}

function StatCell({ label, value, detail, variant = 'default' }: StatCellProps) {
  const variantClass = variant === 'default' ? '' : ` ${variant}`;
  return (
    <div className={`stat${variantClass}`}>
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
          label="Pending Approvals"
          value={summary?.pendingApprovals ?? '—'}
          detail="Awaiting human review"
          variant="amber"
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
