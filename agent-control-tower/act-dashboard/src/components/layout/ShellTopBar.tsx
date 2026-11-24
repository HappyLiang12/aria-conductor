import { useEffect, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { ChevronDown, Moon, Sun } from 'lucide-react';
import { getSummary } from '../../api/dashboard';
import { getAdkProviderHealth, listAdkProviders } from '../../api/adk';
import { listAgents } from '../../api/agents';
import type { AdkProviderInfo, DashboardSummary } from '../../types';
import { queryPhase, type QueryPhase } from '../../utils/queryState';
import { formatClock } from '../../utils/formatTime';
import { NotificationBell } from '../NotificationBell';
import { useWebSocketContext } from '../Layout';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { SidebarTrigger } from '@/components/ui/sidebar';

function formatTokens(tokens: number): string {
  if (tokens <= 0) return '0';
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(2)}M`;
  if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}K`;
  return String(tokens);
}

function kpiValue(
  phase: QueryPhase,
  value: number | undefined,
  format: (n: number) => string = String,
): string {
  if (value === undefined || value === null || phase === 'loading') return '—';
  return format(value);
}

function kpiTitle(phase: QueryPhase): string | undefined {
  if (phase === 'loading') return 'Loading';
  if (phase === 'error') return 'Unavailable';
  if (phase === 'stale') return 'Last known data; refresh failed';
  return undefined;
}

function Kpi({ testId, label, value, title }: {
  testId: string;
  label: string;
  value: string;
  title?: string;
}) {
  return (
    <div
      data-testid={testId}
      title={title}
      className="flex min-w-0 flex-col px-2.5 leading-tight"
    >
      <span className="truncate text-sm font-semibold tabular-nums text-foreground">{value}</span>
      <span className="truncate text-[10px] font-medium tracking-wide text-muted-foreground uppercase">
        {label}
      </span>
    </div>
  );
}

