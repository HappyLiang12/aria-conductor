import { test, expect, type Page } from '@playwright/test';

/**
 * Lightweight E2E: ADK providers surface (no LLM key required).
 *
 * Does NOT depend on DEEPSEEK_API_KEY or LLM configuration:
 * 1. Providers page renders the provider table (langchain = default +
 *    opencode rows) and the Per-Agent Backends block.
 * 2. Crew create form renders the dynamic ADK Provider dropdown with both
 *    'langchain' and 'opencode' options (no submission).
 *
 * Backend requirement: GET /api/v1/adk/providers returns opencode + langchain.
 */

/** Navigate to a view by clicking the rail button. */
async function navigateTo(page: Page, view: string) {
  await page.locator(`.rail-btn[data-view="${view}"]`).click();
  await page.waitForLoadState('networkidle');
}

test.describe.configure({ mode: 'serial', timeout: 120_000 });

// ─────────────────────────────────────────────────────────────────────
test('ADK providers: Providers page renders provider table + Per-Agent Backends', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });

  await navigateTo(page, 'providers');

  // Provider inventory table — assert structure + the Default-marker semantics,
  // not exact table contents: a shared/dirty DB can surface extra rows and the
  // health column varies with what is running locally.
  const providerTable = page.locator('.data-table').first();
  await expect(providerTable).toBeVisible({ timeout: 15_000 });

  // Known backends render with their display names when registered.
  const opencodeRow = providerTable.locator('tbody tr').filter({ hasText: 'OpenCode' }).first();
  await expect(opencodeRow).toBeVisible({ timeout: 15_000 });
  await expect(opencodeRow).toContainText('opencode');

  const langchainRow = providerTable.locator('tbody tr').filter({ hasText: 'LangChain ADK' }).first();
  await expect(langchainRow).toBeVisible();
  await expect(langchainRow).toContainText('langchain');

  // Exactly ONE row carries the Default badge and it is one of the two known
  // backends. WHICH one is a stack-config decision (CI pins langchain for ADK
  // pre-warm; opencode-first local stacks pin opencode), so assert the marker
  // semantics rather than a specific provider — extra/absent non-default rows
  // are tolerated.
  const defaultRow = providerTable.locator('tbody tr').filter({
    has: page.locator('span.type-badge', { hasText: /^Default$/ }),
  });
  await expect(defaultRow).toHaveCount(1);
  await expect(defaultRow.first()).toContainText(/OpenCode|LangChain ADK/);

  // Per-Agent Backends block exists (agent list may be empty on fresh db)
  await expect(page.locator('.card', { hasText: 'Per-Agent Backends' })).toBeVisible();
  await page.screenshot({ path: 'e2e/screenshots/ap-01-providers-page.png' });
});

// ─────────────────────────────────────────────────────────────────────
test('ADK providers: Crew create form renders langchain + opencode options', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });

  await navigateTo(page, 'crew');

  // Open the add-agent mini dialog
  await page.getByRole('button', { name: '+ Add Agent' }).click();
  await expect(page.locator('.mini-dialog.open')).toBeVisible({ timeout: 5_000 });

  // ADK Provider dropdown is rendered dynamically from the providers API
  const adkProviderSelect = page.locator('#add-agent-adk-provider');
  await expect(adkProviderSelect).toBeVisible({ timeout: 10_000 });

  const optionTexts = await adkProviderSelect.locator('option').allTextContents();
  const optionValues = await adkProviderSelect
    .locator('option')
    .evaluateAll((opts) => opts.map((o) => (o as HTMLOptionElement).value));

  expect(optionTexts.some((t) => t.includes('LangChain')), 'dropdown should include LangChain option').toBeTruthy();
  expect(optionTexts.some((t) => t.includes('OpenCode')), 'dropdown should include OpenCode option').toBeTruthy();
  expect(optionValues).toContain('langchain');
  expect(optionValues).toContain('opencode');

  await page.screenshot({ path: 'e2e/screenshots/ap-02-crew-adk-dropdown.png' });

  // Do not submit — close the dialog via Cancel (exact match: on a dirty DB the
  // crew grid holds cards like "Open details for e2e-wf-cancel-agent-…" whose
  // accessible name substring-matches a non-exact 'Cancel' lookup).
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.locator('.mini-dialog.open')).toHaveCount(0, { timeout: 5_000 });
});
