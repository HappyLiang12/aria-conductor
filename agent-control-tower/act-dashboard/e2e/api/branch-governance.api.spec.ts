import { test, expect, type APIRequestContext } from '@playwright/test';

/**
 * Gap 5: SDD branch governance — create (and protect) the chain branch.
 *
 * VERDICT (spike 2026-09-12): LOCAL-ONLY, NOT hermetically testable, and the body below
 * has NOT been executed on this branch — see
 * docs/reviews/2026-09-12-branch-and-pack-gate-spike.md.
 *
 * Why:
 *  - The only reachable branch-creation mechanism is `GitBranchService`, a pure GitHub
 *    REST client (act-execution/.../execution/git/GitBranchService.java:30,70-100) wired by
 *    `GitBranchConfig.java:41-76`. The API base URL is the compile-time constant
 *    `https://api.github.com` (GitBranchService.java:30): the override constructor is
 *    package-private and test-only (:46, used by the WireMock unit test
 *    GitBranchServiceTest.java:43), and no Spring property or env var can redirect it.
 *    There is therefore no local substitute (a bare repo on disk speaks the git protocol,
 *    not the GitHub REST API) short of changing production code.
 *  - With no GitHub credential resolvable, the bean is a disabled no-op that throws
 *    GitBranchException on every call (GitBranchConfig.java:83-110). That is the observed
 *    state of the running local stack and of CI, where no GitHub credentials exist. The
 *    exception now carries the canonical operator guidance
 *    (GitCredentialGuidance.REQUIRED_MESSAGE: "No GitHub credential is configured. Set the
 *    GITHUB_TOKEN environment variable and restart the backend; ...").
 *  - The gate itself is `SpecReviewCoordinator.createBranchAndCommitSpec`
 *    (act-knowledge/.../knowledge/sdd/SpecReviewCoordinator.java:345-363), called from
 *    the SPEC_REVIEW approval handler (:168-175). A GitBranchException propagates and the
 *    chain does not resume.
 *  - The "protect" half of the gap has NO implementation: no branch-protection code path
 *    exists anywhere under agent-control-tower (no hits for branch protection in any
 *    main source file), so there is nothing to assert.
 *
 * Local-only recipe (must be run against a stack started with both values exported):
 *   export GITHUB_TOKEN=<token with repo scope>   # canonical; GH_TOKEN is a deprecated fallback
 *   export SDD_REPO_URL=https://github.com/<owner>/<repo>.git
 *   # ...start backend + frontend with the same environment, plus an LLM key for the BA run
 *   cd agent-control-tower/act-dashboard
 *   npx playwright test e2e/api/branch-governance.api.spec.ts --project=api
 */

const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';
// Canonical name first, deprecated alias second — the precondition must agree with the
// product's own resolution order (GitBranchConfig resolves GITHUB_TOKEN, then GH_TOKEN).
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';
const SDD_REPO_URL = process.env.SDD_REPO_URL || '';

test.describe.configure({ mode: 'serial', timeout: 600_000 });

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
  throw new Error(`pollUntil timed out after ${timeoutMs}ms (last=${JSON.stringify(last)?.slice(0, 200)})`);
}

function ownerRepo(repoUrl: string): string {
  return repoUrl
    .replace(/^https?:\/\//, '')
    .replace(/^[^/]+\//, '')
    .replace(/\.git$/, '')
    .replace(/\/+$/, '');
}

/** Read the chain branch back through the GitHub REST API the product itself uses. */
async function branchExists(request: APIRequestContext, repoUrl: string, branch: string): Promise<boolean> {
  const resp = await request.get(
    `https://api.github.com/repos/${ownerRepo(repoUrl)}/git/ref/heads/${branch}`,
    {
      headers: {
        Authorization: `Bearer ${GITHUB_TOKEN}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      failOnStatusCode: false,
    },
  );
  return resp.status() === 200;
}

test('SDD branch handoff creates the chain branch on a real GitHub remote (local-only)', async ({
  request,
}) => {
  test.skip(
    !GITHUB_TOKEN || !SDD_REPO_URL,
    'local-only: branch creation is a pure GitHub REST call against the hardcoded '
      + 'https://api.github.com (GitBranchService.java:30) with no configurable base URL, and '
      + 'GitBranchConfig.java:83-110 installs a no-op variant when no GitHub credential '
      + 'resolves (GITHUB_TOKEN is canonical, GH_TOKEN is accepted as a deprecated alias). '
      + 'Run locally with GITHUB_TOKEN (or the legacy GH_TOKEN) and '
      + 'SDD_REPO_URL exported against a stack started with the same environment '
      + '(see the recipe at the top of this file).',
  );

  // 0. Role agents the development-workflow template resolves by agent_role.
  const existing = await (await request.get(`${API_URL}/api/v1/agents`)).json();
  const roles = new Set((existing ?? []).map((a: any) => a.role));
  for (const role of ['ba', 'dev', 'qa']) {
    if (!roles.has(role)) {
      const created = await request.post(`${API_URL}/api/v1/agents`, {
        data: { name: `sdd-${role}-${Date.now()}`, role, agentType: 'NATIVE' },
      });
      expect(created.ok()).toBeTruthy();
    }
  }

  // 1. Instantiate the seeded development-workflow template with the real repo.
  const templates = await (await request.get(
    `${API_URL}/api/v1/knowledge?type=WORKFLOW&status=APPROVED`,
  )).json();
  const tpl = templates.find((k: any) => k.name === 'development-workflow');
  expect(tpl, 'V40 seed development-workflow must exist').toBeTruthy();

  const inst = await request.post(`${API_URL}/api/v1/knowledge/${tpl.id}/instantiate-workflow`, {
    data: { parameters: { issueRef: '#1', repoUrl: SDD_REPO_URL } },
  });
  expect(inst.ok()).toBeTruthy();
  const chain = await inst.json();
  expect(chain.id).toBeTruthy();
  const branch = `sdd/${chain.id}`; // GitHandoffMetadata.branchName

  // 2. Wait for the SPEC_REVIEW gate, scoped to THIS chain's runs.
  const approval = await pollUntil<any>(async () => {
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    if (wf.status !== 'WAITING_APPROVAL') return null;
    const runIds = new Set<string>((wf.steps ?? []).map((s: any) => s.runId).filter(Boolean));
    const approvals = await (await request.get(`${API_URL}/api/v1/approvals?status=PENDING`)).json();
    return (approvals ?? []).find((a: any) => a.approvalType === 'SPEC_REVIEW' && runIds.has(a.runId)) ?? null;
  }, 300_000);

  // 3. The branch must not exist before the spec is approved.
  expect(await branchExists(request, SDD_REPO_URL, branch)).toBe(false);

  // 4. Approve the gate → SpecReviewCoordinator.createBranchAndCommitSpec runs.
  const decided = await request.post(`${API_URL}/api/v1/approvals/${approval.id}/decide`, {
    data: { approved: true, reason: 'e2e branch governance' },
  });
  expect(decided.ok()).toBeTruthy();
  expect(
    (await (await request.get(`${API_URL}/api/v1/approvals/${approval.id}`)).json()).status,
  ).toBe('APPROVED');

  // 5. The gate's effect: the chain branch now exists on the remote.
  await pollUntil(async () => ((await branchExists(request, SDD_REPO_URL, branch)) ? true : null), 60_000);
  expect(await branchExists(request, SDD_REPO_URL, branch)).toBe(true);
});
