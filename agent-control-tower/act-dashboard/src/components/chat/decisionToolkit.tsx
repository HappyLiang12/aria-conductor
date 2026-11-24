import { defineToolkit } from '@assistant-ui/react';
import { apiErrorMessage, isCrossOriginRejection, isOperatorRejection } from '@/api/operatorSession';
import { OPERATOR_REMEDY_COPY, crossSiteSessionMessage } from '@/api/errorPresentation';
import { ApprovalToolUI } from './ApprovalToolUI';
import { ClarificationToolUI } from './ClarificationToolUI';
import { MemoryProposalToolUI } from './MemoryProposalToolUI';

/**
 * The backend answers 409 when an ask is no longer PENDING (it expired, the run
 * was cancelled, or the decision was already taken elsewhere). Not a transient
 * failure: no retry can settle it, so the card reports a receipt instead.
 */
export function isUnsettleableAsk(error: unknown): boolean {
  return (error as { response?: { status?: number } } | undefined)?.response?.status === 409;
}

/**
 * The shared decision-failure copy the legacy `AriaApprovalCard` established:
 * a cross-site 403 and an operator-session 403 each name their own remedy and
 * say that nothing was decided; everything else keeps the backend's wording.
 */
export function decisionErrorMessage(
  error: unknown,
  actionLabel: string,
  fallback: string,
): string {
  if (isCrossOriginRejection(error)) {
    return `${crossSiteSessionMessage(actionLabel)} Nothing was decided by the refused call.`;
  }
  if (isOperatorRejection(error)) {
    return `Your operator session was refused this decision: ${apiErrorMessage(error, 'operator-only route')}. `
      + `Decisions are operator-only. ${OPERATOR_REMEDY_COPY} Nothing was decided by the refused call.`;
  }
  return apiErrorMessage(error, fallback);
}

/**
 * Renderers for the backend decision tool calls the SSE adapter emits (Task 6).
 *
 * `display: 'standalone'` keeps each decision card out of the collapsed
 * tool-call group, so an ask is never hidden behind a disclosure. Every card is
 * server-driven: its decision API call is the action, not assistant-ui's
 * requires-action gate (`respondToApproval`).
 */
export const decisionToolkit = defineToolkit({
  __approval: { type: 'backend', display: 'standalone', render: ApprovalToolUI },
  __question: { type: 'backend', display: 'standalone', render: ClarificationToolUI },
  __memory: { type: 'backend', display: 'standalone', render: MemoryProposalToolUI },
});
