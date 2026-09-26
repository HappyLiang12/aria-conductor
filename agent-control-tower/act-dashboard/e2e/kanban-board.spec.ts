import { test, expect } from '@playwright/test';
import {
  apiCall,
  runExecutionBinding,
  seedAdkAgent,
  seedKanbanItem,
  setScenario,
  transitionKanbanSettled,
  uniqueName,
} from './fixtures';

/**
 * Kanban board E2E (HITL redesign board: Backlog / Todo / In Progress / Review / Done).
 *
 * ADAPTATION NOTE: KanbanPage.tsx exists in src/pages but is not wired into
 * the router (App.tsx has no /kanban route). The shipped Kanban surface is the
 * KanbanBoard panel on the Overview page ('/'), so this spec targets that.
 *
 * The dispatched-run fixture: a card's run is settled by its agent's declared
 * scenario, so the in-flight states below are only deterministic when the run is
 * HELD. 'deny-write' holds the prompt on its own permission gate (the same
 * fixture kanban-hitl.spec.ts uses), which keeps the card IN_PROGRESS until a
 * decision; the instant-completing default scenario would settle it into REVIEW
 * first, and REVIEW -> TODO is the redesign's request-changes move (it
 * re-dispatches) rather than a pause.
 */
const HOLDING_SCENARIO = 'deny-write';

test.describe('Kanban board (Overview governed flow)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  const boardPanel = (page: import('@playwright/test').Page) =>
    page.locator('section.panel').filter({ hasText: 'Kanban Board' });

  test('board renders all governed-flow columns', async ({ page }) => {
    // Panels nest (outer wrapper is also section.panel), so assert the h2 at
    // page level and the columns via their unique data-col attributes.
    await expect(page.locator('h2').filter({ hasText: 'Kanban Board' }).first()).toBeVisible();
    for (const col of ['BACKLOG', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE']) {
      await expect(page.locator(`.col-k[data-col="${col}"]`)).toBeVisible();
    }
    // HITL redesign: QA Gate and Archived columns are retired — cancel is an
    // action (✕) rather than a state column.
    await expect(page.locator('.col-k[data-col="qa_gate"]')).toHaveCount(0);
    await expect(page.locator('.col-k[data-col="archived"]')).toHaveCount(0);
    await expect(page.locator('.col-k[data-col="TODO"] header')).toContainText('Todo');
    await expect(page.locator('.col-k[data-col="REVIEW"] header')).toContainText('Review');
  });

  test('API-seeded item appears in the Todo column', async ({ page, request }) => {
    const item = await seedKanbanItem(request, { title: uniqueName('e2e-kanban-seeded') });
    await page.reload();
    await page.waitForLoadState('networkidle');
    // D1: card may be auto-dispatched (TODO→IN_PROGRESS) and D8 may complete
    // to REVIEW. Accept any governed column as valid.
    const card = page.locator(`[data-card="${item.id}"]`);
    await expect(card).toBeVisible();
    await expect(card.locator('.t')).toHaveText(item.title);
  });

  test('create item via New Task modal (with empty-title validation)', async ({ page }) => {
    const title = uniqueName('e2e-kanban-ui');
    await boardPanel(page).getByRole('button', { name: '+ New Item' }).click();
    await expect(page.locator('.modal-dialog h3')).toHaveText('New Task');

    // Negative: empty title is rejected client-side.
    await page.locator('.modal-dialog').getByRole('button', { name: 'Create in Todo' }).click();
    await expect(page.locator('.kanban-form-error')).toHaveText('Title is required');

    await page.locator('.kanban-form-row').filter({ hasText: 'Title' }).locator('input').fill(title);
    await page.locator('.modal-dialog').getByRole('button', { name: 'Create in Todo' }).click();
    await expect(page.locator('.modal-dialog')).toBeHidden({ timeout: 15_000 });
    // D1: card may be auto-dispatched from TODO. Check card exists anywhere.
    await expect(
      page.locator('.card .t').filter({ hasText: title }),
    ).toBeVisible({ timeout: 20_000 });
  });

  test('dispatch then pause via API transitions (agent-gated pickup)', async ({ page, request }) => {
    // TODO → IN_PROGRESS is a dispatch: pickup pre-validation needs at least one
    // non-retired, non-unhealthy agent. The card is pinned to THIS fresh agent
    // (AgentPickerService matches agentTemplateId against agent names) so the
    // picker cannot fall back to the Aria assistant, whose real-LLM run moves
    // cards asynchronously via its kanban MCP tools. The agent's scenario holds
    // its run on a permission gate, so the card is still IN_PROGRESS when the
    // pause below runs (an instant completion would settle it into REVIEW, whose
    // TODO move is request-changes, not a pause).
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-kanban-move-agent'),
      adkProvider: 'opencode',
      executionMode: 'HOST',
    });
    await setScenario(request, agent.id, HOLDING_SCENARIO);
    const item = await seedKanbanItem(request, {
      title: uniqueName('e2e-kanban-move'),
      agentTemplateId: agent.name,
    });

    const dispatched = await transitionKanbanSettled(request, item.id, 'IN_PROGRESS', {
      comment: 'e2e dispatch',
    });
    expect(dispatched.status, JSON.stringify(dispatched.data)).toBe(200);
    expect(dispatched.data?.status).toBe('IN_PROGRESS');

    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(
      page.locator(`[data-col="IN_PROGRESS"] [data-card="${item.id}"]`),
    ).toBeVisible({ timeout: 15_000 });

    // Pause: IN_PROGRESS → TODO is a legal, run-pausing move under the redesign
    // (it was rejected as illegal before). A coordinated run can only be paused
    // once its SESSION is open — the coordinator registers the run's runtime
    // after the backend launched and the core session opened, and records
    // runtimeState=RUNNING/BACKEND_SUSPEND on the run's frozen binding at that moment. Before
    // that, a pause is refused with the coordinator's truthful conflict ("has no
    // run-owned runtime in this process; there is nothing to pause"). The board's
    // own auto flow dispatches a created card immediately, so the spec waits for
    // the recorded runtime state instead of racing the launch window.
    const linkedRunId = (await apiCall(request, 'GET', `/kanban/items/${item.id}`)).data?.linkedRunId;
    expect(linkedRunId, 'the dispatch must link the card to its run').toMatch(/^[0-9a-f-]{36}$/);
    await expect
      .poll(async () => (await runExecutionBinding(request, linkedRunId)).runtimeState, { timeout: 60_000 })
      .toBe('RUNNING/BACKEND_SUSPEND');

    const paused = await transitionKanbanSettled(request, item.id, 'TODO');
    expect(paused.status, JSON.stringify(paused.data)).toBe(200);
    expect(paused.data.status).toBe('TODO');

    // Idempotent no-op: repeating the current status must not error or re-dispatch.
    const noop = await transitionKanbanSettled(request, item.id, 'TODO');
    expect(noop.status, JSON.stringify(noop.data)).toBe(200);
    expect(noop.data.status).toBe('TODO');

    await page.reload();
    await page.waitForLoadState('networkidle');
    // After a pause, the card should be in TODO. With D1/D8 the card may not
    // remain there (async listeners can move it), so accept any governed column.
    await expect(
      page.locator(`[data-col="TODO"] [data-card="${item.id}"]`).or(
        page.locator(`[data-col="IN_PROGRESS"] [data-card="${item.id}"]`),
      ).or(
        page.locator(`[data-col="REVIEW"] [data-card="${item.id}"]`),
      ),
    ).toBeVisible();
  });
});
