import type { Agent, AgentTelemetry } from '../types';
import { dispatchOpenAgentDrawer } from './DrawerContext';
import { RuntimeBadge } from './RuntimeBadge';
import { Badge } from './ui/badge';

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

/** Token tone for the lifecycle pill; the `status` class stays as the shim. */
function statusTone(s: LiveStatus): string {
  switch (s) {
    case 'online':  return 'border-success/30 bg-success/10 text-success';
    case 'idle':    return 'border-warning/30 bg-warning/10 text-warning';
    case 'offline': return 'border-border bg-muted text-muted-foreground';
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
    const shim = on ? `on ${tone}`.trim() : '';
    return (
      <i
        key={i}
        className={`${shim} h-3.5 w-1 rounded-sm ${
          on ? (tone === 'warn' ? 'bg-warning' : 'bg-primary') : 'bg-muted'
        }`}
      />
    );
  });

  return (
    <article
      className={`crew-card role-${avatarKind} ${status} relative flex cursor-pointer flex-col gap-2.5 rounded-xl border border-border bg-card p-3.5 text-card-foreground transition-colors hover:border-primary/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none`}
      role="button"
      tabIndex={0}
      onClick={handleOpen}
      onKeyDown={handleKey}
      aria-label={`Open details for ${agent.name}`}
    >
      <div className="head flex items-center gap-2.5">
        <div className={`avatar ${avatarKind} flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs font-bold text-primary`}>
          {initialsOf(agent.name)}
        </div>
        <div className="who flex min-w-0 flex-1 flex-col">
          <span className="nm truncate text-sm font-bold tracking-wide" title={agent.name}>{agent.name}</span>
          <span
            className="role-tag mt-0.5 truncate text-[10.5px] tracking-[.8px] text-muted-foreground uppercase"
            title={role || 'agent'}
          >
            {role || 'agent'}
          </span>
        </div>
        <RuntimeBadge core={agent.adkProvider} mode={agent.executionMode} />
        {onSelect && (
          <input
            type="checkbox"
            aria-label={`select ${agent.name}`}
            checked={!!selected}
            onClick={(e) => e.stopPropagation()}
            onChange={onSelect}
            className="mr-1.5 size-3.5 accent-primary"
          />
        )}
        <Badge
          variant="outline"
          className={`status border ${statusTone(status)} ${status === 'online' ? '' : status}`}
        >
          {statusLabel(status)}
        </Badge>
        {agent.pickupEligible === false && (
          <Badge
            variant="outline"
            className="status wait border-warning/30 bg-warning/10 text-warning"
            title={`Cannot receive kanban cards: ${(agent.pickupIneligibleReasons ?? []).join(', ') || 'no reason reported'}${
              agent.lastProbedAt ? ` · last probed ${agent.lastProbedAt}` : ''
            }`}
          >
            No pickup
          </Badge>
        )}
      </div>

      <div className="activity flex items-center gap-2.5 border-t border-dashed border-border pt-2">
        <span className="activity-l shrink-0 text-[9.5px] tracking-[1px] text-muted-foreground uppercase">
          Activity · today
        </span>
        <div className="bars flex items-end gap-1">{bars}</div>
        <span className="lvl ml-auto text-[10.5px] text-muted-foreground tabular-nums">{activityLevel}/6</span>
      </div>

      <div className="stats grid grid-cols-2 gap-2">
        <div className="rounded-lg border border-border bg-muted/40 px-2.5 py-2">
          <span className="l block text-[9.5px] tracking-[.9px] text-muted-foreground uppercase">Runs · today</span>
          <span className="v run mt-0.5 block text-sm font-bold tabular-nums">{runCount}</span>
        </div>
        <div className="rounded-lg border border-border bg-muted/40 px-2.5 py-2">
          <span className="l block text-[9.5px] tracking-[.9px] text-muted-foreground uppercase">Tokens · today</span>
          <span className="v tok mt-0.5 block text-sm font-bold tabular-nums text-primary">{fmtTokens(tokensToday)}</span>
        </div>
      </div>

      <div className="meta-foot flex items-center gap-2 text-[10.5px] tracking-wide text-muted-foreground">
        <span className="chip rounded border border-primary/30 bg-primary/10 px-1.5 py-0.5 font-mono text-[10px] text-primary">
          {agent.model || agent.agentType}
        </span>
        <span>{agent.provider || '—'}</span>
      </div>
    </article>
  );
}

export default AgentCard;
