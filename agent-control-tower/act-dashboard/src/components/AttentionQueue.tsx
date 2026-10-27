import { useId } from 'react';
import type { ReactElement } from 'react';
import type { AttentionRow } from '../hooks/useAttentionQueue';
import type { QueryPhase } from '../utils/queryState';
import type { PresentedError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { StateNotice } from './StateNotice';
import '../styles/attention-queue.css';

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
      <button type="button" className="btn" onClick={onOpenConfigure}>
        Open Configure
      </button>
    );
  }
  return (
    <>
      {row.taskId && (
        <button type="button" className="btn" disabled={busy} onClick={() => onOpenTask(row)}>
          Open task
        </button>
      )}
      {row.kind === 'pickup' && row.taskId && (
        <button type="button" className="btn primary" disabled={busy} onClick={() => onRetryPickup(row)}>
          Retry pickup (starts 1 run)
        </button>
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
  const titleId = useId();

  if (collapsed) {
    return (
      <section className="attention-queue" data-testid="attention-queue" data-collapsed="true" aria-labelledby={titleId}>
        <h2 id={titleId} className="attention-queue__collapsed">Needs attention · Nothing needs attention</h2>
      </section>
    );
  }

  return (
    <section className="attention-queue" data-testid="attention-queue" data-collapsed="false" aria-labelledby={titleId}>
      <header className="attention-queue__head">
        <h2 id={titleId}>Needs attention <span className="accent">· recovery</span></h2>
        <span className="attention-queue__count" aria-label={`${count ?? 'unknown'} items need attention`}>
          {count ?? '—'}
        </span>
      </header>

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
      {phase === 'success' && rows.length === 0 && (
        <StateNotice kind="empty" title="Nothing needs attention" />
      )}

      {rows.length > 0 && (
        <ul className="attention-queue__list">
          {rows.map((row) => (
            <li className="attention-row" key={row.id} data-testid={`attention-row-${row.id}`}>
              <div className="attention-row__head">
                <strong className="attention-row__title">{row.title}</strong>
                <span className="pill dim">{row.kind}</span>
                {row.cardStatus && <span className="pill">{row.cardStatus}</span>}
              </div>
              <p className="attention-row__reason">{row.reason}</p>
              <div className="attention-row__actions">
                <Actions
                  row={row}
                  busy={busyId === row.id}
                  onOpenTask={onOpenTask}
                  onRetryPickup={onRetryPickup}
                  onOpenConfigure={onOpenConfigure}
                />
              </div>
              {actionError?.id === row.id && <ErrorNotice presented={actionError.presented} />}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export default AttentionQueue;
