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
 * is forwarded as `reason` on the ask's own decide call), the card-face Deny
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
  // /approvals/{id}/decide {approved:false}, the only decision endpoint the
  // backend exposes (ApprovalController: list, get, decide, answer). That is
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

  // Card state, asserted. The denial cancelled the linked run (ApprovalGate.java:253),
  // and RunKanbanAutoCreator.onRunCompleted maps a cancelled/aborted run to a
  // CANCELLED card (listener/RunKanbanAutoCreator.java:103-104), so the eventual
  // state is deterministic. Polled read-only via GET /kanban/items/{id}.
  //
  // This replaces a cleanup that wrote CANCELLED explicitly and merely logged a
  // non-200. That write was redundant with the listener's move, so the two could
  // interleave and the write could lose the optimistic-lock race (HTTP 409,
  // GlobalExceptionHandler.java:62-70) or be rejected because the card had already
  // moved somewhere the matrix forbids leaving (InvalidStateTransitionException ->
  // 409, :35-38). Removing the write removes the race; the state claim stays.
  // The transition itself is real: CANCELLED routes to cancel()
  // (KanbanTransitionService.java:138), REVIEW -> CANCELLED is allowed
  // (KanbanService.java:42-43), and CANCELLED is terminal, with no outgoing edges
  // (:48). A card that does not reach CANCELLED is a genuine failure here.
  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/kanban/items/${card.id}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .toBe('CANCELLED');
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
 * /approvals/{id}/decide {approved:true}. The backend exposes no
 * /approvals/{id}/approve route (ApprovalController has only list, get, decide,
 * answer); calling one directly is a 404.
 *
 * Timing note: approving the task gate (AgentLoopEngine.java:704-722) lets the
 * run resume into the provider call, so with the healthy local opencode provider
 * the run may start executing. This test therefore asserts the ask becomes
 * APPROVED, that the resumed run leaves PAUSED, and that the card ends in REVIEW
 * — the sign-off column RunKanbanAutoCreator maps a finished run to. It makes no
 * assertion about the run's TERMINAL status, which differs by environment
 * (COMPLETED locally, FAILED in CI without a sandbox).
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

  // The approved decision let the run continue: the task gate pauses it
  // (AgentLoopEngine.java:704) and an approved decide resumes it, so the linked
  // run must leave PAUSED. Asserted first so the card claim below is not vacuous.
  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/runs/${ask.runId}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .not.toBe('PAUSED');

  // Resulting card state, asserted read-only via GET /kanban/items/{id}.
  //
  // Approving changes the RUN, not the card. The resumed run settles COMPLETED on
  // the local stack (a real opencode provider; measured: ask APPROVED at
  // 16:43:50, run COMPLETED at 16:44:16) or FAILED in CI (no sandbox —
  // AgentLoopEngine.java:881 maps SANDBOX_UNAVAILABLE/PROVIDER_ERROR to FAILED),
  // and RunKanbanAutoCreator.onRunCompleted maps both to REVIEW
  // (listener/RunKanbanAutoCreator.java:102 for COMPLETED, :108 for FAILED).
  // That is the same column this spec parked the card in, so the listener's move
  // is a same-status no-op (KanbanTransitionService.java:95-97). CANCELLED is NOT
  // reachable on this path: only ABORTED/CANCELLED runs map to a CANCELLED card
  // (:103-104), and approval resumes the run rather than cancelling it. REVIEW is
  // also the intended sign-off stop for finished work (the D8 rule at :99-101).
  //
  // The previous cleanup wrote CANCELLED explicitly and only logged a non-200 —
  // that write, not the behaviour, is what put earlier cards in CANCELLED. It was
  // removed because it raced the listener: the write duplicates the listener's
  // move, so the two can interleave and the write can lose the optimistic-lock
  // race (HTTP 409, GlobalExceptionHandler.java:62-70) or be rejected because the
  // card has already left to somewhere the matrix forbids leaving
  // (InvalidStateTransitionException -> 409, :35-38). Removing the write removes
  // the race; the state claim stays.
  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/kanban/items/${card.id}`);
        return data?.status;
      },
      { timeout: 30_000 },
    )
    .toBe('REVIEW');
});
