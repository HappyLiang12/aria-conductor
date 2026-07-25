import { test, expect } from '@playwright/test';
import { seedAgent, seedRun, uniqueName } from './fixtures';

/**
 * Chat surface smoke E2E. Threads are derived from runs; no LLM reply is ever
 * asserted (no key on the verification stack).
 */
test.describe('Chat smoke', () => {
  test('renders thread list panel and composer', async ({ page }) => {
    await page.goto('/chat');
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.chat-shell')).toBeVisible();
    await expect(page.locator('.chat-list-panel')).toContainText('Threads');
    await expect(page.locator('.ch-search')).toBeVisible();
    await expect(page.locator('.chat-thread-panel')).toBeVisible();
    await expect(page.locator('.chat-compose textarea')).toBeVisible();
  });

  test('a seeded run surfaces as a thread in the list', async ({ page, request }) => {
    const agent = await seedAgent(request);
    const marker = uniqueName('e2e-chat-thread');
    await seedRun(request, agent.id, marker);

    await page.goto('/chat');
    await page.waitForLoadState('networkidle');
    const listPanel = page.locator('.chat-list-panel');
    // Threads are labeled from the run's agent/prompt; match either.
    await expect(
      listPanel.getByText(new RegExp(`${agent.name}|${marker}`)).first(),
    ).toBeVisible({ timeout: 20_000 });
  });
});
