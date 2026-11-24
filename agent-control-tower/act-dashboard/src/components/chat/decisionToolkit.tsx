import { defineToolkit } from '@assistant-ui/react';
import { ApprovalToolUI } from './ApprovalToolUI';
import { ClarificationToolUI } from './ClarificationToolUI';
import { MemoryProposalToolUI } from './MemoryProposalToolUI';

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
