import { useQuery, useQueries } from '@tanstack/react-query';
import { listAgents } from '../api/agents';
import { listAdkProviders, getAdkProviderHealth } from '../api/adk';
import { RuntimeCredentialsCard } from '../components/RuntimeCredentialsCard';

interface HealthBadgeProps {
  healthy: boolean;
}

function HealthBadge({ healthy }: HealthBadgeProps) {
  const color = healthy ? '#4caf50' : '#f44336';
  return (
    <span
      className="status-badge status-badge-md"
      style={{ backgroundColor: color + '22', color, borderColor: color }}
    >
      {healthy ? 'Healthy' : 'Unhealthy'}
    </span>
  );
}

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
    <div className="page">
      <div className="page-header">
        <h2>Agent Providers</h2>
      </div>

      {/* Provider inventory */}
      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading providers...</span></div>}
      {error && <div className="error-state">Failed to load providers. Please retry.</div>}

      {!isLoading && !error && (providers?.length ?? 0) === 0 && (
        <div className="empty-state">No providers registered.</div>
      )}

      {(providers?.length ?? 0) > 0 && (
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Display Name</th>
                <th>Capability</th>
                <th>Execution modes</th>
                <th>Default</th>
                <th>Health</th>
              </tr>
            </thead>
            <tbody>
              {providers?.map((p, i) => {
                const health = healthResults[i]?.data;
                return (
                  <tr key={p.id}>
                    <td className="cell-mono">{p.id}</td>
                    <td className="cell-primary">{p.displayName}</td>
                    <td><span className="type-badge">{p.supportsTaskExecution ? 'Task' : 'Turn'}</span></td>
                    <td className="cell-mono">
                      {p.executionModes && p.executionModes.length > 0
                        ? p.executionModes.join(' · ')
                        : 'not declared'}
                    </td>
                    <td>{p.isDefault && <span className="type-badge">Default</span>}</td>
                    <td>
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
      )}

      {/* Managed credential (spec 6.1) — masked metadata, explicit test, removal. */}
      <RuntimeCredentialsCard />

      {/* Per-agent backend overview */}
      <div className="card" style={{ marginTop: 24 }}>
        <h3 className="form-title">Per-Agent Backends</h3>
        <p style={{ color: 'var(--text-dim)', fontSize: 12 }}>
          Core and execution mode are stored per agent; a run is admitted against them and is
          never silently re-routed to another core or mode.
        </p>
        {agentsLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading agents...</span></div>}
        {agentsError && <div className="error-state">Failed to load agents. Please retry.</div>}
        {!agentsLoading && !agentsError && (agents?.length ?? 0) === 0 && (
          <div className="empty-state">No agents registered yet.</div>
        )}
        {(agents?.length ?? 0) > 0 && (
          <table className="data-table">
            <thead>
              <tr>
                <th>Agent</th>
                <th>Agent core</th>
                <th>Execution mode</th>
                <th>Readiness</th>
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
                  <tr key={agent.id} className={registered ? undefined : 'row-active'}>
                    <td className="cell-primary">{agent.name}</td>
                    <td>
                      {core == null || core === '' ? (
                        // Legacy record without a stored core: explicit unknown,
                        // not an invented default.
                        <span className="cell-mono">Not recorded</span>
                      ) : registered ? (
                        <span className="cell-mono">{core}</span>
                      ) : (
                        <span
                          className="type-badge"
                          style={{ backgroundColor: 'rgba(255,107,122,.12)', color: '#ff97a3', borderColor: 'rgba(255,107,122,.4)' }}
                        >
                          Unsupported core: {core}
                        </span>
                      )}
                    </td>
                    <td className="cell-mono">
                      {agent.executionMode ?? 'Not recorded'}
                    </td>
                    <td>
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
        )}
      </div>
    </div>
  );
}

export default ProvidersPage;
