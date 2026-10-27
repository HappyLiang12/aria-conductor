import { test, expect, type Page } from '@playwright/test';

/**
 * Task 13: decision-first Overview at 1440x900 with route fixtures only.
 * No backend, no LLM, no credentials: every /api/v1 response is served here.
 */

const SUMMARY = {
  activeAgents: 1,
  runningRuns: 0,
  pendingApprovals: 0,
  totalTokensBurned: 0,
};

const REVIEW_CARD = {
  id: 'k-review',
  title: 'Ship the export',
  description: '',
  status: 'REVIEW',
  priority: 'HIGH',
  assignee: null,
  labels: null,
  linkedRunId: 'run-linked',
  linkedAgentId: null,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:10:00Z',
  pendingAskCount: 2,
  runOutcome: 'COMPLETED',
};

const TODO_CARD = {
  id: 'k-todo',
  title: 'Retry the pickup',
  description: '',
  status: 'TODO',
  priority: 'MEDIUM',
  assignee: null,
  labels: null,
  linkedRunId: null,
  linkedAgentId: null,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:10:00Z',
  lastError: 'dispatch failed: no eligible agent',
};

const CARD_APPROVAL = {
  id: 'a-card',
  runId: 'run-linked',
  kanbanItemId: 'k-review',
  status: 'PENDING',
  reason: 'Task-level approval',
  content: 'Approve the export',
  requestedAt: '2026-10-27T08:01:00Z',
  decidedAt: null,
  expiresAt: null,
  askType: 'APPROVAL',
};

const ORPHAN_APPROVAL = {
  id: 'a-orphan',
  runId: 'run-orphan-1234',
  kanbanItemId: null,
  status: 'PENDING',
  reason: 'Run gate',
  content: 'Approve the orphan run',
  requestedAt: '2026-10-27T08:02:00Z',
  decidedAt: null,
  expiresAt: null,
  askType: 'APPROVAL',
};

const PERMISSION_APPROVAL = {
  id: 'a-perm',
  runId: 'run-linked',
  kanbanItemId: 'k-review',
  status: 'PENDING',
  reason: 'Native permission request req-42 from session sess-7 for tool write_file (NATIVE_TOOL)',
  content: 'Allow write_file once',
  requestedAt: '2026-10-27T08:03:00Z',
  decidedAt: null,
  expiresAt: '2026-10-27T09:00:00Z',
  askType: 'APPROVAL',
};

const RUN = {
  id: 'run-linked',
  agentId: 'a-1',
  status: 'COMPLETED',
  promptSeed: 'Ship the export',
  maxIterations: 10,
  totalTokensUsed: 0,
  iterationCount: 1,
  errorMessage: null,
  finalOutput: 'done',
  createdAt: '2026-10-27T08:00:00Z',
  completedAt: '2026-10-27T08:10:00Z',
};

interface Fixtures {
  approvals?: unknown;
  kanban?: unknown;
  summary?: unknown;
  runs?: unknown;
  agents?: unknown;
  /** Return an HTTP status instead of the approvals payload. */
  approvalsStatus?: number;
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
  const agents = fx.agents ?? [];

  await page.route('**/api/v1/**', async (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (pathname === '/api/v1/approvals') {
      return json(approvals, fx.approvalsStatus ?? 200);
    }
    if (pathname === '/api/v1/kanban/items') return json(kanban);
    if (pathname.startsWith('/api/v1/kanban/items/')) {
      const id = decodeURIComponent(pathname.split('/').pop() ?? '');
      const card = (kanban as Array<{ id: string }>).find((c) => c.id === id);
      return card ? json(card) : json({ message: 'not found' }, 404);
    }
    if (pathname === '/api/v1/runs') return json(fx.runs ?? []);
    if (pathname.startsWith('/api/v1/runs/')) {
      if (pathname.endsWith('/trajectory')) return json([]);
      return json(RUN);
    }
    if (pathname === '/api/v1/agents') return json(agents);
    if (pathname === '/api/v1/adk/providers') return json([]);
    if (pathname === '/api/v1/dashboard/summary') return json(fx.summary ?? SUMMARY);
    if (pathname.startsWith('/api/v1/dashboard/')) return json([]);
    if (pathname === '/api/v1/aria/notifications/count') return json({ unreadCount: 0 });
    if (pathname.startsWith('/api/v1/aria/')) return json([]);
    return json([]);
  });
}

test.use({ viewport: { width: 1440, height: 900 } });

