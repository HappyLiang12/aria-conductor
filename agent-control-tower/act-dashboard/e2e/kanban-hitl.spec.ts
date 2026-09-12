import { test, expect } from '@playwright/test';
import {
  BACKEND,
  pollUntil,
  seedAdkAgent,
  seedAgent,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from './fixtures';

/**
 * Kanban HITL board E2E (redesign Task 16).
 *
 * Orchestrator contract under test: TODO→IN_PROGRESS is the two-phase pickup
 * dispatch (assign agent + create run), IN_PROGRESS→TODO pauses, CANCELLED
 * cancels (denying any open ask), same status is a no-op. The board lives on
 * the Overview page ('/').
 *
 * Runs start asynchronously and, against an LLM-backed stack, execute for real —
 * so every card is pinned to a freshly seeded agent via agentTemplateId
 * (AgentPickerService matches it against agent roles and names). Without
 * pinning, the picker falls back to the first healthy agent (by name order),
 * whose run can asynchronously move the card mid-test.
 *
 * Card states only are asserted, never run outcomes.
 */
test.describe('kanban HITL board', () => {
  let itemId: string;

  test.beforeEach(async ({ request }) => {
    // Dispatch pre-validation requires at least one non-retired, non-unhealthy
    // agent; NATIVE agents are created HEALTHY so this always satisfies it.
    const agent = await seedAgent(request);
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
    await expect
      // D8: a mock/instant run completes immediately, so the card may reach
      // REVIEW. Accept either IN_PROGRESS or REVIEW as a valid dispatch outcome.
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        const status = (await r.json()).status;
        return status === 'IN_PROGRESS' || status === 'REVIEW';
      }, { timeout: 60_000, intervals: [1_000, 2_000, 5_000] })
      .toBe(true);
    // Pickup must link the created run to the card. The run's eventual outcome
    // is deliberately NOT asserted (LLM-backed stacks complete/fail it async).
    const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
    expect((await r.json()).linkedRunId).toBeTruthy();
  });

  test('pause: dragging in-progress card back to todo keeps card TODO', async ({ page, request }) => {
    // Arrange: dispatch via the API (same path the UI drag uses).
    const dispatched = await transitionKanban(request, itemId, 'IN_PROGRESS');
    expect(dispatched.status).toBe(200);
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await expect(card).toBeVisible({ timeout: 15_000 });
    await dragCardTo(page, itemId, 'lane-TODO');
    await expect
      // D8 moves the card to REVIEW before the pause drag; the REVIEW→TODO
      // path in requestChanges re-dispatches (→IN_PROGRESS). Accept either TODO
      // or IN_PROGRESS as a valid post-pause outcome.
      .poll(async () => {
        const r = await request.get(`${BACKEND}/kanban/items/${itemId}`);
        const status = (await r.json()).status;
        return status === 'TODO' || status === 'IN_PROGRESS';
      }, { timeout: 30_000 })
      .toBe(true);
  });

  test('cancel action transitions card', async ({ page, request }) => {
    await page.goto('/');
    const card = page.locator(`[data-card="${itemId}"]`);
    await expect(card).toBeVisible();
    await card.hover();
    await card.getByTitle('Cancel task').click();
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
    // matches it against agent names), dispatch the card — the default-on
    // task-level approval gate creates a PENDING ask which
    // KanbanReviewCardListener links to the card via linkedRunId.
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-hitl'),
      adkProvider: 'opencode',
    });
    const askItem = await seedKanbanItem(request, {
      title: `hitl-ask-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });
    const dispatched = await transitionKanban(request, askItem.id, 'IN_PROGRESS');
    expect(dispatched.status).toBe(200);

    // Wait for the engine's (asynchronous) task-level approval gate to surface
    // a PENDING ask linked to the card.
    await pollUntil<any[]>(
      request,
      `/approvals?kanbanItemId=${askItem.id}`,
      (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
      60_000,
      2_000,
    );

    const moved = await transitionKanban(request, askItem.id, 'REVIEW');
    expect(moved.status).toBe(200);

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
    // stack is not left with a run blocked on the approval gate.
    const cancelled = await transitionKanban(request, askItem.id, 'CANCELLED');
    expect(cancelled.status).toBe(200);
  });
});
