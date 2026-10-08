import { test, expect } from '@playwright/test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import {
  BACKEND,
  OPERATOR_BEARER_TOKEN,
  apiCall,
  decideApproval,
  operatorApiCall,
  pendingRunApprovals,
  permissionOptions,
  pollRunTerminal,
  pollUntil,
  runApprovals,
  runWorkerToken,
  seedAdkAgent,
  seedKanbanItem,
  seedRun,
  setScenario,
  transitionKanban,
  uniqueName,
} from './fixtures';

/**
 * Task 17: the governed permission surface (Task 12) on the deterministic
 * harness.
 *
 * Carries the coverage-map rows whose replacement is this spec:
 *  - review-decision-zone.spec.ts + approvals-decision-flow.spec.ts
 *    (decision zone resolves the ask)
 *  - git-pack-governance.spec.ts (risk-tier gate + approval lifecycle)
 *  - ops-approval-surface.spec.ts (approving from an operator surface)
 *  - api/approval-denial.api.spec.ts (denial reason persists, no denied effect)
 *  - api/git-pack-gate.api.spec.ts (block-then-resume)
 *  - the permission half of api/mcp-sdd-workflow.api.spec.ts
 *
 * Reach path (LLM-free, CI-safe): pin a task-capable opencode agent on a kanban
 * card, dispatch it, and the deterministic peer scenario raises the native
 * permission ask the run is blocked on. The default-on legacy task gate is gone
 * with the ADK runtime: a core-owned run asks only when its core raises a
 * permission need, and that ask is the peer's recorded decision (the T7
 * fixtures), registered by `CoreExecutionService.registerAsk`.
 *
 * Fix round 3 (2026-09-26) — the lookup contract, recorded here and in the
 * coverage map: a NATIVE permission ask is correlated with its RUN
 * (`PermissionCoordinator.register` writes `acp_permission_requests.run_id` and
 * an `Approval.runId`, and deliberately sets no `kanbanItemId`), so
 * `nativeRunAsks` resolves it by runId through `/approvals` +`pendingRunApprovals`.
 * The card path (`/approvals?kanbanItemId=`) belongs to GATE asks: its only
 * writer is the gate path's review-card creator (`KanbanReviewAskCreator` sets
 * `kanbanItemId`), and `cardLinkedAsks` keeps that path explicit so the two
 * correlation contracts are never conflated. No assertion moved: the ask is
 * still required to be PENDING, to offer exactly the recorded one-use grant and
 * reject option, and to be decided exactly once.
 *
 * The decision is operator-only (Task 12). The worker arm (a run-scoped worker
 * token refused with 403) and the two MCP transports use the live run's worker
 * credential, minted by the harness route for the run the native ask belongs to.
 */

// Every case dispatches its own card and agent and asserts exact captured
// state; cases are independent so one refusal can never hide another's outcome.
test.describe.configure({ timeout: 180_000 });

/** The exact optimistic-lock 409 body (GlobalExceptionHandler.handleOptimisticLock). */
const OPTIMISTIC_LOCK_409 = 'Card was modified by another move — refresh and retry.';

/**
 * True only for the disclosed optimistic-lock race: the pickup's AFTER_COMMIT
 * listener updates the card concurrently, so the transition response can be the
 * server's recorded 409 for the raced row. Any other refusal (a 409 from a
 * refused transition, a 500, ...) is not a race and is never retried.
 */
function isOptimisticLockRace(result: { status: number; data: any }): boolean {
  return result.status === 409 && result.data?.message === OPTIMISTIC_LOCK_409;
}

/**
 * Dispatch a card for a fresh opencode agent and capture the PENDING native
 * ask. The agent carries the explicit harness selection (opencode + HOST) and
 * the recorded peer fixture `deny-write` — one native edit permission whose
 * offered options are exactly `proceed_once` (ALLOW_ONCE) and `cancel` (DENY) —
 * so the run behind the ask is a deterministic run rather than a run the
 * harness refuses for a missing scenario. The run owns a DIRECT workspace the
 * spec hands over, so the granted fixture write (and the absence of one after a
 * denial) is observable in the exact bytes on disk.
 */
