import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: OpenCode ADK agent lifecycle (exchangeable agent provider).
 *
 * Mirrors langchain-adk-e2e.spec.ts full flow, plus provider-specific
 * scenarios for the exchangeable-agent-provider feature:
 * 1. Configure DeepSeek LLM provider (Configure modal → LLM Providers tab)
 * 2. Create an OpenCode agent via Crew view (ADK Provider dropdown = opencode)
 * 3. Verify persistence via API: adkProvider == 'opencode'; runtime switch
 *    opencode → langchain → opencode via PUT /api/v1/agents/{id}
 * 4. Providers page: provider table (OpenCode + LangChain ADK, langchain default)
 *    and Per-Agent Backends block shows the agent with 'opencode'
 * 5. Start a run from Runs view, approve the task-level approval gate (default-on
 *    for opencode since 632d3de — POST /api/v1/approvals/{id}/decide {approved:true}),
 *    then poll until the run leaves PENDING/RUNNING and reaches a terminal state
 *    (COMPLETED / FAILED / ABORTED / CANCELLED).
 *    No OpenSandbox in CI → FAILED with a sandbox-layer errorMessage is the
 *    expected path and still proves the delegation path was triggered; COMPLETED
 *    only occurs with a full local stack (sandbox + LLM). A run that never leaves
 *    PENDING/RUNNING times out and fails the test — it is never tolerated.
 *
 * LLM API key is injected via env var DEEPSEEK_API_KEY (DeepSeek-compatible).
 * Idempotency: agent name carries a unique suffix; the agent is retired at the
 * end so reruns do not accumulate dirty state.
 */

const DEEPSEEK_API_KEY = process.env.DEEPSEEK_API_KEY;
const DEEPSEEK_BASE_URL = 'https://api.deepseek.com/v1';
const DEEPSEEK_MODEL = 'deepseek-chat';

const RUN_TIMEOUT = 180_000; // 3 min for a run to complete
const POLL_INTERVAL = 5_000;

