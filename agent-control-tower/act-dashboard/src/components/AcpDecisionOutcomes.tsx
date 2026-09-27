/**
 * C5 / C5-fix1: durable outcome surfaces for ACP permission decides.
 *
 * A decided ACP ask leaves the PENDING list on the next refetch, so an outcome
 * must not live in the panel that renders the pending cards: `AcpDecidedStrip`
 * derives the decided outcomes straight from the card's ask list (the same
 * `GET /api/v1/approvals?kanbanItemId=` payload the parents already hold), so
 * it survives the panel unmount, a refetch and a page reload — no state to
 * lose. `AcpDecisionOutcomes` stays for the Ops queue, whose session-local
 * outcomes are correct there (the page does not unmount on a decide).
 *
 * Both components share one wording/tone mapping; do not duplicate the rules.
 */
import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { approveApproval, rejectApproval } from '../api/approvals';
import { acpToolLabel, deliveryLabel, describeDecisionError, isAcpAsk } from '../utils/acpAsk';
import type { Approval, ApprovalDecisionReceipt } from '../types';

export interface AcpOutcome {
  askId: string;
  ask: Approval;
  label: string | null;
  approved: boolean;
  decision: string | null;
  deliveryState: string | null;
  error: { code: string; message: string } | null;
}

/**
 * Shared decision wording: `${decision} · ${delivery}`. Known decisions are
 * spelled for operators; any other value is rendered as it arrived (never
 * lowercased). A missing decision (never a real wire state) falls back to the
 * delivery text alone instead of printing "null".
 */
export function outcomeText(
  statusOrDecision: string | null,
  deliveryState: string | null,
): string {
  const word =
    statusOrDecision === 'APPROVED'
      ? 'approved'
      : statusOrDecision === 'DENIED'
        ? 'denied'
        : statusOrDecision === 'EXPIRED'
          ? 'expired'
          : statusOrDecision;
  return word ? `${word} · ${deliveryLabel(deliveryState)}` : deliveryLabel(deliveryState);
}

/**
 * Shared tone mapping: an error wins, `DELIVERED`/`CANCELLED` = ok,
 * `FAILED`/`MISSING` = warn (a MISSING ask has no companion row to retry),
 * anything else neutral.
 */
export function outcomeTone(
  deliveryState: string | null,
  hasError: boolean,
): '' | 'ok' | 'warn' | 'err' {
  if (hasError) return 'err';
  if (deliveryState === 'DELIVERED' || deliveryState === 'CANCELLED') return 'ok';
  if (deliveryState === 'FAILED' || deliveryState === 'MISSING') return 'warn';
  return '';
}

interface Props {
  outcomes: AcpOutcome[];
  retrying: boolean;
  onRetry: (outcome: AcpOutcome) => void;
}

/** Ops-queue strip: session-local outcome records keyed by ask id upstream. */
export function AcpDecisionOutcomes({ outcomes, retrying, onRetry }: Props): ReactElement | null {
  if (outcomes.length === 0) return null;
  return (
    <div className="acp-outcomes" role="list" aria-label="ACP decision outcomes">
      {outcomes.map((outcome) => {
        const tone = outcomeTone(outcome.deliveryState, Boolean(outcome.error));
        return (
          <div
            key={outcome.askId}
            className={`acp-outcome${tone ? ` ${tone}` : ''}`}
            data-ask-id={outcome.askId}
            role="listitem"
          >
            <span className="acp-outcome-label">{outcome.label ?? 'ACP permission'}</span>
            <span className="acp-outcome-text">
              {outcome.error
                ? `${outcome.error.code}: ${outcome.error.message}`
                : outcomeText(outcome.decision, outcome.deliveryState)}
            </span>
            {!outcome.error && outcome.deliveryState === 'FAILED' && (
              <button
                className="btn"
                disabled={retrying}
                onClick={() => onRetry(outcome)}
                title="Retry delivery of the recorded decision"
              >
                Retry
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** `decidedAt ?? requestedAt` as a sortable epoch (unparseable sorts oldest). */
function decidedAtOf(ask: Approval): number {
  const parsed = Date.parse(ask.decidedAt ?? ask.requestedAt);
  return Number.isNaN(parsed) ? 0 : parsed;
}

/**
 * The decided ACP asks of one card, derived from the card ask list itself (the
 * server answers terminal rows with `source`/`deliveryState` too). Renders the
 * 5 most recent decisions, newest first, and nothing when there are none.
 *
 * Self-contained: it owns the retry mutation and its invalidation, so parents
 * render it with the ask list only. A retry repeats the recorded decision
 * (approve for APPROVED, reject otherwise) — the server treats the operator
 * repeat as the delivery retry. Retry failures are row-scoped error text, not
 * outcome records: the outcome stays server truth.
 */
export function AcpDecidedStrip({ asks }: { asks: Approval[] }): ReactElement | null {
  const queryClient = useQueryClient();
  const [retryError, setRetryError] = useState<{ askId: string; text: string } | null>(null);

  const retry = useMutation({
    mutationFn: (ask: Approval): Promise<ApprovalDecisionReceipt> =>
      ask.status === 'APPROVED' ? approveApproval(ask.id) : rejectApproval(ask.id),
    onSuccess: (_receipt, ask) => {
      setRetryError((prev) => (prev?.askId === ask.id ? null : prev));
      // The lists the pending cards and this strip read from.
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
    },
    onError: (err, ask) => {
      const rejection = describeDecisionError(err);
      setRetryError({
        askId: ask.id,
        text: rejection
          ? `${rejection.code}: ${rejection.message}`
          : 'Action rejected — please retry.',
      });
    },
  });

  const decided = useMemo(
    () =>
      asks
        .filter(isAcpAsk)
        .filter((ask) => ask.status !== 'PENDING')
        .sort((a, b) => decidedAtOf(b) - decidedAtOf(a))
        .slice(0, 5),
    [asks],
  );

  if (decided.length === 0) return null;
  return (
    <div className="acp-outcomes" role="list" aria-label="ACP decision outcomes">
      {decided.map((ask) => {
        const rowError = retryError?.askId === ask.id ? retryError.text : null;
        const tone = outcomeTone(ask.deliveryState ?? null, Boolean(rowError));
        return (
          <div
            key={ask.id}
            className={`acp-outcome${tone ? ` ${tone}` : ''}`}
            data-ask-id={ask.id}
            role="listitem"
          >
            <span className="acp-outcome-label">{acpToolLabel(ask) ?? 'ACP permission'}</span>
            <span className="acp-outcome-text">
              {outcomeText(ask.status, ask.deliveryState ?? null)}
            </span>
            {rowError && <span className="kanban-form-error">{rowError}</span>}
            {ask.deliveryState === 'FAILED' && (
              <button
                className="btn"
                disabled={retry.isPending}
                onClick={() => retry.mutate(ask)}
                title="Retry delivery of the recorded decision"
              >
                Retry
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default AcpDecisionOutcomes;
