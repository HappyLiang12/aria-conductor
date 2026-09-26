import { test, expect } from '@playwright/test';
import {
  BACKEND,
  apiCall,
  pollUntil,
  permissionOptions,
  seedAdkAgent,
  seedKanbanItem,
  setScenario,
  transitionKanban,
  uniqueName,
} from './fixtures';

/**
 * Kanban HITL board E2E (redesign Task 16) — revised in Task 17 for the governed
 * cores and the deterministic harness.
 *
 * Orchestrator contract under test: TODO→IN_PROGRESS is the two-phase pickup
 * dispatch (assign agent + create run), IN_PROGRESS→TODO pauses, CANCELLED
 * cancels (denying any open ask), same status is a no-op. The board lives on
 * the Overview page ('/').
 *
 * Runs start asynchronously and are served by the deterministic harness — every
 * card state is pinned to exactly one expected value (the former D8
 * "IN_PROGRESS or REVIEW" / "TODO or IN_PROGRESS" alternatives are gone), and
 * the review case asserts the ask's offered one-use grant set (Task 12) rather
 * than only the ask badge text. Card states only are asserted, never run
 * outcomes.
 */
/** The exact optimistic-lock 409 body (GlobalExceptionHandler.handleOptimisticLock). */
const OPTIMISTIC_LOCK_409 = 'Card was modified by another move — refresh and retry.';

/**
 * The deterministic peer fixture of this spec's dispatched runs. The card
 * states under test are the pickup/pause/cancel contract, and the run of a
 * dispatched card must stay in flight (RUNNING, held on its core-raised
 * permission gate) while those states are asserted: a read-only completion
 * would let RunKanbanAutoCreator settle the linked card into REVIEW. The
 * 'deny-write' fixture holds the prompt on exactly that gate -- its native
 * ask stays PENDING (and is the one the review case pins) until a decision --
 * so the dispatched card stays IN_PROGRESS. The selection also matters because
 * the harness refuses a run whose agent has no declared scenario.
 */
const SCENARIO = 'deny-write';

