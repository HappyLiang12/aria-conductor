import { useQuery } from '@tanstack/react-query';
import { getRunToolCalls } from '../api/runs';

function parseArg(args: string, key: string): string | null {
  try {
    const o = JSON.parse(args || '{}');
    const v = o[key];
    return v == null ? null : String(v);
  } catch {
    return null;
  }
}

/**
 * Parent → worker delegation view derived from the orchestrator run's tool calls (create_agent /
 * run_agent). Makes it visible which workers Aria spun up and delegated to, without needing a
 * dedicated parentRunId schema column.
 */
export default function DelegationTree({ runId, agentName }: { runId: string; agentName?: string }) {
  const { data: toolCalls } = useQuery({
    queryKey: ['run-tool-calls', runId],
    queryFn: () => getRunToolCalls(runId),
    enabled: !!runId,
    refetchInterval: 5000,
  });
  const delegations = (toolCalls ?? []).filter(
    (t) => t.toolName === 'run_agent' || t.toolName === 'create_agent',
  );
  if (delegations.length === 0) return null;
  return (
    <div className="delegation-tree" style={{ margin: '6px 0', fontSize: 11 }}>
      <div
        style={{
          color: 'var(--text-mute)',
          textTransform: 'uppercase',
          letterSpacing: 1,
          fontSize: 10,
          marginBottom: 4,
        }}
      >
        Delegation
      </div>
      <div style={{ paddingLeft: 8, borderLeft: '2px solid var(--line-2, #26304d)' }}>
        <div style={{ fontWeight: 600 }}>{agentName || 'Orchestrator'}</div>
        {delegations.map((d) => {
          const label =
            d.toolName === 'create_agent'
              ? `created worker · ${parseArg(d.arguments, 'role') || parseArg(d.arguments, 'name') || 'agent'}`
              : `ran agent · ${parseArg(d.arguments, 'agentId')?.slice(0, 8) || 'worker'}`;
          return (
            <div key={d.id} style={{ paddingLeft: 12, color: 'var(--text-dim)' }}>
              ↳ {label}{' '}
              <span style={{ color: 'var(--text-mute)' }}>({d.status.toLowerCase()})</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
