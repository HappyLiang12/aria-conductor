import { test, expect } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  apiCall,
  approveRunApproval,
  decideApproval,
  pendingRunApprovals,
  pollRunTerminal,
  seedAdkAgent,
  seedRun,
  setScenario,
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
    await approveRunApproval(request, run.id);
    const done = await pollRunTerminal(request, run.id);
    expect(done.status).toBe('COMPLETED');
    expect(done.finalOutput).toBe('fixture-complete');
  });
}

// ── agent create/configure: the selection is persisted exactly ──────────────
test('agent create/configure keeps the captured core, mode and workspace selection', async ({ request }) => {
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-core-settings'),
    adkProvider: 'qoder',
    executionMode: 'HOST',
    workspaceMode: 'WORKTREE',
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
 * Approves every ask the run opens, in order, until the run reaches exactly
 * RUNNING (the long-running scenario opens a task gate and then its execute
 * permission). Each captured ask id is decided once — a decision that is not
 * processed throws inside {@link decideApproval}.
 */
async function approveAllAsksUntilRunning(
  request: Parameters<typeof seedAdkAgent>[0],
  runId: string,
  timeoutMs = 90_000,
) {
  const decided = new Set<string>();
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const { data: run } = await apiCall(request, 'GET', `/runs/${runId}`);
    if (run?.status === 'RUNNING') return decided.size;
    if (Date.now() > deadline) {
      throw new Error(`run ${runId} never reached RUNNING (last status: ${run?.status})`);
    }
    const pending = await pendingRunApprovals(request, runId, 15_000).catch(() => []);
    const next = (pending as any[]).find((a) => !decided.has(a.id));
    if (next) {
      await decideApproval(request, next.id, true, 'e2e control: one-use grant');
      decided.add(next.id);
    } else {
      await new Promise((r) => setTimeout(r, 1_000));
    }
  }
}

// ── control: truthful pause ack, frozen export, resume, exact cancel ────────
test('a pause is acknowledged truthfully and freezes the export until resume', async ({ request }) => {
  const workspace = mkdtempSync(join(tmpdir(), 'aria-e2e-pause-'));
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-pause'),
    adkProvider: 'opencode',
    executionMode: 'HOST',
    workspaceMode: 'DIRECT',
    workspacePath: workspace,
  });
  await setScenario(request, agent.id, 'pause-resume');
  const run = await seedRun(request, agent.id);
  const granted = await approveAllAsksUntilRunning(request, run.id);
  expect(granted).toBeGreaterThan(0);

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

  // ── no writes after pause: the exported workspace is byte-stable ──────────
  const export1 = await apiCall(request, 'GET', `/runs/${run.id}/workspace-diff`);
  expect(export1.status).toBe(200);
  expect(export1.data.hasWorkspace).toBe(true);
  expect(export1.data.diff).toContain('ticks.log');
  await new Promise((r) => setTimeout(r, 3_000));
  const export2 = await apiCall(request, 'GET', `/runs/${run.id}/workspace-diff`);
  expect(export2.status).toBe(200);
  expect(export2.data.diff).toBe(export1.data.diff);

  // ── resume: the writer continues, so the same export must change ──────────
  const resumed = await apiCall(request, 'POST', `/runs/${run.id}/resume`);
  expect(resumed.status, JSON.stringify(resumed.data)).toBe(200);
  expect(resumed.data?.status).toBe('RUNNING');
  await expect
    .poll(
      async () => {
        const { data } = await apiCall(request, 'GET', `/runs/${run.id}/workspace-diff`);
        return data?.diff;
      },
      { timeout: 60_000, intervals: [1_000, 2_000, 5_000] },
    )
    .not.toBe(export1.data.diff);

  // ── cancel: the exact ack and the exact terminal state ────────────────────
  const cancelled = await apiCall(request, 'POST', `/runs/${run.id}/cancel`);
  expect(cancelled.status, JSON.stringify(cancelled.data)).toBe(200);
  expect(cancelled.data?.status).toBe('CANCELLED');
  const done = await pollRunTerminal(request, run.id);
  expect(done.status).toBe('CANCELLED');
});
