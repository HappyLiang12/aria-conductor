import { useId, useState } from 'react';
import type { ReactElement } from 'react';
import type { DecisionRow } from '../hooks/useDecisionQueue';
import type { QueryPhase } from '../utils/queryState';
import type { PresentedError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { StateNotice } from './StateNotice';
import { QueueSection } from './overview/QueueSection';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';
import { Textarea } from './ui/textarea';

export interface DecisionActionError {
  id: string;
  presented: PresentedError;
}

export interface DecisionQueueProps {
  rows: DecisionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  reviewCount: number | null;
  retry: () => void;
  onActivate: (row: DecisionRow) => void;
  onDecide: (row: DecisionRow, approved: boolean) => void;
  onAnswer: (row: DecisionRow, answer: string) => void;
  onOpenReviews?: () => void;
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

function expiryLabel(iso: string | null): string | null {
  if (!iso) return null;
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return null;
  const minutes = Math.ceil((then - Date.now()) / 60000);
  if (minutes <= 0) return 'expiring';
  if (minutes < 60) return `expires in ${minutes}m`;
  return `expires in ${Math.ceil(minutes / 60)}h`;
}

type RiskBadgeVariant = 'destructive' | 'secondary' | 'outline';

function riskOf(row: DecisionRow): { label: string; variant: RiskBadgeVariant } {
  switch (row.riskTier) {
    case 'DESTRUCTIVE':
      return { label: 'Dangerous', variant: 'destructive' };
    case 'PUSH':
      return { label: 'Writes', variant: 'secondary' };
    case 'WRITE_LOCAL':
      return { label: 'Writes locally', variant: 'secondary' };
    case 'READ':
      return { label: 'Read', variant: 'outline' };
    default:
      // An unrecognised tier is shown verbatim, never as a reassuring label.
      if (row.riskTier) return { label: row.riskTier, variant: 'outline' };
      if (row.approvalType === 'SPEC_REVIEW') return { label: 'Spec review', variant: 'secondary' };
      if (row.source === 'CLARIFICATION') return { label: 'Question', variant: 'secondary' };
      return { label: 'Approval', variant: 'outline' };
  }
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
  const risk = riskOf(row);
  const expiry = expiryLabel(row.expiresAt);
  const slotNote = row.blocksRunSlot
    ? `Holds a run slot — ${taskless ? 'task details unavailable — answer here.' : 'open the card to answer.'}`
    : null;

  return (
    <li
      className="decision-row list-none border-0! bg-transparent! p-0!"
      data-risk={row.riskTier ?? undefined}
      data-testid={`decision-row-${row.id}`}
    >
      <Card size="sm">
        <CardContent className="space-y-2">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <strong className="min-w-0 text-sm font-semibold break-words text-card-foreground">
              {row.title}
            </strong>
            <Badge variant={risk.variant}>{risk.label}</Badge>
            {row.toolName && (
              <span className="font-mono text-xs text-muted-foreground">{row.toolName}</span>
            )}
          </div>
          <p className="line-clamp-2 text-xs break-words text-muted-foreground">{row.reason}</p>
          <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
            <span>{waited(row.requestedAt)}</span>
            {expiry && <span>{expiry}</span>}
            {slotNote && <span className="text-amber-500">{slotNote}</span>}
            {taskless && !clarification && (
              <span className="text-amber-500">Task details unavailable — decide here.</span>
            )}
          </div>
          <div className="flex flex-wrap items-end gap-2">
            {!taskless && (
              <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => onActivate(row)}>
                {actionLabel(row)}
              </Button>
            )}
            {taskless && !clarification && (
              <>
                <Button type="button" size="sm" disabled={busy} onClick={() => onDecide(row, true)}>
                  Approve
                </Button>
                <Button type="button" variant="destructive" size="sm" disabled={busy} onClick={() => onDecide(row, false)}>
                  Deny
                </Button>
              </>
            )}
            {clarification && !answerOpen && (
              <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setAnswerOpen(true)}>
                Answer
              </Button>
            )}
            {clarification && answerOpen && (
              <div className="flex w-full flex-col gap-1.5">
                <label htmlFor={answerId} className="text-xs text-muted-foreground">Answer</label>
                <Textarea
                  id={answerId}
                  className="min-h-0"
                  value={draft}
                  rows={2}
                  disabled={busy}
                  onChange={(event) => setDraft(event.target.value)}
                />
                <Button
                  type="button"
                  size="sm"
                  className="self-start"
                  disabled={busy || draft.trim().length === 0}
                  onClick={() => onAnswer(row, draft.trim())}
                >
                  Answer &amp; continue
                </Button>
              </div>
            )}
          </div>
          {/* Task 37 / Item 6: these decide/answer routes are operator-only, and
              `presentError` already marks the refusal. Rendering the bare text left
              the operator with "Decision failed." and no remedy; the shared notice
              replaces it and names the action that was refused. A non-refusal keeps
              the ordinary error path. */}
          {error && (
            <ErrorNotice
              presented={error}
              operatorSession
              actionLabel={clarification ? 'Answering the ask' : 'Deciding the ask'}
            />
          )}
        </CardContent>
      </Card>
    </li>
  );
}

/**
 * The operator's decision surface. Rows are only asks that block work, ordered
 * dangerous-first; post-run REVIEW_REQUEST asks block nothing and collapse to a
 * single count that links to the board's REVIEW column.
 */
export function DecisionQueue({
  rows,
  phase,
  partial,
  count,
  reviewCount,
  retry,
  onActivate,
  onDecide,
  onAnswer,
  onOpenReviews,
  busyIds = EMPTY_BUSY_IDS,
  actionError = null,
  collapsed,
}: DecisionQueueProps): ReactElement {
  const reviews = reviewCount ?? 0;

  return (
    <QueueSection
      className="decision-queue"
      countClassName="decision-queue__count text-sm! font-semibold! text-muted-foreground!"
      countAriaLabel={`${count ?? 'unknown'} decisions waiting`}
      title="Needs your decision"
      collapsedLabel="Needs your decision · No decisions waiting"
      count={count}
      collapsed={collapsed}
      testId="decision-queue"
    >
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
      {phase === 'success' && !partial && rows.length === 0 && reviews === 0 && (
        <StateNotice kind="empty" title="No decisions waiting" />
      )}

      {rows.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
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

      {reviewCount !== null && reviewCount > 0 && (
        <div
          className="flex items-center gap-3 border-t pt-3 text-xs text-muted-foreground"
          data-testid="decision-reviews"
        >
          <span>
            {reviewCount} run{reviewCount === 1 ? '' : 's'} awaiting review
          </span>
          {onOpenReviews && (
            <Button type="button" variant="outline" size="sm" className="ml-auto" onClick={onOpenReviews}>
              Open review board
            </Button>
          )}
        </div>
      )}
    </QueueSection>
  );
}

export default DecisionQueue;
