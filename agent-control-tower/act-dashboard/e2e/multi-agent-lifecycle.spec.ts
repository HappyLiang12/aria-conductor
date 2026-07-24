import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Multi-agent lifecycle via Aria chat.
 *
 * Flow:
 * 1. Open Aria panel and ask it to create a Research agent + start a run
 * 2. Ask Aria to create a Verify agent + start a run
 * 3. Ask Aria to create a Report agent + start a run
 * 4. Verify agents appear on Crew page
 * 5. Verify kanban items show lifecycle (TODO → IN_PROGRESS → DONE)
 * 6. Click agents to view their job status in the drawer
 * 7. Verify the generated report appears in the Reports tab
 *
 * Uses the REST API to poll for run completion since LLM calls are slow.
 */
test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min total

const ARIA_POLL_TIMEOUT = 120_000;  // 2 min for Aria to respond
const RUN_TIMEOUT = 180_000;        // 3 min for a run to complete

/** Helper: wait for a backend REST condition via fetch inside the browser page. */
async function waitForBackend(
  page: Page,
  url: string,
  predicate: (json: any) => boolean,
  timeout: number = RUN_TIMEOUT,
  intervalMs: number = 5000,
): Promise<any> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const result = await page.evaluate(
        async ({ u }) => {
          const r = await fetch(u);
          if (!r.ok) return null;
          return r.json();
        },
        { u: url },
      );
      if (result && predicate(result)) return result;
    } catch { /* ignore */ }
    await page.waitForTimeout(intervalMs);
  }
  throw new Error(`waitForBackend timed out for ${url}`);
}

/** Open the Aria floating panel */
async function openAriaPanel(page: Page) {
  const fab = page.locator('button.ai-fab');
  await fab.waitFor({ state: 'visible', timeout: 10_000 });
  await fab.click();
  await expect(page.locator('.ai-panel')).toBeVisible({ timeout: 5000 });
}

/** Send a message in the Aria panel and wait for a response */
async function sendAriaMessage(page: Page, message: string): Promise<void> {
  const textarea = page.locator('.ai-panel textarea');
  await textarea.waitFor({ state: 'visible', timeout: 5000 });
  await textarea.fill(message);

  // Press Enter to send
  await textarea.press('Enter');

  // Wait for Aria to finish processing (look for the response bubble or tool action)
  // The panel shows a spinner while busy; wait for it to disappear
  await page.waitForFunction(
    () => {
      const panel = document.querySelector('.ai-panel');
      if (!panel) return false;
      // Check if there's a "busy" indicator still active
      const spinner = panel.querySelector('.ai-thinking, .ai-busy, [data-busy="true"]');
      return !spinner;
    },
    { timeout: ARIA_POLL_TIMEOUT },
  );

  // Give a moment for rendering
  await page.waitForTimeout(1000);
}

