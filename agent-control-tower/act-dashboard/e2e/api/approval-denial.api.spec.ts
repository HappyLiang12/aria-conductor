import { test, expect } from '@playwright/test';
import {
  apiCall,
  dispatchSeededCard,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  pollUntil,
  uniqueName,
} from '../fixtures';

/**
 * Gap 1: denying a run gate with a reason.
 *
 * Reach path mirrors kanban-hitl.spec.ts:133-158 — pin a task-capable opencode
 * agent, dispatch the card, and the default-on task-level approval gate
 * (AgentLoopEngine.java:704, BEFORE any provider call) creates a PENDING ask.
 * No LLM key and no OpenSandbox are needed, so this runs ungated in CI.
 *
 * Denial semantics: ApprovalGate.java:253-254 sets DENIED and persists the
 * operator reason; the linked tool call becomes DENIED (:261); the run is
 * driven to CANCELLED (AgentLoopEngine.java:705-713).
 */
const TERMINAL = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];

test.describe('approval denial with reason', () => {
  test('deny persists the reason and cancels the run', async ({ request }) => {
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-deny'),
      adkProvider: 'opencode',
    });
    const card = await seedKanbanItem(request, {
      title: `deny-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });

    // Same auto-dispatch race as ops-approval-surface.spec.ts: the contract is the linked run.
    await dispatchSeededCard(request, card.id);

    const asks = await pollUntil<any[]>(
      request,
      `/approvals?kanbanItemId=${card.id}`,
      (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
      60_000,
      2_000,
    );
    const ask = asks.find((a) => a.status === 'PENDING');
    expect(ask, 'a PENDING ask must be linked to the dispatched card').toBeTruthy();

    const reason = `e2e denial reason ${uniqueName('r')}`;
    const decided = await apiCall(request, 'POST', `/approvals/${ask.id}/decide`, {
      approved: false,
      reason,
    });
    expect(decided.status).toBe(200);

    const after = await apiCall(request, 'GET', `/approvals/${ask.id}`);
    expect(after.status).toBe(200);
    expect(after.data.status).toBe('DENIED');
    expect(after.data.reason).toBe(reason);
    expect(after.data.decidedAt).toBeTruthy();

    const cardAfter = await apiCall(request, 'GET', `/kanban/items/${card.id}`);
    const runId = cardAfter.data?.linkedRunId;
    expect(runId, 'the dispatched card must carry a linked run').toBeTruthy();

    const run = await pollUntil<any>(
      request,
      `/runs/${runId}`,
      (r) => TERMINAL.includes(r.status),
      60_000,
      2_000,
    );
    expect(run.status).toBe('CANCELLED');

    // Cleanup: the denial already drove the run to a terminal state, but the
    // card is still IN_PROGRESS. Cancel it so the board is not left with a
    // dispatched card behind (same teardown as kanban-hitl.spec.ts:174-177).
    const cancelled = await transitionKanban(request, card.id, 'CANCELLED');
    expect(cancelled.status).toBe(200);
  });
});