async function dispatchAsk(request: Parameters<typeof seedAdkAgent>[0]) {
  const workspace = mkdtempSync(join(tmpdir(), 'aria-e2e-perm-ws-'));
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-perm'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'DIRECT',
    workspacePath: workspace,
  });
  await setScenario(request, agent.id, 'deny-write');
  const card = await seedKanbanItem(request, {
    title: uniqueName('perm-card'),
    agentTemplateId: agent.name,
  });

  // The dispatch contract is the captured card state: the two-phase pickup
  // assigns the agent and creates the run, while a commit listener moves the
  // card. Only that concurrent update's optimistic-lock 409 is retried; the
  // accepted transition response is then asserted exactly (status and body),
  // and the card is read back by id.
  const first = await transitionKanban(request, card.id, 'IN_PROGRESS');
  const accepted = isOptimisticLockRace(first)
    ? await transitionKanban(request, card.id, 'IN_PROGRESS')
    : first;
  expect(accepted.status, JSON.stringify(accepted.data)).toBe(200);
  expect(accepted.data?.status).toBe('IN_PROGRESS');
  await pollUntil(
    request,
    `/kanban/items/${card.id}`,
    (item: any) => item?.status === 'IN_PROGRESS',
    30_000,
    500,
  );

  // The pickup links the created run to the card before the move (`linkedRunId`),
  // so the run under test is the card's own linked run — not a guessed row.
  const linked = await pollUntil<any>(
    request,
    `/kanban/items/${card.id}`,
    (item: any) => typeof item?.linkedRunId === 'string' && item.linkedRunId.length > 0,
    30_000,
    500,
  );

  const asks = await nativeRunAsks(request, linked.linkedRunId, 60_000);
  const ask = asks[0];
  // The correlation contract of a native ask: it is resolved by its RUN (the
  // ledger row is keyed run+session+request), and the review-card listener of
  // that run links it to the run's own card so the card's panel can surface the
  // pending permission. The card's own gate ask (askType REVIEW_REQUEST, no
  // offered options) is a different row and stays distinguishable by exactly
  // those two fields.
  expect(ask.runId).toBe(linked.linkedRunId);
  expect(ask.kanbanItemId).toBe(card.id);
  expect(ask.askType).toBe('APPROVAL');
  const cardAsks = await cardLinkedAsks(request, card.id);
  expect(cardAsks.map((a: any) => a.id)).toContain(ask.id);
  // The card's own gate ask (askType REVIEW_REQUEST, no offered options) is a
  // different row; it is created when the card reaches review, so at this point
  // of the flow the native ask is the card's only ask and is identifiable by its
  // offered options, which the case asserts next.
  expect(cardAsks.every((a: any) => a.id === ask.id)).toBe(true);
  return { agent, card, runId: linked.linkedRunId as string, workspace, ask };
}

/**
 * The teardown the originals carried (kanban-hitl.spec.ts:174-177,
 * api/approval-denial.api.spec.ts:76-79, api/approval-request-changes.api.spec.ts:95-99):
 * cancel the dispatched card so a passing case does not leave an IN_PROGRESS
 * card behind. Only the same disclosed optimistic-lock 409 is retried; the
 * accepted response is asserted exactly.
 */
async function cancelCard(request: Parameters<typeof seedAdkAgent>[0], cardId: string) {
  const first = await transitionKanban(request, cardId, 'CANCELLED');
  const accepted = isOptimisticLockRace(first)
    ? await transitionKanban(request, cardId, 'CANCELLED')
    : first;
  expect(accepted.status, JSON.stringify(accepted.data)).toBe(200);
  expect(accepted.data?.status).toBe('CANCELLED');
}

