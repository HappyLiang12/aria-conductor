import { useMemo, type ReactNode } from 'react';
import { Thread, type ThreadComponents } from '../components/assistant-ui/elements/thread.aui';

/** The page owns the composer (slash commands, stop semantics), so the thread keeps only the transcript. */
const NoComposer = () => null;

/**
 * The Aria transcript (Task 7). The generated assistant-ui thread owns the
 * message list, the streaming assistant message, the decision tool cards and
 * their part groups; the page keeps the composer and the empty state.
 *
 * `indicator` renders in place of the default pending dot while the run is live
 * — the page passes the live ProgressTimeline there.
 */
export function AriaThread({ indicator }: { indicator?: ReactNode }) {
  const components = useMemo<ThreadComponents>(() => ({
    Composer: NoComposer,
    Indicator: indicator ? () => <>{indicator}</> : undefined,
  }), [indicator]);

  return (
    <div className="mc-stack">
      <Thread components={components} />
    </div>
  );
}

export default AriaThread;
