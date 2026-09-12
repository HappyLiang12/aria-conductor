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
 * Gap 3: decide through the UI rather than the REST API.
 *
 * kanban-hitl.spec.ts:133-178 reaches the decision zone and asserts it renders,
 * but never clicks a decision control. This spec closes that half.
 *
 * Reach path (LLM-free, CI-safe): kanban-hitl.spec.ts:133-158 — pin a
 * task-capable opencode agent, dispatch the card, and the default-on
 * task-level approval gate (AgentLoopEngine.java:704, BEFORE any provider
 * call) creates a PENDING ask. No LLM key and no sandbox are needed.
 *
 * Locator note: the Overview page ALSO mounts ReviewQueue, which renders a
 * `Deny` button per globally-PENDING approval. Measured on the live stack, an
 * unscoped getByRole('button', { name: 'Deny', exact: true }) matched 9 elements
 * (8 ReviewQueue buttons + the decision zone's), so the unscoped locator is a
 * strict-mode violation AND could resolve the ask through the wrong surface.
 * Scoping to the decision-panel region matched exactly 1 — the control under
 * test. Keep the scoping.
 *
 * Product limitation recorded, not worked around: there is no dedicated denial
 * reason field. The per-ask box is `aria-label="Answer for ask <id>"` (its text
 * is forwarded as `reason` on the fallback decide call), the card-face Deny
 * sends {status:'CANCELLED'} (KanbanBoard.tsx:436), and the drawer's comment box
 * is a transition note (TaskDrawer.tsx:424-430). This spec therefore asserts the
 * ask's terminal status only — never a reason.
 */
test('clicking Deny in the decision zone resolves the ask', async ({ page, request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-oc-uidz'),
    adkProvider: 'opencode',
  });
  const card = await seedKanbanItem(request, {
    title: `uidz-${uniqueName('card')}`,
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

  expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);

  await page.goto('/');
  const reviewCard = page.locator(`[data-col="REVIEW"] [data-card="${card.id}"]`);
  await expect(reviewCard).toBeVisible({ timeout: 15_000 });
  await reviewCard.click();

  const zone = page.getByRole('region', { name: 'Decision panel' });
  await expect(zone).toBeVisible({ timeout: 15_000 });
  await expect(zone.locator('.dz-title')).toContainText('NEEDS YOUR DECISION');

  await zone.getByRole('button', { name: 'Deny', exact: true }).click();

  // The UI Deny routes DecisionPanel -> rejectApproval -> POST
  // /approvals/{id}/reject, which the backend does not expose (404), so the
  // client falls back to POST /approvals/{id}/decide {approved:false}. That is
  // the same path the API denial spec covers: ApprovalGate sets DENIED
  // (ApprovalGate.java:253) and the engine cancels the linked run.
  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .toBe('DENIED');

  // Cleanup (kanban-hitl.spec.ts:174-177): normally a no-op — the denial already
  // cancelled the linked run and RunKanbanAutoCreator moved the card to
  // CANCELLED — but the explicit transition guards the listener race and leaves
  // no card mid-flight either way (same-status transitions are idempotent).
  const cancelled = await transitionKanban(request, card.id, 'CANCELLED');
  expect(cancelled.status).toBe(200);
});
