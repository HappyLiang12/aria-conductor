import { test, expect } from '@playwright/test';

/**
 * E2E: Agent Dev-Workflow Governance (deterministic — no real LLM, no real GitHub).
 *
 * Guards the enriched approvals surface: the Approvals API returns governance
 * fields (toolName/arguments/riskTier) and the kanban Review column renders the
 * data-driven risk badge (no hardcoded "Medium"). The Approvals page and its
 * /approvals route are deleted — the Review column is the single HITL surface.
 *
 * Prerequisites: backend running with the h2 profile (V33+ seeds), frontend dev server up.
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';
const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';

test.describe('Agent Dev-Workflow Governance', () => {

  test('approvals API exposes enriched fields and the UI renders (#24)', async ({ request, page }) => {
    const resp = await request.get(`${API_URL}/api/v1/approvals`);
    expect(resp.ok()).toBeTruthy();
    const approvals = await resp.json();
    expect(Array.isArray(approvals)).toBeTruthy();
    // If any pending approval exists, it must carry the enriched governance fields.
    for (const a of approvals) {
      expect(a).toHaveProperty('riskTier');
      expect(a).toHaveProperty('toolName');
      expect(a).toHaveProperty('arguments');
    }

    await page.goto(`${BASE_URL}/`);
    await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible();
    // The risk badge is data-driven now — it must never be a hardcoded "Medium" for every card.
    const hardcodedMedium = page.locator('.risk-badge', { hasText: 'Medium' });
    expect(await hardcodedMedium.count()).toBe(0);
  });
});