/**
 * The run's PENDING native asks, resolved by RUN (`/approvals` filtered by the
 * correlated ask's runId). The correlation is the run (the ledger row is keyed
 * run+session+request) and the offered options are the discriminator against
 * the card's own gate ask: a native ask carries the core's offered options,
 * while the gate/review ask (`askType REVIEW_REQUEST`) carries none.
 */
async function nativeRunAsks(
  request: Parameters<typeof seedAdkAgent>[0],
  runId: string,
  timeoutMs = 30_000,
) {
  const isNativeAsk = (a: any) =>
    a?.status === 'PENDING'
    && a?.runId === runId
    && a?.askType !== 'REVIEW_REQUEST'
    && typeof a?.optionsJson === 'string'
    && a.optionsJson.trim() !== '';
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const approvals = await pendingRunApprovals(request, runId, 15_000).catch(() => []);
    const native = (approvals as any[]).filter(isNativeAsk);
    if (native.length > 0) return native;
    if (Date.now() > deadline) {
      throw new Error(`no PENDING native permission ask appeared for run ${runId}`);
    }
    await new Promise((r) => setTimeout(r, 1_000));
  }
}

/**
 * The card-linked asks of a kanban card — the GATE-ask path. Only the gate
 * path's review-card creator writes `kanbanItemId`
 * (`KanbanReviewAskCreator`), so this lookup is the contract-accurate way to
 * observe a card's own ask and the wrong one for a native permission ask.
 */
async function cardLinkedAsks(request: Parameters<typeof seedAdkAgent>[0], cardId: string) {
  const { status, data } = await apiCall(request, 'GET', `/approvals?kanbanItemId=${cardId}`);
  expect(status).toBe(200);
  return (data as any[] | null) ?? [];
}

test('an unverifiable bearer falls through to loopback authority', async ({ request }) => {
  const { card, ask } = await dispatchAsk(request);

  // An unverifiable bearer credential counts as "no identity presented" and
  // falls through to the loopback/401 rules (local-authority simplification).
  // The e2e harness calls the backend from loopback, so the local operator
  // authority applies and the decision is processed. The non-loopback refusal
  // is pinned at the MockMvc and integration tiers (ApprovalFlowIntegrationTest
  // forwards an X-Forwarded-For client through a trusted proxy for it).
  const forged = await request.fetch(`${BACKEND}/approvals/${ask.id}/decide`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer not-a-real-credential' },
    data: JSON.stringify({ approved: true, reason: 'forged attempt' }),
  });
  expect(forged.status()).toBe(200);
  expect(await forged.json()).toMatchObject({ status: 'processed' });

  const { data: after } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
  expect(after.status).toBe('APPROVED');

  await cancelCard(request, card.id);
});

