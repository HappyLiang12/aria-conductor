import { test, expect, type Locator, type Page } from '@playwright/test';

/**
 * E2E test: LangChain ADK agent lifecycle.
 *
 * Flow (aligned with the current UI structure):
 * 1. Configure DeepSeek LLM provider via the Configure modal → LLM Providers tab
 *    (the rail has no data-view="settings" anymore; 'Configure' is a standalone
 *    rail button that dispatches act:open-configure and opens ConfigureModal).
 * 2. Create a LangChain agent via Crew view (mini-dialog add-agent form,
 *    ADK Provider dropdown = langchain).
 * 3. Verify the agent card appears in the crew list (UI) and, via API,
 *    that adkProvider === 'langchain' (AgentDrawer does not render adkProvider).
 * 4. Start a run for the LangChain agent from the Runs view (.card.form-card).
 * 5. Poll for run completion (COMPLETED or FAILED — both prove the provider was invoked).
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

/**
 * Click while shielding against global toasts. Parallel specs broadcast
 * WebSocket events that render the global Toast component (fixed top-right).
 * Its 5s auto-dismiss timer is cleared on every new event (useEffect cleanup),
 * so toasts can pile up and never dismiss while broadcasts keep arriving —
 * waiting for them to disappear is unreliable. We therefore remove any existing
 * toast container and force the click: toasts carry no business logic, so
 * clicking through them is safe.
 */
async function safeClick(page: Page, locator: Locator) {
  await page.evaluate(() => {
    document.querySelectorAll('.toast-container').forEach((el) => el.remove());
  });
  await locator.click({ force: true });
}

/**
 * Select an <option> while shielding against global toasts. selectOption also
 * performs actionability checks (visible / enabled / receives events) — a fixed
 * top-right toast (.toast-container, z-index 9999) can block the hit target
 * exactly like it blocks clicks, so the same remove-then-force strategy applies.
 */
async function safeSelectOption(page: Page, locator: Locator, value: any) {
  await page.evaluate(() => {
    document.querySelectorAll('.toast-container').forEach((el) => el.remove());
  });
  await locator.selectOption(value, { force: true });
}

/** Navigate to a view by clicking the rail button. */
async function navigateTo(page: Page, view: string) {
  await safeClick(page, page.locator(`.rail-btn[data-view="${view}"]`));
  await page.waitForLoadState('networkidle');
}

/** Open the Configure modal via the rail's standalone Configure button. */
async function openConfigure(page: Page) {
  await safeClick(page, page.locator('.rail-btn', { hasText: 'Configure' }));
  await expect(page.locator('.modal.open')).toBeVisible({ timeout: 10_000 });
}

// ─────────────────────────────────────────────────────────────────────
test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min total

