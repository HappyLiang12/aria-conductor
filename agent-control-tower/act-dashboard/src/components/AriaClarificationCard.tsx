import { useState } from 'react';
import { answerAsk } from '../api/approvals';
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';

export type AriaClarificationItem = Extract<PanelItem, { kind: 'clarification' }>;
/** The states an answer can produce; `pending` is the absence of one. */
export type ClarificationOutcome = Exclude<AriaClarificationItem['state'], 'pending'>;

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
  onSettled: (state: ClarificationOutcome) => void;
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
      setError(
        isOperatorRejection(err)
          ? `Your operator session refused this answer: ${apiErrorMessage(err, 'operator-only route')}. `
            + 'Establish the operator session again, then answer.'
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