test('the ask offers exactly the one-use grant and the reject option', async ({ request }) => {
  const { card, ask } = await dispatchAsk(request);
  expect(permissionOptions(ask)).toEqual([
    { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
    { optionId: 'cancel', choice: 'DENY' },
  ]);

  await cancelCard(request, card.id);
});

test('an operator allow-once grant resolves the ask and the run leaves PAUSED', async ({ request }) => {
  const { card, workspace, ask } = await dispatchAsk(request);

  await decideApproval(request, ask.id, true, 'e2e operator one-use grant');

  const { data: decided } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
  expect(decided.status).toBe('APPROVED');

  // The granted reply reaches the run's own core session: the peer applies the
  // gated edit only after the genuine allow-once decision, so the exact fixture
  // bytes appear in the run's admitted workspace (the harness round trip
  // `/__peer/pending` -> ask -> `/__peer/decision` is the delivery under test).
  await expect
    .poll(
      () => {
        try {
          return readFileSync(join(workspace, 'probe-deny.txt'), 'utf8');
        } catch {
          return null;
        }
      },
      { timeout: 30_000, intervals: [500, 1_000] },
    )
    .toBe('beta-should-not-exist');

  const { data: run } = await apiCall(request, 'GET', `/runs/${ask.runId}`);
  expect(run.status).not.toBe('PAUSED');

  await cancelCard(request, card.id);
});

test('a decided ask refuses a second decision (grant is consumed once)', async ({ request }) => {
  const { card, ask } = await dispatchAsk(request);
  await decideApproval(request, ask.id, true, 'e2e first decision');

  const again = await operatorApiCall(request, 'POST', `/approvals/${ask.id}/decide`, {
    approved: true,
    reason: 'e2e second decision',
  });
  expect(again.status).toBe(409);
  expect(typeof again.data?.error).toBe('string');
  expect(again.data.error.length).toBeGreaterThan(0);

  await cancelCard(request, card.id);
});

test('an operator denial is recorded and applies no denied effect', async ({ request }) => {
  const { card, workspace, ask } = await dispatchAsk(request);

  await decideApproval(request, ask.id, false, 'e2e operator denial with reason');

  const { data: decided } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
  expect(decided.status).toBe('DENIED');
  // The recorded verdict is the coordinator's own decision text for the offered
  // reject option (PermissionCoordinator.decisionReason), with the peer's
  // recorded request id and tool name.
  expect(decided.reason).toBe('Operator denied Write (native permission request 0)');

  // The native-ask contract after a denial (design V4, peer suite: "denial
  // produces no side effect"): the gated edit is never applied. The allow-once
  // grant above writes `probe-deny.txt` with these exact bytes, so the absence
  // is the denial's own evidence, not a vacuous check.
  expect(readdirSync(workspace)).not.toContain('probe-deny.txt');

  // Fix round 3 re-pin, recorded in the coverage map: the run's own outcome
  // after a denied ask is its core's outcome (the deny-write fixture reports the
  // decision window and completes). The legacy task-gate semantics that
  // cancelled a run on a denial belonged to the retired ADK task gate; the
  // coordinated contract settles the ask (DENIED, reason recorded) and answers
  // the core with the offered reject option.
  const done = await pollRunTerminal(request, ask.runId);
  expect(done.status).toBe('COMPLETED');

  await cancelCard(request, card.id);
});

test('an expired ask is EXPIRED and refuses a late grant', async ({ request }) => {
  // The recorded expiry fixture: the peer closes its permission window after
  // 300 ms (scenarios.json 'permission-expiry'), so the ask it registers must
  // expire rather than stay decidable.
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-expiry'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
  });
  await setScenario(request, agent.id, 'permission-expiry');
  const run = await seedRun(request, agent.id);
  // The ask is captured at any status: its own 300 ms window may already have
  // closed by the time the list is read, which is exactly what this case exists
  // to pin. The offered options still read what the core asked for.
  const asks = await runApprovals(request, run.id, 60_000);
  // Deterministic selection: a completed run's kanban card also carries a
  // REVIEW_REQUEST mirror ask (the Review column's own surface), while this
  // case is about the NATIVE permission ask the core raised — the one whose
  // offered option set this test pins. The list order is not a contract.
  const ask = asks.find((a) => a.askType !== 'REVIEW_REQUEST');
  expect(ask).toBeTruthy();
  expect(permissionOptions(ask)).toEqual([
    { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
    { optionId: 'cancel', choice: 'DENY' },
  ]);

  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
        return data?.status;
      },
      // The scheduled expiry sweep runs once a minute, so a 300 ms decision
      // window is adjudicated at the next sweep: wait past it rather than
      // loosening anything about the outcome.
      { timeout: 120_000 },
    )
    .toBe('EXPIRED');

  const late = await operatorApiCall(request, 'POST', `/approvals/${ask.id}/decide`, {
    approved: true,
    reason: 'e2e late grant after expiry',
  });
  expect(late.status).toBe(409);

  const { data: after } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
  expect(after.status).toBe('EXPIRED');
  // The adjudication is the ask's own timeout, never a consequence of the run
  // ending: an ask whose core window closed reads as expired, not cancelled.
  expect(after.reason).toBe('Auto-rejected: approval expired');
});

