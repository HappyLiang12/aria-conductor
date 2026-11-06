import { test, expect } from '@playwright/test';
import { apiCall, seedAgent, seedKnowledgeItem, seedRun, uniqueName } from './fixtures';

/**
 * Journey: knowledge promotion.
 *
 * Ungated: submit (PENDING) → approve over REST → visible in the unified
 * space → promote to a derived item via POST /{id}/promote.
 * LLM-gated: the prompt-call accumulation path needs a real completed run;
 * that segment is gated on an API key — no assertion theater.
 */
const HAS_LLM_KEY = !!(
  process.env.LLM_API_KEY ||
  process.env.LLM_PROVIDER_API_KEY ||
  process.env.DEEPSEEK_API_KEY
);

test.describe.configure({ mode: 'serial' });

let itemId = '';
let itemName = '';

test('1. submitted knowledge lands in the PENDING review queue', async ({ page, request }) => {
  const item = await seedKnowledgeItem(request, {
    type: 'GUIDELINE',
    content: 'Prefer small reversible changes over big-bang rewrites.',
  });
  itemId = item.id;
  itemName = item.name;
  expect(item.status).toBe('PENDING');

  await page.goto('/knowledge');
  await page.waitForLoadState('networkidle');
  const queuePanel = page.locator('section.panel').filter({ hasText: 'Submitted For Review' });
  await expect(queuePanel.locator('.kitem').filter({ hasText: itemName })).toBeVisible();
});

test('2. REST approval moves it into the unified space (UI verified)', async ({ page, request }) => {
  const review = await apiCall(request, 'POST', `/knowledge/${itemId}/review`, {
    decision: 'APPROVED',
    reason: 'journey approval',
  });
  expect(review.status).toBe(200);
  expect(review.data.status).toBe('APPROVED');

  await page.goto('/knowledge');
  await page.waitForLoadState('networkidle');
  const approvedPanel = page.locator('section.panel').filter({ hasText: 'Unified Knowledge Space' });
  await expect(approvedPanel.locator('.kitem').filter({ hasText: itemName }).first()).toBeVisible({
    timeout: 15_000,
  });
});

test('3. promotion derives a new item of the target type', async ({ request }) => {
  const targetName = uniqueName('e2e-promoted-skill');
  const { status, data } = await apiCall(request, 'POST', `/knowledge/${itemId}/promote`, {
    targetType: 'SKILL',
    targetName,
  });
  expect(status).toBe(201);
  expect(data.id).toBeTruthy();
  expect(data.id).not.toBe(itemId);

  const fetched = await apiCall(request, 'GET', `/knowledge/${data.id}`);
  expect(fetched.status).toBe(200);
  expect(fetched.data.type).toBe('SKILL');
});

test('4. negative: promoting a non-existent item is rejected', async ({ request }) => {
  const { status } = await apiCall(
    request,
    'POST',
    '/knowledge/00000000-0000-0000-0000-000000000000/promote',
    { targetType: 'SKILL' },
  );
  expect(status).toBeGreaterThanOrEqual(400);
});

test.describe('LLM-gated tail', () => {
  test.describe.configure({ timeout: 300_000 });

  test('5. knowledge-informed run completes with a real trajectory', async ({ request }) => {
    test.skip(!HAS_LLM_KEY, 'requires a real LLM API key (LLM_API_KEY / LLM_PROVIDER_API_KEY / DEEPSEEK_API_KEY)');

    const agent = await seedAgent(request);
    const run = await seedRun(
      request,
      agent.id,
      `Apply the guideline "${itemName}" and answer in one sentence.`,
      2,
    );
    await expect
      .poll(async () => (await apiCall(request, 'GET', `/runs/${run.id}`)).data?.status, { timeout: 240_000 })
      .toBe('COMPLETED');

    const trajectory = await apiCall(request, 'GET', `/runs/${run.id}/trajectory`);
    expect(trajectory.status).toBe(200);
    expect(trajectory.data.length).toBeGreaterThan(0);
    // NOTE: prompt-call accumulation exposes no REST metric today; the
    // observable contract ends at a completed knowledge-informed run.
  });
});
