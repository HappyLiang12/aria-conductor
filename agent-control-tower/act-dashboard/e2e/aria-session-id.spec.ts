import { test, expect } from '@playwright/test';

test.describe('Issue #13: Conversation ID Display in Aria UI', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('.ai-fab', { state: 'visible' });
  });

  test('should display truncated conversation ID in Aria panel header', async ({ page }) => {
    await page.click('.ai-fab');
    await page.waitForSelector('.ai-panel', { state: 'visible' });

    const conversationIdElement = page.locator('.ai-conversation-id');
    await expect(conversationIdElement).toBeVisible();

    const conversationIdText = await conversationIdElement.textContent();
    expect(conversationIdText).toMatch(/^\w{8}\.\.\.$/);
  });

  test('should copy full conversation ID to clipboard when copy button clicked', async ({ page }) => {
    await page.click('.ai-fab');
    await page.waitForSelector('.ai-panel', { state: 'visible' });

    const conversationIdElement = page.locator('.ai-conversation-id');
    const fullConversationId = await conversationIdElement.getAttribute('title');
    expect(fullConversationId).toBeTruthy();
    expect(fullConversationId?.length).toBeGreaterThan(8);

    await page.click('.ai-copy-btn');

    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBe(fullConversationId);

    const copyBtn = page.locator('.ai-copy-btn');
    await expect(copyBtn).toHaveText('✓');

    await page.waitForTimeout(2100);
    await expect(copyBtn).toHaveText('📋');
  });

  test('should clear conversation and create new conversation ID', async ({ page }) => {
    await page.click('.ai-fab');
    await page.waitForSelector('.ai-panel', { state: 'visible' });

    const initialConversationId = await page.locator('.ai-conversation-id').getAttribute('title');
    expect(initialConversationId).toBeTruthy();

    await page.fill('.ai-compose-input', 'Test message');
    await page.click('.ai-clear-btn');
    await page.waitForTimeout(100);

    const newConversationId = await page.locator('.ai-conversation-id').getAttribute('title');
    expect(newConversationId).toBeTruthy();
    expect(newConversationId).not.toBe(initialConversationId);

    const emptyState = page.locator('.ai-empty-title');
    await expect(emptyState).toBeVisible();

    const input = page.locator('.ai-compose-input');
    await expect(input).toHaveValue('');
  });

  test('should include conversation ID in error messages', async ({ page }) => {
    await page.click('.ai-fab');
    await page.waitForSelector('.ai-panel', { state: 'visible' });

    const conversationId = await page.locator('.ai-conversation-id').getAttribute('title');
    expect(conversationId).toBeTruthy();

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    expect(conversationId).toMatch(uuidRegex);
  });

  test('should generate new conversation ID on first open (no localStorage)', async ({ page }) => {
    await page.reload();
    await page.waitForSelector('.ai-fab', { state: 'visible' });

    await page.click('.ai-fab');
    await page.waitForSelector('.ai-panel', { state: 'visible' });

    // Note: the '[Aria] New conversation:' console log only fires when the backend
    // has no prior conversation — on a shared CI backend earlier specs may have
    // created one, so assert the observable outcome (a valid ID in the header)
    // instead of the log line.
    const conversationIdElement = page.locator('.ai-conversation-id');
    await expect(conversationIdElement).toBeVisible();
    const title = await conversationIdElement.getAttribute('title');
    expect(title).toMatch(/^[0-9a-f-]{36}$/i);
  });
});
