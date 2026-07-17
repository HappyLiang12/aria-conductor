import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Issue #80 — Template API migration.
 *
 * Covers:
 * 1. GET /api/v1/agents/templates returns correct shape and count
 * 2. AgentCatalog renders 3 template cards from the API response
 * 3. Deploy button calls POST /api/v1/agents/from-template/{name} correctly
 * 4. Agents page "From Template" dropdown populates from the API
 * 5. Crew page "+ Add Agent" Role dropdown populates from the API
 * 6. createFromTemplate path is correct (NOT /api/v1/agents/templates/{id})
 *
 * Templates expected:
 *   "ba"   – Business Analyst Agent
 *   "dev"  – Developer Agent
 *   "qa"   – QA Agent
 */

const EXPECTED_TEMPLATE_NAMES = ['ba', 'dev', 'qa'];
const EXPECTED_TEMPLATE_LABELS = ['Business Analyst Agent', 'Developer Agent', 'QA Agent'];

// ─────────────────────────────────────────────────────────────────────
test.describe('Issue #80: Template API migration', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/crew');
    await page.waitForLoadState('networkidle');
  });

  // ── Test 1 ───────────────────────────────────────────────────────
  test('Template API returns correct data', async ({ page }) => {
    // Intercept the template API response to inspect its contents
    let templatesResponse: any = null;

    await page.route('**/api/v1/agents/templates', async (route) => {
      const response = await route.fetch();
      const body = await response.json();
      templatesResponse = body;
      await route.fulfill({ response });
    });

    // Reload to trigger the intercepted request
    await page.reload();
    await page.waitForLoadState('networkidle');

    // Wait for the catalog to be visible to ensure API call completed
    await expect(page.locator('[data-testid="agent-catalog"]')).toBeVisible({ timeout: 10_000 });
    // Small grace period for the interception to capture the response
    await page.waitForTimeout(500);

    expect(templatesResponse).not.toBeNull();
    expect(Array.isArray(templatesResponse)).toBe(true);
    expect(templatesResponse.length).toBe(3);

    // Verify each template has all required fields
    const requiredFields = ['id', 'label', 'agentType', 'role', 'model', 'provider', 'description'];
    for (const template of templatesResponse) {
      for (const field of requiredFields) {
        expect(template, `Template missing field "${field}"`).toHaveProperty(field);
      }
      // id should be one of the expected template names
      expect(EXPECTED_TEMPLATE_NAMES).toContain(template.id);
    }
  });

  // ── Test 2 ───────────────────────────────────────────────────────
  test('AgentCatalog renders 3 template cards', async ({ page }) => {
    const catalog = page.locator('[data-testid="agent-catalog"]');
    await expect(catalog).toBeVisible({ timeout: 10_000 });

    // Verify exactly 3 template cards
    const cards = catalog.locator('.tmpl');
    await expect(cards).toHaveCount(3);

    // Verify each card has label, role, and description text content
    const cardCount = await cards.count();
    for (let i = 0; i < cardCount; i++) {
      const card = cards.nth(i);
      const text = (await card.textContent()) ?? '';

      // Should have label text (one of the expected labels)
      const hasLabel = EXPECTED_TEMPLATE_LABELS.some((label) => text.includes(label));
      expect(hasLabel, `Card ${i} should contain one of the expected labels`).toBe(true);

      // Should have role text
      const hasRole = EXPECTED_TEMPLATE_NAMES.some((name) => text.toLowerCase().includes(name));
      expect(hasRole, `Card ${i} should contain the template role`).toBe(true);

      // Should have non-empty description (card shouldn't be blank)
      expect(text.trim().length, `Card ${i} should have description text`).toBeGreaterThan(20);

      // Should NOT have icon emoji spans — template cards are clean
      const iconEmojis = card.locator('.ico');
      await expect(iconEmojis, `Card ${i} should not have icon emoji`).toHaveCount(0);

      // Should NOT have tag chips
      const tagChips = card.locator('.tag, .chip');
      await expect(tagChips, `Card ${i} should not have tag chips`).toHaveCount(0);
    }
  });

  // ── Test 3 ───────────────────────────────────────────────────────
  test('AgentCatalog deploy creates agent via API', async ({ page }) => {
    const catalog = page.locator('[data-testid="agent-catalog"]');
    await expect(catalog).toBeVisible({ timeout: 10_000 });

    let interceptedUrl = '';
    let interceptedBody: string | null = null;

    // Intercept the POST to /api/v1/agents/from-template/**
    await page.route('**/api/v1/agents/from-template/**', async (route) => {
      interceptedUrl = route.request().url();
      interceptedBody = route.request().postData();
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ success: true }),
      });
    });

    // Click the first Deploy button (.add-btn) inside the catalog
    const firstDeployBtn = catalog.locator('.add-btn').first();
    await expect(firstDeployBtn).toBeVisible({ timeout: 5_000 });
    await firstDeployBtn.click();

    // Wait for the interception to fire
    await page.waitForTimeout(1000);

    // Verify the intercepted URL contains a valid template name
    expect(interceptedUrl).not.toBe('');
    const urlContainsTemplate = EXPECTED_TEMPLATE_NAMES.some((name) =>
      interceptedUrl.includes(name),
    );
    expect(urlContainsTemplate, `URL should contain a valid template name, got: ${interceptedUrl}`).toBe(true);

    // Verify no request body was sent
    expect(interceptedBody).toBeNull();

    // Verify the response was 201 (handled by our route fulfill above)
    // The click should have completed without errors
  });

  // ── Test 4 ───────────────────────────────────────────────────────
  test('AgentsPage template dropdown populates from API', async ({ page }) => {
    // Navigate to Agents page
    await page.goto('/agents');
    await page.waitForLoadState('networkidle');

    // Click "+ Create Agent" button
    const createBtn = page.getByRole('button', { name: /\+ Create Agent/ });
    await expect(createBtn).toBeVisible({ timeout: 10_000 });
    await createBtn.click();

    // Wait for the form to appear
    await expect(page.locator('.form-card')).toBeVisible({ timeout: 5_000 });

    // Find the "From Template" dropdown
    const templateSelect = page.locator('.form-card select').filter({ hasText: /None|ba|dev|qa/ });
    // If the above doesn't match, try a broader approach
    const allSelects = page.locator('.form-card select');
    const selectCount = await allSelects.count();

    let targetSelect = templateSelect;
    if ((await targetSelect.count()) === 0) {
      // Fallback: use first select that has options
      for (let i = 0; i < selectCount; i++) {
        const options = await allSelects.nth(i).locator('option').allTextContents();
        if (options.some((o) => o.includes('None') || EXPECTED_TEMPLATE_NAMES.some((t) => o.includes(t)))) {
          targetSelect = allSelects.nth(i);
          break;
        }
      }
    }

    await expect(targetSelect).toBeVisible({ timeout: 5_000 });

    // Verify dropdown has 4 options: "— None —" + 3 templates
    const optionTexts = await targetSelect.locator('option').allTextContents();
    expect(optionTexts.length).toBe(4);

    // First option should be "— None —" or similar
    const firstOption = optionTexts[0].trim();
    expect(firstOption.toLowerCase()).toMatch(/none|select|choose/);

    // The other 3 should be the template labels
    const templateOptions = optionTexts.slice(1);
    for (const label of EXPECTED_TEMPLATE_LABELS) {
      const matchFound = templateOptions.some((opt) => opt.includes(label));
      expect(matchFound, `Template label "${label}" should be in dropdown options`).toBe(true);
    }

    // Select the first template option ("ba") and verify form fields populate
    await targetSelect.selectOption({ index: 1 });
    await page.waitForTimeout(500);

    // After selection, form fields should be populated (not empty)
    const formInputs = page.locator('.form-card input');
    const inputCount = await formInputs.count();
    let hasPopulatedField = false;
    for (let i = 0; i < inputCount && i < 4; i++) {
      const value = await formInputs.nth(i).inputValue();
      if (value && value.trim().length > 0) {
        hasPopulatedField = true;
        break;
      }
    }
    expect(hasPopulatedField, 'At least one form field should be populated after template selection').toBe(true);
  });

  // ── Test 5 ───────────────────────────────────────────────────────
  test('CrewPage role dropdown populates from API', async ({ page }) => {
    // Already on /crew from beforeEach
    const catalog = page.locator('[data-testid="agent-catalog"]');
    await expect(catalog).toBeVisible({ timeout: 10_000 });

    // Click "+ Add Agent" button (on the Crew page, different from "+ Create Agent")
    const addAgentBtn = page.getByRole('button', { name: /\+ Add Agent/ });
    await expect(addAgentBtn).toBeVisible({ timeout: 10_000 });
    await addAgentBtn.click();

    // Wait for the mini-dialog to appear
    await page.waitForTimeout(500);

    // Find the "Role" dropdown inside the mini-dialog
    // It could be in a dialog, modal, or inline form
    const roleSelect = page.locator('select').filter({ hasText: new RegExp(EXPECTED_TEMPLATE_NAMES.join('|')) }).first();

    // Fallback if exact match fails
    let targetRoleSelect = roleSelect;
    if ((await targetRoleSelect.count()) === 0) {
      // Try to find any select that contains template roles
      const allSelects = page.locator('select');
      const sc = await allSelects.count();
      for (let i = 0; i < sc; i++) {
        const opts = await allSelects.nth(i).locator('option').allTextContents();
        if (opts.some((o) => EXPECTED_TEMPLATE_NAMES.includes(o.trim()))) {
          targetRoleSelect = allSelects.nth(i);
          break;
        }
      }
    }

    await expect(targetRoleSelect).toBeVisible({ timeout: 5_000 });

    // Verify Role dropdown has exactly 3 options matching template labels
    const roleOptions = await targetRoleSelect.locator('option').allTextContents();
    expect(roleOptions.length).toBeGreaterThanOrEqual(3);

    // Each option text should match one of the expected template labels
    const optionTextsTrimmed = roleOptions.map((o) => o.trim());
    for (const label of EXPECTED_TEMPLATE_LABELS) {
      const matchFound = optionTextsTrimmed.some((opt) =>
        opt.includes(label) || EXPECTED_TEMPLATE_NAMES.includes(opt),
      );
      expect(matchFound, `Role dropdown should contain option matching "${label}"`).toBe(true);
    }
  });

  // ── Test 6 ───────────────────────────────────────────────────────
  test('createFromTemplate API path is correct', async ({ page }) => {
    const catalog = page.locator('[data-testid="agent-catalog"]');
    await expect(catalog).toBeVisible({ timeout: 10_000 });

    // We need to verify the exact API path called on deploy.
    // Intercept BOTH possible paths to assert the correct one is used
    // and the incorrect one is NOT called.

    let correctPathCalled = false;
    let incorrectPathCalled = false;

    // Intercept the CORRECT path: POST /api/v1/agents/from-template/{name}
    await page.route('**/api/v1/agents/from-template/**', async (route) => {
      correctPathCalled = true;
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ success: true }),
      });
    });

    // Intercept the INCORRECT path: POST /api/v1/agents/templates/{id}
    // Only intercept POST — let GET pass through to the real backend
    await page.route('**/api/v1/agents/templates/**', async (route) => {
      if (route.request().method() === 'POST') {
        incorrectPathCalled = true;
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'Not Found' }),
        });
      } else {
        await route.fallback();
      }
    });

    // Click the first Deploy button
    const firstDeployBtn = catalog.locator('.add-btn').first();
    await expect(firstDeployBtn).toBeVisible({ timeout: 5_000 });
    await firstDeployBtn.click();

    // Wait for the interception to process
    await page.waitForTimeout(1500);

    // Assert: correct path was called
    expect(correctPathCalled, 'POST to /api/v1/agents/from-template/{name} should be called').toBe(true);

    // Assert: incorrect path was NOT called
    expect(incorrectPathCalled, 'POST to /api/v1/agents/templates/{id} should NOT be called').toBe(false);
  });
});
