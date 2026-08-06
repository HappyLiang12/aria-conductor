import { useQuery, useQueries } from '@tanstack/react-query';
import { listAgents } from '../api/agents';
import { listAdkProviders, getAdkProviderHealth } from '../api/adk';

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

  const defaultProviderId = providers?.find((p) => p.isDefault)?.id ?? 'langchain';

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

      {/* Per-agent backend overview */}
      <div className="card" style={{ marginTop: 24 }}>
        <h3 className="form-title">Per-Agent Backends</h3>
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
                <th>ADK Provider</th>
              </tr>
            </thead>
            <tbody>
              {agents?.map((agent) => {
                const effective = agent.adkProvider || defaultProviderId;
                const nonDefault = effective !== defaultProviderId;
                return (
                  <tr key={agent.id} className={nonDefault ? 'row-active' : undefined}>
                    <td className="cell-primary">{agent.name}</td>
                    <td>
                      {nonDefault ? <span className="type-badge">{effective}</span> : effective}
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
