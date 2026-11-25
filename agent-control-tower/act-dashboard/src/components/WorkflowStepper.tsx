import { useQuery } from '@tanstack/react-query';
import { getRunToolCalls } from '../api/runs';
import { Badge } from './ui/badge';

const STEPS: { key: string; label: string; tools: string[] }[] = [
  { key: 'discover', label: 'Discover', tools: ['web_fetch', 'http_request', 'query_knowledge', 'search_knowledge'] },
  { key: 'delegate', label: 'Delegate', tools: ['create_agent', 'run_agent'] },
  { key: 'clone', label: 'Clone', tools: ['git_clone'] },
  { key: 'edit', label: 'Edit', tools: ['write_file', 'read_file'] },
  { key: 'commit', label: 'Commit', tools: ['git_commit', 'git_add', 'git_checkout'] },
  { key: 'push', label: 'Push', tools: ['git_push'] },
  { key: 'pr', label: 'Open PR', tools: ['git_create_pr'] },
];

/**
 * Derives a governed dev-workflow progress bar (discover → delegate → clone → edit → commit →
 * push → PR) from a run's tool-call trajectory, giving operators an at-a-glance sense of where the
 * agent is in the flow.
 *
 * Wave 2 D2: each step is a `Badge` token surface; the `.step-chip`/`.done` hooks, the
 * `role="list"` contract, the numbering and the done marker are unchanged.
 */
export default function WorkflowStepper({ runId }: { runId: string }) {
  const { data: toolCalls } = useQuery({
    queryKey: ['run-tool-calls', runId],
    queryFn: () => getRunToolCalls(runId),
    enabled: !!runId,
    refetchInterval: 5000,
  });
  const used = new Set((toolCalls ?? []).map((t) => t.toolName));
  return (
    <div
      className="workflow-stepper my-1.5 flex flex-wrap items-center gap-1.5"
      role="list"
      aria-label="Development workflow progress"
    >
      {STEPS.map((s, i) => {
        const done = s.tools.some((t) => used.has(t));
        return (
          <Badge
            key={s.key}
            role="listitem"
            variant={done ? 'default' : 'outline'}
            className={`step-chip${done ? ' done' : ''}`}
          >
            {done ? '✓ ' : `${i + 1}. `}
            {s.label}
          </Badge>
        );
      })}
    </div>
  );
}
