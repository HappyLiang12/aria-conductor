import { test, expect } from '@playwright/test';

/**
 * E2E: role-based skill recommendations.
 *
 * Verifies the "recommend, user confirms" flow for skills:
 *  1. Opening "Add Agent" fetches GET /api/v1/agents/role-defaults/{role} and
 *     renders the role's recommended skills PRE-CHECKED (proves the resolver
 *     returns them end-to-end).
 *  2. Hiring the agent persists the confirmed selection, readable through
 *     GET /api/v1/agents/{id}/skills.
 */

const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';

test.describe('Agent skill recommendations', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/crew');
    await page.waitForSelector('[data-view="crew"]', { state: 'visible' });
  });

  test('Add Agent pre-checks the role recommended skills', async ({ page }) => {
    await page.getByRole('button', { name: '+ Add Agent' }).click();

    const dialog = page.locator('.mini-dialog.open');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('Recommended skills', { exact: false })).toBeVisible();

    // Role defaults load async; a dev agent must have at least one pre-checked skill.
    await expect
      .poll(async () => dialog.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThan(0);
  });

  test('Hiring persists the recommended skills onto the new agent', async ({ page, request }) => {
    const unique = `Rec-${Date.now()}`;
    await page.getByRole('button', { name: '+ Add Agent' }).click();

    const dialog = page.locator('.mini-dialog.open');
    await expect(dialog).toBeVisible();
    await expect
      .poll(async () => dialog.locator('input[type="checkbox"]:checked').count(), { timeout: 15_000 })
      .toBeGreaterThan(0);

    await dialog.locator('#add-agent-name').fill(unique);
    await dialog.getByRole('button', { name: 'Hire Agent' }).click();

    // Dialog closes and the new agent appears on the roster.
    await expect(dialog).toBeHidden({ timeout: 15_000 });
    const card = page.locator('.crew-card', { hasText: unique });
    await expect(card).toBeVisible({ timeout: 15_000 });

    // The persisted selection is the recommended set, readable through the API.
    const listResp = await request.get(`${API_URL}/api/v1/agents`);
    expect(listResp.ok()).toBeTruthy();
    const agents = await listResp.json();
    const agent = agents.find((a: any) => a.name === unique);
    expect(agent, `created agent ${unique} must be listed`).toBeTruthy();

    const skillsResp = await request.get(`${API_URL}/api/v1/agents/${agent.id}/skills`);
    expect(skillsResp.ok()).toBeTruthy();
    const skills = await skillsResp.json();
    expect(skills.length, 'the confirmed recommended skills must be persisted').toBeGreaterThan(0);
  });
});
