import { useEffect, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { listKanbanItems } from '../api/kanban';
import { useWebSocketContext } from './Layout';
import { formatTimestamp } from '../utils/formatTime';
import { isKanbanEvent, isRunLifecycleEvent } from '../utils/wsEvents';
import { reviewNeedsHuman } from '../utils/reviewState';
import { Card, CardContent } from './ui/card';

function getTimeGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Morning Briefing';
  if (hour < 17) return 'Afternoon Briefing';
  return 'Evening Briefing';
}

function formatNow(): string {
  return formatTimestamp(new Date().toISOString());
}

export default function MorningBriefing() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();

  // S5: react instantly to kanban/run lifecycle events (whitelist — run.progress
  // excluded to avoid invalidation storms).
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (isKanbanEvent(t)) {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    }
    if (isRunLifecycleEvent(t)) {
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    }
  }, [lastMessage, queryClient]);
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
      // Review cards are the waiting-on-review signal; BLOCKED was retired
      // (V52 migration), so counting it always yielded 0. F24: only a REVIEW
      // card with an open ask is waiting on the operator — a finished card
      // parked in REVIEW must not inflate the signal.
      review: items.filter(reviewNeedsHuman).length,
      inProgress: items.filter((i) => i.status === 'IN_PROGRESS').length,
    };
  }, [kanbanItems]);

  const generatedAt = formatNow();
  const running = summary?.runningRuns ?? 0;

  return (
    <section className="flex flex-col gap-2" id="panel-briefing">
      <h2 className="flex items-baseline gap-2 text-sm font-semibold text-foreground">
        <span>{getTimeGreeting()}</span>
        <span className="text-xs font-normal text-muted-foreground">· Auto-generated</span>
      </h2>
      <Card>
        <CardContent className="space-y-2 text-xs leading-relaxed text-muted-foreground">
          <div className="text-sm font-semibold text-card-foreground">
            Snapshot · {generatedAt}
          </div>
          <ul className="m-0 list-disc space-y-1 pl-5">
            {/* The four counts come from two unrelated sources — kanban card columns
                and run status — so each label names its own source; the wording must
                not imply that "in progress" cards and "running" runs are one set. */}
            <li>
              <b className="text-emerald-700 dark:text-emerald-400">{stats.done} cards done</b>
            </li>
            {/* Single HITL signal (D7): only the Review cards count; the
                pendingApprovals item was removed to avoid a second signal. */}
            <li>
              <b className="text-rose-700 dark:text-rose-400">{stats.review} cards in review</b>
            </li>
            <li>
              <b className="text-primary">{running} runs running</b>
            </li>
            <li>
              <b className="text-foreground">{stats.inProgress} cards in progress</b>
            </li>
          </ul>
          <div className="text-[11px] text-muted-foreground">
            Generated at {generatedAt}
          </div>
        </CardContent>
      </Card>
    </section>
  );
}
