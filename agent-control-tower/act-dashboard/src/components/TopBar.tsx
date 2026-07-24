import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../api/dashboard';
import type { DashboardSummary } from '../types';
import { NotificationBell } from './NotificationBell';

function formatClock(date: Date): string {
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

function formatTokens(tokens: number | undefined): string {
  if (!tokens || tokens <= 0) return '0';
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(2)}M`;
  if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}K`;
  return String(tokens);
}

const THEME_KEY = 'aria-theme';

export function TopBar() {
  const [now, setNow] = useState<Date>(() => new Date());
  const [isLight, setIsLight] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light') {
      document.body.classList.add('light');
      return true;
    }
    return false;
  });

  const { data: summary } = useQuery<DashboardSummary>({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 15_000,
  });

  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1_000);
    return () => window.clearInterval(id);
  }, []);

  const toggleTheme = () => {
    const next = !document.body.classList.contains('light');
    document.body.classList.toggle('light', next);
    setIsLight(next);
    try { localStorage.setItem(THEME_KEY, next ? 'light' : 'dark'); } catch { /* ignore */ }
  };

  const activeAgents = summary?.activeAgents ?? 0;
  const runningRuns = summary?.runningRuns ?? 0;
  const pendingApprovals = summary?.pendingApprovals ?? 0;
  const tokensBurned = summary?.totalTokensBurned ?? 0;
  const isHealthy = activeAgents > 0;

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
          {activeAgents} {activeAgents === 1 ? 'Agent' : 'Agents'} Online
        </span>
        <span className={`badge ${isHealthy ? 'live' : 'afterhours'}`}>
          <span className="dot" />
          {isHealthy ? 'System Healthy' : 'System Idle'}
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
        {formatClock(now)}
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
