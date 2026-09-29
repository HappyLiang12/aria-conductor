import { test, expect } from '@playwright/test';
import { apiCall, decideApproval, pollUntil } from './fixtures';

/**
 * Phase 1 E2E contract anchor for the Spec-Driven Development workflow
 * (docs/superpowers/specs/2026-08-12-spec-driven-development-workflow-design.md).
 *
 * Revised in Task 17 for the governed cores and the deterministic harness:
 *  - the TP1 credential-gated skip is REMOVED: the harness supplies the git
 *    handoff deterministically (synthetic handoff credential in the harness
 *    environment + mock diff/push peers), so instantiation must succeed and the
 *    refusal path is no longer an accepted outcome;
 *  - the "no ADK runtime" skips are REMOVED: the BA step runs against the
 *    deterministic core harness, so the chain must reach WAITING_APPROVAL and
 *    resume after the approval without an external runtime;
 *  - the resumed chain is pinned to exactly COMPLETED (the golden deterministic
 *    outcome SddGoldenChainRegressionTest defines) instead of accepting
 *    RUNNING/COMPLETED/FAILED;
 *  - the expired-approval case seeds its own WAITING_APPROVAL chain and waits
 *    for the SPEC_REVIEW ask to reach exactly EXPIRED (the harness runs a short
 *    `approvals.timeout-ms`), then asserts the resubmitted ask exactly.
 *
 * Endpoints used: GET /api/v1/knowledge?type=WORKFLOW&status=APPROVED,
 * GET/POST /api/v1/workflows, POST /api/v1/knowledge/{id}/instantiate-workflow,
 * POST /api/v1/approvals/{id}/decide, POST /api/v1/workflows/{id}/resubmit-approval.
 * V40 seed provides the APPROVED 'development-workflow' template.
 *
 * Prerequisites: the deterministic core harness (recipe in
 * .superpowers/sdd/2026-09-22-agent-core-execution-modes/task-17-report.md) plus
 * the dashboard dev server for the Review-column assertion.
 */

// A serial suite would hide the second case's evidence behind the first
// failure (the round-1 review removed the same construct from the permissions
// spec), and both cases seed their own chain, so they run independently.
test.describe.configure({ timeout: 600_000 }); // 10 min — drives real BA→Dev→QA steps

const SPEC_REVIEW = 'SPEC_REVIEW';

/** Ensures the BA/DEV/QA role agents exist (the template resolves steps by agent_role). */
async function ensureRoleAgents(request: Parameters<typeof apiCall>[0]) {
  const existing = await apiCall(request, 'GET', '/agents');
  expect(existing.status).toBe(200);
  const roles = new Set((existing.data ?? []).map((a: any) => a.role));
  for (const role of ['ba', 'dev', 'qa']) {
    if (!roles.has(role)) {
      const created = await apiCall(request, 'POST', '/agents', {
        name: `sdd-${role}-${Date.now()}`,
        role,
        agentType: 'NATIVE',
      });
      expect(created.status, `create ${role} agent: ${JSON.stringify(created.data)}`).toBe(201);
    }
  }
}

/** The APPROVED development-workflow template (V40 seed). */
async function developmentWorkflowTemplate(request: Parameters<typeof apiCall>[0]) {
  const templates = await apiCall(request, 'GET', '/knowledge?type=WORKFLOW&status=APPROVED');
  expect(templates.status).toBe(200);
  const tpl = (templates.data as any[]).find((k) => k.name === 'development-workflow');
  expect(tpl, 'V40 seed development-workflow must exist').toBeTruthy();
  return tpl;
}

/** Instantiates the template and returns the created chain (must succeed). */
async function instantiateDevelopmentWorkflow(request: Parameters<typeof apiCall>[0]) {
  const tpl = await developmentWorkflowTemplate(request);
  const inst = await apiCall(request, 'POST', `/knowledge/${tpl.id}/instantiate-workflow`, {
    parameters: {
      issueRef: '#1-test',
      repoUrl: 'https://github.com/HappyLiang12/aria-conductor.git',
    },
  });
  expect(inst.status, `instantiate -> HTTP ${inst.status}: ${JSON.stringify(inst.data).slice(0, 300)}`).toBe(200);
  expect(inst.data.id).toMatch(/^[0-9a-f-]{36}$/);
  return inst.data as { id: string };
}

