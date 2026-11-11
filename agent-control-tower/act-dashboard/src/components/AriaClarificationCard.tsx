import { useState } from 'react';
import { answerAsk } from '../api/approvals';
import { OPERATOR_REMEDY_COPY, crossSiteSessionMessage } from '../api/errorPresentation';
import {
  apiErrorMessage,
  applyOperatorHeaders,
  isCrossOriginRejection,
  isOperatorRejection,
} from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';

export type AriaClarificationItem = Extract<PanelItem, { kind: 'clarification' }>;
/** The states an answer can produce; `pending` is the absence of one. */
export type ClarificationOutcome = Exclude<AriaClarificationItem['state'], 'pending'>;

/**
 * The backend answers 409 when the ask is no longer waiting for an answer — it
 * expired, the run was finalized, or a concurrent answer already won
 * (`ApprovalController.java:239-262`). That is not a transient failure and not
 * something a retry can fix; the approval card classifies it the same way.
 */
function isUnsettleableAsk(error: unknown): boolean {
  const status = (error as { response?: { status?: number } } | undefined)?.response?.status;
  return status === 409;
}

/**
 * A parked question rendered in the conversation (spec A3, F1). The question
 * ships as the ask's content; the answer goes through the existing
 * `/api/v1/approvals/{id}/answer` route, which wakes the WAITING_INPUT run that
 * is still holding this conversation's stream.
 */
export function AriaClarificationCard({
  item,
  onSettled,
}: {
  item: AriaClarificationItem;
  onSettled: (state: ClarificationOutcome, reason?: string) => void;
}) {
  const [answer, setAnswer] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const settled = item.state !== 'pending';
  const text = answer.trim();

  async function submit(): Promise<void> {
    if (settled || submitting || text === '') return;
    setSubmitting(true);
    setError(null);
    applyOperatorHeaders();
    try {
      await answerAsk(item.approvalId, { approved: true, answer: text });
      onSettled('answered');
    } catch (err) {
      if (isUnsettleableAsk(err)) {
        // The question can no longer be answered. T45 Item 2: say so with the
        // backend's own wording via the settled line, instead of promising that
        // the question is still waiting and inviting a retry that repeats the
        // 409 forever behind a locked composer.
        onSettled('expired', apiErrorMessage(err, 'This question is no longer waiting for an answer.'));
        return;
      }
      setError(
        isCrossOriginRejection(err)
          // Task 37: the cross-origin 403 is not an authority refusal; its remedy
          // is the trusted origin, not a grant.
          ? `${crossSiteSessionMessage('Answering this question')} Nothing was sent by the refused call.`
          : isOperatorRejection(err)
          // Same posture as the Review panels: say the refusal, say what the
          // operator can do about it, and say that nothing was sent. The remedy
          // is the shared copy (Task 35) — a reload cannot restore an operator
          // session, so this card must not suggest one.
          ? `Your operator session was refused this answer: ${apiErrorMessage(err, 'operator-only route')}. `
            + `Answering is operator-only. ${OPERATOR_REMEDY_COPY} Nothing was sent by the refused call.`
          : apiErrorMessage(err, 'The answer could not be sent — the question is still waiting; retry.'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={`ai-decision-card ask-card ai-decision-clarification ai-decision-${item.state}`}>
      <div className="ai-decision-head">
        <span className="ask-q ai-decision-question">{item.question}</span>
        <span className="ai-decision-tags">
          <span className="pill dim">QUESTION</span>
        </span>
      </div>
      <div className="ask-meta ai-decision-meta">
        Run <code>{item.runId.slice(0, 8)}</code> · parked until you answer
      </div>
      {settled ? (
        <div className="ai-decision-settled">
          {item.state === 'answered' ? 'Answered' : 'Expired before an answer'}
          {item.reason !== undefined && item.reason !== '' ? ` — ${item.reason}` : ''}
        </div>
      ) : (
        <div className="ask-actions ai-decision-actions">
          <textarea
            className="ai-clarify-input"
            aria-label="Your answer"
            rows={2}
            value={answer}
            onChange={(event) => setAnswer(event.target.value)}
            disabled={submitting}
            placeholder="Answer Aria…"
          />
          <button
            type="button"
            className="btn btn-primary ai-decision-answer"
            onClick={() => { void submit(); }}
            disabled={text === '' || submitting}
          >
            {submitting ? 'Sending…' : 'Answer'}
          </button>
        </div>
      )}
      {error !== null && <div role="alert" className="ai-decision-error error-text">{error}</div>}
    </div>
  );
}

export default AriaClarificationCard;