/** Helper: wait for a backend REST condition via fetch inside the browser page. */
async function waitForBackend(
  page: Page,
  url: string,
  predicate: (json: any) => boolean,
  timeout: number = RUN_TIMEOUT,
  intervalMs: number = POLL_INTERVAL,
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

/** Navigate to a view by clicking the rail button. */
async function navigateTo(page: Page, view: string) {
  await page.locator(`.rail-btn[data-view="${view}"]`).click();
  await page.waitForLoadState('networkidle');
}

// ─────────────────────────────────────────────────────────────────────
test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min total

// ─────────────────────────────────────────────────────────────────────
test('OpenCode ADK: configure LLM → create agent → runtime switch → run → verify', async ({ page }) => {
  // Guard: skip if no API key configured
  test.skip(!DEEPSEEK_API_KEY, 'DEEPSEEK_API_KEY env var is required');

  const agentName = `E2E OpenCode Agent ${Date.now()}`;
  let agentId: string | null = null;

  // ── Step 0: Navigate to dashboard ──
  // The rail is height-limited to (100vh - 67px) and the 'Configure' button is
  // the last item; with the default 720px viewport it falls outside the viewport
  // and cannot be clicked. Use a taller viewport so every rail button is visible.
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/oc-01-dashboard.png' });

  // ── Step 1: Configure DeepSeek LLM provider ──
  // The rail has no data-view="settings" anymore: LLM providers live in the
  // Configure modal (rail 'Configure' button) under the 'LLM Providers' tab.
  await page.locator('.rail-btn', { hasText: 'Configure' }).click();
  await expect(page.locator('.modal.open')).toBeVisible({ timeout: 10_000 });
  await page.getByRole('tab', { name: /LLM Providers/ }).click();
  await expect(page.locator('.modal h2', { hasText: 'Settings' })).toBeVisible({ timeout: 10_000 });

  // Check if DeepSeek provider already exists (rerun / persistent H2 db)
  let deepSeekExists = await page.getByText('DeepSeek').isVisible().catch(() => false);

  if (!deepSeekExists) {
    // Click "Add Provider"
    await page.getByRole('button', { name: '+ Add Provider' }).click();
    await expect(page.locator('.form-card')).toBeVisible({ timeout: 5_000 });

    // Fill in DeepSeek provider details (same field layout as langchain spec)
    await page.locator('.form-card input').first().fill('DeepSeek');
    // Type: select OPENAI (DeepSeek is OpenAI-compatible)
    const typeSelect = page.locator('.form-card select').first();
    await typeSelect.selectOption('OPENAI');

    await page.locator('input[placeholder="https://api.openai.com/v1"]').fill(DEEPSEEK_BASE_URL);
    await page.locator('input[type="password"]').fill(DEEPSEEK_API_KEY);
    await page.locator('input[placeholder="gpt-4"]').fill(DEEPSEEK_MODEL);
    await page.locator('.form-card input[type="number"]').fill('4096');

    // Submit
    await page.getByRole('button', { name: 'Create' }).click();
    await page.waitForTimeout(2000);
  }

  // Activate DeepSeek provider if not active
  const activateBtn = page.getByRole('button', { name: 'Activate' }).first();
  if (await activateBtn.isVisible().catch(() => false)) {
    await activateBtn.click();
    await page.waitForTimeout(2000);
  }

  await page.screenshot({ path: 'e2e/screenshots/oc-02-llm-configured.png' });

  // Close the Configure modal before navigating (it overlays the rail)
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page.locator('.modal.open')).toHaveCount(0, { timeout: 5_000 });

  // ── Step 2: Create an OpenCode agent via Crew view ──
  await navigateTo(page, 'crew');

  // Crew view uses the .mini-dialog add-agent form ('+ Add Agent' CTA)
  await page.getByRole('button', { name: '+ Add Agent' }).click();
  await expect(page.locator('.mini-dialog.open')).toBeVisible({ timeout: 5_000 });

  // Name
  await page.locator('#add-agent-name').fill(agentName);
  // Role select defaults to 'dev' (template → agentType ADK); model override
  await page.locator('#add-agent-model').fill(DEEPSEEK_MODEL);

  // ADK Provider dropdown: rendered dynamically from GET /api/v1/adk/providers.
  // Option text contains 'OpenCode' (and 'LangChain'), value is the provider id.
  const adkProviderSelect = page.locator('#add-agent-adk-provider');
  await expect(adkProviderSelect).toBeVisible({ timeout: 10_000 });
  const adkOptionTexts = await adkProviderSelect.locator('option').allTextContents();
  expect(
    adkOptionTexts.some((t) => t.includes('OpenCode')),
    `ADK Provider dropdown should contain an OpenCode option (got: ${adkOptionTexts.join(', ')})`,
  ).toBeTruthy();
  expect(adkOptionTexts.some((t) => t.includes('LangChain'))).toBeTruthy();
  await adkProviderSelect.selectOption('opencode');

  await page.screenshot({ path: 'e2e/screenshots/oc-03-create-opencode-agent.png' });

  // Submit
  await page.getByRole('button', { name: 'Hire Agent' }).click();

  // Verify the agent appears in the crew list
  await expect(page.locator('.crew-card', { hasText: agentName })).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/oc-04-opencode-agent-created.png' });

  // ── Step 3: API verification of persistence + runtime provider switch ──
  const agentInfo = await page.evaluate(async (name) => {
    const r = await fetch('/api/v1/agents');
    if (!r.ok) return null;
    const agents = await r.json();
    return agents.find((a: any) => a.name === name) ?? null;
  }, agentName);
  expect(agentInfo, `agent '${agentName}' should be persisted via API`).toBeTruthy();
  agentId = agentInfo.id;
  expect(agentInfo.adkProvider, 'created agent should have adkProvider=opencode').toBe('opencode');

  const getAdkProvider = (id: string) =>
    page.evaluate(async (aid) => {
      const r = await fetch(`/api/v1/agents/${aid}`);
      if (!r.ok) return null;
      const a = await r.json();
      return a.adkProvider;
    }, id);

  const setAdkProvider = (id: string, provider: string) =>
    page.evaluate(
      async ({ aid, adk }) => {
        await fetch(`/api/v1/agents/${aid}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ adkProvider: adk }),
        });
      },
      { aid: id, adk: provider },
    );

  // opencode → langchain (runtime switch, persistence)
  await setAdkProvider(agentId, 'langchain');
  expect(await getAdkProvider(agentId), 'after PUT adkProvider should be langchain').toBe('langchain');
  // langchain → opencode (switch back)
  await setAdkProvider(agentId, 'opencode');
  expect(await getAdkProvider(agentId), 'after PUT back adkProvider should be opencode').toBe('opencode');

  // ── Step 4: Providers page ──
  await navigateTo(page, 'providers');

  const providerTable = page.locator('.data-table').first();
  await expect(providerTable).toBeVisible({ timeout: 15_000 });
  await expect(providerTable).toContainText('OpenCode');
  await expect(providerTable).toContainText('LangChain ADK');
  // isDefault marker: langchain is the configured default; opencode is not
  await expect(providerTable.locator('tr', { hasText: 'langchain' }).first()).toContainText('Default');
  await expect(providerTable.locator('tr', { hasText: 'opencode' }).first()).not.toContainText('Default');

  // Per-Agent Backends block shows the E2E OpenCode Agent with backend 'opencode'
  const perAgentCard = page.locator('.card', { hasText: 'Per-Agent Backends' });
  await expect(perAgentCard).toBeVisible();
  await expect(perAgentCard.locator('tr', { hasText: agentName })).toContainText('opencode');
  await page.screenshot({ path: 'e2e/screenshots/oc-05-providers-page.png' });

  // ── Step 5: Start a run for the OpenCode agent ──
  await navigateTo(page, 'runs');
  await page.getByRole('button', { name: '+ Start Run' }).click();
  await expect(page.locator('.form-card')).toBeVisible({ timeout: 5_000 });

  // Select the E2E OpenCode Agent (agent is HEALTHY at creation → listed)
  const agentSelect = page.locator('.form-card select').first();
  const options = await agentSelect.locator('option').allTextContents();
  const targetOption = options.find((o) => o.includes(agentName));
  expect(targetOption, `run form should list '${agentName}' (got: ${options.join(', ')})`).toBeTruthy();
  await agentSelect.selectOption({ label: targetOption! });

  // Fill prompt
  await page.locator('.form-card textarea').fill('What is 2+2? Reply with just the number.');

  // Set max iterations to 1 for quick test
  const maxIterInput = page.locator('.form-card input[type="number"]');
  await maxIterInput.fill('1');

  await page.screenshot({ path: 'e2e/screenshots/oc-06-create-run.png' });

  // Submit the run
  await page.locator('.form-card button[type="submit"]').click();
  await page.waitForTimeout(3000);

  await page.screenshot({ path: 'e2e/screenshots/oc-07-run-started.png' });

  const TERMINAL_RUN_STATUSES = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];

  // ── Step 6: Approve the task-level approval gate ──
  // Since 632d3de the task-level path (opencode provider) REQUIRES human approval
  // by default: AgentLoopEngine calls ApprovalGate.requestApproval, creating a
  // PENDING approval, and blocks until a human decides (approvals.timeout-ms,
  // default 30 min). The run stays non-terminal until approved. The backend
  // exposes only PENDING approvals via GET /api/v1/approvals; a decision is
  // granted with POST /api/v1/approvals/{id}/decide { approved: true }.
  // If the run already reached a terminal state (e.g. it failed before the gate),
  // skip approval and let the terminal poll below assert that state. A run that
  // never produces an approval times out here and fails the test — never tolerated:
  // that would indicate the gate/approvals API is broken.
  const alreadyTerminal = await page.evaluate(
    async ({ agentId }) => {
      const r = await fetch(`http://localhost:8080/api/v1/runs?agentId=${agentId}`);
      if (!r.ok) return false;
      const runs = await r.json();
      return Array.isArray(runs) && runs.some((x: any) => TERMINAL_RUN_STATUSES.includes(x.status));
    },
    { agentId },
  );

  if (!alreadyTerminal) {
    // Resolve this agent's run ids, then wait for a PENDING approval tied to one
    // of those runs.
    const agentRuns = await waitForBackend(
      page,
      `http://localhost:8080/api/v1/runs?agentId=${agentId}`,
      (runs: any[]) => Array.isArray(runs) && runs.length > 0,
      RUN_TIMEOUT,
    );
    const runIds = new Set(agentRuns.map((r: any) => r.id));
    const approvals = await waitForBackend(
      page,
      'http://localhost:8080/api/v1/approvals',
      (list: any[]) =>
        Array.isArray(list) && list.some((a: any) => a.status === 'PENDING' && runIds.has(a.runId)),
      RUN_TIMEOUT,
    );
    const approval = approvals.find((a: any) => a.status === 'PENDING' && runIds.has(a.runId));
    console.log(`Task-level approval ${approval.id} requested for run ${approval.runId}; approving…`);
    const decided = await page.evaluate(
      async ({ id }) => {
        const r = await fetch(`/api/v1/approvals/${id}/decide`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ approved: true, reason: 'E2E auto-approval (task-level gate)' }),
        });
        if (!r.ok) throw new Error(`approval decide failed: HTTP ${r.status}`);
        return r.json();
      },
      { id: approval.id },
    );
    console.log(`Approval ${approval.id} decided: ${JSON.stringify(decided)}`);
  }

  // ── Step 7: Poll until the run leaves PENDING/RUNNING ──
  // The spec runs without an OpenSandbox in CI, so the delegated OpenCode path
  // fails inside the sandbox layer with TaskExecutionException(SANDBOX_UNAVAILABLE)
  // → run FAILED with a sandbox-layer errorMessage. COMPLETED only occurs with a
  // full local stack (sandbox + LLM). Either terminal state proves the delegation
  // path was actually triggered; a run that never leaves PENDING/RUNNING (or never
  // appears in the list) makes waitForBackend throw — the timeout is NOT tolerated.
  const runResult = await waitForBackend(
    page,
    `http://localhost:8080/api/v1/runs?agentId=${agentId}`,
    (runs: any[]) =>
      Array.isArray(runs) &&
      runs.some((r: any) => TERMINAL_RUN_STATUSES.includes(r.status)),
    RUN_TIMEOUT,
  );

  const run = runResult.find((r: any) => TERMINAL_RUN_STATUSES.includes(r.status));
  expect(run, 'run object must exist in the runs list (delegation path triggered)').toBeTruthy();
  console.log(`OpenCode run ${run.id} finished with status: ${run.status}`);

  if (run.status === 'FAILED') {
    // CI (no OpenSandbox): the sandbox layer rejects the delegation before any LLM
    // call. errorMessage is the exception message — e.g. "OpenSandbox sandbox
    // creation failed for agent …" — the enum name SANDBOX_UNAVAILABLE itself is
    // NOT part of the message, so match the sandbox-layer wording instead.
    expect(run.errorMessage, 'FAILED run must carry a sandbox-layer errorMessage').toBeTruthy();
    expect(
      run.errorMessage,
      'FAILED run errorMessage must originate from the sandbox layer',
    ).toMatch(/sandbox|serve|OpenSandbox/i);
  } else if (run.status === 'COMPLETED') {
    expect(run.finalOutput, 'COMPLETED run must have non-empty finalOutput').toBeTruthy();
  }
  await page.screenshot({ path: 'e2e/screenshots/oc-08-run-completed.png' });

  // ── Cleanup: retire the test agent (idempotency for reruns) ──
  if (agentId) {
    await page
      .evaluate(async (id) => {
        await fetch(`/api/v1/agents/${id}/retire`, { method: 'POST' });
      }, agentId)
      .catch(() => {});
  }

  // ── Final screenshot ──
  await page.screenshot({ path: 'e2e/screenshots/oc-12-final-state.png' });

  console.log('✅ E2E OpenCode ADK test passed!');
});
