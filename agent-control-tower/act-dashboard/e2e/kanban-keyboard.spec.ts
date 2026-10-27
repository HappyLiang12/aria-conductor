import { test, expect, type Page } from '@playwright/test';

/**
 * Task 13: board keyboard contract at 1440x900, route fixtures only.
 */

const CARD = {
  id: 'k-keyboard',
  title: 'Keyboard card',
  description: 'A review card reachable without a pointer',
  status: 'REVIEW',
  priority: 'HIGH',
  assignee: 'dev-agent',
  labels: null,
  linkedRunId: 'run-kb',
  linkedAgentId: null,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:10:00Z',
  runOutcome: 'COMPLETED',
};

const RUN = {
  id: 'run-kb',
  agentId: 'a-1',
  status: 'COMPLETED',
  promptSeed: 'Keyboard card',
  maxIterations: 10,
  totalTokensUsed: 0,
  iterationCount: 1,
  errorMessage: null,
  finalOutput: 'done',
  createdAt: '2026-10-27T08:00:00Z',
  completedAt: '2026-10-27T08:10:00Z',
};

async function stubWebSocket(page: Page) {
  await page.routeWebSocket(/\/ws\/events/, (ws) => {
    ws.onMessage((message) => {
      const frame = typeof message === 'string' ? message : message.toString();
      if (frame.startsWith('CONNECT')) ws.send('CONNECTED\nversion:1.2\n\n\0');
    });
  });
}

async function stubApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const { pathname } = new URL(route.request().url());
    const method = route.request().method();
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (method === 'POST' && pathname.endsWith('/transition')) return json({ ...CARD, status: 'TODO' });
    if (pathname === '/api/v1/approvals') return json([]);
    if (pathname === '/api/v1/kanban/items') return json([CARD]);
    if (pathname.startsWith('/api/v1/kanban/items/')) return json(CARD);
    if (pathname === '/api/v1/runs') return json([RUN]);
    if (pathname.startsWith('/api/v1/runs/')) {
      return pathname.endsWith('/trajectory') ? json([]) : json(RUN);
    }
    if (pathname === '/api/v1/agents') return json([]);
    if (pathname === '/api/v1/adk/providers') return json([]);
    if (pathname === '/api/v1/dashboard/summary') {
      return json({ activeAgents: 0, runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0 });
    }
    if (pathname.startsWith('/api/v1/dashboard/')) return json([]);
    if (pathname === '/api/v1/aria/notifications/count') return json({ unreadCount: 0 });
    if (pathname.startsWith('/api/v1/aria/')) return json([]);
    return json([]);
  });
}

/** Tab from the document start until `target` owns focus. */
async function tabUntilFocused(page: Page, target: ReturnType<Page['locator']>, max = 80) {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  for (let i = 0; i < max; i += 1) {
    await page.keyboard.press('Tab');
    if (await target.evaluate((el) => el === document.activeElement)) return;
  }
  throw new Error(`Tab never reached ${await target.getAttribute('aria-label')}`);
}

test.use({ viewport: { width: 1440, height: 900 } });

test.describe('kanban keyboard contract', () => {
  test('Tab reaches the named opener; Enter opens; Escape from the drawer closes and returns focus', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    await page.goto('/');

    const opener = page.getByRole('button', { name: 'Open task Keyboard card' });
    await expect(opener).toBeVisible();
    await tabUntilFocused(page, opener);
    await expect(opener).toBeFocused();

    await page.keyboard.press('Enter');
    const drawer = page.locator('.drawer.open');
    await expect(drawer).toBeVisible();
    const heading = drawer.getByRole('heading', { name: 'Keyboard card' });
    await expect(heading).toBeVisible();
    // Opening moves focus to the heading, so Escape is pressed from inside.
    await expect(heading).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(page.locator('.drawer.open')).toHaveCount(0);
    await expect(opener).toBeFocused();
  });

  test('Escape from the comment draft keeps the drawer open and keeps the text', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    await page.goto('/');

    await page.getByRole('button', { name: 'Open task Keyboard card' }).click();
    const drawer = page.locator('.drawer.open');
    await expect(drawer).toBeVisible();

    const note = drawer.getByPlaceholder(/Add a comment/);
    await note.click();
    await note.fill('keep this draft');
    await page.keyboard.press('Escape');

    await expect(drawer).toBeVisible();
    await expect(note).toHaveValue('keep this draft');
  });

  test('a destructive dialog is cancel-first, traps Tab and never mutates on Enter', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    const transitions: string[] = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/transition')) transitions.push(req.url());
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Open task Keyboard card' }).click();
    await page.locator('.drawer.open').getByRole('button', { name: 'Reject', exact: true }).click();

    const dialog = page.getByRole('dialog', { name: 'Reject task — confirmation required' });
    await expect(dialog).toBeVisible();
    const cancel = dialog.getByRole('button', { name: 'Cancel' });
    const confirm = dialog.getByRole('button', { name: 'Confirm' });
    await expect(cancel).toBeFocused();

    // Enter on the focused Cancel dismisses and mutates nothing.
    await page.keyboard.press('Enter');
    await expect(dialog).toHaveCount(0);
    expect(transitions).toHaveLength(0);

    // Reopen: Tab cycles Cancel -> Confirm -> Cancel, Shift+Tab reverses.
    await page.locator('.drawer.open').getByRole('button', { name: 'Reject', exact: true }).click();
    await expect(cancel).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(confirm).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(cancel).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(confirm).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(cancel).toBeFocused();

    // Escape dismisses only the dialog and returns focus to the Reject trigger.
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(
      page.locator('.drawer.open').getByRole('button', { name: 'Reject', exact: true }),
    ).toBeFocused();
    expect(transitions).toHaveLength(0);
  });

  test('dragging the card body still starts a native drag and moves the card', async ({ page }) => {
    // The opener covers the card body; if it refused to be a drag source the
    // only draggable pixels left would be the card padding.
    await stubWebSocket(page);
    await stubApi(page);
    const transitions: string[] = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/transition')) transitions.push(req.url());
    });
    await page.goto('/');

    const card = page.locator('[data-card="k-keyboard"]');
    await expect(card).toBeVisible();

    // The title text is the natural place to grab a card. Its opener must be a
    // drag source itself: a button with draggable={false} swallows the native
    // drag while Playwright's synthetic dragTo still succeeds.
    const hit = await card.evaluate((el) => {
      const title = el.querySelector('.t') as HTMLElement;
      const r = title.getBoundingClientRect();
      const target = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      const opener = target?.closest('.card-open');
      return {
        tag: target?.tagName ?? null,
        openerDraggable: opener instanceof HTMLElement ? opener.draggable : null,
      };
    });
    expect(hit.openerDraggable, JSON.stringify(hit)).toBe(true);

    await card.dragTo(page.getByTestId('lane-TODO'));

    await expect.poll(() => transitions.length).toBeGreaterThan(0);
    expect(transitions[0]).toContain('/kanban/items/k-keyboard/transition');
  });

  test('quick deny stays a single button that moves the card without a dialog', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    const transitions: string[] = [];
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/transition')) transitions.push(req.url());
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Quick deny' }).click();

    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect.poll(() => transitions.length).toBe(1);
  });
});
