import { useId, useState } from 'react';
import type { ReactElement } from 'react';
import type { DecisionRow } from '../hooks/useDecisionQueue';
import type { QueryPhase } from '../utils/queryState';
import type { PresentedError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { StateNotice } from './StateNotice';
import '../styles/decision-queue.css';

export interface DecisionActionError {
  id: string;
  presented: PresentedError;
}

export interface DecisionQueueProps {
  rows: DecisionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  retry: () => void;
  onActivate: (row: DecisionRow) => void;
  onDecide: (row: DecisionRow, approved: boolean) => void;
  onAnswer: (row: DecisionRow, answer: string) => void;
  busyIds?: ReadonlySet<string>;
  actionError?: DecisionActionError | null;
  collapsed: boolean;
}

const EMPTY_BUSY_IDS: ReadonlySet<string> = new Set();

function waited(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return 'waiting';
  const minutes = Math.max(0, Math.floor((Date.now() - then) / 60000));
  if (minutes < 1) return 'waiting just now';
  if (minutes < 60) return `waiting ${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `waiting ${hours}h`;
  return `waiting ${Math.floor(hours / 24)}d`;
}

function actionLabel(row: DecisionRow): string {
  if (row.isPermission) return 'Review permission';
  if (row.state === 'REVIEW') return 'Review';
  return 'Answer';
}

function Row({ row, busy, error, onActivate, onDecide, onAnswer }: {
  row: DecisionRow;
  busy: boolean;
  error: PresentedError | null;
  onActivate: (row: DecisionRow) => void;
  onDecide: (row: DecisionRow, approved: boolean) => void;
  onAnswer: (row: DecisionRow, answer: string) => void;
}): ReactElement {
  const answerId = useId();
  const [answerOpen, setAnswerOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const taskless = row.taskId === null;
  const clarification = taskless && row.source === 'CLARIFICATION';

  return (
    <li className="decision-row" data-testid={`decision-row-${row.id}`}>
      <div className="decision-row__head">
        <strong className="decision-row__title">{row.title}</strong>
        <span className="pill warn">{row.askType}</span>
        <span className="pill dim">{row.state}</span>
      </div>
      <p className="decision-row__reason">{row.reason}</p>
      <div className="decision-row__meta">
        <span>{waited(row.requestedAt)}</span>
        {taskless && (
          <span className="decision-row__note">
            {clarification ? 'Task details unavailable — answer here.' : 'Task details unavailable — decide here.'}
          </span>
        )}
      </div>
      <div className="decision-row__actions">
        {!taskless && (
          <button type="button" className="btn" disabled={busy} onClick={() => onActivate(row)}>
            {actionLabel(row)}
          </button>
        )}
        {taskless && !clarification && (
          <>
            <button type="button" className="btn primary" disabled={busy} onClick={() => onDecide(row, true)}>
              Approve
            </button>
            <button type="button" className="btn danger" disabled={busy} onClick={() => onDecide(row, false)}>
              Deny
            </button>
          </>
        )}
        {clarification && !answerOpen && (
          <button type="button" className="btn" disabled={busy} onClick={() => setAnswerOpen(true)}>
            Answer
          </button>
        )}
        {clarification && answerOpen && (
          <div className="decision-row__answer">
            <label htmlFor={answerId}>Answer</label>
            <textarea
              id={answerId}
              value={draft}
              rows={2}
              disabled={busy}
              onChange={(event) => setDraft(event.target.value)}
            />
            <button
              type="button"
              className="btn primary"
              disabled={busy || draft.trim().length === 0}
              onClick={() => onAnswer(row, draft.trim())}
            >
              Answer &amp; continue
            </button>
          </div>
        )}
      </div>
      {error && <ErrorNotice presented={error} />}
    </li>
  );
}

/**
 * One row per pending approval. A task-backed row opens the review/answer
 * surface; a taskless row stays actionable in place instead of rendering a
 * permanently disabled control.
 */
export function DecisionQueue({
  rows,
  phase,
  partial,
  count,
  retry,
  onActivate,
  onDecide,
  onAnswer,
  busyIds = EMPTY_BUSY_IDS,
  actionError = null,
  collapsed,
}: DecisionQueueProps): ReactElement {
  const titleId = useId();

  if (collapsed) {
    return (
      <section className="decision-queue" data-testid="decision-queue" data-collapsed="true" aria-labelledby={titleId}>
        <h2 id={titleId} className="decision-queue__collapsed">Waiting on you · No decisions waiting</h2>
      </section>
    );
  }

  return (
    <section className="decision-queue" data-testid="decision-queue" data-collapsed="false" aria-labelledby={titleId}>
      <header className="decision-queue__head">
        <h2 id={titleId}>Waiting on you <span className="accent">· decisions</span></h2>
        <span className="decision-queue__count" aria-label={`${count ?? 'unknown'} decisions waiting`}>
          {count ?? '—'}
        </span>
      </header>

      {phase === 'loading' && <StateNotice kind="loading" title="Loading decisions…" />}
      {phase === 'error' && (
        <StateNotice
          kind="error"
          title="Decision queue unavailable"
          message="The pending approvals could not be loaded."
          actionLabel="Retry"
          onAction={retry}
        />
      )}
      {(phase === 'stale' || partial) && phase !== 'error' && (
        <StateNotice
          kind="stale"
          title="Refresh failed — showing last known decisions"
          actionLabel="Retry"
          onAction={retry}
        />
      )}
      {phase === 'success' && !partial && rows.length === 0 && (
        <StateNotice kind="empty" title="No decisions waiting" />
      )}

      {rows.length > 0 && (
        <ul className="decision-queue__list">
          {rows.map((row) => (
            <Row
              key={row.id}
              row={row}
              busy={busyIds.has(row.id)}
              error={actionError?.id === row.id ? actionError.presented : null}
              onActivate={onActivate}
              onDecide={onDecide}
              onAnswer={onAnswer}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

export default DecisionQueue;
