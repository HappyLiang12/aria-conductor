import { test, expect, type Page } from '@playwright/test';

/**
 * Phase 1 acceptance (S1, S4, S5, and the in-thread approval gate).
 *
 * Everything here runs against the real backend for the shell/theme/contrast
 * checks. The approval case is the one exception: the SSE turn is stubbed (the
 * backend's governed cores need a model/container, which CI does not have), so
 * the assertion covers the refreshed Aria page's own mapping of an
 * `approval_required` frame — the card must render inside the assistant-ui
 * thread and Allow must POST the decision to the frame's ask with
 * `{ approved: true }`. The request is answered by a stub, so this proves the
 * request shape and the card's receipt, not that a live backend accepted it.
 */

const ROUTES = [
  '/',
  '/crew',
  '/providers',
  '/knowledge',
  '/memory',
  '/reports',
  '/aria',
  '/chat',
  '/workflows',
  '/ops',
  '/scheduled-jobs',
  '/runs',
];

/** The stubbed conversation the approval case opens (no live turn needed). */
const CONVERSATION = {
  conversationId: 'conv-ui-refresh',
  title: 'Ship the refresh',
  lastMessageAt: '2026-11-24T10:00:00Z',
  runCount: 1,
};

async function stubAriaConversations(page: Page) {
  await page.route(/\/api\/v1\/aria\/conversations\/latest$/, (route) =>
    route.fulfill({ json: CONVERSATION }));
  await page.route(/\/api\/v1\/aria\/conversations\/(?!latest$)[^/?]+$/, (route) =>
    route.fulfill({ json: [] }));
  await page.route(/\/api\/v1\/aria\/conversations(\?.*)?$/, (route) =>
    route.fulfill({ json: [CONVERSATION] }));
  await page.route(/\/api\/v1\/runs\/[^/]+\/progress(\?.*)?$/, (route) =>
    route.fulfill({ json: [] }));
}

