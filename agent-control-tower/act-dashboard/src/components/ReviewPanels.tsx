import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { transitionKanbanItem } from '../api/kanban';
import { answerAsk, approveApproval, finalizeRun, rejectApproval } from '../api/approvals';
import { applyOperatorHeaders, isCrossOriginRejection, isOperatorRejection } from '../api/operatorSession';
import {
  presentError,
  OPERATOR_REMEDY_COPY,
  crossSiteSessionMessage,
  type PresentedError,
} from '../api/errorPresentation';
import ErrorNotice from './ErrorNotice';
import { ApprovalToolDetails } from './ApprovalToolDetails';
import { formatTimestamp } from '../utils/formatTime';
import {
  NativePermissionFacts,
  NativePermissionKindPill,
  nativePermissionOf,
} from './NativePermissionAsk';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Textarea } from './ui/textarea';
import type { Approval, ApprovalDecisionReceipt, KanbanItem, KanbanStatus, RunStatus } from '../types';

interface PanelProps {
  item: KanbanItem;
  pendingAsks: Approval[];
}

function AskMeta({ ask, permission }: { ask: Approval; permission: ReturnType<typeof nativePermissionOf> }) {
  if (permission) {
    return (
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <NativePermissionKindPill permission={permission} />
        <NativePermissionFacts ask={ask} permission={permission} />
      </div>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <Badge variant="outline">{ask.approvalType ?? 'TOOL_CALL'}</Badge>
      {ask.riskTier && <Badge variant="secondary">risk {ask.riskTier}</Badge>}
      {ask.expiresAt && <span>expires {formatTimestamp(ask.expiresAt)}</span>}
    </div>
  );
}

/** Ask decision surface - used by the collapsed drawer and the ReviewWorkspace rail. */
export function DecisionPanel({ item, pendingAsks }: PanelProps) {
  const queryClient = useQueryClient();
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [requestFeedback, setRequestFeedback] = useState('');
  const [error, setError] = useState<PresentedError | null>(null);
  const [operatorRejected, setOperatorRejected] = useState(false);
  // Task 37: the 403 that carries the cross-origin marker is not an authority
  // refusal, so it must not be answered with the operator-grant remedy.
  const [crossOriginRejected, setCrossOriginRejected] = useState(false);
  // Task 41 m4: which mutation was refused, so the cross-site notice names it
  // (Finalize is not operator-gated; Approve/Deny is the other caller).
  const [refusedAction, setRefusedAction] = useState('Approving this ask');

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
      setRefusedAction('Approving this ask');
      setOperatorRejected(isOperatorRejection(err));
      setCrossOriginRejected(isCrossOriginRejection(err));
      setError(presentError(err, 'Action rejected — please retry.'));
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
    onError: (err: unknown) => {
      // Finalize is not operator-gated (spec D6), so its only refusal is the
      // cross-site 403; without these two lines the operator read the bare
      // marker in the plain error notice, with no remedy at all.
      setRefusedAction('Finalizing this run');
      setOperatorRejected(isOperatorRejection(err));
      setCrossOriginRejected(isCrossOriginRejection(err));
      setError(presentError(err, 'Finalize rejected — please retry.'));
    },
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
    onError: (err: unknown) => setError(presentError(err, 'Request changes failed — the card is unchanged.')),
  });

  const resolve = (args: { ask: Approval; approved: boolean; answer?: string }) => {
    setError(null);
    setOperatorRejected(false);
    resolveAsk.mutate(args);
  };

  return (
    <div
      className="decision-zone flex flex-col border-0! bg-transparent! p-0!"
      role="region"
      aria-label="Decision panel"
    >
      <Card>
        <CardHeader>
          <CardTitle className="dz-title text-sm font-semibold">
            ⚑ NEEDS YOUR DECISION · {pendingAsks.length} asks
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {pendingAsks.map((ask) => (
            <div key={ask.id} className="ask-card flex flex-col gap-2 rounded-lg border! border-solid! border-border! p-3!">
              <div className="ask-q text-sm font-semibold break-words text-card-foreground">
                {ask.askType === 'QUESTION' ? 'Question' : ask.askType === 'REVIEW_REQUEST' ? 'Review' : 'Approval'}
                : {ask.content?.slice(0, 160)}
              </div>
              <AskMeta ask={ask} permission={nativePermissionOf(ask)} />
              {/* F7: toolName + redacted arguments when the api/bridge payload
                  carries them; a native ask already names its tool in the facts
                  row above, so only the arguments are added for it. */}
              <ApprovalToolDetails ask={ask} includeToolName={nativePermissionOf(ask) === null} />
              {ask.contextMd && <div className="text-xs text-muted-foreground">{ask.contextMd}</div>}
              <Textarea
                className="min-h-0"
                rows={2}
                aria-label={`Answer for ask ${ask.id}`}
                placeholder="Answer / feedback (optional)"
                value={answers[ask.id] ?? ''}
                onChange={(e) => setAnswers((prev) => ({ ...prev, [ask.id]: e.target.value }))}
              />
              <div className="flex flex-wrap items-center gap-2">
                {ask.source === 'CLARIFICATION' ? (
                  <>
                    {/* Waiting-input (2026-10-05): the ask genuinely needs the operator.
                        Answer & continue routes through /answer (approved=true + the
                        answer) and wakes the parked run; Finalize ends the run with
                        the question preserved and never requires an answer. */}
                    <Button
                      disabled={resolveAsk.isPending || !(answers[ask.id] ?? '').trim()}
                      onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] })}
                    >
                      ▶ Answer &amp; continue
                    </Button>
                    <Button
                      variant="outline"
                      disabled={finalize.isPending}
                      onClick={() => finalize.mutate(ask.runId)}
                    >
                      ■ Finalize
                    </Button>
                  </>
                ) : (
                  <>
                    <Button disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] || undefined })}>Approve</Button>
                    <Button variant="outline" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: false, answer: answers[ask.id] || undefined })}>Deny</Button>
                  </>
                )}
              </div>
            </div>
          ))}
          <Button
            disabled={resolveAsk.isPending || batchApprovable.length === 0}
            title={`Approve ${batchApprovable.length} eligible ask${batchApprovable.length === 1 ? '' : 's'} at once`}
            onClick={() => {
              setError(null);
              batchApprovable.forEach((a) => resolveAsk.mutate({ ask: a, approved: true, answer: answers[a.id] || undefined }));
            }}
          >
            ✓ Approve all ({batchApprovable.length})
          </Button>
          {nativeAsks.length > 0 && (
            <div className="text-xs text-muted-foreground">
              {nativeAsks.length} native permission ask{nativeAsks.length === 1 ? '' : 's'} not included in
              Approve all: each is an allow-once decision and must be resolved individually.
            </div>
          )}
          <Textarea
            className="min-h-0"
            rows={2}
            aria-label="Request-changes feedback"
            placeholder="What should change? (sent back to the agent)"
            value={requestFeedback}
            onChange={(e) => setRequestFeedback(e.target.value)}
          />
          <Button
            variant="outline"
            className="self-start"
            disabled={requestChanges.isPending}
            onClick={() => {
              setError(null);
              requestChanges.mutate(requestFeedback);
              setRequestFeedback('');
            }}
          >
            ✎ Request changes (starts 1 fresh run)
          </Button>
          {/* A refusal is answered by the remedy below, not by the server's wording
              (on a refusal that wording points at no fix — Task 41 m4: Finalize's
              bare marker was the only thing shown). A fault keeps its text. */}
          {error && !operatorRejected && <ErrorNotice className="kanban-form-error" presented={error} />}
          {/* Task 35: the remedy clause is the shared `OPERATOR_REMEDY_COPY`; the
              panel keeps its own frame (what was refused, and that it decided
              nothing) because a refusal here may also carry the server's wording. */}
          {operatorRejected && (
            <div className="rounded-lg border bg-muted/40 p-3 text-xs text-muted-foreground">
              {crossOriginRejected ? (
                <>
                  {crossSiteSessionMessage(refusedAction)} Nothing was decided by the refused
                  call.
                </>
              ) : (
                <>
                  Approvals are operator-only. {OPERATOR_REMEDY_COPY} Nothing was decided by the
                  refused call.
                </>
              )}
            </div>
          )}
        </CardContent>
      </Card>
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

