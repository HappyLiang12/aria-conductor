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
 *
 * The stop case is stubbed the same way: the stream is held open and never
 * fulfilled, so Stop's abort is observed in the browser. It is evidence of the
 * client's cancel path, not of a live backend stopping a run.
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

/** The ask the cards read on mount; the SSE frame names the same id. */
const APPROVAL_DETAIL = {
  id: 'apr-ui-refresh',
  runId: 'run-ui-refresh',
  toolCallId: null,
  status: 'PENDING',
  reason: 'write_file',
  requestedAt: '2026-11-24T10:00:00Z',
  decidedAt: null,
  expiresAt: null,
  toolName: 'write_file',
  arguments: '{"path":"RELEASE.md","content":"# 0.1.0"}',
  riskTier: 'REVIEW',
  toolPolicy: 'WORKER_WRITE',
};

async function stubApprovalDetail(page: Page) {
  await page.route('**/api/v1/approvals/apr-ui-refresh', (route) =>
    route.fulfill({ json: APPROVAL_DETAIL }));
}

/** The one-turn SSE script every approval case replays. */
const APPROVAL_TURN = [
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

async function sendApprovalTurn(page: Page) {
  await page.route('**/api/v1/aria/chat/stream', (route) =>
    route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      body: APPROVAL_TURN,
    }));
  await page.goto('/aria');
  await page.getByRole('textbox').fill('Write the release notes to RELEASE.md');
  await page.getByRole('button', { name: 'Send message' }).click();
  await expect(page.locator('.mc-stack').getByText('Approve write_file?')).toBeVisible({ timeout: 15_000 });
}

