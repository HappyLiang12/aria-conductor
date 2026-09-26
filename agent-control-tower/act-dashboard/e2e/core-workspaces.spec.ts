import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  apiCall,
  pollRunTerminal,
  pollUntil,
  promoteKnowledge,
  reviewKnowledge,
  seedAdkAgent,
  seedKnowledgeItem,
  seedRun,
  setScenario,
  settleRunApproval,
  uniqueName,
} from './fixtures';

/**
 * Task 17: run-owned workspace governance (Task 6) on the deterministic
 * harness.
 *
 * Carries the coverage-map rows whose replacement is this spec:
 *  - journey-knowledge-promotion.spec.ts (the knowledge-informed run tail that
 *    was LLM-gated becomes the harness-driven completed trajectory here)
 *  - harness-governance.spec.ts (workspace-diff preview)
 *  - the workspace arms of the acceptance matrix V2 (invalid worktree request,
 *    concurrent Direct writer, dirty source preserved) whose reach path is the
 *    production admission wired in Task 18. The "dirty source preserved" arm IS
 *    carried here (the case below re-asserts the exact dirty bytes, the captured
 *    HEAD sha and the captured porcelain status after the run); the coverage map
 *    records it as covered/RED until the harness scenario control exists.
 *
 * Pending wire, reported rather than hidden: Task 18 wires the production
 * admission and coordinator. The refusal expectations are pinned to the exact
 * Task 6 contract (RunWorkspaceService): the launch is refused with the T6
 * message verbatim and never a silently downgraded workspace. Run creation
 * commits the run row before the run's own admission (the cutover design: the
 * attempt resolves core/mode/credential and workspace admission and records the
 * binding), so the refusal is the run's recorded failure -- POST /runs answers
 * 201 and the run reaches exactly FAILED carrying the T6 message in
 * errorMessage (Task 18: "a run whose remaining selection admission refuses is
 * failed with the admission message").
 */

/** A disposable, definitely-not-a-git-repository directory. */
function disposableNonGitDirectory(): string {
  return mkdtempSync(join(tmpdir(), 'aria-e2e-nongit-'));
}

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

test('a worktree request over a non-git directory is refused, never downgraded', async ({ request }) => {
  const nonGitDirectory = disposableNonGitDirectory();
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-wt-invalid'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'WORKTREE',
    workspacePath: nonGitDirectory,
  });

  const created = await apiCall(request, 'POST', '/runs', {
    agentId: agent.id,
    promptSeed: uniqueName('e2e-wt-invalid-run'),
    maxIterations: 1,
  });

  // Run creation commits the run row first; the run's own admission is the
  // refusing step (Task 6: RunWorkspaceService's resolveRepository refuses a
  // non-git worktree source with this exact message), and the run reaches
  // exactly FAILED carrying that message (Task 18's recorded cutover contract:
  // a refused selection is failed with the admission message). The invalid
  // source is never silently re-pointed at another directory or mode.
  expect(created.status, JSON.stringify(created.data)).toBe(201);
  const refused = await pollRunTerminal(request, created.data.id);
  expect(refused.status).toBe('FAILED');
  expect(refused.errorMessage).toBe(
    `Worktree workspace requires a git repository: ${nonGitDirectory}`,
  );
});

// ── dirty source preserved (workspaces acceptance matrix) ───────────────────
test('a dirty source repository is preserved byte-for-byte by a worktree run', async ({ request }) => {
  // A disposable source repository with exact committed content and one exact
  // uncommitted (dirty) change. The run must execute in its own worktree and
  // leave the source bit-identical: same file bytes, same HEAD, same porcelain.
  const source = mkdtempSync(join(tmpdir(), 'aria-e2e-dirty-'));
  writeFileSync(join(source, 'tracked.txt'), 'committed\n');
  git(['init'], source);
  git(['checkout', '-b', 'main'], source);
  git(['add', 'tracked.txt'], source);
  git(['commit', '-m', 'e2e dirty-source baseline'], source);
  const headBefore = git(['rev-parse', 'HEAD'], source);
  const dirtyContent = 'uncommitted work in progress\nsecond dirty line\n';
  writeFileSync(join(source, 'tracked.txt'), dirtyContent);
  const statusBefore = git(['status', '--porcelain'], source);
  // The helper trims git output, so the porcelain line is asserted without the
  // column-1 status space: exactly one modified tracked file, nothing else.
  expect(statusBefore).toBe('M tracked.txt');

  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-dirty-source'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'WORKTREE',
    workspacePath: source,
    workspaceBaseRef: 'main',
  });
  await setScenario(request, agent.id, 'reported-usage');
  const run = await seedRun(request, agent.id);
  await settleRunApproval(request, run.id);
  const done = await pollRunTerminal(request, run.id);

  // The run really executed in its worktree (the deterministic completion).
  expect(done.status).toBe('COMPLETED');
  expect(done.finalOutput).toBe('fixture-complete');

  // The source is untouched, exactly: the dirty bytes, HEAD and porcelain status
  // are the captured pre-run values.
  expect(readFileSync(join(source, 'tracked.txt'), 'utf8')).toBe(dirtyContent);
  expect(git(['rev-parse', 'HEAD'], source)).toBe(headBefore);
  expect(git(['status', '--porcelain'], source)).toBe(statusBefore);
});

