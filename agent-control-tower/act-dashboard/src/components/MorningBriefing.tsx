import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { listKanbanItems } from '../api/kanban';

function getTimeGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Morning Briefing';
  if (hour < 17) return 'Afternoon Briefing';
  return 'Evening Briefing';
}

function formatNow(): string {
  return new Date().toLocaleString([], {
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export default function MorningBriefing() {
  const { data: summary } = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 30000,
  });

  const { data: kanbanItems } = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 30000,
  });

  const stats = useMemo(() => {
    const items = kanbanItems ?? [];
    return {
      done: items.filter((i) => i.status === 'DONE').length,
      blocked: items.filter((i) => i.status === 'BLOCKED').length,
      inProgress: items.filter((i) => i.status === 'IN_PROGRESS').length,
    };
  }, [kanbanItems]);

  const generatedAt = formatNow();
  const pending = summary?.pendingApprovals ?? 0;
  const running = summary?.runningRuns ?? 0;

  return (
    <section className="panel" id="panel-briefing">
      <h2>
        <span>{getTimeGreeting()}</span>
        <span className="accent">· Auto-generated</span>
      </h2>
      <div
        className="body"
        style={{ fontSize: 12, color: 'var(--text-dim)', lineHeight: 1.55 }}
      >
        <div style={{ color: 'var(--text)', fontWeight: 600, marginBottom: 6 }}>
          Snapshot · {generatedAt}
        </div>
        <ul style={{ margin: 0, paddingLeft: 18 }}>
          <li>
            <b style={{ color: '#6fe2b6' }}>{stats.done} task{stats.done === 1 ? '' : 's'} completed</b>{' '}
            on the kanban board
          </li>
          <li>
            <b style={{ color: '#ffd884' }}>{pending} item{pending === 1 ? '' : 's'}</b> queued for approval
          </li>
          <li>
            <b style={{ color: '#ff97a3' }}>{stats.blocked} blocker{stats.blocked === 1 ? '' : 's'}</b> waiting on review
          </li>
          <li>
            <b style={{ color: 'var(--brand-2)' }}>{running} run{running === 1 ? '' : 's'}</b> active right now
          </li>
          <li>
            <b style={{ color: 'var(--text)' }}>{stats.inProgress}</b> task{stats.inProgress === 1 ? '' : 's'} in flight
          </li>
        </ul>
        <div style={{ marginTop: 10, fontSize: 11, color: 'var(--text-mute)' }}>
          Generated at {generatedAt}
        </div>
      </div>
    </section>
  );
}
