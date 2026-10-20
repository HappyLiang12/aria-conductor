import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { transitionKanbanItem } from '../api/kanban';
import { answerAsk, approveApproval, finalizeRun, rejectApproval } from '../api/approvals';
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import { formatTimestamp } from '../utils/formatTime';
import {
  NativePermissionFacts,
  NativePermissionKindPill,
  nativePermissionOf,
} from './NativePermissionAsk';
import type { Approval, ApprovalDecisionReceipt, KanbanItem, KanbanStatus, RunStatus } from '../types';

interface PanelProps {
  item: KanbanItem;
  pendingAsks: Approval[];
}

function AskMeta({ ask, permission }: { ask: Approval; permission: ReturnType<typeof nativePermissionOf> }) {
  if (permission) {
    return (
      <div className="ask-meta">
        <NativePermissionKindPill permission={permission} />
        <NativePermissionFacts ask={ask} permission={permission} />
      </div>
    );
  }
  return (
    <div className="ask-meta">
      <span className="pill">{ask.approvalType ?? 'TOOL_CALL'}</span>
      {ask.riskTier && <span className="pill warn">risk {ask.riskTier}</span>}
      {ask.expiresAt && <span className="owner">expires {formatTimestamp(ask.expiresAt)}</span>}
    </div>
  );
}

