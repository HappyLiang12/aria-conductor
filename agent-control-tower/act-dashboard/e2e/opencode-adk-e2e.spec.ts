import { test, expect, type Page } from '@playwright/test';
import {
  BACKEND,
  apiCall,
  pollUntil,
  setScenario,
  settleRunApproval,
  uniqueName,
} from './fixtures';

/**
 * E2E test: OpenCode core agent lifecycle (exchangeable agent core) — revised in
 * Task 17 for the governed cores and the deterministic harness.
 *
 * Mirrors langchain-adk-e2e.spec.ts full flow, plus core-specific scenarios for
 * the exchangeable-agent-core feature. Deterministic in PR CI:
 * 1. The harness supplies the deterministic LLM (the live lane configures a real
 *    provider instead: e2e/agent-core/live-matrix.* for T20). The former
 *    Configure-modal/DeepSeek setup and its DEEPSEEK_API_KEY gate are removed.
 * 2. Create an OpenCode agent via Crew view: the core select offers exactly the
 *    production catalog (opencode + qoder, opencode the single default);
 *    persistence is verified via API: adkProvider == 'opencode'; the runtime
 *    switch is opencode → qoder → opencode via PUT /api/v1/agents/{id} (no
 *    LangChain option, row or switch target exists anymore).
 * 3. Providers page: the core table lists opencode + qoder with exactly one
 *    Default row (opencode) and no langchain row; Per-Agent Backends shows the
 *    agent with its core.
 * 4. Start a run from the Runs view against the deterministic harness scenario,
 *    approve the run's governed permission asks (operator authority, Task 12) and
 *    poll until the run reaches exactly COMPLETED with the exact fixture output.
 *    The former FAILED/ABORTED/CANCELLED alternatives and the sandbox-error
 *    branch are gone: without a container runtime the harness runs the
 *    SANDBOX placement through its process transport.
 *
 * Idempotency: agent name carries a unique suffix; the agent is retired at the
 * end so reruns do not accumulate dirty state.
 */

const RUN_TIMEOUT = 180_000; // 3 min for a run to complete
const POLL_INTERVAL = 5_000;
/**
 * The recorded scenario whose completion is deterministic AND governed: the core
 * raises two permission gates (edit, then execute), so the run only completes
 * once the operator decisions are delivered. The former read-only scenario is
 * not used here: a core that needs no permission legitimately opens no ask, and
 * this spec's contract includes the operator-approved gate.
 */
const SCENARIO = 'write-twice';
/** The exact completion the peer answers with once both writes were applied. */
const FINAL_OUTPUT = 'fixture writes: probe-allow-once.txt applied; probe-write-twice-2.txt applied';

/** Navigate to a view by clicking the rail button. */
async function navigateTo(page: Page, view: string) {
  await page.locator(`.rail-btn[data-view="${view}"]`).click();
  await page.waitForLoadState('networkidle');
}

// ─────────────────────────────────────────────────────────────────────
test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min total

