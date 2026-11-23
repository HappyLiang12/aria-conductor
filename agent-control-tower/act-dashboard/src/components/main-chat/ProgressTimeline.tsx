import { useEffect, useState } from 'react';
import { getRunToolCalls } from '../../api/runs';
import type { ToolCall } from '../../types';
import type { ProgressItem } from './progress';
import { renderMarkdown } from '../../utils/markdown';

function clip(text: string, max = 140): string {
  const oneLine = text.replace(/\s+/g, ' ').trim();
  return oneLine.length > max ? `${oneLine.slice(0, max)}…` : oneLine;
}

function StepRow({ name, state, detail }: { name: string; state: 'running' | 'done' | 'failed'; detail: string }) {
  return (
    <div className="mc-step" data-state={state}>
      <span className="mc-step-dot" aria-hidden="true" />
      <code className="mc-step-name">{name}</code>
      {detail && <span className="mc-step-detail">{clip(detail)}</span>}
    </div>
  );
}

/** The live turn's activity and answer parts, in the order the core reported them. */
export function ProgressTimeline({ items }: { items: ProgressItem[] }) {
  if (items.length === 0) return null;
  return (
    <div className="mc-progress" data-testid="mc-progress">
      {items.map((item) => {
        if (item.kind === 'answer') {
          return (
            <div
              key={item.key}
              className="ai-msg-md markdown-content mc-live-answer"
              dangerouslySetInnerHTML={{ __html: renderMarkdown(item.text) }}
            />
          );
        }
        if (item.kind === 'tool') {
          return (
            <StepRow
              key={item.key}
              name={item.toolName || 'tool'}
              state={item.toolDone ? 'done' : 'running'}
              detail={item.text}
            />
          );
        }
        if (item.kind === 'thinking') {
          return (
            <details key={item.key} className="mc-thinking">
              <summary>Thinking</summary>
              <div>{item.text}</div>
            </details>
          );
        }
        return (
          <div key={item.key} className={`mc-status${item.kind === 'error' ? ' mc-status-error' : ''}`}>
            {clip(item.text, 240)}
          </div>
        );
      })}
    </div>
  );
}

/**
 * The steps behind a finished turn. A live turn passes its already-merged
 * steps; a turn loaded from history fetches the tool-call audit rows the first
 * time it is opened.
 */
export function StepsDisclosure({ runId, liveSteps }: { runId: string; liveSteps?: ProgressItem[] }) {
  const [open, setOpen] = useState(false);
  const [calls, setCalls] = useState<ToolCall[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || liveSteps || calls) return;
    let cancelled = false;
    getRunToolCalls(runId)
      .then((rows) => { if (!cancelled) setCalls(rows); })
      .catch((err) => { if (!cancelled) setError(err instanceof Error ? err.message : String(err)); });
    return () => { cancelled = true; };
  }, [open, liveSteps, calls, runId]);

  const stepCount = liveSteps ? liveSteps.length : calls?.length;

  return (
    <details
      className="mc-steps"
      onToggle={(event) => setOpen((event.currentTarget as HTMLDetailsElement).open)}
    >
      <summary>{stepCount != null && stepCount > 0 ? `Worked · ${stepCount} step${stepCount === 1 ? '' : 's'}` : 'Steps'}</summary>
      {liveSteps ? (
        liveSteps.map((item) => (
          <StepRow
            key={item.key}
            name={item.toolName || 'tool'}
            state={item.toolDone ? 'done' : 'running'}
            detail={item.text}
          />
        ))
      ) : error ? (
        <div className="mc-status mc-status-error">{error}</div>
      ) : calls === null ? (
        <div className="mc-status">Loading steps…</div>
      ) : calls.length === 0 ? (
        <div className="mc-status">No tool calls were recorded for this turn.</div>
      ) : (
        calls.map((call) => (
          <StepRow
            key={call.id}
            name={call.toolName}
            state={call.status === 'FAILED' || call.status === 'DENIED'
              ? 'failed'
              : call.status === 'COMPLETED' ? 'done' : 'running'}
            detail={call.arguments}
          />
        ))
      )}
    </details>
  );
}
