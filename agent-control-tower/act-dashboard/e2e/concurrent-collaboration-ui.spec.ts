import { test, expect } from '@playwright/test';
import { seedAgent, seedWorkflow, uniqueName } from './fixtures';

/**
 * UI-layer E2E for concurrent multi-agent collaboration: seed several workflows
 * at once over the API, then verify the dashboard renders each one independently
 * with a live state-machine badge (no cross-contamination). LLM-free — runs fail
 * fast / stay running without a key, so we assert on rendered chain state, never
 * on LLM output. Complements the API-tier concurrency specs under e2e/api/.
 */
test.describe.configure({ mode: 'serial' });

test('concurrent workflows each render on the Workflows page with a live status', async ({ page, request }) => {
  const agent = await seedAgent(request, uniqueName('e2e-ui-collab-agent'));
  const wfs = await Promise.all([
    seedWorkflow(request, agent.id, { name: uniqueName('e2e-ui-wf-A') }),
    seedWorkflow(request, agent.id, { name: uniqueName('e2e-ui-wf-B') }),
    seedWorkflow(request, agent.id, { name: uniqueName('e2e-ui-wf-C') }),
  ]);
  expect(new Set(wfs.map((w) => w.id)).size).toBe(3); // distinct chains

  await page.goto('/workflows');
  await page.waitForLoadState('networkidle');

  for (const wf of wfs) {
    // Each concurrently-created chain appears exactly as itself.
    await expect(page.getByText(wf.name, { exact: true })).toBeVisible({ timeout: 20_000 });
    // …and carries a valid state-machine badge (proves live per-chain rendering).
    const badge = page.getByText(wf.name, { exact: true }).locator('xpath=preceding-sibling::span[1]');
    await expect(badge).toHaveText(/PENDING|RUNNING|COMPLETED|FAILED|CANCELLED/, { timeout: 20_000 });
  }

  await page.screenshot({
    path: `${process.env.EVIDENCE_DIR || 'test-results'}/ui-concurrent-workflows.png`,
    fullPage: true,
  });
});
