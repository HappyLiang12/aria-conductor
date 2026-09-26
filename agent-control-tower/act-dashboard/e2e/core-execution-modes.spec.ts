import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  apiCall,
  decideApproval,
  pendingRunApprovals,
  pollRunTerminal,
  pollUntil,
  runExecutionBinding,
  seedAdkAgent,
  seedRun,
  setScenario,
  settleRunApproval,
  uniqueName,
} from './fixtures';

/**
 * Task 17: governed execution modes on the deterministic core harness.
 *
 * Carries the coverage-map rows whose replacement is this spec:
 *  - adk-providers.spec.ts (provider inventory → core catalog)
 *  - harness-governance.spec.ts (execution settings contract)
 *  - journey-agent-run-report.spec.ts / journey-crew-deploy-run.spec.ts /
 *    runs-page.spec.ts / kanban-board.spec.ts / multi-agent-lifecycle.spec.ts
 *    (run path on the deterministic harness)
 *  - real-llm-scenarios.spec.ts rl-01 (providers page halves; rl-02 is live-matrix)
 *  - langchain-adk-e2e.spec.ts (exchangeable-provider run path)
 *
 * Known pending wire (reported, not worked around): the production coordinator
 * and admission wiring land in Task 18, and the harness serves
 * `POST /maintenance/core-e2e/scenario` only once that wiring exists. Every
 * assertion below therefore fails loudly — with an exact terminal expectation —
 * rather than being skipped or relaxed.
 */

// ── Step 1: the four supported core/mode combinations complete a task ───────
// The committed scenario that completes with the exact fixture output
// 'fixture-complete' is 'reported-usage' (scenarios.json: the 'complete'
// scenario's fixture completion is 'pong'); the peers are not extended here
// because this round's file scope is the dashboard e2e specs plus the coverage
// map.
//
// Fix round 2 — the approval step is contract-accurate, never a skip: a
// core-owned run opens the run-gate ask only when the core raises a permission
// need, and the read-only 'reported-usage' completion asks nothing by contract
// (AgentLoopEngine's coordinated branch returns before the legacy task-gate
// call site). Each case therefore settles exactly one of the two DOCUMENTED
// outcomes through settleRunApproval and asserts that outcome's own exact
// evidence: an ask -> approved once -> the same terminal completion; no ask ->
// the run reaches the same terminal completion on its own frozen execution
// binding, read back through the harness's read-only binding route (core, mode,
// settings-revision and version recorded). Neither outcome is a "skip if
// absent": a run that opens no ask must still complete exactly, and a run that
// opens one must have every ask decided before it may complete. Permission
// governance keeps its dedicated ask/deny/expiry cases in
// core-permissions.spec.ts.
for (const { core, mode } of [
  { core: 'qoder', mode: 'HOST' },
  { core: 'qoder', mode: 'SANDBOX' },
  { core: 'opencode', mode: 'HOST' },
  { core: 'opencode', mode: 'SANDBOX' },
] as const) {
  test(`${core}/${mode} completes a coding task with exact output`, async ({ request }) => {
    const agent = await seedAdkAgent(request, { adkProvider: core, executionMode: mode });
    await setScenario(request, agent.id, 'reported-usage');
    const run = await seedRun(request, agent.id);

    // Outcome 1 (an ask) or outcome 2 (no ask), each settled to the terminal
    // state; the annotation records which one this run took.
    const gate = await settleRunApproval(request, run.id);
    test.info().annotations.push({
      type: 'run-gate-outcome',
      description:
        `${core}/${mode}: ` +
        (gate.approvedAskIds.length === 0
          ? 'no ask opened (read-only completion)'
          : `${gate.approvedAskIds.length} ask(s) approved before the completion`),
    });
    if (gate.approvedAskIds.length > 0) {
      // The ask outcome's exact evidence: every captured ask id was decided
      // exactly once (decideApproval verifies the processed decision field by
      // field and throws otherwise).
      expect(new Set(gate.approvedAskIds).size).toBe(gate.approvedAskIds.length);
    }

    const done = await pollRunTerminal(request, run.id);
    expect(done.status).toBe('COMPLETED');
    expect(done.finalOutput).toBe('fixture-complete');

    // The completion was served by this run's own frozen binding: the
    // coordinated core path refuses to launch against anything else, and the
    // recorded row must carry exactly the frozen core/mode plus the runtime
    // state the coordinator observed (freeze -> RUNNING -> terminal = the
    // two version-advancing writes, hence version 2).
    const binding = await runExecutionBinding(request, run.id);
    expect(binding.runId).toBe(run.id);
    expect(binding.agentId).toBe(agent.id);
    expect(binding.coreId).toBe(core);
    expect(binding.executionMode).toBe(mode);
    expect(binding.runtimeState).toBe('COMPLETED/BACKEND_SUSPEND');
    expect(binding.version).toBe(2);
  });
}

