import { test, expect, type Page } from '@playwright/test';
import {
  apiCall,
  approveRunApproval,
  pollRunTerminal,
  seedAdkAgent,
  seedAgent,
  seedKanbanItem,
  seedRun,
} from './fixtures';

/**
 * S14: live observability e2e — dual-track acceptance.
 *
 * Track A (gate, runs WITHOUT an LLM key): exercises the WS-driven live UI on
 * a fast-failing NATIVE run — drawer Live Activity Stream (idle empty state,
 * collapse toggle, WS lifecycle fold, retention), Runs live detail panel,
 * kanban transition final state, toast auto-dismiss.
 *
 * Track B (opencode sandbox + LLM key): same chain plus run.progress frames
 * surfacing as 'Agent Progress' stream lines, the iteration display in the
 * Active Run block, and history retention after the run goes terminal.
 * Skipped when LLM_API_KEY is absent (progress broadcast correctness is
 * proven by WireMock unit tests in that case).
 */
test.describe.configure({ timeout: 300_000 });

/**
 * Wait until a run leaves PENDING/INITIALIZING/RUNNING. Terminal states are the
 * normal end; PAUSED is equally valid — with a live LLM the agent performs a
 * real high-risk tool call and the approval gate parks the run until a human
 * decides (governance by design). Either state proves the run actually started
 * and is stable, which is all the UI-shape assertions below need.
 */