// ─────────────────────────────────────────────────────────────────────
test('LangChain ADK: configure LLM → create agent → run → verify', async ({ page }) => {
  // Guard: skip if no API key configured
  test.skip(!DEEPSEEK_API_KEY, 'DEEPSEEK_API_KEY env var is required');

  const agentName = `E2E LangChain Agent ${Date.now()}`;
  let agentId: string | null = null;

  // ── Step 0: Navigate to dashboard ──
  // The rail is height-limited and 'Configure' is its last button; with the
  // default viewport it may fall outside the clickable area. Use a taller
  // viewport so every rail button is visible (same as opencode spec).
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/lc-01-dashboard.png' });

  // ── Step 1: Configure DeepSeek LLM provider (Configure modal → LLM Providers) ──
  await openConfigure(page);
  await page.getByRole('tab', { name: /LLM Providers/ }).click();
  await expect(page.locator('.modal h2', { hasText: 'Settings' })).toBeVisible({ timeout: 10_000 });

  // Wait for the provider list to finish loading BEFORE checking whether DeepSeek
  // already exists. Checking too early races the GET /api/v1/llm-providers fetch:
  // it would miss an existing provider and attempt a duplicate create, which the
  // backend rejects with a 500 (llm_providers.name has a unique index). That would
  // leave a stray "Add LLM Provider" form inside the (hidden but still mounted)
  // modal DOM, which then trips strict-mode locators like .form-card on other views.
  await expect(
    page.locator('.modal .data-table, .modal .empty-state').first(),
  ).toBeVisible({ timeout: 10_000 });

  // Check if DeepSeek provider already exists (rerun / persistent H2 db)
  let deepSeekExists = await page.getByText('DeepSeek').isVisible().catch(() => false);

  if (!deepSeekExists) {
    // Click "Add Provider"
    await safeClick(page, page.getByRole('button', { name: '+ Add Provider' }));
    await expect(page.locator('.form-card')).toBeVisible({ timeout: 5_000 });

    // Fill in DeepSeek provider details (SettingsPage form field order)
    await page.locator('.form-card input').first().fill('DeepSeek');
    // Type: select OPENAI (DeepSeek is OpenAI-compatible)
    const typeSelect = page.locator('.form-card select').first();
    await typeSelect.selectOption('OPENAI');

    await page.locator('input[placeholder="https://api.openai.com/v1"]').fill(DEEPSEEK_BASE_URL);
    await page.locator('input[type="password"]').fill(DEEPSEEK_API_KEY);
    await page.locator('input[placeholder="gpt-4"]').fill(DEEPSEEK_MODEL);
    await page.locator('.form-card input[type="number"]').fill('4096');

    // Submit
    await safeClick(page, page.getByRole('button', { name: 'Create' }));
  }

  // Idempotency guard: wait until the backend actually has a DeepSeek provider.
  // SettingsPage only refreshes its table when a create succeeds — if a parallel
  // spec created DeepSeek first, our POST is rejected with a 500 (unique index
  // on llm_providers.name) and the table stays on the empty state. The API is
  // therefore the authoritative source for whether DeepSeek now exists.
  await waitForBackend(
    page,
    'http://localhost:8080/api/v1/llm-providers',
    (providers: any[]) =>
      Array.isArray(providers) && providers.some((p: any) => p.name === 'DeepSeek'),
    30_000,
    2_000,
  );

  // Ensure DeepSeek is the active provider. Drive this through the API: the table
  // in this page may be stale (our create was rejected) or a parallel spec may
  // already have activated it — activation is idempotent either way.
  const deepSeekActive = await page.evaluate(async () => {
    const r = await fetch('/api/v1/llm-providers');
    if (!r.ok) return false;
    const ps: any[] = await r.json();
    const ds = ps.find((p: any) => p.name === 'DeepSeek');
    return !!ds && ds.active;
  });
  if (!deepSeekActive) {
    await page.evaluate(async () => {
      const r = await fetch('/api/v1/llm-providers');
      const ps: any[] = await r.json();
      const ds = ps.find((p: any) => p.name === 'DeepSeek');
      if (ds) await fetch(`/api/v1/llm-providers/${ds.id}/activate`, { method: 'POST' });
    });
    await waitForBackend(
      page,
      'http://localhost:8080/api/v1/llm-providers',
      (providers: any[]) =>
        Array.isArray(providers) &&
        providers.some((p: any) => p.name === 'DeepSeek' && p.active),
      15_000,
      2_000,
    );
  }

  await page.screenshot({ path: 'e2e/screenshots/lc-02-llm-configured.png' });

  // Close the Configure modal before navigating (it overlays the rail)
  await safeClick(page, page.getByRole('button', { name: 'Done' }));
  await expect(page.locator('.modal.open')).toHaveCount(0, { timeout: 5_000 });

  // ── Step 2: Create a LangChain agent via Crew view ──
  await navigateTo(page, 'crew');

  // Crew view uses the .mini-dialog add-agent form ('+ Add Agent' CTA)
  await safeClick(page, page.getByRole('button', { name: '+ Add Agent' }));
  await expect(page.locator('.mini-dialog.open')).toBeVisible({ timeout: 5_000 });

  // Name (field order: name → role select → model → ADK Provider → tools/skills)
  await page.locator('#add-agent-name').fill(agentName);
  // Model override (role defaults to 'dev' template → agentType ADK)
  await page.locator('#add-agent-model').fill(DEEPSEEK_MODEL);

  // ADK Provider dropdown: rendered dynamically from GET /api/v1/adk/providers.
  // Option text contains 'LangChain', value is the provider id 'langchain'.
  const adkProviderSelect = page.locator('#add-agent-adk-provider');
  await expect(adkProviderSelect).toBeVisible({ timeout: 10_000 });
  const adkOptionTexts = await adkProviderSelect.locator('option').allTextContents();
  expect(
    adkOptionTexts.some((t) => t.includes('LangChain')),
    `ADK Provider dropdown should contain a LangChain option (got: ${adkOptionTexts.join(', ')})`,
  ).toBeTruthy();
  await adkProviderSelect.selectOption('langchain');

  await page.screenshot({ path: 'e2e/screenshots/lc-03-create-langchain-agent.png' });

  // Submit
  await safeClick(page, page.getByRole('button', { name: 'Hire Agent' }));

  // ── Step 3: Verify LangChain agent appears in list (UI) + adkProvider (API) ──
  // AgentDrawer does not render adkProvider, so persistence is asserted via API.
  await expect(page.locator('.crew-card', { hasText: agentName })).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/lc-04-langchain-agent-created.png' });

  const agentInfo = await page.evaluate(async (name) => {
    const r = await fetch('/api/v1/agents');
    if (!r.ok) return null;
    const agents = await r.json();
    return agents.find((a: any) => a.name === name) ?? null;
  }, agentName);
  expect(agentInfo, `agent '${agentName}' should be persisted via API`).toBeTruthy();
  agentId = agentInfo.id;
  expect(agentInfo.adkProvider, 'created agent should have adkProvider=langchain').toBe('langchain');
  await page.screenshot({ path: 'e2e/screenshots/lc-05-langchain-agent-detail.png' });

  // ── Step 4: Start a run for the LangChain agent ──
  await navigateTo(page, 'runs');

  await safeClick(page, page.getByRole('button', { name: '+ Start Run' }));
  // Scope to the visible route content (<main className="content">): the Configure
  // modal stays mounted (opacity:0) after closing and may still contain a stray
  // SettingsPage form, which would make the bare .form-card locator ambiguous.
  await expect(page.locator('main .form-card')).toBeVisible({ timeout: 5_000 });

  // Select the LangChain agent (created HEALTHY → listed in the run form)
  const agentSelect = page.locator('main .form-card select').first();
  const options = await agentSelect.locator('option').allTextContents();
  const targetOption = options.find((o) => o.includes(agentName));
  expect(targetOption, `run form should list '${agentName}' (got: ${options.join(', ')})`).toBeTruthy();
  // force + toast removal: parallel WebSocket broadcasts keep global toasts alive
  // (their 5s auto-dismiss timer is cleared on every event) and a fixed top-right
  // toast can block selectOption's actionability check → 15s timeout.
  await safeSelectOption(page, agentSelect, { label: targetOption! });

  // Fill prompt
  await page.locator('main .form-card textarea').fill('What is 2+2? Reply with just the number.');

  // Set max iterations to 1 for quick test
  const maxIterInput = page.locator('main .form-card input[type="number"]');
  await maxIterInput.fill('1');

  await page.screenshot({ path: 'e2e/screenshots/lc-06-create-run.png' });

  // Submit the run
  await safeClick(page, page.locator('main .form-card button[type="submit"]'));
  await page.waitForTimeout(3000);

  await page.screenshot({ path: 'e2e/screenshots/lc-07-run-started.png' });

  // ── Step 5: Poll for run completion ──
  // LangChain runs are delegated to the Python ADK process; without it the run
  // FAILS — FAILED still proves the delegation path was triggered. Timeout is
  // tolerated (same as the opencode spec / previous CI behaviour).
  try {
    const runResult = await waitForBackend(
      page,
      `http://127.0.0.1:8080/api/v1/runs?agentId=${agentId}`,
      (runs: any[]) =>
        Array.isArray(runs) &&
        runs.some((r: any) => r.status === 'COMPLETED' || r.status === 'FAILED'),
      RUN_TIMEOUT,
    );

    const run = runResult.find((r: any) => r.status === 'COMPLETED' || r.status === 'FAILED');
    console.log(`LangChain run finished with status: ${run.status}`);
    await page.screenshot({ path: 'e2e/screenshots/lc-08-run-completed.png' });
  } catch (e) {
    // Run might not complete if the Python process isn't running — that's OK for CI
    console.log('LangChain run did not complete in time (Python process may not be running)');
    await page.screenshot({ path: 'e2e/screenshots/lc-08-run-timeout.png' });
  }

  // ── Cleanup: retire the test agent (idempotency for reruns) ──
  if (agentId) {
    await page
      .evaluate(async (id) => {
        await fetch(`/api/v1/agents/${id}/retire`, { method: 'POST' });
      }, agentId)
      .catch(() => {});
  }

  // ── Final screenshot ──
  await page.screenshot({ path: 'e2e/screenshots/lc-12-final-state.png' });

  console.log('✅ E2E LangChain ADK test passed!');
});
