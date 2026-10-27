import { test, expect, type Page } from '@playwright/test';

/**
 * Task 13: 390x844 mobile flows with route fixtures only. The assertions cover
 * the state-driven pane switch; the width/overflow checks are the part jsdom
 * cannot verify.
 */

const SUMMARY = {
  activeAgents: 1,
  runningRuns: 0,
  pendingApprovals: 0,
  totalTokensBurned: 0,
};

const AGENT = {
  id: 'a-1',
  name: 'Aria',
  role: 'assistant',
  agentType: 'ADK',
  healthStatus: 'HEALTHY',
  description: '',
  model: '',
  provider: 'opencode',
  createdAt: '2026-01-01T00:00:00Z',
};

const run = (id: string) => ({
  id,
  agentId: 'a-1',
  status: 'COMPLETED',
  promptSeed: '',
  maxIterations: 10,
  iterationCount: 1,
  totalTokensUsed: 0,
  errorMessage: null,
  finalOutput: null,
  createdAt: '2026-10-27T08:00:00Z',
  completedAt: '2026-10-27T08:10:00Z',
  conversationId: null,
});

const TODO_CARD = {
  id: 'k-todo',
  title: 'Todo card',
  description: '',
  status: 'TODO',
  priority: 'MEDIUM',
  assignee: null,
  labels: null,
  linkedRunId: null,
  linkedAgentId: null,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:10:00Z',
};

const REVIEW_CARD = {
  id: 'k-review',
  title: 'Review card',
  description: '',
  status: 'REVIEW',
  priority: 'HIGH',
  assignee: null,
  labels: null,
  linkedRunId: null,
  linkedAgentId: 'a-1',
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:10:00Z',
  runOutcome: 'COMPLETED',
};

interface Fixtures {
  approvals?: unknown;
  kanban?: unknown;
  runs?: unknown;
  agents?: unknown;
}

async function stubWebSocket(page: Page) {
  await page.routeWebSocket(/\/ws\/events/, (ws) => {
    ws.onMessage((message) => {
      const frame = typeof message === 'string' ? message : message.toString();
      if (frame.startsWith('CONNECT')) ws.send('CONNECTED\nversion:1.2\n\n\0');
    });
  });
}

async function stubApi(page: Page, fx: Fixtures = {}) {
  const approvals = fx.approvals ?? [];
  const kanban = fx.kanban ?? [];
  const runs = fx.runs ?? [];
  const agents = fx.agents ?? [AGENT];

  await page.route('**/api/v1/**', async (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (pathname === '/api/v1/approvals') return json(approvals);
    if (pathname === '/api/v1/kanban/items') return json(kanban);
    if (pathname.startsWith('/api/v1/kanban/items/')) {
      const id = decodeURIComponent(pathname.split('/').pop() ?? '');
      const card = (kanban as Array<{ id: string }>).find((c) => c.id === id);
      return card ? json(card) : json({ message: 'not found' }, 404);
    }
    if (pathname === '/api/v1/runs') return json(runs);
    if (pathname.startsWith('/api/v1/runs/')) {
      if (pathname.endsWith('/trajectory')) return json([]);
      if (pathname.endsWith('/tool-calls') || pathname.endsWith('/progress')) return json([]);
      const id = pathname.split('/')[4];
      return json(runs.find((r: { id: string }) => r.id === id) ?? run(id));
    }
    if (pathname === '/api/v1/agents') return json(agents);
    if (pathname === '/api/v1/adk/providers') return json([]);
    if (pathname === '/api/v1/dashboard/summary') return json(SUMMARY);
    if (pathname.startsWith('/api/v1/dashboard/')) return json([]);
    if (pathname === '/api/v1/aria/notifications/count') return json({ unreadCount: 0 });
    if (pathname.startsWith('/api/v1/aria/')) return json([]);
    return json([]);
  });
}

async function noHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
    innerWidth: window.innerWidth,
  }));
  expect(overflow.scrollWidth, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.clientWidth);
}

test.use({ viewport: { width: 390, height: 844 } });

