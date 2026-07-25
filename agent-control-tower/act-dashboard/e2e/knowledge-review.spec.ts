import { test, expect } from '@playwright/test';
import { apiCall, seedKnowledgeItem } from './fixtures';

/**
 * Knowledge review-queue E2E: seed a PENDING item over REST, approve it
 * through the UI, verify it lands in the unified (approved) space, and
 * confirm the state machine guards double reviews.
 */
test.describe.configure({ mode: 'serial' });

let itemId = '';
let itemName = '';

test('0. seed a PENDING knowledge item via API', async ({ request }) => {
  const item = await seedKnowledgeItem(request);
  itemId = item.id;
  itemName = item.name;
  expect(item.status).toBe('PENDING');
});

test('1. seeded item appears in the review queue', async ({ page }) => {
  await page.goto('/knowledge');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('h1').filter({ hasText: 'Knowledge Governance' })).toBeVisible();

  const queuePanel = page.locator('section.panel').filter({ hasText: 'Submitted For Review' });
  await expect(queuePanel.locator('.kitem').filter({ hasText: itemName })).toBeVisible();
});

test('2. approving via UI moves it into the unified space', async ({ page }) => {
  await page.goto('/knowledge');
  await page.waitForLoadState('networkidle');

  const queuePanel = page.locator('section.panel').filter({ hasText: 'Submitted For Review' });
  const queued = queuePanel.locator('.kitem').filter({ hasText: itemName });
  await queued.locator('button[title="Approve"]').click();

  // Leaves the review queue…
  await expect(queued).toHaveCount(0, { timeout: 15_000 });
  // …and shows up among approved knowledge.
  const approvedPanel = page.locator('section.panel').filter({ hasText: 'Unified Knowledge Space' });
  await expect(approvedPanel.locator('.kitem').filter({ hasText: itemName }).first()).toBeVisible({
    timeout: 15_000,
  });

  // API is the source of truth for the transition.
  await expect
    .poll(async () => (await apiCall(page.request, 'GET', `/knowledge/${itemId}`)).data?.status)
    .toBe('APPROVED');
});

test('3. negative: re-reviewing an approved item is rejected', async ({ request }) => {
  const { status } = await apiCall(request, 'POST', `/knowledge/${itemId}/review`, {
    decision: 'APPROVED',
    reason: 'double review must fail',
  });
  expect(status).toBeGreaterThanOrEqual(400);
});