// ── agent create/configure: the selection is persisted exactly ──────────────

/** Git with a pinned identity so a commit is reproducible on any machine. */
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

/** A disposable real git repository: admission refuses a WORKTREE selection without one. */
function disposableGitRepository(): string {
  const repo = mkdtempSync(join(tmpdir(), 'aria-e2e-settings-repo-'));
  writeFileSync(join(repo, 'tracked.txt'), 'committed\n');
  git(['init'], repo);
  git(['checkout', '-b', 'main'], repo);
  git(['add', 'tracked.txt'], repo);
  git(['commit', '-m', 'e2e settings baseline'], repo);
  return repo;
}

test('agent create/configure keeps the captured core, mode and workspace selection', async ({ request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-core-settings'),
    adkProvider: 'qoder',
    executionMode: 'HOST',
    workspaceMode: 'WORKTREE',
    workspacePath: disposableGitRepository(),
    workspaceBaseRef: 'main',
  });

  const { status, data } = await apiCall(request, 'GET', `/agents/${agent.id}`);
  expect(status).toBe(200);
  expect(data.adkProvider).toBe('qoder');
  expect(data.executionMode).toBe('HOST');
  expect(data.workspaceMode).toBe('WORKTREE');
  expect(data.workspaceBaseRef).toBe('main');
});

// ── core inventory: exactly the production cores, no LangChain row ──────────
test('the provider inventory is exactly the production core catalog', async ({ request }) => {
  const { status, data } = await apiCall(request, 'GET', '/adk/providers');
  expect(status).toBe(200);
  expect(Array.isArray(data)).toBeTruthy();

  const ids = (data as Array<{ id: string }>).map((p) => p.id).sort();
  expect(ids).toEqual(['opencode', 'qoder']);

  const defaults = (data as Array<{ id: string; isDefault: boolean }>).filter((p) => p.isDefault);
  expect(defaults.map((p) => p.id)).toEqual(['opencode']);
});

// ── control: cancel is the exact ack and the exact terminal state ───────────
test('cancelling a run is acknowledged and reaches exactly CANCELLED', async ({ request }) => {
  const agent = await seedAdkAgent(request, { adkProvider: 'opencode', executionMode: 'HOST' });
  await setScenario(request, agent.id, 'pause-resume');
  const run = await seedRun(request, agent.id);

  const cancelled = await apiCall(request, 'POST', `/runs/${run.id}/cancel`);
  expect(cancelled.status).toBe(200);
  expect(cancelled.data.status).toBe('CANCELLED');

  const done = await pollRunTerminal(request, run.id);
  expect(done.status).toBe('CANCELLED');
});

/**
 * Approves every ask the run opens, in order, and returns the captured ask ids.
 * The caller has already observed the run exactly RUNNING: the long-running
 * scenarios hold their prompt open while the asked-for work runs, and the ask
 * the run is blocked on must be granted (exactly once — a decision that is not
 * processed throws inside {@link decideApproval}) before the writer exists.
 */
async function grantRunAsks(
  request: Parameters<typeof seedAdkAgent>[0],
  runId: string,
  timeoutMs = 90_000,
) {
  const decided: string[] = [];
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const pending = await pendingRunApprovals(request, runId, 15_000).catch(() => []);
    const next = (pending as any[]).find((a) => !decided.includes(a.id));
    if (next) {
      await decideApproval(request, next.id, true, 'e2e control: one-use grant');
      decided.push(next.id);
      continue;
    }
    if (decided.length > 0) return decided;
    if (Date.now() > deadline) {
      throw new Error(`run ${runId} opened no decidable ask`);
    }
    await new Promise((r) => setTimeout(r, 1_000));
  }
}

/** The byte size of the run's writer log (0 while absent). */
function writerBytes(ticks: string): number {
  try {
    return statSync(ticks).size;
  } catch {
    return 0;
  }
}

