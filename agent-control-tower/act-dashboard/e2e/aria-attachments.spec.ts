import { test, expect, type Page } from '@playwright/test';

/**
 * Chat image attachments, end to end (Task 8).
 *
 * What this proves against the deterministic core E2E harness:
 *   - an image handed to the composer's file input becomes exactly one pending
 *     chip (the client-side validation path then holds a 1x1 PNG as-is, so the
 *     chip keeps the original filename);
 *   - sending the turn puts that image on the user turn as a thumbnail inside
 *     `.ai-msg-user .ai-msg-attachments` — drawn from the client's own object
 *     URL, since nothing about the bytes is ever persisted — and the composer
 *     goes back to enabled, i.e. the turn settled instead of being refused
 *     pre-stream (a refusal would leave the images on the failed turn and the
 *     composer enabled just the same, so the thumbnail count is the real proof);
 *   - a >2048 px image is redrawn through the helper's canvas path and the
 *     chip's filename follows the JPEG rename (`big.png` -> `big.jpg`). The 1x1
 *     PNG never reaches the redraw branch, so without this second test the
 *     canvas/rename half of `prepareImage` would go unexercised end to end.
 */
test.describe.configure({ mode: 'serial', timeout: 240_000 });

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

async function openAria(page: Page): Promise<void> {
  const fab = page.locator('button.ai-fab');
  await fab.waitFor({ state: 'visible', timeout: 15_000 });
  if (!(await page.locator('.ai-panel').isVisible().catch(() => false))) {
    await fab.click();
  }
  await expect(page.locator('.ai-panel')).toBeVisible({ timeout: 10_000 });
}

test('an attached image is shown as a thumbnail and sent with the turn', async ({ page }) => {
  await page.goto('/');
  await openAria(page);

  await page.setInputFiles('[data-testid="aria-attachment-input"]', {
    name: 'probe.png',
    mimeType: 'image/png',
    buffer: PNG,
  });
  await expect(page.locator('.ai-panel .ai-attachment-chip')).toHaveCount(1);

  const textarea = page.locator('.ai-panel .ai-compose-input');
  await textarea.fill('Describe the attached image');
  await textarea.press('Enter');

  await expect(page.locator('.ai-panel .ai-msg-user .ai-msg-attachments img')).toHaveCount(1, {
    timeout: 10_000,
  });
  await expect(page.locator('.ai-panel .ai-compose-input')).toBeEnabled({ timeout: 180_000 });
});

test('a large image is redrawn to JPEG before it is sent', async ({ page }) => {
  await page.goto('/');
  await openAria(page);

  await page.evaluate(async () => {
    const canvas = document.createElement('canvas');
    canvas.width = 2100;
    canvas.height = 2100;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('no 2d context');
    const gradient = ctx.createLinearGradient(0, 0, 2100, 2100);
    gradient.addColorStop(0, '#123456');
    gradient.addColorStop(1, '#fedcba');
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, 2100, 2100);
    const blob = await new Promise<Blob>((resolve) => {
      canvas.toBlob((value) => resolve(value as Blob), 'image/png');
    });
    const file = new File([blob], 'big.png', { type: 'image/png' });
    const data = new DataTransfer();
    data.items.add(file);
    const input = document.querySelector('[data-testid="aria-attachment-input"]') as HTMLInputElement;
    input.files = data.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });

  await expect(page.locator('.ai-panel .ai-attachment-chip')).toContainText('big.jpg', {
    timeout: 15_000,
  });
});
