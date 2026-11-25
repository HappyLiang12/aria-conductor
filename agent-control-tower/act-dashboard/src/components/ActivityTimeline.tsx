import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getRecentActivity } from '../api/dashboard';
import { useWebSocketContext } from './Layout';
import { formatTimestamp } from '../utils/formatTime';
import { Card, CardContent } from './ui/card';
import type { ActivityEvent } from '../types';

function eventVariant(resourceType: string, action: string): string {
  const r = resourceType.toLowerCase();
  const a = action.toLowerCase();
  if (r === 'approval' || a.includes('approve')) return 'app';
  if (r === 'run' && (a.includes('fail') || a.includes('block'))) return 'blk';
  if (r === 'knowledge') return 'qa';
  if (a.includes('night') || a.includes('overnight')) return 'night';
  if (a.includes('block') || a.includes('fail')) return 'blk';
  return '';
}

function formatTime(iso: string): string {
  return formatTimestamp(iso);
}

function describe(ev: ActivityEvent): string {
  const subject = ev.resourceType
    ? ev.resourceType.charAt(0).toUpperCase() + ev.resourceType.slice(1)
    : 'Event';
  const id = ev.resourceId ? ` ${ev.resourceId.slice(0, 8)}` : '';
  const action = ev.action ? ev.action.replace(/_/g, ' ') : 'updated';
  const extra = ev.details ? ` — ${ev.details.slice(0, 60)}` : '';
  return `${subject}${id} · ${action}${extra}`;
}

export default function ActivityTimeline() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();

  // S5: relevant WS events refresh the audit feed instantly (polling stays as
  // fallback). High-frequency stream events (run.progress / housekeeping.
  // progress) are NOT persisted, so refetching on them would be a pure storm.
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (t === 'run.progress' || t === 'housekeeping.progress') return;
    queryClient.invalidateQueries({ queryKey: ['dashboard-activity'] });
  }, [lastMessage, queryClient]);
  const { data: events, isLoading, error } = useQuery({
    queryKey: ['dashboard-activity'],
    queryFn: getRecentActivity,
    refetchInterval: 15000,
  });

  const recent: ActivityEvent[] = (events ?? []).slice(0, 10);

  return (
    <section className="flex flex-col gap-2" id="panel-timeline">
      <h2 className="flex items-baseline gap-2 text-sm font-semibold text-foreground">
        <span>Activity Timeline</span>
        <span className="text-xs font-normal text-muted-foreground">· Recent</span>
      </h2>
      <Card>
        <CardContent>
          {isLoading && (
            <div className="px-1 py-2 text-xs text-muted-foreground">Loading activity…</div>
          )}
          {error && (
            <div className="px-1 py-2 text-xs text-destructive">Failed to load activity.</div>
          )}
          {!isLoading && recent.length === 0 && (
            <div className="px-1 py-2 text-xs text-muted-foreground">No events yet — system is quiet.</div>
          )}
          {recent.map((ev, idx) => {
            const variant = eventVariant(ev.resourceType, ev.action);
            return (
              <div
                key={`${ev.timestamp}-${idx}`}
                className={`ev${variant ? ` ${variant}` : ''} flex gap-3 border-b border-border/60 py-1.5 last:border-0`}
                title={ev.conversationId ? `Conv: ${ev.conversationId.slice(0, 8)}…` : undefined}
              >
                <div className="time w-28 shrink-0 text-[11px] text-muted-foreground tabular-nums">
                  {formatTime(ev.timestamp)}
                </div>
                <div className="msg min-w-0 text-xs break-words text-card-foreground">
                  {describe(ev)}
                </div>
              </div>
            );
          })}
        </CardContent>
      </Card>
    </section>
  );
}
