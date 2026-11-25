import type { ReactElement } from 'react';
import type { AttentionRow } from '../hooks/useAttentionQueue';
import type { QueryPhase } from '../utils/queryState';
import type { PresentedError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { StateNotice } from './StateNotice';
import { QueueSection } from './overview/QueueSection';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';

export interface AttentionActionError {
  id: string;
  presented: PresentedError;
}

export interface AttentionQueueProps {
  rows: AttentionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  retry: () => void;
  onOpenTask: (row: AttentionRow) => void;
  onRetryPickup: (row: AttentionRow) => void;
  onOpenConfigure: () => void;
  busyId?: string | null;
  actionError?: AttentionActionError | null;
  collapsed: boolean;
}

function Actions({ row, busy, onOpenTask, onRetryPickup, onOpenConfigure }: {
  row: AttentionRow;
  busy: boolean;
  onOpenTask: (row: AttentionRow) => void;
  onRetryPickup: (row: AttentionRow) => void;
  onOpenConfigure: () => void;
}): ReactElement {
  if (row.kind === 'provider') {
    return (
      <Button type="button" variant="outline" size="sm" onClick={onOpenConfigure}>
        Open Configure
      </Button>
    );
  }
  return (
    <>
      {row.taskId && (
        <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => onOpenTask(row)}>
          Open task
        </Button>
      )}
      {row.kind === 'pickup' && row.taskId && (
        <Button type="button" size="sm" disabled={busy} onClick={() => onRetryPickup(row)}>
          Retry pickup (starts 1 run)
        </Button>
      )}
    </>
  );
}

/** "Needs attention" rows; no batch action and no fabricated retry. */
export function AttentionQueue({
  rows,
  phase,
  partial,
  count,
  retry,
  onOpenTask,
  onRetryPickup,
  onOpenConfigure,
  busyId = null,
  actionError = null,
  collapsed,
}: AttentionQueueProps): ReactElement {
  return (
    <QueueSection
      className="attention-queue"
      countClassName="attention-queue__count text-sm! font-semibold! text-muted-foreground!"
      countAriaLabel={`${count ?? 'unknown'} items need attention`}
      title="Needs attention"
      accent="recovery"
      collapsedLabel="Needs attention · Nothing needs attention"
      count={count}
      collapsed={collapsed}
      testId="attention-queue"
    >
      {phase === 'loading' && <StateNotice kind="loading" title="Loading attention items…" />}
      {phase === 'error' && (
        <StateNotice
          kind="error"
          title="Attention queue unavailable"
          message="The recovery sources could not be loaded."
          actionLabel="Retry"
          onAction={retry}
        />
      )}
      {(phase === 'stale' || partial) && phase !== 'error' && (
        <StateNotice
          kind="stale"
          title="Refresh failed — showing last known attention items"
          actionLabel="Retry"
          onAction={retry}
        />
      )}
      {phase === 'success' && !partial && rows.length === 0 && (
        <StateNotice kind="empty" title="Nothing needs attention" />
      )}

      {rows.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {rows.map((row) => (
            <li
              className="attention-row list-none border-0! bg-transparent! p-0!"
              key={row.id}
              data-testid={`attention-row-${row.id}`}
            >
              <Card size="sm">
                <CardContent className="space-y-2">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <strong className="min-w-0 text-sm font-semibold break-words text-card-foreground">
                      {row.title}
                    </strong>
                    <Badge variant="outline">{row.kind}</Badge>
                    {row.cardStatus && <Badge variant="secondary">{row.cardStatus}</Badge>}
                  </div>
                  <p className="text-xs break-words text-muted-foreground">{row.reason}</p>
                  <div className="flex flex-wrap items-end gap-2">
                    <Actions
                      row={row}
                      busy={busyId === row.id}
                      onOpenTask={onOpenTask}
                      onRetryPickup={onRetryPickup}
                      onOpenConfigure={onOpenConfigure}
                    />
                  </div>
                  {actionError?.id === row.id && <ErrorNotice presented={actionError.presented} />}
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}
    </QueueSection>
  );
}

export default AttentionQueue;