test.describe('kanban HITL board', () => {
  let itemId: string;
  let agentId: string;

  test.beforeEach(async ({ request }) => {
    // Dispatch pre-validation requires at least one non-retired, non-unhealthy
    // agent, and the dispatched run must carry an explicit governed selection
    // with a declared peer fixture: the agent is created on the harness core and
    // mode (opencode + HOST) and its scenario is selected here, so the run is
    // driven by a deterministic fixture instead of failing closed.
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-hitl'),
      adkProvider: 'opencode',
      executionMode: 'HOST',
    });
    await setScenario(request, agent.id, SCENARIO);
    agentId = agent.id;
    const created = await seedKanbanItem(request, {
      title: `hitl-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });
    itemId = created.id;
  });

  /**
   * Drags a board card onto a drop lane by dispatching real DOM DragEvents.
   *
   * Playwright's locator.dragTo does not fire the HTML5 drag sequence on this
   * stack (real Chrome via channel:'chrome' swallows the CDP-intercepted drag —
   * no dragstart/drop reaches the page, the card simply never moves). Synthetic
   * DragEvents go through the exact same React handlers (onDragStart →
   * onDragOver → onDrop → transitionMutation → backend orchestrator), and the
   * inter-frame wait lets React commit the draggingId state before the drop.
   */
  async function dragCardTo(page: import('@playwright/test').Page, cardId: string, laneTestId: string) {
    await page.evaluate(async ({ cardId, laneTestId }) => {
      const card = document.querySelector(`[data-card="${cardId}"]`);
      const lane = document.querySelector(`[data-testid="${laneTestId}"]`);
      if (!card || !lane) throw new Error(`drag sources not found: ${cardId} → ${laneTestId}`);
      const dt = new DataTransfer();
      card.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: dt }));
      await new Promise((r) => setTimeout(r, 50));
      lane.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt }));
      lane.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
      card.dispatchEvent(new DragEvent('dragend', { bubbles: true, cancelable: true, dataTransfer: dt }));
    }, { cardId, laneTestId });
  }

  test('board shows five status columns only', async ({ page }) => {
    await page.goto('/');
    for (const col of ['BACKLOG', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE']) {
      await expect(page.locator(`.col-k[data-col="${col}"]`)).toBeVisible();
    }
    // Retired surfaces: no QA Gate / Cancelled / Archived columns may render.
    await expect(page.locator('.col-k[data-col="qa_gate"]')).toHaveCount(0);
    await expect(page.locator('.col-k[data-col="CANCELLED"]')).toHaveCount(0);
    await expect(page.locator('.col-k[data-col="archived"]')).toHaveCount(0);
  });

  test('drag todo card to in progress dispatches pickup', async ({ page, request }) => {
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await expect(card).toBeVisible();
    await dragCardTo(page, itemId, 'lane-IN_PROGRESS');

    // A Todo create is a dispatch intent (KanbanAutoDispatchListener picks it
    // up), and a drag onto the card's own lane is the same-status no-op: both
    // paths land the card in exactly IN_PROGRESS (the two-phase pickup's
    // captured state); the governed run then holds on its permission gate
    // without moving the card. The former "IN_PROGRESS or REVIEW" tolerance is
    // gone.
    await expect
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        return (await r.json()).status;
      }, { timeout: 60_000, intervals: [1_000, 2_000, 5_000] })
      .toBe('IN_PROGRESS');

    // Pickup must link the run it created to the card: the linked id is resolved
    // and pinned to this test's captured agent — a link to any other run fails.
    const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
    const linkedRunId = (await r.json()).linkedRunId as string;
    expect(linkedRunId).toMatch(/^[0-9a-f-]{36}$/);
    const linked = await apiCall(request, 'GET', `/runs/${linkedRunId}`);
    expect(linked.status).toBe(200);
    expect(linked.data.agentId).toBe(agentId);
  });

  test('pause: dragging in-progress card back to todo keeps card TODO', async ({ page, request }) => {
    // Arrange: dispatch via the API (same path the UI drag uses).
    const dispatched = await transitionKanban(request, itemId, 'IN_PROGRESS');
    expect(dispatched.status, JSON.stringify(dispatched.data)).toBe(200);
    expect(dispatched.data?.status).toBe('IN_PROGRESS');
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await expect(card).toBeVisible({ timeout: 15_000 });
    await dragCardTo(page, itemId, 'lane-TODO');

    // A pause parks the card in exactly TODO (the coordinated stop detaches the
    // run link); the former "TODO or IN_PROGRESS" tolerance is gone.
    await expect
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        const body = await r.json();
        return { status: body.status, linkedRunId: body.linkedRunId };
      }, { timeout: 30_000 })
      .toEqual({ status: 'TODO', linkedRunId: null });
  });

  test('cancel action transitions card', async ({ page, request }) => {
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await expect(card).toBeVisible();
    await card.hover();
    // Exact title: an in-flight card holding a pending ask also renders the
    // quick-deny control whose title "Deny (cancel task)" substring-matches a
    // non-exact 'Cancel task' lookup.
    await card.getByTitle('Cancel task', { exact: true }).click();
    // Task 10: the ✕ only opens the confirmation; the transition fires on Confirm.
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        return (await r.json()).status;
      }, { timeout: 30_000 })
      .toBe('CANCELLED');
  });

  test('review card shows ask badge and opens decision zone', async ({ page, request }) => {
    // There is no approval seed endpoint, so this drives the REAL ask path:
    // pin a task-capable opencode agent via agentTemplateId (AgentPickerService
    // matches it against agent names) and dispatch the card — the held
    // 'deny-write' fixture's core-raised native permission request creates the
    // PENDING ask, which KanbanReviewCardListener links to the card via
    // linkedRunId.
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-hitl'),
      adkProvider: 'opencode',
      executionMode: 'HOST',
    });
    await setScenario(request, agent.id, SCENARIO);
    const askItem = await seedKanbanItem(request, {
      title: `hitl-ask-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });
    const dispatched = await transitionKanban(request, askItem.id, 'IN_PROGRESS');
    expect(dispatched.status, JSON.stringify(dispatched.data)).toBe(200);
    expect(dispatched.data?.status).toBe('IN_PROGRESS');

    // Wait for the run's (asynchronous) core-raised native ask to surface
    // linked to the card, and pin its exact offered grant set: the one-use
    // ALLOW_ONCE option plus the reject option (Task 12).
    const asks = await pollUntil<any[]>(
      request,
      `/approvals?kanbanItemId=${askItem.id}`,
      (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
      60_000,
      2_000,
    );
    const ask = asks.find((a) => a.status === 'PENDING')!;
    expect(permissionOptions(ask)).toEqual([
      { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
      { optionId: 'cancel', choice: 'DENY' },
    ]);

    const moved = await transitionKanban(request, askItem.id, 'REVIEW');
    expect(moved.status, JSON.stringify(moved.data)).toBe(200);
    expect(moved.data?.status).toBe('REVIEW');

    await page.goto('/');
    const reviewCard = page.locator(`[data-col="REVIEW"] [data-card="${askItem.id}"]`);
    await expect(reviewCard).toBeVisible({ timeout: 15_000 });
    await expect(reviewCard.locator('.pill.warn')).toHaveText('1 asks');

    // Clicking the review card opens the Task drawer with the decision zone.
    await reviewCard.click();
    const dzTitle = page.locator('.decision-zone .dz-title');
    await expect(dzTitle).toContainText('NEEDS YOUR DECISION');
    await expect(dzTitle).toContainText('1 asks');

    // Cleanup: cancel denies the open ask and cancels the linked run, so the
    // stack is not left with a run blocked on the approval gate. The accepted
    // response is asserted exactly (200 + CANCELLED, the exact-ack teardown of
    // core-permissions.spec.ts:110-117); only the disclosed optimistic-lock 409
    // for the raced card row is retried, never any other refusal.
    const first = await transitionKanban(request, askItem.id, 'CANCELLED');
    const cancelled =
      first.status === 409 && first.data?.message === OPTIMISTIC_LOCK_409
        ? await transitionKanban(request, askItem.id, 'CANCELLED')
        : first;
    expect(cancelled.status, JSON.stringify(cancelled.data)).toBe(200);
    expect(cancelled.data?.status).toBe('CANCELLED');
  });
});