export function ShellTopBar() {
  const [now, setNow] = useState<Date>(() => new Date());
  const { isConnected } = useWebSocketContext();
  // M5: read the persisted theme so the choice survives a refresh.
  const [isLight, setIsLight] = useState<boolean>(() => {
    try {
      const stored = localStorage.getItem('aria-theme');
      if (stored) return stored === 'light';
    } catch { /* localStorage unavailable */ }
    return typeof document !== 'undefined' && document.body.classList.contains('light');
  });

  const { data: summary, isPending, isError, isSuccess } = useQuery<DashboardSummary>({
    queryKey: ['dashboard-summary'],
    queryFn: getSummary,
    refetchInterval: 15_000,
  });

  const { data: providers } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
    refetchInterval: 15_000,
  });

  // There is no runtime endpoint in Phase 1. The fleet is uniform (the backend
  // refuses a mixed runtime), so the first non-retired agent IS the runtime;
  // the provider catalog supplies the core when no agent exists yet.
  const { data: fleet } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
    refetchInterval: 30_000,
  });

  const healthResults = useQueries({
    queries: (providers ?? []).map((p) => ({
      queryKey: ['adk-provider-health', p.id],
      queryFn: () => getAdkProviderHealth(p.id),
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

  const summaryPhase = queryPhase({ data: summary, isPending, isError, isSuccess });
  const kpiDetail = kpiTitle(summaryPhase);
  const activeAgents = summary?.activeAgents;
  const runningRuns = summary?.runningRuns;
  const pendingApprovals = summary?.pendingApprovals;
  const tokensBurned = summary?.totalTokensBurned;
  // A zero total beside runs that reported nothing is a silence, not a
  // measurement — render the silence, not a false zero. A positive total is
  // still partial while any run was silent, so the count is disclosed there too.
  const runsWithoutUsage = summary?.runsWithoutUsage ?? 0;
  const tokensUnreported = tokensBurned === 0 && runsWithoutUsage > 0;
  const tokensDetail =
    runsWithoutUsage > 0
      ? [kpiDetail, `${runsWithoutUsage} run${runsWithoutUsage === 1 ? '' : 's'} reported no usage`]
          .filter(Boolean)
          .join(' · ')
      : kpiDetail;
  const healthyProviders = healthResults.filter((r) => r.data?.healthy === true).length;
  // Honesty rule: the badge may only report a verdict it actually has evidence for.
  // `data` is the only field that carries a verdict — a pending probe has none yet and
  // an errored probe never produced one (an error is absence of evidence, not proof of
  // unhealth). So evidence is: the provider list settled AND every probe has data.
  const hasProviderEvidence =
    providers !== undefined && healthResults.every((r) => r.data !== undefined);
  const providerBadgeState: 'healthy' | 'unavailable' | 'unknown' =
    healthyProviders > 0 ? 'healthy' : hasProviderEvidence ? 'unavailable' : 'unknown';
  const providerBadgeDot =
    providerBadgeState === 'healthy'
      ? 'bg-emerald-400'
      : providerBadgeState === 'unavailable'
        ? 'bg-amber-400'
        : 'bg-muted-foreground/50';
  const providerBadgeText =
    providerBadgeState === 'healthy'
      ? `${healthyProviders} of ${providers?.length ?? 0} Providers Healthy`
      : providerBadgeState === 'unavailable'
        ? 'Providers Unavailable'
        : 'Checking providers…';

  // What each verdict rests on: the menu lists the probes behind the badge.
  const providerEvidence = (provider: AdkProviderInfo) => {
    const index = (providers ?? []).findIndex((p) => p.id === provider.id);
    const result = index >= 0 ? healthResults[index] : undefined;
    if (!result || result.data === undefined) return 'Unverified';
    return result.data.healthy ? 'Healthy' : 'Unhealthy';
  };

  // No runtime endpoint exists in Phase 1, so the badge reports what the app
  // already knows: the fleet is uniform, so the first non-retired agent's frozen
  // core/mode pair IS the runtime; the provider catalog is the fallback before
  // any agent exists. A mode neither source declares is never guessed.
  const defaultProvider = (providers ?? []).find((provider) => provider.isDefault) ?? providers?.[0];
  const runtimeAgent = (fleet ?? []).find(
    (agent) => agent.healthStatus !== 'RETIRED' && agent.adkProvider !== undefined,
  );
  const runtimeCore = runtimeAgent?.adkProvider ?? defaultProvider?.id;
  const runtimeMode = runtimeAgent?.executionMode ?? defaultProvider?.executionModes?.[0];
  const runtimeLabel = runtimeCore
    ? `${runtimeCore}${runtimeMode ? `/${runtimeMode}` : ''}`
    : 'runtime unknown';

  return (
    <header className="flex flex-wrap items-center gap-x-2 gap-y-1 border-b border-border bg-background px-3 py-1.5">
      <SidebarTrigger />
      <div className="flex min-w-0 flex-col leading-tight">
        <span className="truncate text-sm font-semibold text-foreground">Aria Conductor</span>
        <span className="truncate text-[10px] text-muted-foreground">Local</span>
      </div>

      <div className="ml-2 flex min-w-0 flex-wrap items-center gap-1.5">
        <Badge variant="outline" data-testid="runtime-badge" title="Default agent core from the provider catalog">
          {runtimeLabel}
        </Badge>
        <span
          data-testid="ws-indicator"
          role="status"
          aria-label={isConnected ? 'Live feed connected' : 'Live feed offline'}
          className="inline-flex items-center gap-1.5 px-1 text-[11px] text-muted-foreground"
        >
          <span
            aria-hidden="true"
            className={`size-1.5 rounded-full ${isConnected ? 'bg-emerald-400' : 'bg-amber-400'}`}
          />
          {isConnected ? 'Live' : 'Live feed offline'}
        </span>
        <Badge variant="secondary">
          {activeAgents ?? '—'} {activeAgents === 1 ? 'Agent' : 'Agents'}
        </Badge>
        <DropdownMenu>
          <DropdownMenuTrigger
            render={<Button variant="ghost" size="sm" className="gap-1.5 text-xs" />}
          >
            <span className={`size-1.5 rounded-full ${providerBadgeDot}`} aria-hidden="true" />
            {providerBadgeText}
            <ChevronDown aria-hidden="true" className="size-3 opacity-60" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-64">
            <DropdownMenuLabel>Provider probes</DropdownMenuLabel>
            <DropdownMenuSeparator />
            {providers && providers.length > 0 ? (
              providers.map((provider) => (
                <DropdownMenuItem key={provider.id} disabled className="justify-between gap-4">
                  <span className="truncate">{provider.displayName}</span>
                  <span className="text-xs text-muted-foreground">{providerEvidence(provider)}</span>
                </DropdownMenuItem>
              ))
            ) : (
              <DropdownMenuItem disabled>
                {providers === undefined ? 'Checking providers…' : 'No providers registered'}
              </DropdownMenuItem>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* Counters need ~460px; below `md` the row would push the document
          wider than the viewport, so they live on the desktop shell only. */}
      <div className="ml-auto hidden items-center gap-1 md:flex">
        <Kpi testId="kpi-active-agents" label="Active Agents" value={kpiValue(summaryPhase, activeAgents)} title={kpiDetail} />
        <Kpi testId="kpi-running-runs" label="Running Runs" value={kpiValue(summaryPhase, runningRuns)} title={kpiDetail} />
        <Kpi testId="kpi-pending-approvals" label="Approvals Pending" value={kpiValue(summaryPhase, pendingApprovals)} title={kpiDetail} />
        <Kpi
          testId="kpi-tokens"
          label="Recorded tokens"
          value={tokensUnreported ? 'not reported' : kpiValue(summaryPhase, tokensBurned, formatTokens)}
          title={tokensDetail}
        />
      </div>

      <div className="flex items-center gap-1.5 pl-2">
        <Badge variant="ghost" className="font-normal text-muted-foreground tabular-nums" aria-label="Current time">
          {formatClock(now, true)}
        </Badge>
        <NotificationBell />
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={toggleTheme}
          aria-pressed={isLight}
          aria-label="Toggle theme"
        >
          {isLight ? <Moon aria-hidden="true" /> : <Sun aria-hidden="true" />}
        </Button>
      </div>
    </header>
  );
}

export default ShellTopBar;
