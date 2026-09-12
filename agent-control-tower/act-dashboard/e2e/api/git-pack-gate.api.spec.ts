import { test, expect } from '@playwright/test';
import http from 'node:http';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { apiCall, pollUntil, seedAdkAgent, uniqueName } from '../fixtures';

/**
 * Gap 6: the git-pack PUSH gate, end to end — a run that is actually BLOCKED on the
 * governed git_push approval gate and then RESUMED.
 *
 * Why this is hermetic (no external GitHub, no LLM key, no OpenSandbox):
 *
 *  - The PUSH tier is what gates the tool: `git_push` has riskTier=PUSH
 *    (V33 seed; GET /api/v1/tools) → `ToolRiskResolver.requiresApproval`
 *    (act-execution/.../pipeline/ToolRiskResolver.java:37-40) → ActionClassifier
 *    highRisk (:24-27) → ActionExecutionPipeline stage 4 blocks on ApprovalGate
 *    (:90-104). The run is left PAUSED (:93) while the gate is open.
 *  - A git_push ACTION can only come from the model, so the spec supplies the model:
 *    a loopback OpenAI-compatible mock. `LangChainAdkProvider.buildRequestBody`
 *    (act-execution/.../adk/LangChainAdkProvider.java:395-408) takes llm_base_url and
 *    the api key from the ACTIVE DB LlmProvider row, so pointing a temporary provider
 *    at 127.0.0.1 gives a deterministic tool call with no API key and no egress.
 *  - The tool itself is a real `git push` (GitPackHandler.java:120-127) executed in the
 *    run workspace (`_workspaceDir`, ToolExecutionEngine.java:100-113 + WorkspaceManager
 *    `getOrProvision` = `<workspace-root>/<runId>`). While the gate is blocked, the spec
 *    turns that directory into a git repo whose `origin` is a bare repository on disk,
 *    so the approved push lands in a local remote that the spec can read back. The
 *    workspace root follows the backend's CWD (see candidateWorkspaceRoots below);
 *    set TOOLS_FILE_WORKSPACE_DIR to the same value the backend was started with to
 *    pin it explicitly.
 *
 * Mutation note: the mock provider is created, activated and then deleted again in
 * afterAll, and the previously active provider is re-activated. Keep the window short
 * (the spec restores state as soon as the run is terminal) — the switch is global.
 */

const SPEC_DIR = path.dirname(fileURLToPath(import.meta.url));
const MODULE_DIR = path.resolve(SPEC_DIR, '../../..'); // agent-control-tower

/**
 * The backend provisions run workspaces under `<cwd>/data/workspaces`
 * (application.yml:38, `tools.file.workspace-dir`), so the root depends on how the
 * backend was launched. Verified roots:
 *   - TOOLS_FILE_WORKSPACE_DIR set (docker compose sets it to /workspaces)
 *   - CWD = agent-control-tower        → <module>/data/workspaces        (CI start-stack)
 *   - CWD = agent-control-tower/act-app → <module>/act-app/data/workspaces (local dev scripts)
 * The spec prepares its git repo in every candidate root; the assertion that the sha the
 * push landed in the local bare remote is a member of the set of prepared roots' shas
 * proves that one of them was the root the run actually used (which one is not
 * observable from the remote alone). A wrong guess cannot pass silently — the push
 * would fail (as it does outside a prepared root).
 */
function candidateWorkspaceRoots(): string[] {
  const roots: string[] = [];
  if (process.env.TOOLS_FILE_WORKSPACE_DIR) {
    roots.push(path.resolve(process.env.TOOLS_FILE_WORKSPACE_DIR));
  }
  roots.push(path.join(MODULE_DIR, 'data', 'workspaces'));
  roots.push(path.join(MODULE_DIR, 'act-app', 'data', 'workspaces'));
  return [...new Set(roots)];
}

