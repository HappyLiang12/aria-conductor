import { useQuery } from '@tanstack/react-query';
import { getRunToolCalls } from '../api/runs';
import { Card } from './ui/card';

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
 *
 * Wave 2 D2: the panel is a token `Card`; the `.delegation-tree` hook and the worker copy stay.
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
    <Card size="sm" className="delegation-tree my-1.5 text-[11px]">
      <div className="mb-1 px-(--card-spacing) text-[10px] tracking-wider text-muted-foreground uppercase">
        Delegation
      </div>
      <div className="mx-(--card-spacing) border-l-2 border-border pl-2">
        <div className="font-semibold text-foreground">{agentName || 'Orchestrator'}</div>
        {delegations.map((d) => {
          const label =
            d.toolName === 'create_agent'
              ? `created worker · ${parseArg(d.arguments, 'role') || parseArg(d.arguments, 'name') || 'agent'}`
              : `ran agent · ${parseArg(d.arguments, 'agentId')?.slice(0, 8) || 'worker'}`;
          return (
            <div key={d.id} className="pl-3 text-muted-foreground">
              ↳ {label}{' '}
              <span className="text-muted-foreground">({d.status.toLowerCase()})</span>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
