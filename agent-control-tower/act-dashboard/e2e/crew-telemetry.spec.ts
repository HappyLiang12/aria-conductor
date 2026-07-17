import { test, expect } from '@playwright/test';

test.describe('Crew Telemetry Display', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/crew');
    // Wait for the crew view to be rendered
    await page.waitForSelector('[data-view="crew"]', { state: 'visible' });
  });

  test('should display agent cards with telemetry data', async ({ page }) => {
    // Wait for the crew grid to be visible (may appear after loading)
    await page.waitForSelector('.crew-grid', { state: 'visible', timeout: 15_000 });
    
    // Verify agent cards are present
    const cards = page.locator('.crew-card');
    await expect(cards.first()).toBeVisible();
    const cardCount = await cards.count();
    expect(cardCount).toBeGreaterThan(0);

    // Verify each card has a .stats section with token and cost display
    for (let i = 0; i < cardCount; i++) {
      const card = cards.nth(i);
      const statsSection = card.locator('.stats');
      await expect(statsSection).toBeVisible();

      // Token display — the .v.tok element inside .stats
      const tokenValue = statsSection.locator('.v.tok');
      await expect(tokenValue).toBeVisible();
      const tokenText = await tokenValue.textContent();
      expect(tokenText).toBeTruthy();

      // Cost display — the .v.cost element inside .stats
      const costValue = statsSection.locator('.v.cost');
      await expect(costValue).toBeVisible();
      const costText = await costValue.textContent();
      expect(costText).toBeTruthy();
    }

    // Verify activity bars are rendered on the first card
    const firstCard = cards.first();
    const barsContainer = firstCard.locator('.bars');
    await expect(barsContainer).toBeVisible();
    const barItems = barsContainer.locator('i');
    await expect(barItems.first()).toBeVisible();
  });

  test('should display Roster Cost banner with aggregated data', async ({ page }) => {
    // Verify the crew-cost section is visible
    const costSection = page.locator('.crew-cost');
    await expect(costSection).toBeVisible();

    // Verify "Tokens · today" label is present
    const tokensCell = costSection.locator('.cell.tok');
    await expect(tokensCell).toBeVisible();
    const tokensLabel = tokensCell.locator('.l');
    await expect(tokensLabel).toHaveText('Tokens · today');

    // Verify "Estimated spend" label is present
    const spendCell = costSection.locator('.cell.spend');
    await expect(spendCell).toBeVisible();
    const spendLabel = spendCell.locator('.l');
    await expect(spendLabel).toHaveText('Estimated spend');

    // Verify Active count is displayed (first .cell has "Active" label)
    const activeCell = costSection.locator('.cell').first();
    await expect(activeCell).toBeVisible();
    const activeLabel = activeCell.locator('.l');
    await expect(activeLabel).toHaveText('Active');
    const activeValue = activeCell.locator('.v');
    await expect(activeValue).toBeVisible();
    const activeCountText = await activeValue.textContent();
    expect(Number(activeCountText)).toBeGreaterThanOrEqual(0);

    // Verify the "reachable · idle" detail text is present
    const activeDetail = activeCell.locator('.d');
    await expect(activeDetail).toBeVisible();
    const detailText = await activeDetail.textContent();
    expect(detailText).toMatch(/\d+\s+reachable\s*·\s*\d+\s+idle/);
  });

  test('should return valid JSON array from agent telemetry API', async ({ page }) => {
    // Call the telemetry API directly from browser context (goes through Vite proxy)
    const response = await page.evaluate(async () => {
      const res = await fetch('/api/v1/dashboard/agent-telemetry');
      if (!res.ok) {
        throw new Error(`API responded with status ${res.status}: ${res.statusText}`);
      }
      return res.json();
    });

    // Verify response is an array
    expect(Array.isArray(response)).toBe(true);

    // Verify each item has the required telemetry fields
    if (response.length > 0) {
      for (const item of response) {
        expect(item).toHaveProperty('agentId');
        expect(typeof item.agentId).toBe('string');
        expect(item).toHaveProperty('totalTokensToday');
        expect(typeof item.totalTokensToday).toBe('number');
        expect(item).toHaveProperty('callCountToday');
        expect(typeof item.callCountToday).toBe('number');
      }
    }
  });

  test('should show zero values for agents with no activity', async ({ page }) => {
    // Wait for page to load — crew grid may or may not exist
    const crewGrid = page.locator('.crew-grid');
    const crewEmpty = page.locator('.crew-empty');
    
    await Promise.race([
      crewGrid.waitFor({ state: 'visible', timeout: 15_000 }),
      crewEmpty.waitFor({ state: 'visible', timeout: 15_000 }),
    ]).catch(() => {
      // If neither appears in time, the test handles it gracefully below
    });

    const hasGrid = await crewGrid.isVisible();
    const hasEmpty = await crewEmpty.isVisible();

    if (hasGrid) {
      // Agents exist — verify that telemetry is rendered (even if zero)
      const cards = page.locator('.crew-card');
      const cardCount = await cards.count();

      for (let i = 0; i < cardCount; i++) {
        const card = cards.nth(i);
        
        // Activity bars should always be rendered (even if all off)
        const bars = card.locator('.bars');
        await expect(bars).toBeVisible();
        const barItems = bars.locator('i');
        const barCount = await barItems.count();
        expect(barCount).toBe(6); // always 6 bars

        // Stats section should always be visible with token/cost values
        const stats = card.locator('.stats');
        await expect(stats).toBeVisible();

        // Token value: should be "0" or "0.0k" or similar for no-activity agents
        const tokenValue = stats.locator('.v.tok');
        await expect(tokenValue).toBeVisible();
        const tokenText = await tokenValue.textContent();

        // Cost value: should show "$0.00" for agents with no tokens
        const costValue = stats.locator('.v.cost');
        await expect(costValue).toBeVisible();
        const costText = await costValue.textContent();
        expect(costText).toMatch(/^\$/);
        expect(costText).toBeTruthy();

        // Both should be non-null and present
        expect(tokenText).toBeTruthy();
        expect(costText).toBeTruthy();
      }
    } else if (hasEmpty) {
      // No agents on crew — empty state is expected
      const emptyText = await crewEmpty.textContent();
      expect(emptyText).toBeTruthy();
      // The cost banner should still show zero aggregates
      const tokensCell = page.locator('.crew-cost .cell.tok .v');
      await expect(tokensCell).toBeVisible();
      const tokensValue = await tokensCell.textContent();
      expect(tokensValue).toBe('0');
    } else {
      // Neither grid nor empty state appeared within timeout
      expect(false, 'Neither crew-grid nor crew-empty became visible').toBe(true);
    }
  });
});