/** Polls the chain until it is exactly WAITING_APPROVAL with a PENDING SPEC_REVIEW ask. */
async function waitingSpecReview(request: Parameters<typeof apiCall>[0], chainId: string, timeoutMs = 180_000) {
  // A chain that already ended can never open the gate: fail immediately with
  // the observed status instead of burning the whole poll budget. This is a
  // fail-fast guard on the way to the exact WAITING_APPROVAL expectation, not
  // an accepted alternative outcome.
  const deadline = Date.now() + timeoutMs;
  let wf: any = null;
  for (;;) {
    const { data } = await apiCall(request, 'GET', `/workflows/${chainId}`);
    wf = data;
    if (data?.status === 'WAITING_APPROVAL') break;
    if (['FAILED', 'CANCELLED', 'COMPLETED'].includes(data?.status)) {
      throw new Error(
        `chain ${chainId} reached ${data.status} before WAITING_APPROVAL: ${JSON.stringify(data).slice(0, 400)}`,
      );
    }
    if (Date.now() > deadline) {
      throw new Error(`chain ${chainId} never reached WAITING_APPROVAL (last status: ${data?.status})`);
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }
  const chainRunIds = new Set<string>((wf.steps ?? []).map((s: any) => s.runId).filter(Boolean));
  expect(chainRunIds.size).toBeGreaterThan(0);
  const approvals = await pollUntil<any[]>(
    request,
    '/approvals',
    (list) =>
      Array.isArray(list) &&
      list.some((a) => a.approvalType === SPEC_REVIEW && a.status === 'PENDING' && chainRunIds.has(a.runId)),
    timeoutMs,
    2_000,
  );
  const approval = approvals.find(
    (a) => a.approvalType === SPEC_REVIEW && a.status === 'PENDING' && chainRunIds.has(a.runId),
  )!;
  return approval;
}

test('development-workflow: spec approval then PASS verdict completes the chain', async ({ page, request }) => {
  // 0. Ensure the BA/DEV/QA role agents exist (template resolves steps by agent_role).
  await ensureRoleAgents(request);

  // 1. Instantiate the seeded development-workflow template. The TP1 credential
  //    gate must be satisfied by the harness handoff credential: instantiation
  //    is no longer allowed to refuse with the GITHUB_TOKEN guidance.
  const chain = await instantiateDevelopmentWorkflow(request);
  expect(chain.id).toMatch(/^[0-9a-f-]{36}$/);

  // 2. The chain must reach WAITING_APPROVAL with a SPEC_REVIEW approval.
  //    Contract: SPEC_REVIEW approvals carry the spec content, the knowledge
  //    link (the versioned spec item is named exactly `spec-<chainId>`), and a
  //    null toolCallId (no tool gate involved). On the deterministic harness the
  //    spec content is exactly the BA run's fixture completion: the coordinator
  //    prefers the BA sandbox's /workspace/spec.md and falls back to the run's
  //    finalOutput when that read is unavailable (the harness process transport
  //    has no sandbox command channel), so the recorded content is that exact
  //    deterministic text -- never a markdown assumption about a fixture.
  const approval = await waitingSpecReview(request, chain.id);
  expect(approval.content).toBe('fixture-complete');
  expect(approval.knowledgeItemId).toMatch(/^[0-9a-f-]{36}$/);
  expect(approval.toolCallId).toBeNull();
  const specItem = await apiCall(request, 'GET', `/knowledge/${approval.knowledgeItemId}`);
  expect(specItem.status).toBe(200);
  expect(specItem.data.name).toBe(`spec-${chain.id}`);
  expect(specItem.data.status).toBe('PENDING');

  // 3. The Review surface renders without crashing on a null toolCallId ask.
  //    The /approvals page is deleted; the SPEC_REVIEW ask itself is not a Review
  //    card (SpecReviewCoordinator creates it with no kanbanItemId), so assert the
  //    reachable Review surface on the overview rather than the retired page.
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible({ timeout: 15_000 });

  // 4. Approve -> the coordinator writes back to knowledge and resumes the chain.
  //    Operator-only route: the decision goes through the fixture that carries the
  //    environment credential and verifies the processed ack field by field.
  await decideApproval(request, approval.id, true, 'lgtm');

  // 5. The chain must leave WAITING_APPROVAL and reach exactly COMPLETED on the
  //    deterministic harness (the golden PASS verdict chain). No alternative
  //    terminal states are accepted.
  const completed = await pollUntil<any>(
    request,
    `/workflows/${chain.id}`,
    (wf) => wf?.status === 'COMPLETED' || wf?.status === 'FAILED' || wf?.status === 'CANCELLED',
    300_000,
    2_000,
  );
  expect(completed.status, JSON.stringify(completed).slice(0, 300)).toBe('COMPLETED');
});

test('development-workflow: resubmit-approval recreates an EXPIRED approval', async ({ request }) => {
  // Own deterministic fixture: instantiate a chain, wait for its SPEC_REVIEW ask,
  // let it expire (the harness runs a short approvals.timeout-ms) and resubmit.
  const chain = await instantiateDevelopmentWorkflow(request);
  const expired = await waitingSpecReview(request, chain.id);

  const expiredAsk = await pollUntil<any>(
    request,
    `/approvals/${expired.id}`,
    (a) => a?.status === 'EXPIRED',
    180_000,
    2_000,
  );
  expect(expiredAsk.status).toBe('EXPIRED');
  expect(expiredAsk.approvalType).toBe(SPEC_REVIEW);

  const res = await apiCall(request, 'POST', `/workflows/${chain.id}/resubmit-approval`);
  expect(res.status, JSON.stringify(res.data)).toBe(200);
  // The replacement ask: a new PENDING SPEC_REVIEW for the same BA run, exactly.
  expect(res.data.id).not.toBe(expired.id);
  expect(res.data.id).toMatch(/^[0-9a-f-]{36}$/);
  expect(res.data.status).toBe('PENDING');
  expect(res.data.approvalType).toBe(SPEC_REVIEW);
  expect(res.data.reason).toBe(`Spec resubmitted for review: spec-${chain.id}`);
  expect(res.data.runId).toBe(expired.runId);

  // The chain stays WAITING_APPROVAL until the replacement is decided.
  const wf = await apiCall(request, 'GET', `/workflows/${chain.id}`);
  expect(wf.status).toBe(200);
  expect(wf.data.status).toBe('WAITING_APPROVAL');
});