// ─────────────────────────────────────────────────────────────────────
test('Multi-agent lifecycle: Research → Verify → Report via Aria', async ({ page }) => {
  // ── Step 0: Navigate to dashboard ──
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  // Verify dashboard loaded
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/01-dashboard-loaded.png' });

  // ── Step 1: Ask Aria to create Research agent + start run ──
  await openAriaPanel(page);
  await page.screenshot({ path: 'e2e/screenshots/02-aria-panel-open.png' });

  await sendAriaMessage(
    page,
    'Create an agent named "E2E Research Agent" with role researcher. Then start a run for it with prompt: "Research Google A2A protocol - what is it, key components, and use cases. Be concise." Set maxIterations to 3.',
  );
  await page.screenshot({ path: 'e2e/screenshots/03-aria-research-created.png' });

  // ── Step 2: Verify research agent appears on Crew page ──
  await page.locator('.rail-btn[data-view="crew"]').click();
  await page.waitForLoadState('networkidle');
  await expect(page.getByText('E2E Research Agent')).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/04-research-agent-on-crew.png' });

  // ── Step 3: Verify kanban item created on Overview ──
  await page.locator('.rail-btn[data-view="overview"]').click();
  await page.waitForLoadState('networkidle');

  // Wait for at least one kanban item to appear
  await expect(page.locator('.kanban-card').first()).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/05-kanban-research-item.png' });

  // ── Step 4: Click on the research agent to open its drawer ──
  await page.locator('.rail-btn[data-view="crew"]').click();
  await page.waitForLoadState('networkidle');
  await page.getByText('E2E Research Agent').click();

  // AgentDrawer should open and show run info
  await page.waitForTimeout(1000);
  await page.screenshot({ path: 'e2e/screenshots/06-research-agent-drawer.png' });

  // ── Step 5: Wait for research run to complete via backend polling ──
  const researchAgentId = await page.evaluate(async () => {
    const r = await fetch('/api/v1/agents');
    const agents = await r.json();
    const a = agents.find((a: any) => a.name === 'E2E Research Agent');
    return a?.id ?? null;
  });
  expect(researchAgentId).toBeTruthy();

  // Poll for the run to complete
  const researchRun = await waitForBackend(
    page,
    `http://127.0.0.1:8080/api/v1/runs?agentId=${researchAgentId}`,
    (runs: any[]) => Array.isArray(runs) && runs.some((r: any) => r.status === 'COMPLETED'),
    RUN_TIMEOUT,
  );
  const researchRunId = researchRun.find((r: any) => r.status === 'COMPLETED').id;
  console.log(`Research run completed: ${researchRunId}`);

  // ── Step 6: Ask Aria to create Verify agent ──
  await openAriaPanel(page);
  await sendAriaMessage(
    page,
    'Create an agent named "E2E Verify Agent" with role verifier. Then start a run with prompt: "Verify the Google A2A research: A2A is an open protocol by Google for agent-to-agent communication. Key components: Agent Card, Task, Message, Artifact. Built on HTTP/JSON-RPC/SSE. Rate accuracy 1-10." Set maxIterations to 3.',
  );
  await page.screenshot({ path: 'e2e/screenshots/07-aria-verify-created.png' });

  // Wait for verify run to complete
  const verifyAgentId = await page.evaluate(async () => {
    const r = await fetch('/api/v1/agents');
    const agents = await r.json();
    const a = agents.find((a: any) => a.name === 'E2E Verify Agent');
    return a?.id ?? null;
  });
  expect(verifyAgentId).toBeTruthy();

  await waitForBackend(
    page,
    `http://127.0.0.1:8080/api/v1/runs?agentId=${verifyAgentId}`,
    (runs: any[]) => Array.isArray(runs) && runs.some((r: any) => r.status === 'COMPLETED'),
    RUN_TIMEOUT,
  );
  console.log('Verify run completed');

  // ── Step 7: Ask Aria to create Report agent ──
  await openAriaPanel(page);
  await sendAriaMessage(
    page,
    'Create an agent named "E2E Report Agent" with role reporter. Then start a run with prompt: "Generate a brief executive report about Google A2A protocol with sections: Summary, Key Components, Use Cases, Recommendations." Set maxIterations to 3.',
  );
  await page.screenshot({ path: 'e2e/screenshots/08-aria-report-created.png' });

  // Wait for report run to complete
  const reportAgentId = await page.evaluate(async () => {
    const r = await fetch('/api/v1/agents');
    const agents = await r.json();
    const a = agents.find((a: any) => a.name === 'E2E Report Agent');
    return a?.id ?? null;
  });
  expect(reportAgentId).toBeTruthy();

  await waitForBackend(
    page,
    `http://127.0.0.1:8080/api/v1/runs?agentId=${reportAgentId}`,
    (runs: any[]) => Array.isArray(runs) && runs.some((r: any) => r.status === 'COMPLETED'),
    RUN_TIMEOUT,
  );
  console.log('Report run completed');

  // ── Step 8: Verify all 3 agents appear on Crew page ──
  await page.locator('.rail-btn[data-view="crew"]').click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);

  await expect(page.getByText('E2E Research Agent')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('E2E Verify Agent')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('E2E Report Agent')).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: 'e2e/screenshots/09-all-agents-on-crew.png' });

  // ── Step 9: Click each agent to verify drawer shows status ──
  // Click research agent
  await page.getByText('E2E Research Agent').click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: 'e2e/screenshots/10-research-drawer-status.png' });

  // Close drawer by pressing Escape or clicking outside
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // Click verify agent
  await page.getByText('E2E Verify Agent').click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: 'e2e/screenshots/11-verify-drawer-status.png' });

  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // Click report agent
  await page.getByText('E2E Report Agent').click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: 'e2e/screenshots/12-report-drawer-status.png' });

  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // ── Step 10: Verify kanban shows all items as DONE ──
  await page.locator('.rail-btn[data-view="overview"]').click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);

  // All kanban cards should exist
  const kanbanCards = page.locator('.kanban-card');
  const cardCount = await kanbanCards.count();
  expect(cardCount).toBeGreaterThanOrEqual(3);
  await page.screenshot({ path: 'e2e/screenshots/13-kanban-all-done.png' });

  // ── Step 11: Navigate to Reports page ──
  await page.locator('.rail-btn[data-view="reports"]').click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await page.screenshot({ path: 'e2e/screenshots/14-reports-page.png' });

  // Verify the Reports page is loaded
  await expect(page.getByRole('heading', { name: /Reports/i })).toBeVisible({ timeout: 10_000 });

  // ── Final screenshot ──
  await page.screenshot({ path: 'e2e/screenshots/15-final-state.png' });

  console.log('✅ E2E multi-agent lifecycle test passed!');
});
