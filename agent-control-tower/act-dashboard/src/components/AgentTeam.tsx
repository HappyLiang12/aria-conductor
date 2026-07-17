import { useQuery } from '@tanstack/react-query';
import { listAgents } from '../api/agents';
import type { Agent, AgentHealthStatus } from '../types';
import { dispatchOpenAgentDrawer } from './DrawerContext';

const AVATAR_VARIANTS = ['ba', 'sm', 'dev', 'qa', 'ver', 'night'] as const;

function avatarClass(index: number): string {
  return `avatar ${AVATAR_VARIANTS[index % AVATAR_VARIANTS.length]}`;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 0) return '??';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

interface StatusInfo {
  rowClass: string;
  pillClass: string;
  label: string;
  filledBars: number;
  warnBars: number;
}

function statusInfo(health: AgentHealthStatus): StatusInfo {
  switch (health) {
    case 'HEALTHY':
      return { rowClass: 'agent running', pillClass: 'status', label: 'Online', filledBars: 4, warnBars: 0 };
    case 'DEGRADED':
      return { rowClass: 'agent running', pillClass: 'status wait', label: 'Degraded', filledBars: 3, warnBars: 1 };
    case 'UNHEALTHY':
      return { rowClass: 'agent blocked', pillClass: 'status blocked', label: 'Unhealthy', filledBars: 1, warnBars: 0 };
    case 'RETIRED':
      return { rowClass: 'agent', pillClass: 'status done', label: 'Retired', filledBars: 0, warnBars: 0 };
    default:
      return { rowClass: 'agent', pillClass: 'status', label: 'Unknown', filledBars: 0, warnBars: 0 };
  }
}

function ActivityBars({ filled, warn }: { filled: number; warn: number }) {
  const total = 5;
  const cells = [];
  for (let i = 0; i < total; i++) {
    if (i < filled - warn) {
      cells.push(<i key={i} className="on" />);
    } else if (i < filled) {
      cells.push(<i key={i} className="on warn" />);
    } else {
      cells.push(<i key={i} />);
    }
  }
  return <div className="bars">{cells}</div>;
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
    <section className="panel" id="panel-agents">
      <h2>
        <span>Agent Team</span>
        <span className="accent">· {activeCount} active</span>
      </h2>
      <div className="agents">
        {isLoading && (
          <div style={{ padding: '8px 4px', color: 'var(--text-mute)', fontSize: 12 }}>
            Loading agents…
          </div>
        )}
        {error && (
          <div style={{ padding: '8px 4px', color: 'var(--red)', fontSize: 12 }}>
            Failed to load agents.
          </div>
        )}
        {!isLoading && visibleAgents.length === 0 && (
          <div style={{ padding: '8px 4px', color: 'var(--text-mute)', fontSize: 12 }}>
            No agents registered yet.
          </div>
        )}
        {visibleAgents.map((agent, idx) => {
          const info = statusInfo(agent.healthStatus);
          return (
            <div
              key={agent.id}
              className={info.rowClass}
              data-agent={agent.id}
              onClick={() => dispatchOpenAgentDrawer(agent.id)}
            >
              <div className={avatarClass(idx)}>{initials(agent.name)}</div>
              <div>
                <div className="name">{agent.name}</div>
                <div className="task">
                  {agent.role || agent.agentType} · {agent.model || 'default'}
                </div>
              </div>
              <div className="right">
                <div className={info.pillClass}>{info.label}</div>
                <ActivityBars filled={info.filledBars} warn={info.warnBars} />
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