test('the operator credential the fixtures use is the harness credential', async ({ request }) => {
  // The whole spec depends on operator authority being the environment-supplied
  // synthetic credential, not a default: prove the wiring is live.
  expect(OPERATOR_BEARER_TOKEN).not.toBe('');
  const { status } = await operatorApiCall(request, 'POST', '/maintenance/initialize-builtins');
  expect(status).toBe(200);
});

// ─────────────────────────────────────────────────────────────────────────────
// MCP parity (real HTTP, both Java transports) and the worker boundary
// ─────────────────────────────────────────────────────────────────────────────

const API_ORIGIN = new URL(BACKEND).origin;

/** Connects the external MCP client to one transport, authenticated as the worker. */
async function connectMcp(kind: 'streamable' | 'sse', workerToken: string): Promise<Client> {
  const headers = { Authorization: `Bearer ${workerToken}` };
  const client = new Client({ name: 'core-e2e-worker', version: '0.1.0' });
  if (kind === 'streamable') {
    await client.connect(
      new StreamableHTTPClientTransport(new URL(`${API_ORIGIN}/mcp`), { requestInit: { headers } }),
    );
  } else {
    await client.connect(
      new SSEClientTransport(new URL(`${API_ORIGIN}/sse`), { requestInit: { headers } }),
    );
  }
  return client;
}

/** Calls a tool and parses the uniform JSON envelope without asserting ok. */
async function callEnvelope(client: Client, name: string, args: Record<string, unknown>) {
  const result = await client.callTool({ name, arguments: args });
  const text = (result.content as Array<{ type: string; text: string }>)
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('');
  expect(text, `${name} returned no content`).not.toBe('');
  return JSON.parse(text) as Record<string, any>;
}

test('an authenticated worker sees the same MCP answer over both transports', async ({ request }) => {
  const { card, ask } = await dispatchAsk(request);
  const workerToken = await runWorkerToken(request, ask.runId as string);

  // The same read over the two real HTTP transports: streamable /mcp and the
  // SSE pair (/sse + /mcp/message). Both envelopes must be the recorded answer
  // for this run's own ask — parity by exact equality, not by "both non-empty".
  const streamable = await connectMcp('streamable', workerToken);
  let streamableEnvelope: Record<string, any>;
  try {
    streamableEnvelope = await callEnvelope(streamable, 'list_approvals', { status: 'PENDING' });
  } finally {
    await streamable.close();
  }
  const sse = await connectMcp('sse', workerToken);
  let sseEnvelope: Record<string, any>;
  try {
    sseEnvelope = await callEnvelope(sse, 'list_approvals', { status: 'PENDING' });
  } finally {
    await sse.close();
  }

  expect(streamableEnvelope.ok).toBe(true);
  expect(sseEnvelope.ok).toBe(true);
  expect(streamableEnvelope).toEqual(sseEnvelope);
  const ids = (streamableEnvelope.data as any[]).map((a) => a.id);
  expect(ids).toContain(ask.id);

  await cancelCard(request, card.id);
});

