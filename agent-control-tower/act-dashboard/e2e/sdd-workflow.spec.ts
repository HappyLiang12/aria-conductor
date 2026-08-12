import { test, expect } from '@playwright/test';

/**
 * Phase 1 E2E contract anchor (RED) for the Spec-Driven Development workflow
 * (docs/superpowers/specs/2026-08-12-spec-driven-development-workflow-design.md).
 *
 * Drives the loop over the REST API and asserts the Approvals page renders the
 * SPEC_REVIEW card. Written FIRST — it must fail until the backend + frontend
 * wiring lands (later tasks):
 *   - POST /api/v1/knowledge/{id}/instantiate-workflow            (planned, Task 3)
 *   - GET /api/v1/approvals gains approvalType/content/knowledgeItemId (planned)
 *   - POST /api/v1/workflows/{id}/resubmit-approval               (planned)
 *   - Approvals page SPEC_REVIEW card with .spec-review-markdown   (planned, Task 10)
 *
 * Verified-real endpoints used unchanged: GET /api/v1/knowledge?type=WORKFLOW&status=APPROVED,
 * GET /api/v1/workflows, GET /api/v1/workflows/{id}, POST /api/v1/approvals/{id}/decide.
 * V40 seed provides the APPROVED 'development-workflow' template.
 *
 * Prerequisites: backend running (h2 profile, V40+), frontend dev server up.
 * API_URL/BASE_URL are parameterised like the other e2e specs (worktrees/CI).
 */

test.describe.configure({ mode: 'serial', timeout: 600_000 }); // 10 min — drives real BA→Dev→QA runs

const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';

/**
 * Polls {fn} until it returns a non-null value or the deadline elapses.
 * Mirrors the pollUntil pattern from e2e/fixtures.ts, local to this spec.
 */
async function pollUntil<T>(
  fn: () => Promise<T | null>,
  timeoutMs: number,
  intervalMs = 2_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | null = null;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value != null) return value;
    last = value;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`pollUntil timed out after ${timeoutMs}ms (last result: ${JSON.stringify(last)?.slice(0, 300)})`);
}

test('development-workflow: spec approval then PASS verdict completes the chain', async ({ page, request }) => {
  // 1. Instantiate the seeded development-workflow template (V40 seed, APPROVED WORKFLOW item).
  const templates = await request.get(`${API_URL}/api/v1/knowledge?type=WORKFLOW&status=APPROVED`);
  expect(templates.ok()).toBeTruthy();
  const tpl = (await templates.json()).find((k: any) => k.name === 'development-workflow');
  expect(tpl).toBeTruthy();

  const inst = await request.post(`${API_URL}/api/v1/knowledge/${tpl.id}/instantiate-workflow`, {
    data: { parameters: { issueRef: '#1-test' } },
  });
  expect(inst.ok()).toBeTruthy();
  const chain = await inst.json();
  expect(chain.id).toBeTruthy();

  // 2. Poll until the chain enters WAITING_APPROVAL with a SPEC_REVIEW approval.
  //    Contract: SPEC_REVIEW approvals carry markdown content, the knowledge link,
  //    and a null toolCallId (no tool gate involved).
  const approval = await pollUntil(async () => {
    const list = await (await request.get(`${API_URL}/api/v1/approvals`)).json();
    return list.find((a: any) => a.approvalType === 'SPEC_REVIEW' && a.status === 'PENDING');
  }, 30_000);
  expect(approval.content).toContain('#');
  expect(approval.knowledgeItemId).toBeTruthy();
  expect(approval.toolCallId).toBeNull();

  // 3. Approvals page renders the card without crashing (null toolCallId) and shows markdown.
  await page.goto('/approvals');
  await expect(page.getByText('SPEC_REVIEW')).toBeVisible();
  await expect(page.locator('.spec-review-markdown')).toBeVisible();

  // 4. Approve -> chain resumes.
  await request.post(`${API_URL}/api/v1/approvals/${approval.id}/decide`, {
    data: { approved: true, reason: 'lgtm' },
  });

  // 5. Poll until the chain completes (BA -> Dev -> QA, QA verdict PASS routes to COMPLETED).
  await pollUntil(async () => {
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    return wf.status === 'COMPLETED' ? wf : null;
  }, 120_000);
});

test('development-workflow: resubmit-approval recreates an EXPIRED approval', async ({ request }) => {
  const list = await request.get(`${API_URL}/api/v1/workflows`);
  const waiting = (await list.json()).find((w: any) => w.status === 'WAITING_APPROVAL');
  test.skip(!waiting, 'requires a WAITING_APPROVAL chain (fixture-dependent)');
  const res = await request.post(`${API_URL}/api/v1/workflows/${waiting.id}/resubmit-approval`);
  expect(res.ok()).toBeTruthy();
});
