import { useQuery } from '@tanstack/react-query';
import { getRecentActivity } from '../api/dashboard';
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
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '--:--';
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
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
  const { data: events, isLoading, error } = useQuery({
    queryKey: ['dashboard-activity'],
    queryFn: getRecentActivity,
    refetchInterval: 15000,
  });

  const recent: ActivityEvent[] = (events ?? []).slice(0, 10);

  return (
    <section className="panel" id="panel-timeline">
      <h2>
        <span>Activity Timeline</span>
        <span className="accent">· Recent</span>
      </h2>
      <div className="timeline">
        <div className="tline">
          {isLoading && (
            <div style={{ padding: '8px 4px', color: 'var(--text-mute)', fontSize: 12 }}>
              Loading activity…
            </div>
          )}
          {error && (
            <div style={{ padding: '8px 4px', color: 'var(--red)', fontSize: 12 }}>
              Failed to load activity.
            </div>
          )}
          {!isLoading && recent.length === 0 && (
            <div style={{ padding: '8px 4px', color: 'var(--text-mute)', fontSize: 12 }}>
              No events yet — system is quiet.
            </div>
          )}
          {recent.map((ev, idx) => {
            const variant = eventVariant(ev.resourceType, ev.action);
            return (
              <div
                key={`${ev.timestamp}-${idx}`}
                className={`ev${variant ? ` ${variant}` : ''}`}
                title={ev.conversationId ? `Conv: ${ev.conversationId.slice(0, 8)}…` : undefined}
              >
                <div className="time">{formatTime(ev.timestamp)}</div>
                <div className="msg">{describe(ev)}</div>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