/** Ask decision surface - used by the collapsed drawer and the ReviewWorkspace rail. */
export function DecisionPanel({ item, pendingAsks }: PanelProps) {
  const queryClient = useQueryClient();
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [requestFeedback, setRequestFeedback] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [operatorRejected, setOperatorRejected] = useState(false);

  // A native permission ask is authorizable exactly once, never in bulk: the
  // batch control below skips them so a batch action can never stand in for a
  // persistent native authorization (spec §6.3). They stay decidable one by one.
  // A CLARIFICATION ask (waiting-input spec §5) is likewise never bulk-resolved:
  // it is answered (which wakes the parked run), not approved.
  const nativeAsks = pendingAsks.filter((ask) => nativePermissionOf(ask) !== null);
  const batchApprovable = pendingAsks.filter(
    (ask) => nativePermissionOf(ask) === null && ask.source !== 'CLARIFICATION'
  );

  const resolveAsk = useMutation({
    mutationFn: ({
      ask,
      approved,
      answer,
    }: {
      ask: Approval;
      approved: boolean;
      answer?: string;
    }): Promise<Approval | ApprovalDecisionReceipt> => {
      // Operator-only routes: carry the locally established session's CSRF token.
      applyOperatorHeaders();
      return ask.askType === 'QUESTION'
        ? answerAsk(ask.id, { approved, answer })
        : approved
          ? approveApproval(ask.id, answer)
          : rejectApproval(ask.id, answer);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      // Badge + Waiting-on-you staleness: cards carry pendingAskCount.
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      // Resolving changes run state too (approve resumes a paused run, an
      // answer wakes a WAITING_INPUT run) — the same symmetry finalize has.
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: unknown) => {
      setOperatorRejected(isOperatorRejection(err));
      setError(apiErrorMessage(err, 'Action rejected — please retry.'));
    },
  });
  // Waiting-input (2026-10-05): end a run parked in WAITING_INPUT with the
  // question preserved — the operator's alternative to answering it.
  const finalize = useMutation({
    mutationFn: (runId: string) => finalizeRun(runId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: unknown) => setError(apiErrorMessage(err, 'Finalize rejected — please retry.')),
  });
  // Card-level fallback (spec 10.1): send the whole card back to the agent
  // with feedback even while asks are still pending on it.
  const requestChanges = useMutation({
    mutationFn: (feedback: string) => {
      applyOperatorHeaders();
      return transitionKanbanItem(item.id, { status: 'TODO', feedback: feedback.trim() || undefined });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
    },
    onError: () => setError('Action rejected — please retry.'),
  });

  const resolve = (args: { ask: Approval; approved: boolean; answer?: string }) => {
    setError(null);
    setOperatorRejected(false);
    resolveAsk.mutate(args);
  };

  return (
    <div className="decision-zone" role="region" aria-label="Decision panel">
      <div className="dz-title">⚑ NEEDS YOUR DECISION · {pendingAsks.length} asks</div>
      {pendingAsks.map((ask) => (
        <div key={ask.id} className="ask-card">
          <div className="ask-q">
            {ask.askType === 'QUESTION' ? 'Question' : ask.askType === 'REVIEW_REQUEST' ? 'Review' : 'Approval'}
            : {ask.content?.slice(0, 160)}
          </div>
          <AskMeta ask={ask} permission={nativePermissionOf(ask)} />
          {ask.contextMd && <div className="ask-ctx">{ask.contextMd}</div>}
          <textarea
            className="dod-textarea"
            rows={2}
            aria-label={`Answer for ask ${ask.id}`}
            placeholder="Answer / feedback (optional)"
            value={answers[ask.id] ?? ''}
            onChange={(e) => setAnswers((prev) => ({ ...prev, [ask.id]: e.target.value }))}
          />
          <div className="ask-actions">
            {ask.source === 'CLARIFICATION' ? (
              <>
                {/* Waiting-input (2026-10-05): the ask genuinely needs the operator.
                    Answer & continue routes through /answer (approved=true + the
                    answer) and wakes the parked run; Finalize ends the run with
                    the question preserved and never requires an answer. */}
                <button
                  className="btn primary"
                  disabled={resolveAsk.isPending || !(answers[ask.id] ?? '').trim()}
                  onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] })}
                >
                  ▶ Answer &amp; continue
                </button>
                <button
                  className="btn"
                  disabled={finalize.isPending}
                  onClick={() => finalize.mutate(ask.runId)}
                >
                  ■ Finalize
                </button>
              </>
            ) : (
              <>
                <button className="btn primary" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] || undefined })}>Approve</button>
                <button className="btn" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: false, answer: answers[ask.id] || undefined })}>Deny</button>
              </>
            )}
          </div>
        </div>
      ))}
      <button
        className="btn primary"
        disabled={resolveAsk.isPending || batchApprovable.length === 0}
        onClick={() => {
          setError(null);
          batchApprovable.forEach((a) => resolveAsk.mutate({ ask: a, approved: true, answer: answers[a.id] || undefined }));
        }}
      >
        ✓ Approve all
      </button>
      {nativeAsks.length > 0 && (
        <div className="ask-ctx">
          {nativeAsks.length} native permission ask{nativeAsks.length === 1 ? '' : 's'} not included in
          Approve all: each is an allow-once decision and must be resolved individually.
        </div>
      )}
      <textarea
        className="dod-textarea"
        rows={2}
        aria-label="Request-changes feedback"
        placeholder="What should change? (sent back to the agent)"
        value={requestFeedback}
        onChange={(e) => setRequestFeedback(e.target.value)}
      />
      <button
        className="btn"
        disabled={requestChanges.isPending}
        onClick={() => {
          setError(null);
          requestChanges.mutate(requestFeedback);
          setRequestFeedback('');
        }}
      >
        ✎ Request changes
      </button>
      {error && <div className="kanban-form-error">{error}</div>}
      {operatorRejected && (
        <div className="ask-ctx">
          Approvals are operator-only — establish the operator session (Providers page, Operator
          access) and retry. Nothing was decided by the refused call.
        </div>
      )}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/*  Run outcome (Task 7): chips and honest copy on REVIEW cards               */
/* -------------------------------------------------------------------------- */

type KnownRunOutcome = 'COMPLETED' | 'FAILED' | 'ACTIVE' | 'CANCELLED';

/** Chip tone per outcome, following this file's existing pill classes. */
const RUN_OUTCOME_PILL: Record<KnownRunOutcome, string> = {
  COMPLETED: 'pill ok',
  FAILED: 'pill risk',
  ACTIVE: 'pill',
  CANCELLED: 'pill dim',
};

/** Decision copy per outcome; only COMPLETED keeps the original sign-off text. */
const RUN_OUTCOME_TITLE: Record<KnownRunOutcome, string> = {
  COMPLETED: 'Run completed — quick decision',
  FAILED: 'Run failed — rework or accept',
  ACTIVE: 'Run still in progress',
  CANCELLED: 'Run cancelled',
};