test.describe('mobile flows', () => {
  test('overview fits the viewport, keeps decisions above the board and exposes New Item', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, {
      approvals: [
        {
          id: 'a-1',
          runId: 'run-1',
          kanbanItemId: null,
          status: 'PENDING',
          reason: 'Run gate',
          content: 'Approve the orphan run',
          requestedAt: '2026-10-27T08:00:00Z',
          decidedAt: null,
          expiresAt: null,
          askType: 'APPROVAL',
        },
      ],
      kanban: [TODO_CARD, REVIEW_CARD],
    });
    await page.goto('/');

    await expect(page.getByTestId('decision-queue')).toBeVisible();
    const queueBox = await page.getByTestId('decision-queue').boundingBox();
    const boardHeading = await page.getByText('Kanban Board', { exact: true }).boundingBox();
    expect(queueBox!.y).toBeLessThan(boardHeading!.y);

    const newItem = page.getByRole('button', { name: '+ New Item' });
    await expect(newItem).toBeVisible();
    await newItem.click();
    await expect(page.getByRole('dialog')).toContainText(/New Task/i);

    await noHorizontalOverflow(page);
  });

  test('the lane selector switches the visible mobile list and cards keep named openers', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, { kanban: [TODO_CARD, REVIEW_CARD] });
    await page.goto('/');

    const laneSelect = page.getByLabel('Select lane');
    await expect(laneSelect).toBeVisible();

    // Default lane is TODO on mobile.
    await expect(page.locator('.kanban-mobile-list')).toContainText('Todo card');
    await expect(page.locator('.kanban-mobile-list')).not.toContainText('Review card');

    await laneSelect.selectOption('REVIEW');
    await expect(page.locator('.kanban-mobile-list')).toContainText('Review card');
    await expect(page.locator('.kanban-mobile-list')).not.toContainText('Todo card');

    await laneSelect.selectOption('TODO');
    await expect(page.getByRole('button', { name: 'Open task Todo card' })).toBeVisible();
    await noHorizontalOverflow(page);
  });

  test('chat shows the list first, opens a full-width transcript and Back preserves the row', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, { runs: [run('1111aaaa-0000'), run('2222bbbb-0000')] });
    await page.goto('/');
    await page.getByRole('link', { name: 'Chat' }).click();

    const shell = page.getByTestId('chat-shell');
    await expect(shell).toHaveAttribute('data-mobile-pane', 'list');
    await expect(page.getByRole('heading', { name: 'Run conversations' })).toBeVisible();

    await page.getByRole('button', { name: /run 2222/i }).click();
    await expect(shell).toHaveAttribute('data-mobile-pane', 'thread');

    // The transcript must use the viewport, not the old 320px list track.
    const transcriptBox = await page.locator('.chat-thread-panel').boundingBox();
    expect(transcriptBox).not.toBeNull();
    expect(transcriptBox!.width).toBeGreaterThan(300);
    await noHorizontalOverflow(page);

    await page.getByRole('button', { name: 'Back to conversations' }).click();
    await expect(shell).toHaveAttribute('data-mobile-pane', 'list');
    await expect(page.locator('.chat-ch.active')).toContainText('2222');

    // Reopening resumes the same thread.
    await page.getByRole('button', { name: /run 2222/i }).click();
    await expect(shell).toHaveAttribute('data-mobile-pane', 'thread');
    await expect(page.locator('.chat-thread-panel .h-ttl')).toContainText('2222');
    await noHorizontalOverflow(page);
  });

  test('ops stacks to a single column and fits the viewport', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, {});
    await page.goto('/');
    await page.getByRole('link', { name: 'Ops' }).click();

    const grid = page.locator('.ops-grid-2');
    await expect(grid).toBeVisible();
    const columns = await grid.evaluate((el) => getComputedStyle(el).gridTemplateColumns);
    expect(columns.trim().split(/\s+/)).toHaveLength(1);

    // Wide tables must scroll inside their own box, never widen the document.
    await noHorizontalOverflow(page);
  });
});