const TERMINAL_TOOL_CALL = ['COMPLETED', 'FAILED', 'DENIED'];
const TERMINAL_RUN = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];
/** Name prefix of the temporary provider this spec creates; also the stale-run marker. */
const MOCK_PROVIDER_PREFIX = 'e2e-mock-llm';

const GIT_ENV = {
  ...process.env,
  GIT_AUTHOR_NAME: 'aria-e2e',
  GIT_AUTHOR_EMAIL: 'aria-e2e@aria-conductor.local',
  GIT_COMMITTER_NAME: 'aria-e2e',
  GIT_COMMITTER_EMAIL: 'aria-e2e@aria-conductor.local',
};

function git(args: string[], cwd: string): string {
  return execFileSync('git', args, { cwd, env: GIT_ENV, encoding: 'utf8', stdio: 'pipe' }).trim();
}

let mockServer: http.Server;
let mockPort = 0;
let mockProviderId: string | null = null;
let previouslyActiveProviderId: string | null = null;
let tempRoot: string;
const mockRequests: string[] = [];
/** Run workspace directories this spec created under the candidate roots (fixtures). */
const createdWorkspaceDirs: string[] = [];
let branchName = '';

test.describe.configure({ mode: 'serial', timeout: 300_000 });

test.beforeAll(async ({ request }) => {
  branchName = uniqueName('e2e-pack-gate');

  // 1. Loopback OpenAI-compatible mock. First non-safety call answers with a git_push
  //    tool call; once the tool result comes back it answers with plain content.
  mockServer = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => (raw += chunk));
    req.on('end', () => {
      mockRequests.push(raw);
      let body: any = {};
      try {
        body = JSON.parse(raw || '{}');
      } catch {
        /* non-JSON keepalive */
      }
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const hasToolResult = messages.some((m: any) => m.role === 'tool');
      const isSafetyReview = raw.includes('safety reviewer');
      // Only THIS spec's run (its prompt/tool result carries the unique branch name) gets the
      // git_push tool call; anything else (a foreign spec's run racing the provider switch)
      // receives a benign plain completion so it never stalls on a fabricated approval gate.
      const isOurs = raw.includes(branchName);

      const message: any = { role: 'assistant', content: null };
      let finishReason = 'tool_calls';
      if (isSafetyReview) {
        message.content = 'PASS\nlooks safe';
        finishReason = 'stop';
      } else if (hasToolResult || !isOurs) {
        message.content = hasToolResult ? 'git_push finished.' : 'ok';
        finishReason = 'stop';
      } else {
        message.tool_calls = [
          {
            id: 'call_e2e_git_push',
            type: 'function',
            function: { name: 'git_push', arguments: JSON.stringify({ branch: branchName, remote: 'origin' }) },
          },
        ];
      }

      const payload = JSON.stringify({
        id: 'chatcmpl-e2e-git-pack-gate',
        object: 'chat.completion',
        created: 0,
        model: body.model ?? 'e2e-mock-model',
        choices: [{ index: 0, message, finish_reason: finishReason }],
        usage: { prompt_tokens: 11, completion_tokens: 7, total_tokens: 18 },
      });
      res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) });
      res.end(payload);
    });
  });
  await new Promise<void>((resolve) => mockServer.listen(0, '127.0.0.1', resolve));
  mockPort = (mockServer.address() as any).port;

  // 2. Local substitute for the git remote: a bare repository on disk.
  tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'aria-pack-gate-'));
  fs.mkdirSync(path.join(tempRoot, 'remote.git'));
  git(['init', '--bare', 'remote.git'], tempRoot);

  // 3. Point the ACTIVE LLM provider at the mock (langchain ADK reads it per call).
  // A previous interrupted run can leave a mock provider active, which would silently
  // break every other LLM call — self-heal it before doing anything else.
  const providers = await apiCall(request, 'GET', '/llm-providers');
  const all: any[] = providers.data ?? [];
  const isMock = (p: any) => String(p?.name ?? '').startsWith(MOCK_PROVIDER_PREFIX);
  const real = all.filter((p) => !isMock(p));
  previouslyActiveProviderId = (real.find((p) => p.active) ?? real[0])?.id ?? null;
  if (previouslyActiveProviderId && !real.some((p) => p.active)) {
    await apiCall(request, 'POST', `/llm-providers/${previouslyActiveProviderId}/activate`);
  }
  for (const stale of all.filter(isMock)) {
    await apiCall(request, 'DELETE', `/llm-providers/${stale.id}`);
  }

  const created = await apiCall(request, 'POST', '/llm-providers', {
    name: uniqueName(MOCK_PROVIDER_PREFIX),
    type: 'OPENAI',
    baseUrl: `http://127.0.0.1:${mockPort}/v1`,
    apiKey: 'e2e-mock-key',
    defaultModel: 'e2e-mock-model',
  });
  expect(created.status, `create mock provider: ${JSON.stringify(created.data)}`).toBe(201);
  mockProviderId = created.data.id;

  const activated = await apiCall(request, 'POST', `/llm-providers/${mockProviderId}/activate`);
  expect(activated.status).toBe(200);
  expect(activated.data.active).toBe(true);
});

