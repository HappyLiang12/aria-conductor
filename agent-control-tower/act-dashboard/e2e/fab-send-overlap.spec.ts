import { test, expect } from '@playwright/test';

// F1 regression: the fixed-position Aria FAB (bottom-right) must never cover
// the Chat page's inject "Send ▶" button, otherwise clicks toggle the Aria
// panel instead of sending the injected message.
test('Aria FAB does not occlude the Chat inject Send button', async ({ page }) => {
  await page.goto('/chat');

  const send = page.getByRole('button', { name: 'Send ▶' }).first();
  const fab = page.getByRole('button', { name: /Open Aria panel|Close Aria panel/ });

  await expect(send).toBeVisible();
  await expect(fab).toBeVisible();
  await send.scrollIntoViewIfNeeded();

  const sb = await send.boundingBox();
  const fb = await fab.boundingBox();
  expect(sb, 'Send button bounding box').toBeTruthy();
  expect(fb, 'Aria FAB bounding box').toBeTruthy();

  const overlaps =
    sb!.x < fb!.x + fb!.width &&
    fb!.x < sb!.x + sb!.width &&
    sb!.y < fb!.y + fb!.height &&
    fb!.y < sb!.y + sb!.height;
  expect(overlaps, 'FAB must not overlap the Send button').toBe(false);
});

test('inject Send button receives clicks (Aria panel state unchanged)', async ({ page }) => {
  await page.goto('/chat');

  const send = page.getByRole('button', { name: 'Send ▶' }).first();
  const compose = page.getByLabel('Inject message');
  await compose.fill('e2e overlap probe');

  const fabClosed = page.getByRole('button', { name: 'Open Aria panel' });
  const wasClosed = (await fabClosed.count()) > 0;

  // Click the RIGHT edge of the Send button — the exact zone the FAB used to
  // cover. After the fix that zone must belong to the button itself.
  const sb = await send.boundingBox();
  expect(sb, 'Send button bounding box').toBeTruthy();
  await send.click({ position: { x: sb!.width - 2, y: sb!.height / 2 } });

  if (wasClosed) {
    await expect(page.getByRole('button', { name: 'Open Aria panel' })).toBeVisible();
  } else {
    await expect(page.getByRole('button', { name: 'Close Aria panel' })).toBeVisible();
  }
  // The draft is consumed by the inject handler (textarea cleared) — proof the
  // click reached Send and not the FAB.
  await expect(compose).toHaveValue('');
});