test.describe('UI refresh acceptance', () => {
  test('every route renders in the new shell without console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(`${page.url()} :: ${message.text()}`);
    });
    page.on('pageerror', (error) => errors.push(`${page.url()} :: ${error.message}`));

    for (const route of ROUTES) {
      await page.goto(route);
      // The shell is the rail; a route that fails to mount leaves no rail links.
      await expect(page.locator('.rail-btn[data-view]')).not.toHaveCount(0);
      // Every route is reachable from the shell and keeps its URL (no redirect
      // back to the Overview, and none of the routes is the not-found page).
      expect(new URL(page.url()).pathname).toBe(route);
    }

    expect(errors).toEqual([]);
  });

  test('sidebar groups and theme toggle work', async ({ page }) => {
    await page.goto('/');

    for (const group of ['Operate', 'Build', 'Chat', 'System']) {
      await expect(page.getByText(group, { exact: true }).first()).toBeVisible();
    }
    await expect(page.getByRole('link', { name: 'Runs' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');

    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await expect(page.locator('body')).toHaveClass(/light/);
    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await expect(page.locator('body')).not.toHaveClass(/light/);
  });

  test('body text and rail controls meet AA in both themes', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('main')).toBeVisible();
    // `.rail-btn` carries a 150 ms colour transition; measuring mid-fade would
    // compare the outgoing theme's colour against the incoming surface. The
    // audit is of the settled theme, so transitions are removed for the page.
    await page.addStyleTag({ content: '* { transition: none !important; }' });

    const darkBody = await contrast(page, 'main p');
    const darkControl = await contrast(page, 'a.rail-btn[data-view="runs"]');
    console.log(`contrast dark: body=${darkBody.toFixed(2)} rail=${darkControl.toFixed(2)}`);
    expect(darkBody, 'dark body text').toBeGreaterThanOrEqual(4.5);
    expect(darkControl, 'dark rail control').toBeGreaterThanOrEqual(4.5);

    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await expect(page.locator('body')).toHaveClass(/light/);
    const lightBody = await contrast(page, 'main p');
    const lightControl = await contrast(page, 'a.rail-btn[data-view="runs"]');
    console.log(`contrast light: body=${lightBody.toFixed(2)} rail=${lightControl.toFixed(2)}`);
    expect(lightBody, 'light body text').toBeGreaterThanOrEqual(4.5);
    expect(lightControl, 'light rail control').toBeGreaterThanOrEqual(4.5);
  });

  test('an approval_required frame renders a card in the thread and Allow decides', async ({ page }) => {
    await stubAriaConversations(page);

    const decisions: Array<{ method: string; pathname: string; body: unknown }> = [];
    await page.route('**/api/v1/approvals/apr-ui-refresh/decide', async (route) => {
      const request = route.request();
      decisions.push({
        method: request.method(),
        pathname: new URL(request.url()).pathname,
        body: request.postDataJSON(),
      });
      await route.fulfill({ json: { approvalId: 'apr-ui-refresh', approved: true, status: 'processed' } });
    });

    const body = [
      'event: thinking',
      'data: {"status":"processing","runId":"run-ui-refresh","mode":"core"}',
      '',
      'event: approval_required',
      'data: {"runId":"run-ui-refresh","approvalId":"apr-ui-refresh","toolName":"write_file","riskTier":"REVIEW","toolPolicy":"WORKER_WRITE"}',
      '',
      'event: message',
      'data: {"content":"Waiting on your decision."}',
      '',
      'event: done',
      'data: {"runId":"run-ui-refresh","conversationId":"conv-ui-refresh","intent":"write"}',
      '',
      '',
    ].join('\n');
    await page.route('**/api/v1/aria/chat/stream', (route) =>
      route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body,
      }));

    await page.goto('/aria');
    await page.getByRole('textbox').fill('Write the release notes to RELEASE.md');
    await page.getByRole('button', { name: 'Send message' }).click();

    // The card is a standalone part of the assistant-ui thread, not a panel
    // overlay and not a tool disclosure the operator has to expand.
    const card = page.locator('.mc-stack').getByText('Approve write_file?');
    await expect(card).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.mc-stack').getByText('WORKER_WRITE')).toBeVisible();

    await page.locator('.mc-stack').getByRole('button', { name: 'Allow' }).click();

    // Allow posted the decision to the frame's own ask. The ask id travels in the
    // URL; the decision payload is the `{ approved }` the endpoint accepts.
    await expect.poll(() => decisions.length).toBe(1);
    expect(decisions[0].method).toBe('POST');
    expect(decisions[0].pathname).toBe('/api/v1/approvals/apr-ui-refresh/decide');
    expect(decisions[0].body).toEqual({ approved: true });
    await expect(page.locator('.mc-stack').getByText('Allowed · write_file')).toBeVisible();
  });
});

/**
 * WCAG 2.1 relative-luminance contrast of the first match, against the
 * background actually painted behind it.
 *
 * The brief's helper falls back to `body`'s `backgroundColor`; both themes paint
 * the page with a gradient on `body`, so that value is `rgba(0, 0, 0, 0)` and the
 * fallback silently compares against black. This walks the ancestor chain to the
 * nearest ancestor with a non-transparent background colour and finally the
 * document element (which carries the solid theme base colour), so the measured
 * pair is the one the operator sees.
 */
async function contrast(page: Page, selector: string): Promise<number> {
  return page.locator(selector).first().evaluate((el) => {
    interface Rgba { rgb: [number, number, number]; alpha: number }
    const parse = (value: string): Rgba | null => {
      const numbers = value.match(/[\d.]+/g)?.map(Number) ?? [];
      if (numbers.length < 3) return null;
      return {
        rgb: [numbers[0], numbers[1], numbers[2]],
        alpha: numbers.length > 3 ? numbers[3] : 1,
      };
    };
    const lum = (rgb: [number, number, number]) => {
      const channels = rgb.map((value) => {
        const s = value / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    };

    const foreground = parse(getComputedStyle(el).color);
    if (!foreground) throw new Error(`unreadable colour on ${el.tagName}`);

    const solid = (node: Element): Rgba | null => {
      const painted = parse(getComputedStyle(node).backgroundColor);
      return painted && painted.alpha > 0 ? painted : null;
    };
    let background: Rgba | null = null;
    for (let node: Element | null = el; node; node = node.parentElement) {
      background = solid(node);
      if (background) break;
    }
    if (!background) background = parse(getComputedStyle(document.documentElement).backgroundColor);
    if (!background) throw new Error('no painted background found');

    const fg = lum(foreground.rgb);
    const bg = lum(background.rgb);
    return (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05);
  });
}
