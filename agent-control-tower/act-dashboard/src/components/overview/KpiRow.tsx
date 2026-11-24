import { useQuery } from '@tanstack/react-query';
import { getSummary } from '../../api/dashboard';
import { queryKeys } from '../../api/queryKeys';
import { queryPhase, type QueryPhase } from '../../utils/queryState';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';

function formatNumber(n: number | undefined): string {
  if (n === undefined || n === null) return '—';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}K`;
  return n.toLocaleString();
}

function phaseDetail(phase: QueryPhase): string | null {
  if (phase === 'loading') return 'Loading';
  if (phase === 'error') return 'Unavailable';
  if (phase === 'stale') return 'Last known data; refresh failed';
  return null;
}

interface Kpi {
  key: string;
  label: string;
  value: string;
  detail: string;
}

/**
 * The four headline metrics, on the `dashboard-summary` read shared with
 * ExecutiveSummary. Token honesty is the same rule as that panel: a zero total
 * beside runs that reported nothing is a silence, not a measured zero, so the
 * value says `not reported` and the detail names how many runs were silent. A
 * positive total is still partial while any run was silent, and says so.
 */
export function KpiRow() {
  const summaryQuery = useQuery({ queryKey: queryKeys.summary, queryFn: getSummary });
  const summary = summaryQuery.data;
  const phase = queryPhase(summaryQuery);
  const fresh = phase === 'success' || phase === 'stale';

  const healthy = summary?.healthyAgents ?? 0;
  const degraded = summary?.degradedAgents ?? 0;
  const runsWithoutUsage = summary?.runsWithoutUsage ?? 0;
  const tokensUnreported = fresh && summary?.totalTokensBurned === 0 && runsWithoutUsage > 0;
  const tokensSilent = fresh && runsWithoutUsage > 0;
  const silentDetail = `${runsWithoutUsage} run${runsWithoutUsage === 1 ? '' : 's'} reported no usage`;

  const cards: Kpi[] = [
    {
      key: 'active-agents',
      label: 'Active agents',
      value: fresh ? formatNumber(summary?.activeAgents) : '—',
      detail:
        phaseDetail(phase)
        ?? (degraded > 0 ? `${healthy} healthy · ${degraded} degraded` : `${healthy} healthy`),
    },
    {
      key: 'running-runs',
      label: 'Running runs',
      value: fresh ? formatNumber(summary?.runningRuns) : '—',
      detail: phaseDetail(phase) ?? 'Executing now',
    },
    {
      key: 'pending-approvals',
      label: 'Pending approvals',
      value: fresh ? formatNumber(summary?.pendingApprovals) : '—',
      detail: phaseDetail(phase) ?? 'Across the decision queue',
    },
    {
      key: 'recorded-tokens',
      label: 'Recorded tokens',
      value: fresh
        ? tokensUnreported
          ? 'not reported'
          : formatNumber(summary?.totalTokensBurned)
        : '—',
      detail:
        phaseDetail(phase)
        ?? (tokensUnreported
          ? silentDetail
          : tokensSilent
            ? `Cumulative recorded input + output over retained records · ${silentDetail}`
            : 'Cumulative recorded input + output over retained records'),
    },
  ];

  return (
    <section
      data-testid="kpi-row"
      aria-label="Key metrics"
      className="grid gap-3 px-4 pt-4 sm:grid-cols-2 xl:grid-cols-4"
    >
      {cards.map((card) => (
        <Card key={card.key} size="sm" data-testid={`kpi-card-${card.key}`}>
          <CardHeader>
            <CardTitle className="text-sm font-medium text-muted-foreground">
              {card.label}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl leading-8 font-semibold tracking-tight text-foreground tabular-nums">
              {card.value}
            </div>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">{card.detail}</p>
          </CardContent>
        </Card>
      ))}
    </section>
  );
}

export default KpiRow;
