import { useState } from 'react';
import { decideApproval } from '../api/approvals';
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';

export type AriaApprovalItem = Extract<PanelItem, { kind: 'approval' }>;
export type ApprovalOutcome = AriaApprovalItem['state'];

/**
 * A governed tool ask rendered in the conversation (spec A3, F1): the operator
 * decides in place and the parked turn resumes on its still-open stream, so
 * there is no timeout to wait out and no page switch to the Review Queue.
 *
 * `decideApproval` is operator-only, so the local session's CSRF token is
 * applied first — without it the backend fails closed (401/403). A refusal is
 * reported where the operator can act on it, never retried silently.
 */
export function AriaApprovalCard({
  item,
  onSettled,
}: {
  item: AriaApprovalItem;
  onSettled: (state: ApprovalOutcome, reason?: string) => void;
}) {
  const [submitting, setSubmitting] = useState<'allow' | 'deny' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const settled = item.state !== 'pending';

  async function decide(approved: boolean): Promise<void> {
    if (submitting !== null || settled) return;
    setSubmitting(approved ? 'allow' : 'deny');
    setError(null);
    applyOperatorHeaders();
    try {
      await decideApproval(item.approvalId, { approved });
      onSettled(approved ? 'approved' : 'denied');
    } catch (err) {
      setError(
        isOperatorRejection(err)
          ? `Your operator session refused this decision: ${apiErrorMessage(err, 'operator-only route')}. `
            + 'Establish the operator session again, then decide.'
          : apiErrorMessage(err, 'The decision could not be recorded — the ask is still pending; retry.'),
      );
    } finally {
      setSubmitting(null);
    }
  }

  return (
    <div className={`ai-decision-card ask-card ai-decision-approval ai-decision-${item.state}`}>
      <div className="ai-decision-head">
        <span className="ask-q ai-decision-tool"><code>{item.toolName}</code></span>
        <span className="ai-decision-tags">
          {item.riskTier !== undefined && <span className="pill risk">{item.riskTier}</span>}
          {item.toolPolicy !== undefined && <span className="pill dim">{item.toolPolicy}</span>}
        </span>
      </div>
      <div className="ask-meta ai-decision-meta">
        Run <code>{item.runId.slice(0, 8)}</code> · this tool needs your decision before it runs
      </div>
      {item.args !== undefined && item.args !== '' && (
        <pre className="ask-args ai-decision-args">{item.args}</pre>
      )}
      {settled ? (
        <div className="ai-decision-settled">
          {item.state === 'approved' ? 'Allowed once' : item.state === 'denied' ? 'Denied' : 'Expired before a decision'}
          {item.reason !== undefined && item.reason !== '' ? ` — ${item.reason}` : ''}
        </div>
      ) : (
        <div className="ask-actions ai-decision-actions">
          <button
            type="button"
            className="btn btn-primary ai-decision-allow"
            onClick={() => { void decide(true); }}
            disabled={submitting !== null}
          >
            {submitting === 'allow' ? 'Allowing…' : 'Allow once'}
          </button>
          <button
            type="button"
            className="btn btn-ghost ai-decision-deny"
            onClick={() => { void decide(false); }}
            disabled={submitting !== null}
          >
            Deny
          </button>
        </div>
      )}
      {error !== null && <div role="alert" className="ai-decision-error error-text">{error}</div>}
    </div>
  );
}

export default AriaApprovalCard;
