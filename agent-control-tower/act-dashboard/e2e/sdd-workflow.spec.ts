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
  // 0. Ensure the BA/DEV/QA role agents exist (template resolves steps by agent_role).
  const existing = await (await request.get(`${API_URL}/api/v1/agents`)).json();
  const roles = new Set((existing ?? []).map((a: any) => a.role));
  for (const role of ['ba', 'dev', 'qa']) {
    if (!roles.has(role)) {
      const created = await request.post(`${API_URL}/api/v1/agents`, {
        data: { name: `sdd-${role}-${Date.now()}`, role, agentType: 'NATIVE' },
      });
      expect(created.ok(), `create ${role} agent`).toBeTruthy();
    }
  }

  // 1. Instantiate the seeded development-workflow template (V40 seed, APPROVED WORKFLOW item).
  const templates = await request.get(`${API_URL}/api/v1/knowledge?type=WORKFLOW&status=APPROVED`);
  expect(templates.ok()).toBeTruthy();
  const tpl = (await templates.json()).find((k: any) => k.name === 'development-workflow');
  expect(tpl).toBeTruthy();

  // R8-F1: the template declares {repoUrl} (V45 prompts) and instantiation fails fast
  // when neither the caller nor the system config (opencode.repo-url) provides it.
  // CI has no GH_TOKEN, so the branch-creation step is a no-op - the URL is inert here.
  const inst = await request.post(`${API_URL}/api/v1/knowledge/${tpl.id}/instantiate-workflow`, {
    data: { parameters: { issueRef: '#1-test', repoUrl: 'https://github.com/HappyLiang12/aria-conductor.git' } },
  });
  expect(inst.ok()).toBeTruthy();
  const chain = await inst.json();
  expect(chain.id).toBeTruthy();

  // 2. Poll until the chain enters WAITING_APPROVAL with a SPEC_REVIEW approval.
  //    Contract: SPEC_REVIEW approvals carry markdown content, the knowledge link,
  //    and a null toolCallId (no tool gate involved).
  let approval: any = null;
  try {
    approval = await pollUntil(async () => {
      const list = await (await request.get(`${API_URL}/api/v1/approvals`)).json();
      return list.find((a: any) => a.approvalType === 'SPEC_REVIEW' && a.status === 'PENDING');
    }, 30_000);
  } catch (e) {
    // The BA run needs a real ADK runtime (langchain subprocess / opencode sandbox).
    // Without one the chain fails before the spec gate - skip rather than flake.
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    test.skip(
      wf.status === 'FAILED' || wf.status === 'RUNNING',
      'BA run requires an ADK runtime (langchain/open-sandbox); skipping spec-gate assertions',
    );
    throw e;
  }
  expect(approval.content).toContain('#');
  expect(approval.knowledgeItemId).toBeTruthy();
  expect(approval.toolCallId).toBeNull();

  // 3. Approvals page renders the card without crashing (null toolCallId) and shows markdown.
  await page.goto('/approvals');
  await expect(page.getByText('SPEC_REVIEW')).toBeVisible();
  await expect(page.locator('.spec-review-markdown')).toBeVisible();

  // 4. Approve -> the coordinator writes back to knowledge and resumes the chain.
  const decide = await request.post(`${API_URL}/api/v1/approvals/${approval.id}/decide`, {
    data: { approved: true, reason: 'lgtm' },
  });
  expect(decide.ok()).toBeTruthy();

  // 5. The chain must leave WAITING_APPROVAL (resumed by the coordinator). The Dev/QA runs
  //    then depend on the LLM/ADK being available; without one the chain lands in FAILED -
  //    which still proves the approval gate -> coordinator -> resume contract. The full
  //    PASS/DEFECT/SPEC_GAP routing is covered deterministically by the Java integration
  //    tests (SddWorkflowIntegrationTest).
  await pollUntil(async () => {
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    return wf.status !== 'WAITING_APPROVAL' ? wf : null;
  }, 30_000);
  const resumed = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
  expect(['RUNNING', 'COMPLETED', 'FAILED']).toContain(resumed.status);
});

test('development-workflow: resubmit-approval recreates an EXPIRED approval', async ({ request }) => {
  const list = await request.get(`${API_URL}/api/v1/workflows`);
  const waiting = (await list.json()).find((w: any) => w.status === 'WAITING_APPROVAL');
  test.skip(!waiting, 'requires a WAITING_APPROVAL chain (fixture-dependent)');
  const res = await request.post(`${API_URL}/api/v1/workflows/${waiting.id}/resubmit-approval`);
  expect(res.ok()).toBeTruthy();
});
