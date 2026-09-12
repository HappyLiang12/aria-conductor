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
  // Deliberately NOT asserted: this is housekeeping, not the behaviour under test.
  // The transition is expected to succeed from any status this spec leaves the card
  // in (REVIEW or CANCELLED; KanbanTransitionService.java:136-138 + the same-status
  // no-op guard at :95-97), but a full-suite run saw it rejected with 409 after
  // something else had already moved the card. Failing the spec for that would report
  // housekeeping noise, and the card's terminal state is what matters here.
  const cancelled = await transitionKanban(request, card.id, 'CANCELLED');
  if (cancelled.status !== 200) {
    console.log(`[review-decision-zone] cleanup CANCELLED transition returned ${cancelled.status}; ignored`);
  }
});

/**
 * Gap 3, approve half: the design spec (2026-09-12-workflow-e2e-gap-closure-design.md:141)
 * mandates "one approve and one reject" through the Review column decide controls.
 * This is the approve half; the test above is the reject half.
 *
 * Same reach path and same 60 s PENDING poll as the Deny test above.
 *
 * Locator note: the decision panel also renders a bulk `✓ Approve all` button
 * (ReviewPanels.tsx:74-83), so `exact: true` is required to avoid matching it
 * (its accessible name is `✓ Approve all`, not `Approve`). Measured on the live
 * stack while the zone was open: an unscoped
 * getByRole('button', { name: 'Approve', exact: true }) matched 10 elements —
 * 8 ReviewQueue rows (OverviewPage.tsx mounts ReviewQueue, which renders an
 * `Approve` per globally-PENDING approval, ReviewQueue.tsx:99-108), the
 * decision zone's per-ask control, and the TaskDrawer footer's quick `Approve`
 * (TaskDrawer.tsx:458-464, `handleApprove` = card transition to DONE, not an ask
 * decision). Two of those would resolve the ask through the wrong surface or
 * move the card out from under it, so the unscoped locator is both a
 * strict-mode violation and semantically wrong. Scoped to the decision-panel
 * region it matched exactly 1 — the control under test. Keep the scoping.
 *
 * Product limitation recorded, not worked around: like the Deny path, the UI
 * Approve routes DecisionPanel -> approveApproval -> POST
 * /approvals/{id}/approve, which the backend does not expose (404 —
 * ApprovalController has only list, get, decide, answer), so the client falls
 * back to POST /approvals/{id}/decide {approved:true}. That fallback is the path
 * under test.
 *
 * Timing note: approving the task gate (AgentLoopEngine.java:704-722) lets the
 * run resume into the provider call, so with the healthy local opencode provider
 * the run may start executing. This test therefore asserts only that the ask
 * becomes APPROVED, then cancels the card, which cancels the linked run
 * (KanbanTransitionService.java:240-254). It deliberately makes no assertion
 * about the run's terminal state.
 */
test('clicking Approve in the decision zone resolves the ask', async ({ page, request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-oc-uiaz'),
    adkProvider: 'opencode',
  });
  const card = await seedKanbanItem(request, {
    title: `uiaz-${uniqueName('card')}`,
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

  await zone.getByRole('button', { name: 'Approve', exact: true }).click();

  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .toBe('APPROVED');

  // Cleanup (kanban-hitl.spec.ts:174-177): cancel the card, which cancels the
  // linked run (KanbanTransitionService.java:240-254). Unlike the Deny path the
  // approval itself does not cancel anything — it resumes the run — so this
  // transition is the real teardown here, not a race guard; cancel() also denies
  // any ask the resumed run left pending. The cleanup is expected to succeed from
  // the status this spec leaves the card in (REVIEW; KanbanTransitionService.java:138
  // routes CANCELLED to cancel(), and REVIEW -> CANCELLED is allowed by
  // KanbanService.java:42-43; a card already CANCELLED hits the same-status no-op
  // guard at :95-97). It is deliberately NOT asserted — this is housekeeping, not the
  // behaviour under test, and a rejected cleanup must not fail the spec (see the Deny
  // test above).
  const cancelled = await transitionKanban(request, card.id, 'CANCELLED');
  if (cancelled.status !== 200) {
    console.log(`[review-decision-zone] cleanup CANCELLED transition returned ${cancelled.status}; ignored`);
  }
});
