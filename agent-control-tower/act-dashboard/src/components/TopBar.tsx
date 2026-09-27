import { useEffect, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import { getAdkProviderHealth, listAdkProviders } from '../api/adk';
import type { DashboardSummary } from '../types';
import { formatClock } from '../utils/formatTime';
import { NotificationBell } from './NotificationBell';

function formatTokens(tokens: number | undefined): string {
  if (!tokens || tokens <= 0) return '0';
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(2)}M`;
  if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}K`;
  return String(tokens);
}

export function TopBar() {
  const [now, setNow] = useState<Date>(() => new Date());
  // M5: read the persisted theme so the choice survives a refresh.
  const [isLight, setIsLight] = useState<boolean>(() => {
    try {
      const stored = localStorage.getItem('aria-theme');
      if (stored) return stored === 'light';
    } catch { /* localStorage unavailable */ }
    return typeof document !== 'undefined' && document.body.classList.contains('light');
  });

  const { data: summary } = useQuery<DashboardSummary>({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 15_000,
  });

  const { data: providers } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
    refetchInterval: 15_000,
  });

  const healthResults = useQueries({
    queries: (providers ?? []).map((p) => ({
      queryKey: ['adk-provider-health', p.id],
      queryFn: () => getAdkProviderHealth(p.id),
      // The qoder key is shared with QoderCredentialCard and the ProvidersPage
      // inventory table: query-core takes the retry policy from whichever
      // observer triggers the fetch, so this always-mounted observer pins the
      // same `retry:false` as the other two (a probe error is deterministic).
      retry: false,
      refetchInterval: 15_000,
    })),
  });

  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1_000);
    return () => window.clearInterval(id);
  }, []);

  // M5: apply the persisted theme on mount (avoids resetting to dark on refresh).
  useEffect(() => {
    document.body.classList.toggle('light', isLight);
  }, [isLight]);

  const toggleTheme = () => {
    const next = !document.body.classList.contains('light');
    document.body.classList.toggle('light', next);
    setIsLight(next);
    try {
      localStorage.setItem('aria-theme', next ? 'light' : 'dark');
    } catch { /* localStorage unavailable */ }
  };

  const activeAgents = summary?.activeAgents ?? 0;
  const runningRuns = summary?.runningRuns ?? 0;
  const pendingApprovals = summary?.pendingApprovals ?? 0;
  const tokensBurned = summary?.totalTokensBurned ?? 0;
  const healthyProviders = healthResults.filter((r) => r.data?.healthy === true).length;
  // Honesty rule: the badge may only report a verdict it actually has evidence for.
  // `data` is the only field that carries a verdict — a pending probe has none yet and
  // an errored probe never produced one (an error is absence of evidence, not proof of
  // unhealth). So evidence is: the provider list settled AND every probe has data.
  const hasProviderEvidence =
    providers !== undefined && healthResults.every((r) => r.data !== undefined);
  const providerBadgeState: 'healthy' | 'unavailable' | 'unknown' =
    healthyProviders > 0 ? 'healthy' : hasProviderEvidence ? 'unavailable' : 'unknown';
  const providerBadgeClass =
    providerBadgeState === 'healthy' ? 'live' : providerBadgeState === 'unavailable' ? 'afterhours' : '';
  const providerBadgeText =
    providerBadgeState === 'healthy'
      ? `${healthyProviders} of ${providers?.length ?? 0} Providers Healthy`
      : providerBadgeState === 'unavailable'
        ? 'Providers Unavailable'
        : 'Checking providers…';

  return (
    <header className="topbar">
      <div className="brand">
        <div className="logo" aria-hidden="true" />
        <h1>
          Aria Conductor
          <small>Aria Conductor · Local</small>
        </h1>
      </div>

      <div className="badges">
        <span className="badge governed">
          <span className="dot" />
          {activeAgents} {activeAgents === 1 ? 'Agent' : 'Agents'}
        </span>
        <span className={`badge ${providerBadgeClass}`}>
          <span className="dot" />
          {providerBadgeText}
        </span>
      </div>

      <div className="top-counters">
        <div className="kpi brand">
          <div className="v">{activeAgents}</div>
          <div className="l">Active Agents</div>
        </div>
        <div className="kpi">
          <div className="v">{runningRuns}</div>
          <div className="l">Running Runs</div>
        </div>
        <div className="kpi amber">
          <div className="v">{pendingApprovals}</div>
          <div className="l">Approvals Pending</div>
        </div>
        <div className="kpi green">
          <div className="v">{formatTokens(tokensBurned)}</div>
          <div className="l">Tokens Today</div>
        </div>
      </div>

      <div className="clock" aria-label="Current time">
        {formatClock(now, true)}
      </div>

      <div className="top-actions">
        <NotificationBell />
        <button
          type="button"
          className="btn"
          onClick={toggleTheme}
          aria-pressed={isLight}
          aria-label="Toggle theme"
        >
          {isLight ? '🌙 Dark' : '☀ Light'}
        </button>
      </div>
    </header>
  );
}

export default TopBar;