const RUN_OUTCOME_BADGE: Record<KnownRunOutcome, 'secondary' | 'destructive' | 'outline'> = {
  COMPLETED: 'secondary',
  FAILED: 'destructive',
  ACTIVE: 'outline',
  CANCELLED: 'outline',
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
  return (
    <Badge variant={RUN_OUTCOME_BADGE[known]} className={RUN_OUTCOME_PILL[known]}>
      {known}
    </Badge>
  );
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
  const [error, setError] = useState<PresentedError | null>(null);
  const move = useMutation({
    mutationFn: ({ status, feedback: fb }: { status: KanbanStatus; feedback?: string }) =>
      transitionKanbanItem(item.id, { status, feedback: fb }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      // Drawer item query staleness (title/status/asks read on open).
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      setError(null);
    },
    onError: (err: unknown) => setError(presentError(err, 'Move failed — the card is unchanged.')),
  });

  // Task 7: the linked run's outcome drives both the chip and the copy — a
  // FAILED/CANCELLED/ACTIVE run must never read as "Run completed".
  const effectiveOutcome = runOutcome ?? item.runOutcome;
  const outcome = knownRunOutcome(effectiveOutcome);

  return (
    <div
      className="decision-zone short-view flex flex-col border-0! bg-transparent! p-0!"
      role="region"
      aria-label="Decision panel"
    >
      <Card>
        <CardHeader>
          <CardTitle className="dz-title flex flex-wrap items-center gap-2 text-sm font-semibold">
            <RunOutcomeChip outcome={effectiveOutcome} />{' '}
            {outcome ? RUN_OUTCOME_TITLE[outcome] : 'Quick decision'}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <div className="text-xs text-muted-foreground">
            agent {item.assignee ?? 'n/a'} · run {item.linkedRunId ? item.linkedRunId.slice(0, 8) : '—'}
          </div>
          <Textarea
            className="min-h-0"
            rows={2}
            aria-label="Request-changes feedback"
            placeholder="What should change? (sent back to the agent)"
            value={feedback}
            onChange={(e) => setFeedback(e.target.value)}
          />
          {error && <ErrorNotice className="kanban-form-error" presented={error} />}
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => move.mutate({ status: 'DONE' })}>Approve</Button>
            <Button variant="outline" onClick={() => { move.mutate({ status: 'TODO', feedback: feedback.trim() || undefined }); setFeedback(''); }}>Request changes (starts 1 fresh run)</Button>
            <Button variant="destructive" onClick={() => move.mutate({ status: 'CANCELLED' })}>Deny</Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