// ─────────────────────────────────────────────────────────────────────
test('OpenCode core: create agent → runtime switch → run → verify', async ({ page, request }) => {
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

  // ── Step 1: Create an OpenCode agent via Crew view ──
  await navigateTo(page, 'crew');

  // Crew view uses the .mini-dialog add-agent form ('+ Add Agent' CTA)
  await page.getByRole('button', { name: '+ Add Agent' }).click();
  await expect(page.locator('.mini-dialog.open')).toBeVisible({ timeout: 5_000 });

  // Name
  await page.locator('#add-agent-name').fill(agentName);
  // Role select defaults to 'dev' (template → agentType ADK); model override
  await page.locator('#add-agent-model').fill('efficient');

  // Agent core select: rendered from GET /api/v1/adk/providers. The options are
  // exactly the production catalog; the LangChain option no longer exists.
  const coreSelect = page.locator('#add-agent-core');
  await expect(coreSelect).toBeVisible({ timeout: 10_000 });
  const coreValues = await coreSelect.locator('option').evaluateAll(
    (options) => options.map((o) => (o as HTMLOptionElement).value).filter((v) => v !== ''),
  );
  expect(coreValues.sort()).toEqual(['opencode', 'qoder']);
  // The dialog applies the inventory's declared default: exactly one core is
  // marked default and it is opencode.
  await expect(coreSelect).toHaveValue('opencode');
  const selectedLabel = await coreSelect.locator('option:checked').textContent();
  expect(selectedLabel).not.toContain('unsupported');

  await page.screenshot({ path: 'e2e/screenshots/oc-03-create-opencode-agent.png' });

  // Submit
  await page.getByRole('button', { name: 'Hire Agent' }).click();

  // Verify the agent appears in the crew list
  await expect(page.locator('.crew-card', { hasText: agentName })).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: 'e2e/screenshots/oc-04-opencode-agent-created.png' });

  // ── Step 2: API verification of persistence + core switch ──
  const agents = await apiCall(request, 'GET', '/agents');
  expect(agents.status).toBe(200);
  const agentInfo = (agents.data as any[]).find((a) => a.name === agentName);
  expect(agentInfo, `agent '${agentName}' should be persisted via API`).toBeTruthy();
  agentId = agentInfo!.id;
  expect(agentInfo!.adkProvider, 'created agent should have adkProvider=opencode').toBe('opencode');

  // The run path is deterministic: select the recorded scenario for this agent
  // (harness-only control; the peer is launched with it).
  await setScenario(request, agentId!, SCENARIO);

  const setCore = async (core: string) => {
    const put = await apiCall(request, 'PUT', `/agents/${agentId}`, { adkProvider: core });
    expect(put.status, JSON.stringify(put.data)).toBe(200);
    const read = await apiCall(request, 'GET', `/agents/${agentId}`);
    expect(read.status).toBe(200);
    return read.data.adkProvider;
  };

  // opencode → qoder (core switch, persistence)
  expect(await setCore('qoder'), 'after PUT adkProvider should be qoder').toBe('qoder');
  // qoder → opencode (switch back)
  expect(await setCore('opencode'), 'after PUT back adkProvider should be opencode').toBe('opencode');

  // ── Step 3: Providers page ──
  await navigateTo(page, 'providers');

  const providerTable = page.locator('.data-table').first();
  await expect(providerTable).toBeVisible({ timeout: 15_000 });
  await expect(providerTable).toContainText('OpenCode');
  await expect(providerTable).toContainText('Qoder');
  // No LangChain row may render: the provider is removed from the catalog.
  await expect(providerTable.locator('tr', { hasText: 'langchain' })).toHaveCount(0);
  // isDefault marker: opencode is the single configured default; qoder is not.
  await expect(providerTable.locator('tr', { hasText: 'opencode' }).first()).toContainText('Default');
  await expect(providerTable.locator('tr', { hasText: 'qoder' }).first()).not.toContainText('Default');

  // Per-Agent Backends block shows the E2E OpenCode Agent with core 'opencode'
  const perAgentCard = page.locator('.card', { hasText: 'Per-Agent Backends' });
  await expect(perAgentCard).toBeVisible();
  await expect(perAgentCard.locator('tr', { hasText: agentName })).toContainText('opencode');
  await page.screenshot({ path: 'e2e/screenshots/oc-05-providers-page.png' });

  // ── Step 4: Start a run for the OpenCode agent ──
  await navigateTo(page, 'runs');
  await page.getByRole('button', { name: '+ Start Run' }).click();
  // Scope to the visible route content (<main className="content">): the Configure
  // modal stays mounted (opacity:0) after closing and may still contain a stray
  // SettingsPage form, which would make the bare .form-card locator ambiguous.
  await expect(page.locator('main .form-card')).toBeVisible({ timeout: 5_000 });

  // Select the E2E OpenCode Agent (agent is HEALTHY at creation → listed)
  const agentSelect = page.locator('main .form-card select').first();
  const options = await agentSelect.locator('option').allTextContents();
  const targetOption = options.find((o) => o.includes(agentName));
  expect(targetOption, `run form should list '${agentName}' (got: ${options.join(', ')})`).toBeTruthy();
  await agentSelect.selectOption({ label: targetOption! });

  // Fill prompt
  await page.locator('main .form-card textarea').fill('What is 2+2? Reply with just the number.');

  // Set max iterations to 1 for quick test
  const maxIterInput = page.locator('main .form-card input[type="number"]');
  await maxIterInput.fill('1');

  await page.screenshot({ path: 'e2e/screenshots/oc-06-create-run.png' });

  // Submit the run
  await page.locator('main .form-card button[type="submit"]').click();
  await page.waitForTimeout(3000);

  await page.screenshot({ path: 'e2e/screenshots/oc-07-run-started.png' });

  // ── Step 5: Approve the run's governed asks (operator authority) ──
  // The run is created by the UI; resolve its captured id from the API, then
  // approve its pending asks through the operator-only /decide route (Task 12).
  // The core raises exactly two permission gates for this scenario (edit, then
  // execute); each ask is a separate one-use grant, and the run reaches its
  // terminal state only after both decisions are delivered.
  const runs = await pollUntil<any[]>(
    request,
    `/runs?agentId=${agentId}`,
    (list) => Array.isArray(list) && list.length > 0,
    RUN_TIMEOUT,
    2_000,
  );
  const runId = (runs[0] as any).id as string;
  expect(runId).toMatch(/^[0-9a-f-]{36}$/);
  const settled = await settleRunApproval(request, runId, RUN_TIMEOUT);
  expect(settled.approvedAskIds).toHaveLength(2);

  // ── Step 6: The run must reach exactly COMPLETED with the exact fixture output ──
  const run = await pollUntil<any>(
    request,
    `/runs/${runId}`,
    (r) => ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'].includes(r?.status),
    RUN_TIMEOUT,
    POLL_INTERVAL,
  );
  expect(run.status, JSON.stringify(run).slice(0, 400)).toBe('COMPLETED');
  expect(run.finalOutput).toBe(FINAL_OUTPUT);
  await page.screenshot({ path: 'e2e/screenshots/oc-08-run-completed.png' });

  // ── Cleanup: retire the test agent (idempotency for reruns) ──
  // POST /agents/{id}/retire is a 200-only route: AgentController.java:59-61
  // returns ResponseEntity.ok(AgentResponse), and AgentService.retireAgent is
  // idempotent for an existing agent (it re-sets RETIRED and returns the
  // retired entity), so a repeated retire is exactly 200 with healthStatus
  // RETIRED, never 204.
  if (agentId) {
    const retired = await apiCall(request, 'POST', `/agents/${agentId}/retire`);
    expect(retired.status, JSON.stringify(retired.data)).toBe(200);
    expect(retired.data?.healthStatus).toBe('RETIRED');
  }

  // ── Final screenshot ──
  await page.screenshot({ path: 'e2e/screenshots/oc-12-final-state.png' });

  console.log('E2E OpenCode core test passed!');
});
