import { test, expect } from '@playwright/test';

// STALE: the /settings route and rail Settings button were removed in the UI
// redesign — LLM provider config now lives in ConfigureModal (see Layout.tsx).
// Rewrite this suite against the current UI in the Phase E E2E overhaul.
test.describe.skip('Issue #18: LLM Provider Configuration UI Navigation', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to the dashboard
    await page.goto('/');
    // Wait for the app to load
    await page.waitForSelector('.rail', { state: 'visible' });
  });

  test('should display Settings item in sidebar navigation', async ({ page }) => {
    // Check that Settings button exists in the rail navigation
    const settingsBtn = page.locator('.rail-btn', { hasText: 'Settings' });
    await expect(settingsBtn).toBeVisible();
    
    // Verify it has the correct icon
    const icon = settingsBtn.locator('.ico');
    await expect(icon).toHaveText('⚙️');
  });

  test('should navigate to Settings page when clicking Settings button', async ({ page }) => {
    // Click Settings button
    await page.click('.rail-btn', { hasText: 'Settings' });
    
    // Wait for navigation
    await page.waitForURL('/settings');
    
    // Verify URL changed
    expect(page.url()).toContain('/settings');
    
    // Verify Settings page content is visible
    const settingsHeading = page.locator('h2', { hasText: 'Settings' });
    await expect(settingsHeading).toBeVisible();
    
    // Verify "Add Provider" button is visible
    const addProviderBtn = page.locator('button', { hasText: '+ Add Provider' });
    await expect(addProviderBtn).toBeVisible();
  });

  test('should highlight Settings button as active when on /settings route', async ({ page }) => {
    // Navigate directly to /settings
    await page.goto('/settings');
    await page.waitForLoadState('networkidle');
    
    // Check that Settings button has active class
    const settingsBtn = page.locator('.rail-btn.active', { hasText: 'Settings' });
    await expect(settingsBtn).toBeVisible();
  });

  test('should not display Configure button (replaced by Settings)', async ({ page }) => {
    // Verify Configure button no longer exists
    const configureBtn = page.locator('.rail-btn', { hasText: 'Configure' });
    await expect(configureBtn).not.toBeVisible();
    
    // Verify rail-sep divider was also removed
    const railSep = page.locator('.rail-sep');
    await expect(railSep).not.toBeVisible();
  });

  test('should load Settings page directly at /settings URL', async ({ page }) => {
    // Navigate directly to /settings
    await page.goto('/settings');
    await page.waitForLoadState('networkidle');
    
    // Verify Settings page loads
    await expect(page.locator('h2', { hasText: 'Settings' })).toBeVisible();
    
    // Verify LLM provider functionality is accessible
    await expect(page.locator('button', { hasText: '+ Add Provider' })).toBeVisible();
    
    // Verify page has settings-related content
    const pageContent = await page.locator('.page').textContent();
    expect(pageContent).toContain('Settings');
  });

  test('should navigate from Settings to other pages and back', async ({ page }) => {
    // Go to Settings
    await page.click('.rail-btn', { hasText: 'Settings' });
    await page.waitForURL('/settings');
    await expect(page.locator('h2', { hasText: 'Settings' })).toBeVisible();
    
    // Navigate to Overview
    await page.click('.rail-btn', { hasText: 'Overview' });
    await page.waitForURL('/');
    await expect(page.locator('h2', { hasText: 'Overview' })).toBeVisible();
    
    // Navigate back to Settings
    await page.click('.rail-btn', { hasText: 'Settings' });
    await page.waitForURL('/settings');
    await expect(page.locator('h2', { hasText: 'Settings' })).toBeVisible();
  });
});
