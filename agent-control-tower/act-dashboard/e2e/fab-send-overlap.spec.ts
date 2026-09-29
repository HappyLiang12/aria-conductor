import { test, expect } from '@playwright/test';
import { seedAgent, seedRun, uniqueName } from './fixtures';

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

test('inject Send button receives clicks (Aria panel state unchanged)', async ({ page, request }) => {
  // The inject composer is DISABLED until a thread is selected, so this case seeds
  // its own thread (a run surfaces as one) and selects it: on a fresh CI database
  // the chat page has no thread, and the previous version timed out on a disabled
  // textarea instead of probing the FAB overlap it exists for.
  const agent = await seedAgent(request);
  const marker = uniqueName('e2e-fab-inject');
  await seedRun(request, agent.id, marker);

  await page.goto('/chat');
  await page.waitForLoadState('networkidle');
  const thread = page
    .locator('.chat-list-panel')
    .getByText(new RegExp(`${agent.name}|${marker}`))
    .first();
  await expect(thread).toBeVisible({ timeout: 20_000 });
  await thread.click();

  const send = page.getByRole('button', { name: 'Send ▶' }).first();
  const compose = page.getByLabel('Inject message');
  // The click that matters is on the button's right edge; the composer must accept
  // the text first (it enables once the selected thread is active).
  await expect(compose).toBeEnabled({ timeout: 20_000 });
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