/** A completed turn whose assistant message carries a markdown link. */
const LINK_TURN = [
  'event: thinking',
  'data: {"status":"processing","runId":"run-ui-refresh","mode":"core"}',
  '',
  'event: message',
  'data: {"content":"Read [the runbook](https://example.com/runbook) before you decide."}',
  '',
  'event: done',
  'data: {"runId":"run-ui-refresh","conversationId":"conv-ui-refresh","intent":"answer"}',
  '',
  '',
].join('\n');

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

  test('a failed turn shows the human reason and run id, never [object Object]', async ({ page }) => {
    await stubAriaConversations(page);
    const body = [
      'event: thinking',
      'data: {"status":"processing","runId":"run-ui-refresh","mode":"core"}',
      '',
      'event: error',
      'data: {"message":"The model declined the request","turnFailed":true,"runId":"run-ui-refresh","reason":"policy refusal"}',
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
    await page.getByRole('textbox').fill('Draft the policy summary');
    await page.getByRole('button', { name: 'Send message' }).click();

    const errorBox = page.locator('.aui-message-error-root').first();
    await expect(errorBox).toContainText('The model declined the request', { timeout: 15_000 });
    await expect(errorBox).toContainText('run-ui-refresh');
    await expect(page.locator('.mc-stack')).not.toContainText('[object Object]');
  });

  test('an approval_required frame renders a card in the thread and Allow decides', async ({ page }) => {
    await stubAriaConversations(page);
    await stubApprovalDetail(page);

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

    await sendApprovalTurn(page);

    // The card is a standalone part of the assistant-ui thread, not a panel
    // overlay and not a tool disclosure the operator has to expand. It reads the
    // ask it names, so the redacted arguments and policy are on screen.
    const card = page.locator('.mc-stack').getByText('Approve write_file?');
    await expect(card).toBeVisible();
    await expect(page.locator('.mc-stack').getByText('WORKER_WRITE')).toBeVisible();
    await expect(page.locator('.mc-stack')).toContainText('path: RELEASE.md');
    await expect(page.locator('.mc-stack')).toContainText('content: # 0.1.0');

    await page.locator('.mc-stack').getByRole('button', { name: 'Allow' }).click();

    // Allow posted the decision to the frame's own ask. The ask id travels in the
    // URL; the decision payload is the `{ approved }` the endpoint accepts.
    await expect.poll(() => decisions.length).toBe(1);
    expect(decisions[0].method).toBe('POST');
    expect(decisions[0].pathname).toBe('/api/v1/approvals/apr-ui-refresh/decide');
    expect(decisions[0].body).toEqual({ approved: true });
    await expect(page.locator('.mc-stack').getByText('Allowed · write_file')).toBeVisible();
  });

  test('a refused decision names its error, and that text meets AA in both themes', async ({ page }) => {
    await stubAriaConversations(page);
    await stubApprovalDetail(page);
    await page.route('**/api/v1/approvals/apr-ui-refresh/decide', (route) =>
      route.fulfill({
        status: 500,
        json: { message: 'The decision store is unavailable' },
      }));

    await sendApprovalTurn(page);
    await page.locator('.mc-stack').getByRole('button', { name: 'Allow' }).click();

    const alert = page.locator('.mc-stack [role="alert"]');
    await expect(alert).toContainText('The decision store is unavailable');
    // The error text is `text-destructive`; the old 71%-lightness red measured
    // 2.75:1 on white, so both themes are measured on the card background.
    await page.addStyleTag({ content: '* { transition: none !important; }' });
    const darkError = await contrast(page, '.mc-stack [role="alert"]');
    console.log(`contrast dark decision error: ${darkError.toFixed(2)}`);
    expect(darkError, 'dark decision error').toBeGreaterThanOrEqual(4.5);

    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await expect(page.locator('body')).toHaveClass(/light/);
    const lightError = await contrast(page, '.mc-stack [role="alert"]');
    console.log(`contrast light decision error: ${lightError.toFixed(2)}`);
    expect(lightError, 'light decision error').toBeGreaterThanOrEqual(4.5);
  });

  test('markdown links meet AA at rest and on hover in both themes', async ({ page }) => {
    await stubAriaConversations(page);
    await page.route('**/api/v1/aria/chat/stream', (route) =>
      route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: LINK_TURN,
      }));

    await page.goto('/aria');
    await page.getByRole('textbox').fill('Show me the runbook');
    await page.getByRole('button', { name: 'Send message' }).click();

    const link = page.locator('.mc-stack .aui-md-a').first();
    await expect(link).toBeVisible({ timeout: 15_000 });
    await page.addStyleTag({ content: '* { transition: none !important; }' });

    // Resting colour, pointer away from the link, then the hovered colour. The
    // earlier `hover:text-link/80` measured ~4.3:1 on white; both states must
    // now clear AA.
    await page.mouse.move(0, 0);
    const darkRest = await contrast(page, '.mc-stack .aui-md-a');
    await link.hover();
    const darkHover = await contrast(page, '.mc-stack .aui-md-a');
    console.log(`contrast dark link: rest=${darkRest.toFixed(2)} hover=${darkHover.toFixed(2)}`);
    expect(darkRest, 'dark link at rest').toBeGreaterThanOrEqual(4.5);
    expect(darkHover, 'dark link on hover').toBeGreaterThanOrEqual(4.5);

    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await expect(page.locator('body')).toHaveClass(/light/);
    await page.mouse.move(0, 0);
    const lightRest = await contrast(page, '.mc-stack .aui-md-a');
    await link.hover();
    const lightHover = await contrast(page, '.mc-stack .aui-md-a');
    console.log(`contrast light link: rest=${lightRest.toFixed(2)} hover=${lightHover.toFixed(2)}`);
    expect(lightRest, 'light link at rest').toBeGreaterThanOrEqual(4.5);
    expect(lightHover, 'light link on hover').toBeGreaterThanOrEqual(4.5);
  });

  test('the Overview does not overflow horizontally at 390/1024/1400', async ({ page }) => {
    // W4: the refreshed queue/board/summary surfaces must fit the three review
    // widths. `documentElement.scrollWidth` is the whole page because the shell
    // rail and the Overview grid share the viewport.
    for (const width of [390, 1024, 1400]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/');
      await expect(page.getByTestId('decision-queue')).toBeVisible();
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, `horizontal overflow at ${width}px`).toBeLessThanOrEqual(1);
    }
  });

  test('stopping an in-flight turn aborts the SSE fetch (stubbed path)', async ({ page }) => {
    await stubAriaConversations(page);
    // The stream request is intercepted but never fulfilled, so the turn stays
    // in flight exactly as a slow core would leave it. Stop's abort is then the
    // only way the request can leave the browser.
    const held: import('@playwright/test').Route[] = [];
    await page.route('**/api/v1/aria/chat/stream', (route) => {
      held.push(route);
    });

    try {
      await page.goto('/aria');
      await page.getByRole('textbox').fill('Draft an endless answer');
      const failed = page.waitForEvent('requestfailed', {
        predicate: (request) => request.url().includes('/api/v1/aria/chat/stream'),
        timeout: 15_000,
      });
      await page.getByRole('button', { name: 'Send message' }).click();

      const stop = page.getByRole('button', { name: 'Stop the turn' });
      await expect(stop).toBeVisible({ timeout: 15_000 });
      await stop.click();

      const failure = await failed;
      expect(failure.failure()?.errorText ?? '').toContain('ERR_ABORTED');
      // The composer returns to Send: the aborted turn released the runtime.
      await expect(page.getByRole('button', { name: 'Send message' })).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole('button', { name: 'Stop the turn' })).toHaveCount(0);
    } finally {
      // Release a still-held route so a failed assertion cannot hang the suite.
      for (const route of held) {
        try {
          await route.abort();
        } catch {
          // The page already aborted the request.
        }
      }
      await page.unroute('**/api/v1/aria/chat/stream');
    }
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
