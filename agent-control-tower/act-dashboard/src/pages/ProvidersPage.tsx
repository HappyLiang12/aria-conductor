import { useQuery, useQueries } from '@tanstack/react-query';
import { listAgents } from '../api/agents';
import { listAdkProviders, getAdkProviderHealth } from '../api/adk';
import { PageHeader } from '../components/layout/PageHeader';
import { SectionCard } from '../components/layout/SectionCard';
import { Badge } from '../components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';

interface HealthBadgeProps {
  healthy: boolean;
}

function HealthBadge({ healthy }: HealthBadgeProps) {
  return (
    <Badge
      variant="outline"
      className={`status-badge status-badge-md border ${
        healthy
          ? 'border-success/30 bg-success/10 text-success'
          : 'border-destructive/30 bg-destructive/10 text-destructive'
      }`}
    >
      {healthy ? 'Healthy' : 'Unhealthy'}
    </Badge>
  );
}

const TH_CLASS =
  'border-b border-border bg-muted/50 px-3 py-2 text-left text-[10px] font-semibold tracking-[1.2px] text-muted-foreground uppercase';
const TD_CLASS = 'border-b border-border px-3 py-2 align-middle';

export function ProvidersPage() {
  const { data: providers, isLoading, error } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
  });

  const { data: agents, isLoading: agentsLoading, error: agentsError } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  const healthResults = useQueries({
    queries: (providers ?? []).map((p) => ({
      queryKey: ['adk-provider-health', p.id],
      queryFn: () => getAdkProviderHealth(p.id),
    })),
  });

  // The registered inventory is the only core catalog: a stored core value that
  // is not registered (e.g. the removed `langchain`) renders as an explicit
  // unsupported state — never silently replaced by another core or a default.
  const registeredCores = new Set((providers ?? []).map((p) => p.id));

  return (
    <div className="mx-auto w-full max-w-[1400px] p-4 sm:p-6">
      <PageHeader title="Agent Providers" />

      {/* Provider inventory */}
      {isLoading && (
        <div className="loading-spinner flex items-center gap-2 py-6 text-sm text-muted-foreground">
          <div className="spinner size-4 animate-spin rounded-full border-2 border-muted border-t-primary" />
          <span>Loading providers...</span>
        </div>
      )}
      {error && (
        <div className="error-state rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          Failed to load providers. Please retry.
        </div>
      )}

      {!isLoading && !error && (providers?.length ?? 0) === 0 && (
        <div className="empty-state rounded-lg border border-dashed border-border px-3 py-6 text-center text-sm text-muted-foreground">
          No providers registered.
        </div>
      )}

      {(providers?.length ?? 0) > 0 && (
        <SectionCard title="Registered Cores">
          <div className="table-wrapper overflow-x-auto rounded-xl border border-border">
            <table className="data-table w-full border-collapse text-sm">
              <thead>
                <tr>
                  <th className={TH_CLASS}>ID</th>
                  <th className={TH_CLASS}>Display Name</th>
                  <th className={TH_CLASS}>Capability</th>
                  <th className={TH_CLASS}>Default</th>
                  <th className={TH_CLASS}>Health</th>
                </tr>
              </thead>
              <tbody>
                {providers?.map((p, i) => {
                  const health = healthResults[i]?.data;
                  return (
                    <tr key={p.id} className="hover:bg-muted/40">
                      <td className={`${TD_CLASS} cell-mono font-mono text-[11.5px] text-muted-foreground`}>{p.id}</td>
                      <td className={`${TD_CLASS} cell-primary font-semibold text-foreground`}>{p.displayName}</td>
                      <td className={TD_CLASS}>
                        <Badge variant="outline" className="type-badge">
                          {p.supportsTaskExecution ? 'Task' : 'Turn'}
                        </Badge>
                      </td>
                      <td className={TD_CLASS}>
                        {p.isDefault && (
                          <Badge variant="secondary" className="type-badge">Default</Badge>
                        )}
                      </td>
                      <td className={TD_CLASS}>
                        {health ? (
                          <HealthBadge healthy={health.healthy} />
                        ) : healthResults[i]?.isError ? (
                          <HealthBadge healthy={false} />
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {/* Per-agent backend overview */}
      <Card className="card mt-6">
        <CardHeader>
          <CardTitle className="form-title">Per-Agent Backends</CardTitle>
          <CardDescription>
            Core and execution mode are stored per agent; a run is admitted against them and is
            never silently re-routed to another core or mode.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {agentsLoading && (
            <div className="loading-spinner flex items-center gap-2 py-6 text-sm text-muted-foreground">
              <div className="spinner size-4 animate-spin rounded-full border-2 border-muted border-t-primary" />
              <span>Loading agents...</span>
            </div>
          )}
          {agentsError && (
            <div className="error-state rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              Failed to load agents. Please retry.
            </div>
          )}
          {!agentsLoading && !agentsError && (agents?.length ?? 0) === 0 && (
            <div className="empty-state rounded-lg border border-dashed border-border px-3 py-6 text-center text-sm text-muted-foreground">
              No agents registered yet.
            </div>
          )}
          {(agents?.length ?? 0) > 0 && (
            <div className="table-wrapper overflow-x-auto rounded-xl border border-border">
              <table className="data-table w-full border-collapse text-sm">
                <thead>
                  <tr>
                    <th className={TH_CLASS}>Agent</th>
                    <th className={TH_CLASS}>Agent core</th>
                    <th className={TH_CLASS}>Execution mode</th>
                    <th className={TH_CLASS}>Readiness</th>
                  </tr>
                </thead>
                <tbody>
                  {agents?.map((agent) => {
                    const core = agent.adkProvider;
                    const registered = core != null && registeredCores.has(core);
                    const healthIndex = (providers ?? []).findIndex((p) => p.id === core);
                    const health = healthIndex >= 0 ? healthResults[healthIndex]?.data : undefined;
                    const healthFailed = healthIndex >= 0 && healthResults[healthIndex]?.isError;
                    return (
                      <tr key={agent.id} className={registered ? 'hover:bg-muted/40' : 'row-active bg-primary/10'}>
                        <td className={`${TD_CLASS} cell-primary font-semibold text-foreground`}>{agent.name}</td>
                        <td className={TD_CLASS}>
                          {core == null || core === '' ? (
                            // Legacy record without a stored core: explicit unknown,
                            // not an invented default.
                            <span className="cell-mono font-mono text-[11.5px] text-muted-foreground">Not recorded</span>
                          ) : registered ? (
                            <span className="cell-mono font-mono text-[11.5px] text-muted-foreground">{core}</span>
                          ) : (
                            <Badge
                              variant="outline"
                              className="type-badge border-destructive/40 bg-destructive/10 text-destructive"
                            >
                              Unsupported core: {core}
                            </Badge>
                          )}
                        </td>
                        <td className={`${TD_CLASS} cell-mono font-mono text-[11.5px] text-muted-foreground`}>
                          {agent.executionMode ?? 'Not recorded'}
                        </td>
                        <td className={TD_CLASS}>
                          {registered ? (
                            health ? (
                              <HealthBadge healthy={health.healthy} />
                            ) : healthFailed ? (
                              <HealthBadge healthy={false} />
                            ) : (
                              '—'
                            )
                          ) : (
                            // An unregistered core has no probe and no admitted mode
                            // pair to report; the state itself is the finding.
                            '—'
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

export default ProvidersPage;
