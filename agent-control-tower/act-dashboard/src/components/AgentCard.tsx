import type { Agent, AgentTelemetry } from '../types';
import { dispatchOpenAgentDrawer } from './DrawerContext';
import { estimateCost } from '../utils/pricing';

/**
 * AgentCard — rich agent panel rendered inside the Crew view.
 *
 * Visualises the live state of a registered agent: avatar, status pulse,
 * recent activity bars (based on today's prompt call count), and a cost
 * breakdown (tokens used today, estimated daily spend). Clicking the card
 * opens the agent drawer.
 *
 * Token/cost/activity data is sourced from the backend
 * GET /api/v1/dashboard/agent-telemetry endpoint, which aggregates
 * real PromptCall rows for the current UTC day.
 */

export interface AgentCardProps {
  agent: Agent;
  telemetry?: AgentTelemetry;
  onManageTools?: (agent: Agent) => void;
  /** H3 bulk selection: when provided, renders a selection checkbox. */
  selected?: boolean;
  onSelect?: () => void;
}

type LiveStatus = 'online' | 'idle' | 'offline';

function avatarClassFor(role: string | undefined): string {
  const r = (role || '').toLowerCase();
  if (r.includes('qa') || r.includes('test')) return 'qa';
  if (r.includes('analyst') || r.includes('ba') || r.includes('research')) return 'ba';
  if (r.includes('dev') && !r.includes('devops')) return 'dev';
  if (r.includes('devops') || r.includes('sre') || r.includes('infra')) return 'infra';
  if (r.includes('write') || r.includes('doc')) return 'doc';
  if (r.includes('data')) return 'data';
  if (r.includes('verifier') || r.includes('review')) return 'ver';
  if (r.includes('orchestr') || r.includes('manager') || r.includes('sm')) return 'sm';
  return 'dev';
}

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '··';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function liveStatusFor(agent: Agent): LiveStatus {
  if (agent.healthStatus === 'RETIRED' || agent.healthStatus === 'UNHEALTHY') return 'offline';
  if (agent.healthStatus === 'DEGRADED') return 'idle';
  return 'online';
}

function statusLabel(s: LiveStatus): string {
  switch (s) {
    case 'online':  return 'Online';
    case 'idle':    return 'Idle';
    case 'offline': return 'Offline';
  }
}

/** Quick number formatter — adds k / M suffixes for tokens. */
function fmtTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'k';
  return String(n);
}

function fmtCost(usd: number): string {
  return '$' + usd.toFixed(2);
}

/** Map daily prompt call count to a 0-6 bar activity level. */
function computeActivityLevel(callCount: number): number {
  if (callCount <= 0) return 0;
  if (callCount <= 1) return 1;
  if (callCount <= 3) return 2;
  if (callCount <= 6) return 3;
  if (callCount <= 10) return 4;
  if (callCount <= 15) return 5;
  return 6;
}

export function AgentCard({ agent, telemetry, onManageTools, selected, onSelect }: AgentCardProps) {
  const status = liveStatusFor(agent);
  const role = agent.role || agent.agentType.toLowerCase();
  const avatarKind = avatarClassFor(role);

  const callCount = telemetry?.callCountToday ?? 0;
  const tokensToday = telemetry?.totalTokensToday ?? 0;
  const costToday   = estimateCost(tokensToday);
  const activityLevel = computeActivityLevel(callCount);

  const handleOpen = () => {
    dispatchOpenAgentDrawer(agent.id);
  };

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleOpen();
    }
  };

  const bars = Array.from({ length: 6 }).map((_, i) => {
    const on = i < activityLevel;
    const tone = activityLevel >= 5 ? 'warn' : '';
    return <i key={i} className={on ? `on ${tone}`.trim() : ''} />;
  });

  return (
    <article
      className={`crew-card role-${avatarKind} ${status}`}
      role="button"
      tabIndex={0}
      onClick={handleOpen}
      onKeyDown={handleKey}
      aria-label={`Open details for ${agent.name}`}
    >
      <div className="head">
        <div className={`avatar ${avatarKind}`}>{initialsOf(agent.name)}</div>
        <div className="who">
          <span className="nm" title={agent.name}>{agent.name}</span>
          <span className="role-tag">{role || 'agent'}</span>
        </div>
        {onSelect && (
          <input
            type="checkbox"
            aria-label={`select ${agent.name}`}
            checked={!!selected}
            onClick={(e) => e.stopPropagation()}
            onChange={onSelect}
            style={{ marginRight: 6 }}
          />
        )}
        <span className={`status ${status === 'online' ? '' : status}`}>
          {statusLabel(status)}
        </span>
      </div>

      <div className="activity">
        <span className="activity-l">Activity · today</span>
        <div className="bars">{bars}</div>
        <span className="lvl">{activityLevel}/6</span>
      </div>

      <div className="stats">
        <div>
          <span className="l">Tokens · today</span>
          <span className="v tok">{fmtTokens(tokensToday)}</span>
        </div>
        <div>
          <span className="l">Spend · today</span>
          <span className="v cost">{fmtCost(costToday)}</span>
        </div>
      </div>

      <div className="meta-foot">
        <span className="chip">{agent.model || agent.agentType}</span>
        <span>{agent.provider || 'native'}</span>
        {onManageTools && (
          <button
            type="button"
            className="chip"
            title="Assign tools"
            style={{ cursor: 'pointer', border: 'none', marginLeft: 'auto' }}
            onClick={(e) => { e.stopPropagation(); onManageTools(agent); }}
          >
            🔧 Tools
          </button>
        )}
      </div>
    </article>
  );
}

export default AgentCard;