// ── control: truthful pause ack, frozen writer bytes, resume, exact cancel ──
//
// Fix round 3 (2026-09-26) — the premise of this case, recorded here and in the
// coverage map: the run must be observably RUNNING (its core's prompt held open)
// while the owned writer is live, or there is nothing truthful to pause. The
// committed scenarios that HOLD a prompt are: the opencode `git-push` gate (held
// until its decision resolves, scenarios.json) and the qoder `pause-resume`
// prompt (peer suite: "the pause-resume prompt stays open until the session is
// cancelled"). The opencode `pause-resume` prompt completes by contract — the
// peer suite pins the immediate `DONE` response and only the writer survives
// (`peer-actions.test.mjs:1377-1382`) — so an opencode `pause-resume` run is
// COMPLETED before any pause can be truthful. The case is therefore pinned to
// the qoder core, whose harness session answers pause/resume with the harness
// process transport's verified process-tree suspension (the recorded control
// technique; the bridge protocol has no pause RPC). The writer's bytes are then
// observed directly in the run's admitted workspace: the `workspace-diff` route
// reads the engine's legacy per-run scratch directory, not the run-owned
// admitted workspace, and its own contract stays covered by
// core-workspaces.spec.ts.
test('a pause is acknowledged truthfully and freezes the writer until resume', async ({ request }) => {
  const workspace = mkdtempSync(join(tmpdir(), 'aria-e2e-pause-'));
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-pause'),
    adkProvider: 'qoder',
    executionMode: 'HOST',
    workspaceMode: 'DIRECT',
    workspacePath: workspace,
  });
  await setScenario(request, agent.id, 'pause-resume');
  const run = await seedRun(request, agent.id);

  // ── the premise: RUNNING while the core's prompt is held open ─────────────
  const started = await pollUntil<any>(
    request,
    `/runs/${run.id}`,
    (r) => r?.status === 'RUNNING',
    60_000,
    500,
  );
  expect(started.status).toBe('RUNNING');

  // The held prompt is waiting on exactly the execute permission the writer is
  // gated on: grant it once and the owned writer starts writing.
  const grants = await grantRunAsks(request, run.id);
  expect(grants.length).toBeGreaterThan(0);
  expect(new Set(grants).size).toBe(grants.length);
  const stillRunning = await apiCall(request, 'GET', `/runs/${run.id}`);
  expect(stillRunning.data?.status).toBe('RUNNING');

  const ticks = join(workspace, 'ticks.log');
  await expect
    .poll(() => writerBytes(ticks), { timeout: 30_000, intervals: [500, 1_000] })
    .toBeGreaterThan(0);

  // ── pause: the acknowledgement states exactly PAUSED ──────────────────────
  // A PAUSED ack is only truthful when the runtime reached PAUSED: the
  // coordinator's verified ControlAck gates the persisted state, so a pause the
  // core did not confirm can never be answered as PAUSED.
  const paused = await apiCall(request, 'POST', `/runs/${run.id}/pause`);
  expect(paused.status, JSON.stringify(paused.data)).toBe(200);
  expect(paused.data?.status).toBe('PAUSED');
  const pausedReadBack = await apiCall(request, 'GET', `/runs/${run.id}`);
  expect(pausedReadBack.status).toBe(200);
  expect(pausedReadBack.data?.status).toBe('PAUSED');

  // ── no writes after pause: the writer's bytes are frozen ──────────────────
  const frozen = writerBytes(ticks);
  expect(frozen).toBeGreaterThan(0);
  await new Promise((r) => setTimeout(r, 3_000));
  expect(writerBytes(ticks)).toBe(frozen);

  // ── resume: the writer continues, so the same file must grow ──────────────
  const resumed = await apiCall(request, 'POST', `/runs/${run.id}/resume`);
  expect(resumed.status, JSON.stringify(resumed.data)).toBe(200);
  expect(resumed.data?.status).toBe('RUNNING');
  await expect
    .poll(() => writerBytes(ticks), { timeout: 60_000, intervals: [1_000, 2_000, 5_000] })
    .toBeGreaterThan(frozen);

  // ── cancel: the exact ack and the exact terminal state ────────────────────
  const cancelled = await apiCall(request, 'POST', `/runs/${run.id}/cancel`);
  expect(cancelled.status, JSON.stringify(cancelled.data)).toBe(200);
  expect(cancelled.data?.status).toBe('CANCELLED');
  const done = await pollRunTerminal(request, run.id);
  expect(done.status).toBe('CANCELLED');
});
