import { test, expect, type Page, type Route } from '@playwright/test';

/**
 * C5: ACP permission asks in the dashboard — fully API-mocked, no backend
 * needed (pattern per e2e/aria-timeout-cancel.spec.ts:148 and
 * e2e/notification-bell.spec.ts: `page.route` fulfilling JSON).
 *
 * Covers the C5 wire contract against the real React app:
 * - the Ops approval queue renders an ACP ask (`.pill.acp` badge, resolved tool
 *   label, `Allow once` / `Deny`) and a typed 409 `{code, error}` surfaces as
 *   `${code}: ${error}` in the toast;
 * - a FAILED delivery stays retryable on the Ops surface with the recorded
 *   decision;
 * - board → `⤢ Expand review workspace` shows the ACP ask card with the
 *   displayJson preview, and after the mocked decide the card ask list serves
 *   the ask as decided (the real refetch lifecycle): the ACP card is gone but
 *   the outcome strip — derived from that list on the parent — survives the
 *   panel unmount (C5-fix1 F1/M2).
 */

const ACP_ASK_ID = 'acp-e2e-1';
const CARD_ID = 'card-e2e-1';
const RUN_ID = 'run-e2e-1';

/** displayJson exactly as AcpPermissionCoordinator builds it (JSON string). */
const DISPLAY_JSON = JSON.stringify({
  rawInputTruncated: false,
  grantable: true,
  toolName: 'Write',
  rawInput: 'file_path: /workspace/out.csv',
  options: [
    { optionId: 'opt-allow', kind: 'allow_once', name: 'Allow once' },
    { optionId: 'opt-deny', kind: 'reject_once', name: 'Deny' },
  ],
});

function acpAsk() {
  return {
    id: ACP_ASK_ID,
    runId: RUN_ID,
    toolCallId: null,
    status: 'PENDING',
    reason: 'qoder requests Write',
    requestedAt: new Date().toISOString(),
    decidedAt: null,
    expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    askType: 'APPROVAL',
    source: 'ACP_PERMISSION',
    deliveryState: 'PENDING',
    displayJson: DISPLAY_JSON,
  };
}

/** The same ask after a decide: GET /approvals?kanbanItemId= serves it as decided. */
function decidedAcpAsk() {
  return {
    ...acpAsk(),
    status: 'APPROVED',
    decidedAt: new Date().toISOString(),
    deliveryState: 'DELIVERED',
  };
}

function kanbanCard(overrides: Record<string, unknown> = {}) {
  return {
    id: CARD_ID,
    title: 'qoder permission card',
    description: 'needs a permission decision',
    status: 'REVIEW',
    priority: 'MEDIUM',
    assignee: 'dev-agent',
    labels: null,
    linkedRunId: RUN_ID,
    linkedAgentId: null,
    agentTemplateId: null,
    lastError: null,
    pendingAskCount: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  };
}

function linkedRun() {
  return {
    id: RUN_ID,
    agentId: 'agent-e2e-1',
    status: 'COMPLETED',
    promptSeed: 'qoder writes the export',
    maxIterations: 5,
    totalTokensUsed: 1200,
    iterationCount: 2,
    errorMessage: null,
    finalOutput: 'export written',
    createdAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
  };
}

interface MockOptions {
  /** PENDING list served by `GET /approvals` (no params). */
  pendingApprovals: () => unknown[];
  /** Ask list served by `GET /approvals?kanbanItemId=...`. */
  cardAsks: () => unknown[];
  /** Receipt served by `POST /approvals/{id}/decide` (called per request). */
  decide: () => { status: number; body: unknown };
}

/** Answers every /api/v1 call locally; list endpoints default to empty. */
async function mockApi(page: Page, opts: MockOptions) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (method === 'POST' && path === `/api/v1/approvals/${ACP_ASK_ID}/decide`) {
      const response = opts.decide();
      return json(response.body, response.status);
    }
    if (method === 'GET' && path === '/api/v1/approvals') {
      return url.searchParams.get('kanbanItemId') ? json(opts.cardAsks()) : json(opts.pendingApprovals());
    }
    if (method === 'GET' && path === '/api/v1/kanban/items') return json([kanbanCard()]);
    if (method === 'GET' && path === `/api/v1/kanban/items/${CARD_ID}`) return json(kanbanCard());
    if (method === 'GET' && path === `/api/v1/runs/${RUN_ID}`) return json(linkedRun());
    if (method === 'GET' && path === '/api/v1/dashboard/summary') {
      return json({
        activeAgents: 1,
        healthyAgents: 1,
        degradedAgents: 0,
        runningRuns: 0,
        pendingApprovals: 1,
        totalTokensBurned: 0,
      });
    }
    // Remaining list endpoints (agents, templates, runs, activity, knowledge…)
    // answer an empty list; nothing else is expected to be called.
    return json([]);
  });
}