/** Narrow the outcome union to the classes that render a chip and a claim. */
function knownRunOutcome(outcome: KanbanItem['runOutcome']): KnownRunOutcome | null {
  return outcome === 'COMPLETED' || outcome === 'FAILED' || outcome === 'ACTIVE' || outcome === 'CANCELLED'
    ? outcome
    : null;
}

/** Outcome chip for a REVIEW card's linked run; UNKNOWN/absent render nothing. */
export function RunOutcomeChip({ outcome }: { outcome: KanbanItem['runOutcome'] }) {
  const known = knownRunOutcome(outcome);
  if (!known) return null;
  return <span className={RUN_OUTCOME_PILL[known]}>{known}</span>;
}

/**
 * Maps a fetched run status onto the listing's outcome classes (mirror of the
 * backend's KanbanService#runOutcome). Fallback for surfaces whose item
 * payload carries no runOutcome: GET /kanban/items/{id} is not enriched (the
 * T1 enrichment is listing-only), so the drawer and the workspace derive the
 * chip from the run they already fetch.
 */
export function runOutcomeFromStatus(status: RunStatus | null | undefined): KanbanItem['runOutcome'] {
  if (!status) return null;
  switch (status) {
    case 'COMPLETED':
      return 'COMPLETED';
    case 'FAILED':
      return 'FAILED';
    case 'CANCELLED':
    case 'ABORTED':
      return 'CANCELLED';
    default:
      // PENDING / INITIALIZING / RUNNING / PAUSED / WAITING_INPUT — a run parked
      // for operator input is still an in-flight (active) run.
      return 'ACTIVE';
  }
}

/** Quick decision surface for ask-less Review cards (spec 10.1). */
export function ShortApprovalView({
  item,
  runOutcome,
}: {
  item: KanbanItem;
  /**
   * Effective outcome when the caller already resolved it (the drawer and the
   * workspace fetch the linked run separately; the single-item payload does
   * not carry runOutcome).
   */
  runOutcome?: KanbanItem['runOutcome'];
}) {
  const queryClient = useQueryClient();
  const [feedback, setFeedback] = useState('');
  const [error, setError] = useState<string | null>(null);
  const move = useMutation({
    mutationFn: ({ status, feedback: fb }: { status: KanbanStatus; feedback?: string }) =>
      transitionKanbanItem(item.id, { status, feedback: fb }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      // Drawer item query staleness (title/status/asks read on open).
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      setError(null);
    },
    onError: () => setError('Action rejected — the card is unchanged.'),
  });

  // Task 7: the linked run's outcome drives both the chip and the copy — a
  // FAILED/CANCELLED/ACTIVE run must never read as "Run completed".
  const effectiveOutcome = runOutcome ?? item.runOutcome;
  const outcome = knownRunOutcome(effectiveOutcome);

  return (
    <div className="decision-zone short-view" role="region" aria-label="Decision panel">
      <div className="dz-title">
        <RunOutcomeChip outcome={effectiveOutcome} />{' '}
        {outcome ? RUN_OUTCOME_TITLE[outcome] : 'Quick decision'}
      </div>
      <div className="ask-ctx">
        agent {item.assignee ?? 'n/a'} · run {item.linkedRunId ? item.linkedRunId.slice(0, 8) : '—'}
      </div>
      <textarea
        className="dod-textarea"
        rows={2}
        aria-label="Request-changes feedback"
        placeholder="What should change? (sent back to the agent)"
        value={feedback}
        onChange={(e) => setFeedback(e.target.value)}
      />
      {error && <div className="kanban-form-error">{error}</div>}
      <div className="ask-actions">
        <button className="btn primary" onClick={() => move.mutate({ status: 'DONE' })}>Approve</button>
        <button className="btn" onClick={() => { move.mutate({ status: 'TODO', feedback: feedback.trim() || undefined }); setFeedback(''); }}>Request changes</button>
        <button className="btn danger" onClick={() => move.mutate({ status: 'CANCELLED' })}>Deny</button>
      </div>
    </div>
  );
}
