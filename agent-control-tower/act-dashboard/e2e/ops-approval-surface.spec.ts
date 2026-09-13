import { test, expect } from '@playwright/test';
import {
  apiCall,
  pollUntil,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from './fixtures';

/**
 * Regression guard for the Operations approval surface.
 *
 * The Ops page carried its own copy of approveApproval/rejectApproval that
 * POSTed /approvals/{id}/approve and /approvals/{id}/reject. Neither route
 * exists on ApprovalController (list, get, decide, answer), so every decision
 * taken here died as a 404 and the approval stayed PENDING.
 * review-decision-zone.spec.ts covers the kanban decision zone, which uses a
 * different api module, so this surface had no coverage and the bug shipped.
 *
 * Reach path: identical to review-decision-zone.spec.ts — the default-on
 * task-level approval gate (AgentLoopEngine.java:704, before any provider call)
 * creates a PENDING ask, so no LLM key and no sandbox are needed.
 */
test('approving from the Operations surface resolves the ask', async ({ page, request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-oc-opsap'),
    adkProvider: 'opencode',
  });
  const card = await seedKanbanItem(request, {
    title: `opsap-${uniqueName('card')}`,
    agentTemplateId: agent.name,
  });
  expect((await transitionKanban(request, card.id, 'IN_PROGRESS')).status).toBe(200);

  const asks = await pollUntil<any[]>(
    request,
    `/approvals?kanbanItemId=${card.id}`,
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
    60_000,
    2_000,
  );
  const ask = asks.find((a) => a.status === 'PENDING');
  expect(ask).toBeTruthy();
  const approvalId = ask!.id;

  await page.goto('/ops');
  const approvalCard = page.locator(`[data-approval-id="${approvalId}"]`);
  await expect(approvalCard).toBeVisible({ timeout: 15_000 });

  const [decisionRequest] = await Promise.all([
    page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/api/v1/approvals/')),
    approvalCard.getByRole('button', { name: /Approve/ }).click(),
  ]);

  // The endpoint contract is the regression under guard: /approve does not exist.
  expect(new URL(decisionRequest.url()).pathname).toBe(
    `/api/v1/approvals/${approvalId}/decide`,
  );

  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/approvals/${approvalId}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .toBe('APPROVED');
});
