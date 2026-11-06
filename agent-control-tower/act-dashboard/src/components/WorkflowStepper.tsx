import { useQuery } from '@tanstack/react-query';
import { getRunToolCalls } from '../api/runs';

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
      className="workflow-stepper"
      role="list"
      aria-label="Development workflow progress"
      style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center', margin: '6px 0' }}
    >
      {STEPS.map((s, i) => {
        const done = s.tools.some((t) => used.has(t));
        return (
          <span
            key={s.key}
            role="listitem"
            className={`step-chip${done ? ' done' : ''}`}
            style={{
              fontSize: 10.5,
              padding: '2px 8px',
              borderRadius: 999,
              border: '1px solid var(--line-2, #26304d)',
              background: done
                ? 'linear-gradient(135deg, var(--brand,#5b8cff), var(--accent,#8b5bff))'
                : 'transparent',
              color: done ? '#fff' : 'var(--text-mute, #8b95b5)',
            }}
          >
            {done ? '✓ ' : `${i + 1}. `}
            {s.label}
          </span>
        );
      })}
    </div>
  );
}