test.describe('decision-first overview', () => {
  test('puts decision rows above the board and counts approvals, not asks', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, {
      approvals: [CARD_APPROVAL, ORPHAN_APPROVAL, PERMISSION_APPROVAL],
      kanban: [REVIEW_CARD, TODO_CARD],
    });
    await page.goto('/');

    const queue = page.getByTestId('decision-queue');
    await expect(queue).toBeVisible();
    await expect(page.locator('.decision-queue__count')).toHaveText('3');
    await expect(page.locator('.decision-row')).toHaveCount(3);

    // The board claims 2 pending asks on one card; the queue counts approvals.
    const queueBox = await queue.boundingBox();
    const boardHeading = await page.getByText('Kanban Board', { exact: true }).boundingBox();
    expect(queueBox).not.toBeNull();
    expect(boardHeading).not.toBeNull();
    expect(queueBox!.y).toBeLessThan(boardHeading!.y);

    // The unlinked approval stays actionable in place.
    const orphanRow = page.getByTestId('decision-row-a-orphan');
    await expect(orphanRow).toContainText('Run run-orph');
    await expect(orphanRow.getByRole('button', { name: 'Approve', exact: true })).toBeVisible();
    await expect(orphanRow.getByRole('button', { name: 'Deny', exact: true })).toBeVisible();

    // A native permission ask is decided by review, never a batch control.
    const permissionRow = page.getByTestId('decision-row-a-perm');
    await expect(permissionRow.getByRole('button', { name: 'Review permission' })).toBeVisible();
    await expect(permissionRow.getByRole('button', { name: /approve all/i })).toHaveCount(0);

    // The main lane must own the decision-first template, not inherit the old
    // global three-column .layout (260px board column + an empty 340px one).
    const mainBox = await page.locator('.overview-main').boundingBox();
    expect(mainBox).not.toBeNull();
    expect(mainBox!.width).toBeGreaterThan(700);
  });

  test('Review opens the in-flow workspace for a task-backed row', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, {
      approvals: [CARD_APPROVAL],
      kanban: [REVIEW_CARD],
    });
    await page.goto('/');

    await page.getByTestId('decision-row-a-card').getByRole('button', { name: 'Review' }).click();

    const workspace = page.getByTestId('review-workspace');
    await expect(workspace).toBeVisible();
    await expect(workspace.getByRole('button', { name: 'Collapse review' })).toBeVisible();
  });

  test('Answer opens the task drawer for a task-backed TODO row', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, {
      approvals: [{ ...CARD_APPROVAL, id: 'a-todo', kanbanItemId: 'k-todo', runId: 'run-linked', content: 'Answer the pickup question' }],
      kanban: [TODO_CARD],
    });
    await page.goto('/');

    await page.getByTestId('decision-row-a-todo').getByRole('button', { name: 'Answer' }).click();

    const drawer = page.locator('.drawer.open');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('heading', { name: 'Retry the pickup' })).toBeVisible();
  });

  test('a failed approvals route shows an error, never a false all-clear', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, {
      approvalsStatus: 500,
      approvals: { message: 'boom' },
      kanban: [],
    });
    await page.goto('/');

    await expect(page.getByText('Decision queue unavailable')).toBeVisible();
    await expect(page.getByText('The pending approvals could not be loaded.')).toBeVisible();
    await expect(page.locator('.decision-queue')).not.toContainText('No decisions waiting');
  });

  test('a stale refetch keeps the rows and shows the refresh-failed notice', async ({ page }) => {
    await stubWebSocket(page);
    let failApprovals = false;
    const approvals = [CARD_APPROVAL];
    await stubApi(page, { approvals, kanban: [REVIEW_CARD] });

    // Override after install: the 10s poll then fails once, keeping cached rows.
    await page.route(/\/api\/v1\/approvals(\?|$)/, async (route) => {
      if (!failApprovals) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(approvals) });
      }
      return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'boom' }) });
    });

    await page.goto('/');
    await expect(page.locator('.decision-row')).toHaveCount(1);
    failApprovals = true;

    await expect(page.getByText('Refresh failed — showing last known decisions')).toBeVisible({ timeout: 25_000 });
    // The cached row survives the failed refresh.
    await expect(page.locator('.decision-row')).toHaveCount(1);
  });

  test('both queues collapse to their all-clear lines only on zero/zero authoritative data', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, { approvals: [], kanban: [], runs: [] });
    await page.goto('/');

    await expect(page.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'true');
    await expect(page.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'true');
    await expect(page.getByTestId('decision-queue')).toContainText('No decisions waiting');
    await expect(page.getByTestId('attention-queue')).toContainText('Nothing needs attention');
  });
});

test.describe('decision-first overview at 1024x768', () => {
  test.use({ viewport: { width: 1024, height: 768 } });

  test('stacks the support column under a full-width main lane with no overflow', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page, { approvals: [CARD_APPROVAL], kanban: [REVIEW_CARD] });
    await page.goto('/');

    const queue = page.getByTestId('decision-queue');
    await expect(queue).toContainText('Ship the export');

    // <=1100 collapses to one lane; the support column must sit below it, not
    // in a stray third column of the old .layout template.
    const mainBox = await page.locator('.overview-main').boundingBox();
    const supportBox = await page.locator('.overview-support').boundingBox();
    const queueBox = await queue.boundingBox();
    expect(mainBox).not.toBeNull();
    expect(supportBox).not.toBeNull();
    expect(mainBox!.width).toBeGreaterThan(800);
    expect(supportBox!.y).toBeGreaterThan(queueBox!.y);

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(overflow.scrollWidth, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.clientWidth);
  });
});
