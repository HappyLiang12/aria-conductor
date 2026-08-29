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
 * a fast-failing NATIVE run — drawer stream window + pump row + collapse,
 * Runs live detail panel, kanban transition flash, toast auto-dismiss.
 *
 * Track B (opencode sandbox + LLM key): same chain plus the progress pump —
 * run.progress source lines in the drawer and pump attached → detached.
 * Skipped when LLM_API_KEY is absent (pump/broadcast correctness is proven by
 * WireMock unit tests in that case).
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
  test('drawer stream window: pump row, collapse toggle, WS lines, toast 5s', async ({ page, request }) => {
    const agent = await seedAgent(request);
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await openAgentDrawer(page, agent.id);

    // Pump status row starts idle and advertises the poll contract.
    await expect(page.locator('.agent-drawer .pump')).toContainText('idle');
    // Fixed-height stream window badge (UX contract: last 60 lines).
    await expect(page.locator('.agent-drawer .winbadge')).toContainText('last 60 lines');

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

    // Seed line + at least one WS-fed line (run.started / run.completed).
    await expect(page.locator('.agent-drawer .stream .ln')).not.toHaveCount(1, { timeout: 15_000 });

    // run.completed is toast-worthy; the toast auto-dismisses after 5s.
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

  test('Kanban live transition: card moves columns with flash class', async ({ page, request }) => {
    const item = await seedKanbanItem(request, { title: 'e2e-obs-live-move' });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`[data-col="todo"] [data-card="${item.id}"]`)).toBeVisible();

    const { status } = await apiCall(request, 'POST', `/kanban/items/${item.id}/transition`, {
      status: 'IN_PROGRESS',
      comment: 'e2e observability flash',
    });
    expect(status).toBe(200);

    // WS kanban.transitioned → invalidate + 1.2s flash on the moved card.
    const moved = page.locator(`[data-col="in_progress"] [data-card="${item.id}"]`);
    await expect(moved).toBeVisible({ timeout: 15_000 });
    await expect(moved).toHaveClass(/moving/, { timeout: 5_000 });
    // Flash clears after the animation window.
    await expect(moved).not.toHaveClass(/moving/, { timeout: 8_000 });
  });
});

// ─────────────────────────────────────────────────────────────────────
// Track B — opencode progress pump (requires LLM key + sandbox stack)
// ─────────────────────────────────────────────────────────────────────
test.describe('Track B — opencode progress pump live chain', () => {
  test('run.progress lines + pump attached → detached + trajectory growth', async ({ page, request }) => {
    test.skip(!process.env.LLM_API_KEY, 'Track B requires LLM_API_KEY + opencode sandbox stack');

    const agent = await seedAdkAgent(request, { adkProvider: 'opencode' });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await openAgentDrawer(page, agent.id);
    await expect(page.locator('.agent-drawer .pump')).toContainText('idle');

    const run = await seedRun(request, agent.id, 'Reply with the single word: pong', 1);
    await approveRunApproval(request, run.id);

    // Pump attaches while the task is in flight (first poll within Δ2s).
    await expect(page.locator('.agent-drawer .pump.on')).toContainText('attached', {
      timeout: 180_000,
    });
    // Pump fragments surface as 'Agent Progress' stream lines.
    await expect(
      page.locator('.agent-drawer .stream .ln .msg').filter({ hasText: 'Agent Progress' }).first(),
    ).toBeVisible({ timeout: 60_000 });

    const terminal = await pollRunTerminal(request, run.id, 300_000);
    expect(terminal.status).toBe('COMPLETED');

    // Pump detaches once the run completes.
    await expect(page.locator('.agent-drawer .pump')).toContainText('detached', { timeout: 30_000 });

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
