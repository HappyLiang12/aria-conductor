import { useQuery } from '@tanstack/react-query';
import { listAgents } from '../api/agents';
import type { Agent, AgentHealthStatus } from '../types';
import { dispatchOpenAgentDrawer } from './DrawerContext';
import { Badge } from './ui/badge';
import { Card, CardContent } from './ui/card';

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 0) return '??';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

interface StatusInfo {
  variant: 'secondary' | 'destructive' | 'outline';
  label: string;
  filledBars: number;
  warnBars: number;
}

function statusInfo(health: AgentHealthStatus): StatusInfo {
  switch (health) {
    case 'HEALTHY':
      return { variant: 'secondary', label: 'Online', filledBars: 4, warnBars: 0 };
    case 'DEGRADED':
      return { variant: 'secondary', label: 'Degraded', filledBars: 3, warnBars: 1 };
    case 'UNHEALTHY':
      return { variant: 'destructive', label: 'Unhealthy', filledBars: 1, warnBars: 0 };
    case 'RETIRED':
      return { variant: 'outline', label: 'Retired', filledBars: 0, warnBars: 0 };
    default:
      return { variant: 'outline', label: 'Unknown', filledBars: 0, warnBars: 0 };
  }
}

function ActivityBars({ filled, warn }: { filled: number; warn: number }) {
  return (
    <div className="flex gap-0.5" aria-hidden>
      {Array.from({ length: 5 }, (_, i) => {
        const tone = i < filled - warn
          ? 'bg-emerald-500'
          : i < filled
            ? 'bg-amber-500'
            : 'bg-muted-foreground/30';
        return <span key={i} className={`h-1 w-1 rounded-full ${tone}`} />;
      })}
    </div>
  );
}

export default function AgentTeam() {
  const { data: agents, isLoading, error } = useQuery({
    queryKey: ['agents-all'],
    queryFn: listAgents,
    refetchInterval: 20000,
  });

  const visibleAgents: Agent[] = (agents ?? []).filter((a) => a.healthStatus !== 'RETIRED');
  const activeCount = visibleAgents.filter((a) => a.healthStatus === 'HEALTHY').length;

  return (
    <section className="flex flex-col gap-2" id="panel-agents">
      <h2 className="flex items-baseline gap-2 text-sm font-semibold text-foreground">
        <span>Agent Team</span>
        <span className="text-xs font-normal text-muted-foreground">· {activeCount} active</span>
      </h2>
      <Card>
        <CardContent className="space-y-1">
          {isLoading && (
            <div className="px-1 py-2 text-xs text-muted-foreground">Loading agents…</div>
          )}
          {error && (
            <div className="px-1 py-2 text-xs text-destructive">Failed to load agents.</div>
          )}
          {!isLoading && visibleAgents.length === 0 && (
            <div className="px-1 py-2 text-xs text-muted-foreground">No agents registered yet.</div>
          )}
          {visibleAgents.map((agent) => {
            const info = statusInfo(agent.healthStatus);
            const taskLine = `${agent.role || agent.agentType} · ${agent.model || 'default'}`;
            return (
              <div
                key={agent.id}
                className="agent flex cursor-pointer items-center gap-2 rounded-lg border border-transparent p-2 hover:border-border hover:bg-muted/40"
                data-agent={agent.id}
                role="button"
                tabIndex={0}
                onClick={() => dispatchOpenAgentDrawer(agent.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') dispatchOpenAgentDrawer(agent.id);
                }}
              >
                <div className="flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-[11px] font-bold text-muted-foreground">
                  {initials(agent.name)}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-xs font-semibold text-card-foreground">{agent.name}</div>
                  <div className="truncate text-[11px] text-muted-foreground" title={taskLine}>{taskLine}</div>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1">
                  <Badge variant={info.variant}>{info.label}</Badge>
                  {agent.pickupEligible === false && (
                    <Badge
                      variant="outline"
                      title={`Cannot receive kanban cards: ${(agent.pickupIneligibleReasons ?? []).join(', ') || 'no reason reported'}${
                        agent.lastProbedAt ? ` · last probed ${agent.lastProbedAt}` : ''
                      }`}
                    >
                      No pickup
                    </Badge>
                  )}
                  <ActivityBars filled={info.filledBars} warn={info.warnBars} />
                </div>
              </div>
            );
          })}
        </CardContent>
      </Card>
    </section>
  );
}
