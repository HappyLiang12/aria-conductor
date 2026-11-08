import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { apiCall, BACKEND } from './fixtures';

/**
 * E2E: Pack credential storage and resolution path (Issue #113).
 *
 * PR #109 retired the legacy tool-execution surface and deleted three governance
 * specs (git-pack-governance, agent-tool-skill-recommendations, harness-governance).
 * However, the pack credential path remains live: the git handoff resolves its
 * token from the git pack's stored credentials through PackCredentialService.
 *
 * This spec covers the retained credential path end-to-end:
 * - API: store, per-agent override, validation
 * - UI: GitCredentialCard store + empty-input validation
 *
 * The pack-git-0001 row is seeded by V33 migration.
 */

const PACK_ID = 'pack-git-0001';
const CRED_KEY = 'GITHUB_TOKEN';

function testToken(label: string): string {
  return `${label}-e2e-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
}

test.describe('Pack credential path (API + UI)', () => {
  // ── API tests ─────────────────────────────────────────────────────────

  test('API: store a credential for the git pack', async ({ request }) => {
    const token = testToken('test-token');
    const { status, data } = await apiCall(request, 'POST', `/packs/${PACK_ID}/credentials`, {
      key: CRED_KEY,
      value: token,
    });

    expect(status).toBe(200);
    expect(data).toMatchObject({
      status: 'stored',
      key: CRED_KEY,
      restartRequired: false,
    });
  });

  test('API: store a per-agent override credential', async ({ request }) => {
    const agentToken = testToken('agent-token');
    const agentId = 'agent-123';
    const { status, data } = await apiCall(request, 'POST', `/packs/${PACK_ID}/credentials`, {
      key: CRED_KEY,
      value: agentToken,
      agentId,
    });

    expect(status).toBe(200);
    expect(data).toMatchObject({
      status: 'stored',
      key: CRED_KEY,
      restartRequired: false,
    });
  });

  test('API: validate required fields (missing key)', async ({ request }) => {
    const { status, data } = await apiCall(request, 'POST', `/packs/${PACK_ID}/credentials`, {
      value: 'some-value',
    });

    expect(status).toBe(400);
    expect(data).toMatchObject({ error: 'key and value are required' });
  });

  test('API: validate required fields (missing value)', async ({ request }) => {
    const { status, data } = await apiCall(request, 'POST', `/packs/${PACK_ID}/credentials`, {
      key: CRED_KEY,
    });

    expect(status).toBe(400);
    expect(data).toMatchObject({ error: 'key and value are required' });
  });

  // ── UI tests ──────────────────────────────────────────────────────────

  test('UI: GitCredentialCard stores a credential', async ({ page }) => {
    await openConfigureAndNavigateToRuntime(page);

    const card = page.locator('[data-testid="git-credential-card"]');
    await expect(card).toBeVisible({ timeout: 10_000 });

    const input = card.locator('input[type="password"]');
    await expect(input).toBeVisible();

    const token = testToken('ui-token');
    await input.fill(token);

    await card.getByRole('button', { name: 'Save git credential' }).click();

    // Assert success message
    const outcome = page.locator('[data-testid="git-credential-outcome"]');
    await expect(outcome).toBeVisible({ timeout: 10_000 });
    await expect(outcome).toContainText(
      'Credential stored — new workflow runs resolve it without a restart.',
    );

    // Assert the input is cleared after save (secret never outlives the request)
    await expect(input).toHaveValue('');
  });

  test('UI: GitCredentialCard validates empty input', async ({ page }) => {
    await openConfigureAndNavigateToRuntime(page);

    const card = page.locator('[data-testid="git-credential-card"]');
    await expect(card).toBeVisible({ timeout: 10_000 });

    // Attempt to save with an empty credential
    await card.getByRole('button', { name: 'Save git credential' }).click();

    // Assert error message
    const alert = card.locator('[role="alert"]');
    await expect(alert).toBeVisible({ timeout: 5_000 });
    await expect(alert).toContainText('A git credential value is required');
  });
});

/**
 * Open the Configure modal and click the Runtime tab.
 * The GitCredentialCard lives in the Runtime tab.
 */
async function openConfigureAndNavigateToRuntime(page: Page): Promise<void> {
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });

  // Open Configure modal
  await page.locator('.rail-btn', { hasText: 'Configure' }).click();
  await expect(page.locator('.modal.open')).toBeVisible({ timeout: 10_000 });

  // Click Runtime tab
  await page.locator('.tab', { hasText: 'Runtime' }).click();
  await expect(page.locator('[data-testid="git-credential-card"]')).toBeVisible({ timeout: 10_000 });
}