test('two concurrent Direct writers on one directory are exclusive', async ({ request }) => {
  const shared = mkdtempSync(join(tmpdir(), 'aria-e2e-direct-'));
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-direct'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'DIRECT',
    workspacePath: shared,
  });
  await setScenario(request, agent.id, 'pause-resume');

  const first = await seedRun(request, agent.id, uniqueName('e2e-direct-a'));
  // The first writer must hold the lease before the second launch can be
  // observed as the refused one: admission is the run's own step (the lease is
  // acquired before RUNNING), so wait for exactly RUNNING first.
  await pollUntil(
    request,
    `/runs/${first.id}`,
    (run: any) => run?.status === 'RUNNING',
    60_000,
    1_000,
  );

  // The second launch is refused by workspace admission, exactly: the conflict
  // (RunWorkspaceService.rejectOverlap) names the canonical root the test
  // selected and the identity of the active lease — the lease's owning run is
  // the first run's captured id, compared exactly. Creation itself commits the
  // row first (201), so the refusal is the second run's recorded failure
  // carrying the exact conflict message in errorMessage.
  const second = await apiCall(request, 'POST', '/runs', {
    agentId: agent.id,
    promptSeed: uniqueName('e2e-direct-b'),
    maxIterations: 1,
  });
  expect(second.status, JSON.stringify(second.data)).toBe(201);
  const refused = await pollRunTerminal(request, second.data.id);
  expect(refused.status).toBe('FAILED');
  const conflict = /^Workspace conflict: (.+) overlaps active lease [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} \(run ([0-9a-f-]{36})\)$/
    .exec(refused.errorMessage ?? '');
  expect(conflict, `unexpected refusal: ${JSON.stringify(refused.errorMessage)}`).not.toBeNull();
  expect(conflict![1]).toBe(shared);
  expect(conflict![2]).toBe(first.id);

  // Cleanup: the first run releases the lease only through the verified stop
  // path (exact cancel ack).
  const cancelled = await apiCall(request, 'POST', `/runs/${first.id}/cancel`);
  expect(cancelled.status).toBe(200);
  expect(cancelled.data?.status).toBe('CANCELLED');
});

test('workspace-diff preview answers for a run without a workspace and for an owned one', async ({ request }) => {
  // Ported from harness-governance.spec.ts: an unknown run answers exactly
  // hasWorkspace=false (deterministic, no repository involved).
  const unknown = await apiCall(request, 'GET', '/runs/00000000-0000-0000-0000-0000000000ff/workspace-diff');
  expect(unknown.status).toBe(200);
  expect(unknown.data.hasWorkspace).toBe(false);
  expect(unknown.data).toHaveProperty('diff');

  // A run that owns a workspace answers hasWorkspace=true with the captured diff.
  const agent = await seedAdkAgent(request, {
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'DIRECT',
    workspacePath: mkdtempSync(join(tmpdir(), 'aria-e2e-diff-')),
  });
  await setScenario(request, agent.id, 'write-twice');
  const run = await seedRun(request, agent.id);
  // write-twice raises two permit gates (edit then execute); settleRunApproval
  // grants every pending ask of this run until it reaches its terminal state --
  // a single-grant helper would leave the second gate PENDING forever.
  await settleRunApproval(request, run.id);
  const done = await pollRunTerminal(request, run.id);
  expect(done.status).toBe('COMPLETED');

  const { status, data } = await apiCall(request, 'GET', `/runs/${run.id}/workspace-diff`);
  expect(status).toBe(200);
  expect(data.hasWorkspace).toBe(true);
  expect(typeof data.diff).toBe('string');
});

test('knowledge promotion completes a trajectory on the deterministic harness', async ({ request }) => {
  // Ported from journey-knowledge-promotion.spec.ts: the promotion pipeline is
  // runtime-agnostic, and the former LLM-gated run tail is now the harness-
  // driven deterministic run whose trajectory must carry the promoted item.
  const item = await seedKnowledgeItem(request, { name: uniqueName('e2e-promote') });
  expect((await reviewKnowledge(request, item.id, 'APPROVED')).status).toBe(200);
  const promoted = await promoteKnowledge(request, item.id, 'GUIDELINE', uniqueName('e2e-promoted'));
  expect(promoted.status).toBe(201);
  expect(promoted.data.status).toBe('PENDING');

  // A real source repository the governed WORKTREE run is created from: the
  // admission refuses a WORKTREE selection without a repository path, and this
  // case needs a creatable agent, so the repository is part of the seed.
  const source = mkdtempSync(join(tmpdir(), 'aria-e2e-promote-src-'));
  writeFileSync(join(source, 'tracked.txt'), 'committed\n');
  git(['init'], source);
  git(['checkout', '-b', 'main'], source);
  git(['add', 'tracked.txt'], source);
  git(['commit', '-m', 'e2e promotion baseline'], source);

  const agent = await seedAdkAgent(request, {
    adkProvider: 'qoder',
    executionMode: 'HOST',
    workspaceMode: 'WORKTREE',
    workspacePath: source,
    workspaceBaseRef: 'main',
  });
  // 'reported-usage' is the committed scenario whose fixture completion is
  // exactly 'fixture-complete' (the 'complete' scenario completes as 'pong');
  // the peers are not extended in this round (file scope: the dashboard e2e
  // specs plus the coverage map).
  await setScenario(request, agent.id, 'reported-usage');
  const run = await seedRun(request, agent.id, `Use the promoted guidance: ${promoted.data.name}`);
  await settleRunApproval(request, run.id);
  const done = await pollRunTerminal(request, run.id);
  expect(done.status).toBe('COMPLETED');
  expect(done.finalOutput).toBe('fixture-complete');
});
