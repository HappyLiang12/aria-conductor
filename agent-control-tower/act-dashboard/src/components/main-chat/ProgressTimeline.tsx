import { useEffect, useState } from 'react';
import { getRunToolCalls } from '../../api/runs';
import type { ToolCall } from '../../types';
import { Card } from '../ui/card';
import type { ProgressItem } from './progress';
import { renderMarkdown } from '../../utils/markdown';

function clip(text: string, max = 140): string {
  const oneLine = text.replace(/\s+/g, ' ').trim();
  return oneLine.length > max ? `${oneLine.slice(0, max)}…` : oneLine;
}

/** The dot colour of a step's state, kept beside the `.mc-step` hook. */
function stepDotClass(state: 'running' | 'done' | 'failed'): string {
  if (state === 'running') return 'mc-step-dot size-1.5 shrink-0 rounded-full bg-primary';
  if (state === 'failed') return 'mc-step-dot size-1.5 shrink-0 rounded-full bg-destructive';
  return 'mc-step-dot size-1.5 shrink-0 rounded-full bg-muted-foreground';
}

function StepRow({ name, state, detail }: { name: string; state: 'running' | 'done' | 'failed'; detail: string }) {
  return (
    <div className="mc-step flex items-baseline gap-2 text-xs text-muted-foreground" data-state={state}>
      <span className={stepDotClass(state)} aria-hidden="true" />
      <code className="mc-step-name font-mono text-[11.5px] text-foreground">{name}</code>
      {detail && (
        <span className="mc-step-detail overflow-hidden text-ellipsis whitespace-nowrap text-muted-foreground">
          {clip(detail)}
        </span>
      )}
    </div>
  );
}

/**
 * The live turn's activity and answer parts, in the order the core reported them.
 *
 * Wave 2 D6: the timeline is a token Card; `.mc-progress`, its `mc-progress` test
 * id and the `.mc-step*` hooks stay because the page/E2E suite drives them.
 */
export function ProgressTimeline({ items }: { items: ProgressItem[] }) {
  if (items.length === 0) return null;
  return (
    <Card
      className="mc-progress flex w-full max-w-[88%] flex-col gap-1.5 rounded-2xl border border-dashed border-border bg-card p-2.5 [--card-spacing:0px]"
      data-testid="mc-progress"
    >
      {items.map((item) => {
        if (item.kind === 'answer') {
          return (
            <div
              key={item.key}
              className="ai-msg-md markdown-content mc-live-answer max-w-full text-sm text-card-foreground"
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
            <details key={item.key} className="mc-thinking text-xs text-muted-foreground">
              <summary className="cursor-pointer">Thinking</summary>
              <div className="mt-1 whitespace-pre-wrap">{item.text}</div>
            </details>
          );
        }
        return (
          <div
            key={item.key}
            className={`mc-status text-xs text-muted-foreground${item.kind === 'error' ? ' mc-status-error text-destructive' : ''}`}
          >
            {clip(item.text, 240)}
          </div>
        );
      })}
    </Card>
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
      className="mc-steps text-[11px] text-muted-foreground"
      onToggle={(event) => setOpen((event.currentTarget as HTMLDetailsElement).open)}
    >
      <summary className="cursor-pointer">{stepCount != null && stepCount > 0 ? `Worked · ${stepCount} step${stepCount === 1 ? '' : 's'}` : 'Steps'}</summary>
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
        <div className="mc-status mc-status-error text-xs text-destructive">{error}</div>
      ) : calls === null ? (
        <div className="mc-status text-xs text-muted-foreground">Loading steps…</div>
      ) : calls.length === 0 ? (
        <div className="mc-status text-xs text-muted-foreground">No tool calls were recorded for this turn.</div>
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
