import type { Agent, AgentTelemetry } from '../types';
import { dispatchOpenAgentDrawer } from './DrawerContext';
import { RuntimeBadge } from './RuntimeBadge';

/**
 * AgentCard — rich agent panel rendered inside the Crew view.
 *
 * Visualises the live state of a registered agent: avatar, status pulse,
 * recent activity bars (today's run count) and recorded token usage. Clicking
 * the card opens the agent drawer.
 *
 * Data comes from GET /api/v1/dashboard/agent-telemetry, which aggregates the
 * runs that actually reported usage. There is no price table, so no dollar
 * estimate is shown.
 */

export interface AgentCardProps {
  agent: Agent;
  telemetry?: AgentTelemetry;
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

// UX-3: the status pill renders the agent lifecycle flag (set at creation /
// retire), not a live probe, so the labels must not claim online/offline
// connectivity. The LiveStatus values stay as CSS class hooks; only the
// rendered text changes.
function statusLabel(s: LiveStatus): string {
  switch (s) {
    case 'online':  return 'Active';
    case 'idle':    return 'Degraded';
    case 'offline': return 'Retired';
  }
}

/** Quick number formatter — adds K / M suffixes for tokens. */
function fmtTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

/** Map today's run count to a 0-6 bar activity level. */
function computeActivityLevel(runCount: number): number {
  if (runCount <= 0) return 0;
  if (runCount <= 1) return 1;
  if (runCount <= 3) return 2;
  if (runCount <= 6) return 3;
  if (runCount <= 10) return 4;
  if (runCount <= 15) return 5;
  return 6;
}

export function AgentCard({ agent, telemetry, selected, onSelect }: AgentCardProps) {
  const status = liveStatusFor(agent);
  const role = agent.role || agent.agentType.toLowerCase();
  const avatarKind = avatarClassFor(role);

  const runCount = telemetry?.runCountToday ?? 0;
  const tokensToday = telemetry?.totalTokensToday ?? 0;
  const activityLevel = computeActivityLevel(runCount);

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
          <span className="role-tag" title={role || 'agent'}>{role || 'agent'}</span>
        </div>
        <RuntimeBadge core={agent.adkProvider} mode={agent.executionMode} />
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
        {agent.pickupEligible === false && (
          <span
            className="status wait"
            title={`Cannot receive kanban cards: ${(agent.pickupIneligibleReasons ?? []).join(', ') || 'no reason reported'}${
              agent.lastProbedAt ? ` · last probed ${agent.lastProbedAt}` : ''
            }`}
          >
            No pickup
          </span>
        )}
      </div>

      <div className="activity">
        <span className="activity-l">Activity · today</span>
        <div className="bars">{bars}</div>
        <span className="lvl">{activityLevel}/6</span>
      </div>

      <div className="stats">
        <div>
          <span className="l">Runs · today</span>
          <span className="v run">{runCount}</span>
        </div>
        <div>
          <span className="l">Tokens · today</span>
          <span className="v tok">{fmtTokens(tokensToday)}</span>
        </div>
      </div>

      <div className="meta-foot">
        <span className="chip">{agent.model || agent.agentType}</span>
        <span>{agent.provider || '—'}</span>
      </div>
    </article>
  );
}

export default AgentCard;