async function pollRunStable(request: Parameters<typeof pollRunTerminal>[0], runId: string, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  let last: any = null;
  while (Date.now() < deadline) {
    const { status, data } = await apiCall(request, 'GET', `/runs/${runId}`);
    last = data;
    if (status >= 200 && status < 300 && data && data.status !== 'RUNNING'
        && data.status !== 'PENDING' && data.status !== 'INITIALIZING') {
      return data;
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }
  throw new Error(`pollRunStable timed out for ${runId}; last=${JSON.stringify(last)?.slice(0, 200)}`);
}

/** Open the agent live drawer from the Overview agent team list. */
async function openAgentDrawer(page: Page, agentId: string) {
  await page.locator(`[data-agent="${agentId}"]`).first().click();
  await expect(page.locator('.agent-drawer.open')).toBeVisible({ timeout: 10_000 });
}

// ─────────────────────────────────────────────────────────────────────
// Track A — gate (no LLM key required)
// ─────────────────────────────────────────────────────────────────────
test.describe('Track A — live observability gate (no LLM key)', () => {
  test('drawer stream window: idle empty state, collapse toggle, WS lines, toast 5s', async ({ page, request }) => {
    const agent = await seedAgent(request);
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await openAgentDrawer(page, agent.id);

    // Rebuilt drawer (93d25b4): the fake pump stub is gone — the idle state is
    // the stream's empty-state line and progress history replays into the
    // Live Activity Stream section.
    const drawer = page.locator('.agent-drawer');
    await expect(drawer.getByText('Live Activity Stream')).toBeVisible();
    // Fixed-height stream window badge (UX contract: last 60 lines).
    await expect(page.locator('.agent-drawer .winbadge')).toContainText('last 60 lines');
    // An empty stream renders the honest idle hint (no seeded demo line).
    await expect(page.locator('.agent-drawer .stream .ln .tag')).toHaveText('idle');

    // Collapse toggle hides the stream body but keeps the section header.
    const tgl = page.locator('.agent-drawer .tgl').first();
    await tgl.click();
    await expect(page.locator('.agent-drawer .stream.collapsed')).toBeHidden();
    await expect(page.locator('.agent-drawer').getByText('Live Activity Stream')).toBeVisible();
    await tgl.click();
    await expect(page.locator('.agent-drawer .stream:not(.collapsed)')).toBeVisible();

    // Start a run while the drawer is open: lifecycle events fold into the stream.
    const run = await seedRun(request, agent.id, 'e2e observability track A');
    await pollRunStable(request, run.id);

    // At least one WS-fed lifecycle line (Run Started / Run Failed / Run
    // Completed) is folded in AND still visible after the run went terminal —
    // the completion-time query invalidation must not wipe the stream.
    await expect(
      page
        .locator('.agent-drawer .stream .ln')
        .filter({ hasText: /Run (Started|Failed|Completed)/ })
        .first(),
    ).toBeVisible({ timeout: 15_000 });
    // The idle empty-state hint is replaced once real lines exist.
    await expect(page.locator('.agent-drawer .stream .ln .tag').filter({ hasText: 'idle' })).toHaveCount(0);

    // Failures surface as run.completed toasts ("Run Failed"); auto-dismiss 5s.
    const toast = page.locator('.toast-container .toast-item').first();
    await expect(toast).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.toast-container')).toBeHidden({ timeout: 8_000 });
  });

  test('Runs live detail: trajectory panel renders with collapse toggles', async ({ page, request }) => {
    const agent = await seedAgent(request);
    const run = await seedRun(request, agent.id, 'e2e observability runs panel');
    await pollRunStable(request, run.id);

    await page.goto('/runs');
    await page.waitForLoadState('networkidle');
    const row = page.locator('.data-table tbody tr').filter({ hasText: run.id.slice(0, 8) }).first();
    await row.getByRole('button', { name: 'Details' }).click();

    const panel = page.locator('.run-detail-panel');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    await expect(panel.getByRole('heading', { name: /Session Trajectory/ })).toBeVisible();
    await expect(panel.getByRole('heading', { name: /Tool Calls/ })).toBeVisible();
    // Trajectory column shows data or the explicit empty state (no key → fast fail).
    const trajCol = panel.locator('.detail-col-wide');
    await expect(trajCol.locator('.trajectory-list, .empty-mini').first()).toBeVisible();

    // Both columns expose collapse toggles; trajectory collapse hides its body.
    await expect(panel.locator('.tgl')).not.toHaveCount(0);
    await panel.locator('.tgl').first().click();
    await expect(trajCol.locator('.trajectory-list, .empty-mini')).toHaveCount(0);
    await panel.locator('.tgl').first().click();
    await expect(trajCol.locator('.trajectory-list, .empty-mini').first()).toBeVisible();
  });

  test('Kanban live transition: card moves columns (final state)', async ({ page, request }) => {
    // HITL redesign: TODO → IN_PROGRESS is a dispatch (two-phase pickup), so
    // the card is pinned to THIS fresh agent (AgentPickerService matches
    // agentTemplateId against agent names) — without pinning the picker falls
    // back to the Aria assistant, whose real-LLM run moves cards asynchronously
    // via its kanban MCP tools.
    const agent = await seedAgent(request);
    const item = await seedKanbanItem(request, {
      title: 'e2e-obs-live-move',
      agentTemplateId: agent.name,
    });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    // D1: card may be auto-dispatched from TODO. Accept any governed column.
    await expect(
      page.locator(`[data-card="${item.id}"]`),
    ).toBeVisible({ timeout: 15_000 });

    const { status } = await apiCall(request, 'POST', `/kanban/items/${item.id}/transition`, {
      status: 'IN_PROGRESS',
      comment: 'e2e observability move',
    });
    expect(status).toBe(200);

    // WS kanban.transitioned → invalidate → the card lands in the target column.
    // The 1.2s flash class on the moved card is timing-fragile to observe E2E
    // (it can expire before the refetched card renders); the flash behavior is
    // covered deterministically by KanbanBoard.test.tsx ("flashes the moved
    // card on kanban.transitioned and clears after ~1.2s"), so here we assert
    // the observable final state: present in the new column, gone from todo.
    // D8: a mock/instant run completes immediately, moving the card to REVIEW.
    const moved = page.locator(`[data-col="IN_PROGRESS"] [data-card="${item.id}"]`).or(
      page.locator(`[data-col="REVIEW"] [data-card="${item.id}"]`),
    );
    await expect(moved).toBeVisible({ timeout: 15_000 });
    await expect(page.locator(`[data-col="TODO"] [data-card="${item.id}"]`)).toHaveCount(0);
  });
});

// ─────────────────────────────────────────────────────────────────────
// Track B — opencode progress replay (requires LLM key + sandbox stack)
// ─────────────────────────────────────────────────────────────────────
test.describe('Track B — opencode progress replay live chain', () => {
  test('run.progress lines + iteration display + history retention + trajectory growth', async ({ page, request }) => {
    test.skip(!process.env.LLM_API_KEY, 'Track B requires LLM_API_KEY + opencode sandbox stack');

    const agent = await seedAdkAgent(request, { adkProvider: 'opencode' });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await openAgentDrawer(page, agent.id);
    // Rebuilt drawer: no pump stub — the Live Activity Stream section is the
    // progress surface (REST backlog replay + live WS fold).
    await expect(page.locator('.agent-drawer').getByText('Live Activity Stream')).toBeVisible();
    await expect(page.locator('.agent-drawer .winbadge')).toContainText('last 60 lines');

    const run = await seedRun(request, agent.id, 'Reply with the single word: pong', 1);
    await approveRunApproval(request, run.id);

    // Active Run block carries the honest iteration display (replaces the
    // removed token/context cards) while the task is in flight.
    const nowTask = page.locator('.agent-drawer .now-task');
    await expect(nowTask).toContainText('Active Run', { timeout: 180_000 });
    await expect(nowTask).toContainText(/Iter \d+ \/ \d+/);

    // run.progress frames surface as 'Agent Progress' stream lines.
    await expect(
      page.locator('.agent-drawer .stream .ln .msg').filter({ hasText: 'Agent Progress' }).first(),
    ).toBeVisible({ timeout: 60_000 });

    const terminal = await pollRunTerminal(request, run.id, 300_000);
    expect(terminal.status).toBe('COMPLETED');

    // History retention: the completion-time query invalidation must not wipe
    // the stream — replayed/live 'Agent Progress' lines persist after the run
    // goes terminal (replaces the removed pump "detached" assertion).
    await expect(
      page.locator('.agent-drawer .stream .ln .msg').filter({ hasText: 'Agent Progress' }).first(),
    ).toBeVisible({ timeout: 15_000 });

    // Runs detail shows the grown trajectory with live toggles.
    await page.goto('/runs');
    await page.waitForLoadState('networkidle');
    const row = page.locator('.data-table tbody tr').filter({ hasText: run.id.slice(0, 8) }).first();
    await row.getByRole('button', { name: 'Details' }).click();
    const panel = page.locator('.run-detail-panel');
    await expect(panel.locator('.trajectory-list .trajectory-turn')).not.toHaveCount(0, {
      timeout: 20_000,
    });
  });
});
