import { test, expect } from '@playwright/test';
import {
  apiCall,
  establishOperatorSession,
  pollUntil,
  seedAdkAgent,
  seedKanbanItem,
  setScenario,
  transitionKanbanSettled,
  uniqueName,
} from './fixtures';

/**
 * Gap 3: decide through the UI rather than the REST API.
 *
 * kanban-hitl.spec.ts:133-178 reaches the decision zone and asserts it renders,
 * but never clicks a decision control. This spec closes that half.
 *
 * Reach path (LLM-free, CI-safe): pin an opencode agent whose core fixture is
 * the recorded 'write-twice' scenario (its first offer is an edit gate), dispatch
 * the card, and the run holds on that native ask. Post-cutover there is no
 * task-level gate to reach -- the core's own permission request IS the ask, and
 * the harness refuses to launch a peer for an agent without a declared
 * scenario, so the selection is part of the reach path. No LLM key and no
 * sandbox are needed.
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
  await setScenario(request, agent.id, 'deny-write');
  const card = await seedKanbanItem(request, {
    title: `uidz-${uniqueName('card')}`,
    agentTemplateId: agent.name,
  });
  expect((await transitionKanbanSettled(request, card.id, 'IN_PROGRESS')).status).toBe(200);

  const asks = await pollUntil<any[]>(
    request,
    `/approvals?kanbanItemId=${card.id}`,
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
    60_000,
    2_000,
  );
  const ask = asks.find((a) => a.status === 'PENDING');
  expect(ask).toBeTruthy();

  expect((await transitionKanbanSettled(request, card.id, 'REVIEW')).status).toBe(200);

  await establishOperatorSession(page);
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

  // Card state, asserted. Since the review-flow amendment (D6, 2026-10-04) a
  // settling ask settles its card: the DENIED native ask moves this REVIEW card
  // to CANCELLED at decide time (ApprovalSettleCardListener takes the
  // DENIED -> CANCELLED branch; the card is in REVIEW, so the settle applies).
  // The run's own end still arrives (the fixture's refusal completes the turn;
  // RunKanbanAutoCreator then finds a card that has already left REVIEW and
  // skips it) - the settled card state is the contract asserted here.
  // Polled read-only via GET /kanban/items/{id}.
  //
  // This replaces a cleanup that wrote CANCELLED explicitly and merely logged a
  // non-200. That write raced the listener's move (optimistic-lock 409,
  // GlobalExceptionHandler.java:62-70, or InvalidStateTransitionException ->
  // 409, :35-38). Removing the write removes the race; the state claim stays.
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
 * Timing note: approving the core's gate lets the run-owned session continue, so
 * the run proceeds to its scenario's completion. This test therefore asserts the
 * ask becomes APPROVED, that the run is not parked PAUSED, and that the card ends
 * in REVIEW — the sign-off column RunKanbanAutoCreator maps a finished run to. It
 * makes no assertion about the run's TERMINAL status beyond that.
 */
test('clicking Approve in the decision zone resolves the ask', async ({ page, request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-oc-uiaz'),
    adkProvider: 'opencode',
  });
  await setScenario(request, agent.id, 'deny-write');
  const card = await seedKanbanItem(request, {
    title: `uiaz-${uniqueName('card')}`,
    agentTemplateId: agent.name,
  });
  expect((await transitionKanbanSettled(request, card.id, 'IN_PROGRESS')).status).toBe(200);

  const asks = await pollUntil<any[]>(
    request,
    `/approvals?kanbanItemId=${card.id}`,
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
    60_000,
    2_000,
  );
  const ask = asks.find((a) => a.status === 'PENDING');
  expect(ask).toBeTruthy();

  expect((await transitionKanbanSettled(request, card.id, 'REVIEW')).status).toBe(200);

  await establishOperatorSession(page);
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
  // Since the review-flow amendment (D6, 2026-10-04) a settling ask settles its
  // card: the APPROVED native ask moves this REVIEW card to DONE at decide time
  // (ApprovalSettleCardListener takes the APPROVED -> DONE branch; the settled
  // ask's card is exempt from the run-active DONE guard, so DONE is reachable
  // while the resumed run is still in flight). The resumed run then completes in
  // its own lane; RunKanbanAutoCreator finds a card that has already left REVIEW
  // and skips it. DONE is also the intended sign-off stop for finished work
  // (the D8 rule at :99-101).
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
    .toBe('DONE');
});