test('Ops queue renders an ACP ask and surfaces a typed 409 as code: message', async ({ page }) => {
  await mockApi(page, {
    pendingApprovals: () => [acpAsk()],
    cardAsks: () => [],
    decide: () => ({
      status: 409,
      body: { code: 'ALREADY_DECIDED', error: 'ask was decided elsewhere' },
    }),
  });

  await page.goto('/ops');
  const row = page.locator(`[data-approval-id="${ACP_ASK_ID}"]`);
  await expect(row).toBeVisible({ timeout: 15_000 });

  await expect(row.locator('.pill.acp')).toHaveText('ACP permission');
  await expect(row.locator('.ttl')).toHaveText('Write');
  const allow = row.getByRole('button', { name: 'Allow once' });
  await expect(allow).toBeEnabled();
  await expect(row.getByRole('button', { name: 'Deny' })).toBeEnabled();
  // The legacy pair is not offered on an ACP row.
  await expect(row.getByRole('button', { name: /Approve/ })).toHaveCount(0);

  await allow.click();
  await expect(page.getByRole('status')).toContainText(
    'ALREADY_DECIDED: ask was decided elsewhere',
  );
});

test('Ops keeps a FAILED delivery retryable with the recorded decision', async ({ page }) => {
  let decides = 0;
  await mockApi(page, {
    pendingApprovals: () => [acpAsk()],
    cardAsks: () => [],
    decide: () => {
      decides += 1;
      return {
        status: 200,
        body: {
          approvalId: ACP_ASK_ID,
          approved: true,
          status: 'processed',
          decision: 'APPROVED',
          deliveryState: decides === 1 ? 'FAILED' : 'DELIVERED',
        },
      };
    },
  });

  await page.goto('/ops');
  const row = page.locator(`[data-approval-id="${ACP_ASK_ID}"]`);
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row.getByRole('button', { name: 'Allow once' }).click();

  const strip = page.locator('.acp-outcomes');
  await expect(strip).toContainText('approved · delivery failed');
  await expect(strip.locator('.acp-outcome.warn')).toHaveCount(1);

  await strip.getByRole('button', { name: 'Retry' }).click();
  await expect(strip.locator('.acp-outcome.ok')).toContainText('approved · delivered');
  expect(decides).toBe(2);
  await expect(page.getByRole('status')).toContainText('Permission delivered to the agent');
});

test('board → expand review workspace renders the ACP card and a delivered outcome', async ({ page }) => {
  // The card ask list follows the real lifecycle: pending before the decide,
  // decided after it (this is what makes the strip a view of server truth).
  let decided = false;
  await mockApi(page, {
    pendingApprovals: () => (decided ? [] : [acpAsk()]),
    cardAsks: () => (decided ? [decidedAcpAsk()] : [acpAsk()]),
    decide: () => {
      decided = true;
      return {
        status: 200,
        body: {
          approvalId: ACP_ASK_ID,
          approved: true,
          status: 'processed',
          decision: 'APPROVED',
          deliveryState: 'DELIVERED',
        },
      };
    },
  });

  await page.goto('/');
  const card = page.locator(`[data-col="REVIEW"] [data-card="${CARD_ID}"]`);
  await expect(card).toBeVisible({ timeout: 15_000 });
  await card.getByLabel('Expand review workspace').click();

  const zone = page.getByRole('region', { name: 'Decision panel' });
  await expect(zone).toBeVisible({ timeout: 15_000 });
  await expect(zone.locator('.pill.acp')).toHaveText('ACP permission');
  await expect(zone.getByText('Write')).toBeVisible();
  await expect(zone.locator('pre.acp-preview')).toHaveText('file_path: /workspace/out.csv');
  await expect(zone.getByRole('button', { name: 'Allow once' })).toBeEnabled();

  await zone.getByRole('button', { name: 'Allow once' }).click();

  // The decide's invalidation refetches the card ask list, which now serves the
  // ask as decided (APPROVED/DELIVERED): the ACP card and the pending panel are
  // gone (ShortApprovalView takes over) — the strip derived from the ask list
  // must survive the panel unmount (F1).
  const strip = page.locator('.acp-outcomes');
  await expect(strip).toContainText('approved · delivered');
  await expect(strip.locator('.acp-outcome.ok')).toHaveCount(1);
  await expect(zone.locator('.pill.acp')).toHaveCount(0);
  await expect(zone.getByText(/Run completed/)).toBeVisible();
});