test.afterAll(async ({ request }) => {
  // Restore the shared configuration first — nothing below depends on it. Both calls are
  // ASSERTED: the provider switch is global, so a teardown that fails silently would leave
  // the mock ACTIVE and route every later LLM call on this stack into the dead loopback
  // port, with no failure reported. (A hard-killed worker never runs afterAll at all — the
  // describe has a 300s timeout — so the loud path guards exactly the recoverable case:
  // an unhealthy stack at teardown.)
  if (previouslyActiveProviderId) {
    const restored = await apiCall(request, 'POST', `/llm-providers/${previouslyActiveProviderId}/activate`);
    // Activate is idempotent — activating an already-active provider returns 200
    // (LlmProviderService.activate:85-89) — so a healthy restore passes whether or not
    // the switch had actually happened.
    expect(
      restored.status,
      `restore LLM provider ${previouslyActiveProviderId}: ${JSON.stringify(restored.data)}`,
    ).toBe(200);
  }
  if (mockProviderId) {
    const deleted = await apiCall(request, 'DELETE', `/llm-providers/${mockProviderId}`);
    // A successful delete is 204 No Content (LlmProviderController.java:48-52; verified
    // against the live stack: first delete 204, repeat 404), and 404 is accepted too: the
    // row being gone already is the desired end state (a re-run's self-heal or an earlier
    // teardown may have removed it). Only a surviving mock matters, not a missing one.
    expect(
      [200, 204, 404],
      `delete mock LLM provider ${mockProviderId}: ${JSON.stringify(deleted.data)}`,
    ).toContain(deleted.status);
  }
  if (mockServer) {
    await new Promise<void>((resolve) => mockServer.close(() => resolve()));
  }
  // Remove the fixture workspaces this spec created (the app cleans only its own root).
  for (const dir of createdWorkspaceDirs) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  if (tempRoot && fs.existsSync(tempRoot)) {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

test('a run blocks on the git_push PUSH gate and resumes after approval, pushing to the local remote', async ({
  request,
}) => {
  // A role=dev ADK(langchain) agent resolves the git pack tools (incl. git_push).
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-git-gate-agent'),
    adkProvider: 'langchain',
  });

  const started = await apiCall(request, 'POST', '/runs', {
    agentId: agent.id,
    promptSeed: `Push branch ${branchName} to origin.`,
    maxIterations: 1,
  });
  expect([200, 201]).toContain(started.status);
  const runId: string = started.data.id;

  // ── BLOCKED: the PUSH tier routes the tool call through the human gate ────────
  const asks = await pollUntil<any[]>(
    request,
    '/approvals',
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING' && a.runId === runId),
    120_000,
    2_000,
  );
  const ask = asks.find((a) => a.status === 'PENDING' && a.runId === runId)!;
  // The TOOL gate carries the tool call; the task-level gate (gap 1) does not.
  expect(ask.toolCallId, 'the PUSH gate must be linked to a tool call').toBeTruthy();
  expect(ask.reason).toContain('git_push');
  expect(ask.approvalType).toBe('TOOL_CALL');

  const blockedCalls = await apiCall(request, 'GET', `/runs/${runId}/tool-calls`);
  const gitPushCall = (blockedCalls.data ?? []).find((tc: any) => tc.toolName === 'git_push')!;
  expect(gitPushCall, 'the blocked tool call must be git_push').toBeTruthy();
  expect(gitPushCall.status).toBe('PENDING');

  const blockedRun = await pollUntil<any>(
    request,
    `/runs/${runId}`,
    (run) => run.status === 'PAUSED',
    15_000,
    1_000,
  );
  expect(blockedRun.status).toBe('PAUSED');

  // The gate is open: the tool has NOT executed yet (no result on the call).
  expect(gitPushCall.result).toBeNull();

  // ── Prepare the local remote while the gate is open ──────────────────────────
  // The run workspace is <workspace-root>/<runId>; turning it into a git repo with a
  // local `origin` makes the approved push land in a bare repo the spec can read.
  // Prepared in every candidate root, since the root follows the backend's CWD. The
  // remote only reveals that the push came from ONE of them, so collect each root's sha
  // and assert membership below rather than equality with one (arbitrary) candidate.
  const workspaceShas: string[] = [];
  for (const root of candidateWorkspaceRoots()) {
    const workspace = path.join(root, runId);
    try {
      fs.mkdirSync(workspace, { recursive: true });
      git(['init'], workspace);
      git(['checkout', '-b', branchName], workspace);
      git(['commit', '--allow-empty', '-m', 'e2e pack gate commit'], workspace);
      git(['remote', 'add', 'origin', path.join(tempRoot, 'remote.git')], workspace);
      createdWorkspaceDirs.push(workspace);
      workspaceShas.push(git(['rev-parse', 'HEAD'], workspace));
    } catch (e: any) {
      throw new Error(`could not prepare workspace candidate ${workspace}: ${e?.message}`);
    }
  }
  expect(createdWorkspaceDirs.length).toBeGreaterThan(0);

  // ── RESUMED: approve and watch the governed push execute ────────────────────
  const decided = await apiCall(request, 'POST', `/approvals/${ask.id}/decide`, {
    approved: true,
    reason: 'e2e git pack gate approval',
  });
  expect(decided.status).toBe(200);

  const settled = await pollUntil<any[]>(
    request,
    `/runs/${runId}/tool-calls`,
    (calls) =>
      Array.isArray(calls) &&
      calls.some((tc) => tc.toolName === 'git_push' && TERMINAL_TOOL_CALL.includes(tc.status)),
    120_000,
    2_000,
  );
  const settledCall = settled.find((tc) => tc.toolName === 'git_push')!;
  expect(settledCall.status, `git_push result: ${settledCall.result}`).toBe('COMPLETED');

  const run = await pollUntil<any>(
    request,
    `/runs/${runId}`,
    (r) => TERMINAL_RUN.includes(r.status),
    120_000,
    2_000,
  );
  expect(run.status).toBe('COMPLETED');

  // The push really happened against the local remote: the branch is there and points
  // at a commit the spec created in one of the prepared workspace candidates. The sha is
  // compared by membership, not equality, because the live root is whichever candidate
  // the backend's CWD maps to and the remote cannot tell us which one that was.
  const remoteSha = git(
    ['--git-dir', path.join(tempRoot, 'remote.git'), 'rev-parse', `refs/heads/${branchName}`],
    tempRoot,
  );
  expect(
    workspaceShas,
    `remote sha ${remoteSha} must come from a prepared workspace candidate: ${JSON.stringify(workspaceShas)}`,
  ).toContain(remoteSha);
  expect(mockRequests.length).toBeGreaterThan(0);
});
