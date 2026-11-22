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
 *     URL, since nothing about the bytes is ever persisted;
 *   - the turn COMPLETED, not merely settled: the thumbnail is drawn optimistically
 *     and a pre-stream refusal also ends with the composer enabled (it takes the
 *     sent bubble back and restores the draft), so completion is proven by the
 *     deterministic harness's assistant bubble, with no assistant error bubble
 *     (a mid-stream failure) and no `.ai-attachment-error` notice (a refusal);
 *   - a >2048 px image is redrawn through the helper's canvas path and the
 *     chip's filename follows the JPEG rename (`big.png` -> `big.jpg`). The 1x1
 *     PNG never reaches the redraw branch, so without this second test the
 *     canvas/rename half of `prepareImage` would go unexercised end to end.
 */
// The tests are independent (each opens its own Aria panel), so no serial mode:
// serial would hide test 2's redraw proof behind test 1. Only the timeout is
// raised, so test 1's 180 s completion wait fits inside the spec's own timeout.
test.describe.configure({ timeout: 240_000 });

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
  // The request body is the proof that the image left the composer: the
  // thumbnail below is drawn from the client's own object URL before anything
  // is sent, so it alone would pass for a turn that dropped the attachment.
  const sent = page.waitForRequest((candidate) =>
    candidate.url().includes('/api/v1/aria/chat/stream') && candidate.method() === 'POST');
  await textarea.press('Enter');
  const body = (await sent).postDataJSON() as {
    attachments?: Array<{ filename: string; mimeType: string }>;
  };
  expect(body.attachments).toEqual([
    expect.objectContaining({ filename: 'probe.png', mimeType: 'image/png' }),
  ]);

  await expect(page.locator('.ai-panel .ai-msg-user .ai-msg-attachments img')).toHaveCount(1, {
    timeout: 10_000,
  });

  // Completion, not optimism: the thumbnail is drawn before any response and a
  // pre-stream refusal also ends with the composer enabled, so settle first and
  // then assert the reply itself. `.ai-msg-assistant` is the selector
  // e2e/aria-inflow-decisions.spec.ts uses for a completed turn; the thinking
  // placeholder shares that class, which is why this runs after busy is cleared.
  await expect(page.locator('.ai-panel .ai-compose-input')).toBeEnabled({ timeout: 180_000 });
  await expect(page.locator('.ai-panel .ai-msg-assistant').last()).toBeVisible({ timeout: 180_000 });
  await expect(page.locator('.ai-panel .ai-msg-assistant .ai-msg-bubble-error')).toHaveCount(0);
  await expect(page.locator('.ai-panel .ai-attachment-error')).toHaveCount(0);
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
    // A solid fill keeps the encoded PNG tiny on every browser's encoder; the
    // >2048 px edge is what must trigger the redraw, not the byte threshold.
    ctx.fillStyle = '#123456';
    ctx.fillRect(0, 0, 2100, 2100);
    const blob = await new Promise<Blob>((resolve) => {
      canvas.toBlob((value) => resolve(value as Blob), 'image/png');
    });
    if (blob.size > 4 * 1024 * 1024) {
      throw new Error(`generated image too large for the phase-1 cap: ${blob.size}`);
    }
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
