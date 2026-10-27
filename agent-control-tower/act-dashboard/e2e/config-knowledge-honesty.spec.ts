import { test, expect, type Page } from '@playwright/test';

/**
 * Task 13: Configure and Knowledge must state what the build actually enforces.
 * Route fixtures only; the agent list is mutable so the reload can prove that
 * ownership never migrates when an unrelated agent is added.
 */

const agent = (id: string, name: string, role: string) => ({
  id,
  name,
  role,
  agentType: 'ADK',
  healthStatus: 'HEALTHY',
  description: '',
  model: '',
  provider: 'opencode',
  createdAt: '2026-01-01T00:00:00Z',
});

const state = {
  agents: [agent('agent-a', 'Agent A', 'developer'), agent('agent-b', 'Agent B', 'qa')],
};

const KNOWLEDGE = [
  {
    id: 'k-legacy',
    name: 'Legacy playbook',
    type: 'GUIDELINE',
    description: 'Imported before ownership was recorded',
    currentVersion: 1,
    status: 'APPROVED',
    sensitivity: 'INTERNAL',
    createdAt: '2026-10-01T00:00:00Z',
    agentId: 'missing-agent',
  },
];

const SKILLS = [
  { id: 's-1', name: 'browser-use', category: 'Web', description: 'Drive a browser' },
];

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
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (pathname === '/api/v1/agents') return json(state.agents);
    if (pathname === '/api/v1/skills') return json(SKILLS);
    if (pathname === '/api/v1/knowledge') return json(KNOWLEDGE);
    if (pathname === '/api/v1/system-config') return json([]);
    if (pathname === '/api/v1/llm-providers') return json([]);
    if (pathname === '/api/v1/approvals') return json([]);
    if (pathname === '/api/v1/kanban/items') return json([]);
    if (pathname === '/api/v1/runs') return json([]);
    if (pathname.startsWith('/api/v1/runs/')) return json([]);
    if (pathname === '/api/v1/adk/providers') return json([]);
    if (pathname === '/api/v1/dashboard/summary') {
      return json({ activeAgents: 2, runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0 });
    }
    if (pathname.startsWith('/api/v1/dashboard/')) return json([]);
    if (pathname === '/api/v1/aria/notifications/count') return json({ unreadCount: 0 });
    if (pathname.startsWith('/api/v1/aria/')) return json([]);
    return json([]);
  });
}

test.use({ viewport: { width: 1440, height: 900 } });

test.describe('configure honesty', () => {
  test('Skills is a read-only catalog with no invented permission control', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    await page.goto('/');

    await page.getByRole('button', { name: 'Open configuration' }).click();
    await page.getByRole('tab', { name: /Skills/ }).click();

    await expect(page.getByText('browser-use')).toBeVisible();
    await expect(
      page.getByText('Read-only catalog. No skill permission is editable or enforced in this build.'),
    ).toBeVisible();
    await expect(page.getByText('1 skills in the read-only catalog')).toBeVisible();

    // No permission column, value or control may exist.
    await expect(page.getByRole('columnheader', { name: 'Permission' })).toHaveCount(0);
    await expect(page.locator('.skill-table select')).toHaveCount(0);
    await expect(page.locator('.skill-table .lvl')).toHaveCount(0);
    await expect(page.locator('.modal .tab-body').getByText('USE', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reset to defaults' })).toHaveCount(0);
  });

  test('Gates stays present but every reference control is inert', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    await page.goto('/');

    await page.getByRole('button', { name: 'Open configuration' }).click();
    await page.getByRole('tab', { name: /Approval Gates/ }).click();

    await expect(
      page.getByText('Reference only — not enforced by the backend. This panel does not change runtime behavior.'),
    ).toBeVisible();
    await expect(page.getByText('Reference only — gates are not enforced')).toBeVisible();

    const switches = page.getByRole('switch');
    await expect(switches).toHaveCount(6);
    for (const control of await switches.all()) {
      await expect(control).toHaveAttribute('aria-disabled', 'true');
    }
    const before = await switches.evaluateAll((els) => els.map((el) => el.getAttribute('aria-checked')));
    await switches.first().click({ force: true });
    await switches.first().press('Enter');
    const after = await switches.evaluateAll((els) => els.map((el) => el.getAttribute('aria-checked')));
    expect(after).toEqual(before);

    await expect(page.getByRole('combobox')).toHaveCount(6);
    for (const select of await page.getByRole('combobox').all()) {
      await expect(select).toBeDisabled();
    }
    for (const spin of await page.getByRole('spinbutton').all()) {
      await expect(spin).toBeDisabled();
    }
    await expect(page.getByRole('button', { name: 'Reset to defaults' })).toHaveCount(0);
  });

  test('Runtime, LLM and System Config remain real tabs', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    await page.goto('/');

    await page.getByRole('button', { name: 'Open configuration' }).click();
    await expect(page.getByRole('tab', { name: /Runtime/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /LLM Providers/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /System Config/ })).toBeVisible();

    await page.getByRole('tab', { name: /System Config/ }).click();
    await expect.poll(() => page.getByTitle(/^Save /).count()).toBeGreaterThan(0);
    await expect(page.getByTitle('Reset to default').first()).toBeVisible();
  });
});

test.describe('knowledge ownership honesty', () => {
  test.beforeEach(() => {
    state.agents = [agent('agent-a', 'Agent A', 'developer'), agent('agent-b', 'Agent B', 'qa')];
  });

  test('an unowned item is Unattributed in the space, list and detail; a new agent changes nothing', async ({ page }) => {
    await stubWebSocket(page);
    await stubApi(page);
    await page.goto('/knowledge');

    const unattributed = page.getByRole('button', { name: /Unattributed/ });
    await expect(unattributed).toBeVisible();
    await expect(unattributed.locator('.metrics b').first()).toHaveText('1');

    const row = page.locator('.kitem', { hasText: 'Legacy playbook' });
    await expect(row).toContainText('Unattributed');
    await expect(page.getByText('Shared', { exact: true })).toHaveCount(0);

    await row.click();
    await expect(page.locator('.kb-detail .meta')).toContainText('Unattributed');
    await expect(page.locator('.kb-detail .meta')).not.toContainText('Shared');

    // A third, unrelated agent appears: ownership cannot migrate to it.
    state.agents = [
      ...state.agents,
      agent('agent-c', 'Agent C', 'reviewer'),
    ];
    await page.reload();

    const unattributedAfter = page.getByRole('button', { name: /Unattributed/ });
    await expect(unattributedAfter).toBeVisible();
    await expect(unattributedAfter.locator('.metrics b').first()).toHaveText('1');
    await expect(page.getByRole('button', { name: /Agent C/ }).locator('.metrics b').first()).toHaveText('0');
    await expect(page.locator('.kitem', { hasText: 'Legacy playbook' })).toContainText('Unattributed');
    await expect(page.getByText('Shared', { exact: true })).toHaveCount(0);
  });
});