test('a run-scoped worker credential cannot self-approve at REST', async ({ request }) => {
  const { card, ask } = await dispatchAsk(request);
  const workerToken = await runWorkerToken(request, ask.runId as string);

  // The valid worker credential is a real, verifiable identity — and it is still
  // the wrong authority for the operator-only route: exactly 403, not 401 (the
  // credential is valid) and not 200 (the decision must never be processed).
  const attempt = await request.fetch(`${BACKEND}/approvals/${ask.id}/decide`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${workerToken}` },
    data: JSON.stringify({ approved: true, reason: 'worker self-approval' }),
  });
  expect(attempt.status()).toBe(403);

  const { data: after } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
  expect(after.status).toBe('PENDING');

  await cancelCard(request, card.id);
});

test('a run-scoped worker credential cannot self-approve over either MCP transport', async ({ request }) => {
  const { card, ask } = await dispatchAsk(request);
  const workerToken = await runWorkerToken(request, ask.runId as string);

  const attempts: Array<Record<string, any>> = [];
  for (const kind of ['streamable', 'sse'] as const) {
    const client = await connectMcp(kind, workerToken);
    try {
      attempts.push(await callEnvelope(client, 'decide_approval', {
        approvalId: ask.id,
        approved: true,
        reason: `worker self-approval over ${kind}`,
      }));
    } finally {
      await client.close();
    }
  }

  // Both transports deliver the same governed refusal: the tool envelope is a
  // refusal (never a transport failure) naming the exact FORBIDDEN code and the
  // exact operator-authority message.
  expect(attempts).toEqual([
    { ok: false, errorType: 'FORBIDDEN', message: 'Operator authority required' },
    { ok: false, errorType: 'FORBIDDEN', message: 'Operator authority required' },
  ]);

  const { data: after } = await apiCall(request, 'GET', `/approvals/${ask.id}`);
  expect(after.status).toBe('PENDING');

  await cancelCard(request, card.id);
});

// ─────────────────────────────────────────────────────────────────────────────
// governed git push: PUSH-tier gate, resume, disposable bare remote
// ─────────────────────────────────────────────────────────────────────────────

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

/** The run's worktree path, discovered from the source repository's worktree list. */
function runWorktreeOf(sourceRepo: string): string {
  const entries = git(['worktree', 'list', '--porcelain'], sourceRepo)
    .split('\n')
    .filter((line) => line.startsWith('worktree '))
    .map((line) => line.slice('worktree '.length).trim());
  // git reports its own separators; compare normalised paths so the source's
  // own worktree entry is excluded on every platform.
  const sourceCanonical = resolve(sourceRepo).toLowerCase();
  const worktrees = entries.filter((p) => resolve(p).toLowerCase() !== sourceCanonical);
  expect(worktrees, `expected a run worktree beside ${sourceRepo}: ${entries.join(', ')}`).toHaveLength(1);
  return worktrees[0];
}

test('a run blocks on the git_push PUSH gate and the approved push lands in a disposable bare remote', async ({ request }) => {
  test.setTimeout(300_000);

  // A disposable bare remote the spec owns, plus a source repository the run's
  // worktree is created from (the source's `origin` is that bare remote).
  const bareRoot = mkdtempSync(join(tmpdir(), 'aria-e2e-push-remote-'));
  git(['init', '--bare', 'remote.git'], bareRoot);
  const bareRepo = join(bareRoot, 'remote.git');

  const source = mkdtempSync(join(tmpdir(), 'aria-e2e-push-source-'));
  writeFileSync(join(source, 'tracked.txt'), 'committed\n');
  git(['init'], source);
  git(['checkout', '-b', 'main'], source);
  git(['add', 'tracked.txt'], source);
  git(['commit', '-m', 'e2e git push baseline'], source);
  git(['remote', 'add', 'origin', bareRepo], source);

  const branchName = uniqueName('e2e-pack-gate');

  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-git-gate-agent'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'WORKTREE',
    workspacePath: source,
    workspaceBaseRef: 'main',
  });
  await setScenario(request, agent.id, 'git-push');

  const run = await seedRun(request, agent.id, `Push branch ${branchName} to origin.`);

  // ── BLOCKED: the PUSH tier routes the tool call through the human gate ────
  // The task-level gate precedes the tool gate; it is approved as the operator
  // (one-use grant) while the run waits, then the PUSH ask must appear.
  let ask: any = null;
  const gateDeadline = Date.now() + 120_000;
  while (ask == null && Date.now() < gateDeadline) {
    const pending = await pendingRunApprovals(request, run.id, 15_000).catch(() => []);
    const push = (pending as any[]).find(
      (a) => a.approvalType === 'TOOL_CALL' && String(a.reason).includes('git_push'),
    );
    if (push) {
      ask = push;
      break;
    }
    const taskGate = (pending as any[]).find((a) => a.approvalType !== 'TOOL_CALL');
    if (taskGate) {
      await decideApproval(request, taskGate.id, true, 'e2e task gate before the git push gate');
    } else {
      await new Promise((r) => setTimeout(r, 1_000));
    }
  }
  expect(ask, `no git_push TOOL_CALL ask appeared for run ${run.id}`).toBeTruthy();
  // Fix round 7 — the truthful correlation of a NATIVE ask. The production DTO
  // for a core-owned permission ask cannot carry a tool call: the Approval row is
  // written by `PermissionCoordinator.register`, which sets no `toolCallId`, and
  // the platform records no ToolCall row either — the tool call itself happens
  // INSIDE the core (`git_push` runs in the run-owned worktree behind the gate),
  // so the platform's only correlation is the run plus its own ledger row, whose
  // request id and session the registration reason spells exactly. The absent
  // approvalType is rendered as TOOL_CALL by the operator surface
  // (`ApprovalController.toDetail`).
  expect(ask.runId).toBe(run.id);
  expect(ask.approvalType).toBe('TOOL_CALL');
  expect(ask.toolCallId).toBeNull();
  expect(ask.reason).toMatch(
    /^Native permission request \d+ from session ses_[0-9a-f]+ for tool git_push \(NATIVE_TOOL\)$/,
  );

  // The same truthful boundary on the tool-call route: a core-owned run executes
  // its tools inside the core, so the platform records no ToolCall row for the
  // blocked git_push. The run's blocked state is exactly that PENDING ask: the
  // core holds its own turn (the peer withholds the write), so the run stays
  // RUNNING and the remote has no branch yet -- the gate is what blocks the push.
  const blockedCalls = await apiCall(request, 'GET', `/runs/${run.id}/tool-calls`);
  expect(blockedCalls.status).toBe(200);
  expect((blockedCalls.data as any[]).filter((tc) => tc.toolName === 'git_push')).toHaveLength(0);

  const held = await apiCall(request, 'GET', `/runs/${run.id}`);
  expect(held.status).toBe(200);
  expect(held.data?.status).toBe('RUNNING');
  const branchesBefore = execFileSync('git', ['--git-dir', bareRepo, 'branch', '--list', branchName],
      { encoding: 'utf8' }).trim();
  expect(branchesBefore).toBe('');

  // ── Prepare the exact commit the approved push must land ──────────────────
  // The run owns a worktree of the source repository; the spec discovers it from
  // the source's worktree list, writes the exact bytes and records the sha.
  const worktree = runWorktreeOf(source);
  writeFileSync(join(worktree, 'e2e-push.txt'), 'pushed by the governed gate\n');
  git(['add', 'e2e-push.txt'], worktree);
  git(['commit', '-m', 'e2e git push commit'], worktree);
  const pushedSha = git(['rev-parse', 'HEAD'], worktree);
  expect(pushedSha).toMatch(/^[0-9a-f]{40}$/);

  // ── RESUMED: approve and watch the governed push land ─────────────────────
  await decideApproval(request, ask.id, true, 'e2e git push gate approval');

  // The tool call happens inside the core, so the platform records no ToolCall
  // row for it: the push is observed where it lands -- the disposable bare
  // remote must gain exactly the branch at exactly the commit the spec created
  // in the run's worktree, and the run must then reach COMPLETED.
  await expect
    .poll(
      () => {
        try {
          return git(['--git-dir', bareRepo, 'rev-parse', `refs/heads/${branchName}`], bareRoot);
        } catch {
          return null;
        }
      },
      { timeout: 120_000, intervals: [1_000, 2_000] },
    )
    .toBe(pushedSha);

  const done = await pollUntil<any>(
    request,
    `/runs/${run.id}`,
    (r) => ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'].includes(r?.status),
    120_000,
    2_000,
  );
  expect(done.status, JSON.stringify(done).slice(0, 300)).toBe('COMPLETED');

  // The push really happened against the disposable remote: the branch exists
  // and points at exactly the commit the spec created in the run's worktree.
  const remoteSha = git(['--git-dir', bareRepo, 'rev-parse', `refs/heads/${branchName}`], bareRoot);
  expect(remoteSha).toBe(pushedSha);
});
