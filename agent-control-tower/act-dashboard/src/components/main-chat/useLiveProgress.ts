import { useCallback, useEffect, useState } from 'react';
import { useWebSocketContext } from '../Layout';
import { getRunProgress } from '../../api/runs';
import { applyProgress, emptyProgress, type ProgressEntryLike, type ProgressState } from './progress';

/**
 * Live progress of one Aria turn. WS `run.progress` frames are merged while
 * `activeRunId` is set; `replay` fills whatever the socket missed from the
 * persisted rows (the SSE `message` remains the authoritative answer either
 * way). The last state survives `activeRunId` becoming null so the finished
 * turn can show its steps.
 */
export function useLiveProgress(activeRunId: string | null) {
  const { subscribe } = useWebSocketContext();
  const [progress, setProgress] = useState<ProgressState | null>(null);

  useEffect(() => {
    if (!activeRunId) return;
    setProgress(emptyProgress(activeRunId));
    const subscription = subscribe((event) => {
      if (event.type !== 'run.progress') return;
      const payload = event.payload as unknown as ProgressEntryLike;
      if (!payload || payload.runId !== activeRunId) return;
      setProgress((prev) =>
        prev && prev.runId === activeRunId ? applyProgress(prev, payload) : prev);
    });
    return () => subscription.unsubscribe();
  }, [activeRunId, subscribe]);

  const replay = useCallback(async (runId: string) => {
    try {
      const rows = await getRunProgress(runId, 0);
      setProgress((prev) =>
        prev && prev.runId === runId
          ? rows.reduce((acc, row) => applyProgress(acc, row as ProgressEntryLike), prev)
          : prev);
    } catch {
      // Best effort: the final SSE message still carries the whole answer.
    }
  }, []);

  return { progress, replay };
}
