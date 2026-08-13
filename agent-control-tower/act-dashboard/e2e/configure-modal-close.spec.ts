import { test, expect, type Page } from '@playwright/test';

/**
 * E2E: Configure modal closed-state visibility (F1).
 *
 * The Configure modal (Gates + Skills) stays mounted in the DOM after closing —
 * it merely flips to `opacity: 0` and `pointer-events: none`. Before F1 it
 * lacked `visibility: hidden`, so Playwright's visibility model (and assistive
 * tech / keyboard focus) still treated the overlay as present. This spec pins
 * the fix: after closing, the scrim and modal must be genuinely hidden and
 * must not intercept pointer events.
 *
 * Requires a running backend + frontend stack (BASE_URL / localhost:5173).
 * Run by the controller against the full stack — not runnable in isolation.
 */

/** Open the Configure modal via the rail's standalone Configure button. */
async function openConfigure(page: Page): Promise<void> {
  // The rail is height-limited and 'Configure' is its last button; use a taller
  // viewport so the button is within the clickable area (same as other specs).
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.rail')).toBeVisible({ timeout: 15_000 });
  await page.locator('.rail-btn', { hasText: 'Configure' }).click();
  await expect(page.locator('.modal.open')).toBeVisible({ timeout: 10_000 });
}

test.describe('Configure modal closed-state visibility (F1)', () => {
  test('scrim and modal are hidden and non-interactive after Done', async ({ page }) => {
    await openConfigure(page);

    // While open, both layers are visible and interactive.
    await expect(page.locator('.modal-scrim.open')).toBeVisible();
    await expect(page.locator('.modal.open')).toBeVisible();

    // Close via the Done button in the modal footer.
    await page.getByRole('button', { name: 'Done' }).click();

    // The `.open` class is removed, so both layers collapse to the closed state.
    await expect(page.locator('.modal.open')).toHaveCount(0, { timeout: 5_000 });

    const scrim = page.locator('.modal-scrim');
    const modal = page.locator('.modal');

    // F1 core assertion: Playwright must treat both layers as hidden now.
    await expect(scrim).toBeHidden({ timeout: 5_000 });
    await expect(modal).toBeHidden({ timeout: 5_000 });
    await expect(scrim).not.toBeVisible();
    await expect(modal).not.toBeVisible();

    // Verify the computed CSS: hidden visibility AND no pointer interception.
    const closedStyles = await scrim.evaluate((el) => {
      const s = getComputedStyle(el);
      return { visibility: s.visibility, pointerEvents: s.pointerEvents };
    });
    expect(closedStyles.visibility).toBe('hidden');
    expect(closedStyles.pointerEvents).toBe('none');

    // The closed scrim must not intercept pointer events: clicking a rail item
    // underneath it must succeed (navigates without being blocked).
    await page.locator('.rail-btn', { hasText: 'Overview' }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.rail')).toBeVisible();
  });

  test('reopening the modal restores visibility', async ({ page }) => {
    await openConfigure(page);

    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.locator('.modal.open')).toHaveCount(0, { timeout: 5_000 });
    await expect(page.locator('.modal-scrim')).toBeHidden();

    // Reopen — the `.open` class restores visibility for both layers.
    await page.locator('.rail-btn', { hasText: 'Configure' }).click();
    await expect(page.locator('.modal.open')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('.modal-scrim.open')).toBeVisible();
  });
});
