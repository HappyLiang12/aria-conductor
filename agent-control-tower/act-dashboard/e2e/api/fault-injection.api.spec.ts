import { test, expect, type APIRequestContext } from '@playwright/test';
import { BACKEND, apiCall, seedAdkAgent, seedRun, approveRunApproval, pollRunTerminal, uniqueName } from '../fixtures';

/**
 * API-layer FAULT INJECTION for a run-owned core run (opencode).
 *
 * These specs drive the real backend over REST (no browser) so they are fast
 * and deterministic enough to sit in the CI api shard. They exercise the
 * failure paths of the run-owned execution runtime:
 *   D1-2 an opencode run exceeds the run deadline → CANCELLED with
 *        RUN_DEADLINE_REACHED / SYSTEM_CONFIG
 *   D2-2 an executing run is cancelled → terminal within 15s
 *
 * Prerequisites: backend on ${API_URL:-localhost:8080} with an active LLM
 * provider and a reachable OpenSandbox (8090). The deadline is the mode-neutral
 * run deadline aria.tasks.deadline-minutes (INT minutes, min 1, default 45),
 * frozen into the run's binding at admission. The timeout case needs that key
 * set to 1 and a long-generation prompt (LLM streaming longer than 60s), so
 * D1-2 reads the System Config value and skips itself unless it is 1: this spec
 * never mutates a global setting that parallel shards share. Without a live
 * sandbox the run fails before any deadline can fire, so D1-2 also skips itself
 * when the opencode provider health probe is not healthy.
 */

const HAS_LLM_KEY = !!(
  process.env.LLM_API_KEY ||
  process.env.LLM_PROVIDER_API_KEY ||
  process.env.DEEPSEEK_API_KEY
);

/** The System Config key every new run freezes as its admission deadline. */
const DEADLINE_KEY = 'aria.tasks.deadline-minutes';

const TERMINAL = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'];

test.describe('OpenCode run-owned fault injection', () => {
  test('D1-2 run over deadline → CANCELLED with the deadline identity', async ({ request }) => {
    test.skip(!HAS_LLM_KEY, 'requires an LLM key for a real opencode run');

    // The timeout path can only fire inside a live sandbox: without one the
    // run fails before the deadline is reached, which would green-wash a
    // tautological assertion. Skip explicitly instead.
    const health = await apiCall(request, 'GET', '/adk/providers/opencode/health');
    test.skip(
      health.status !== 200 || health.data?.healthy !== true,
      'requires a reachable OpenSandbox (opencode provider health probe)',
    );

    const deadlineConfig = await apiCall(request, 'GET', `/system-config/${DEADLINE_KEY}`);
    const configuredDeadline = deadlineConfig.status === 200
      ? deadlineConfig.data?.configValue
      : undefined;
    test.skip(
      configuredDeadline !== '1',
      `requires ${DEADLINE_KEY}=1 so the frozen run deadline fires inside the poll window`
        + ` (Configure > System Config currently serves ${configuredDeadline ?? `HTTP ${deadlineConfig.status}`})`,
    );

    const agent = await seedAdkAgent(request, { adkProvider: 'opencode' });
    // Long-generation prompt: the run freezes a 1-minute deadline at admission,
    // so the deadline fires while the core is still streaming.
    const run = await seedRun(
      request,
      agent.id,
      'Write an extremely detailed 3000-word technical report about distributed systems architecture, covering every aspect in depth with many examples. Be exhaustive.',
      1,
    );
    await approveRunApproval(request, run.id);

    const terminal = await pollRunTerminal(request, run.id, 240_000);
    console.log(`D1-2 run ${run.id} terminal: ${terminal.status}`
      + ` (${terminal.errorCode ?? 'no code'}/${terminal.errorSurface ?? 'no surface'})`);

    // Both enforcers -- the coordinator's timer and the core session's own
    // prompt timeout -- settle the run as CANCELLED at the deadline, and both
    // persist the same repair identity. The assertion is strong on purpose:
    // any other ending means the run did not freeze the configured deadline
    // (config mismatch) and the test should fail loudly.
    expect(
      terminal.status,
      `expected CANCELLED at the run deadline, got ${terminal.status}: ${terminal.errorMessage ?? ''}`,
    ).toBe('CANCELLED');
    expect(terminal.errorCode, 'the deadline must carry its stable failure code').toBe('RUN_DEADLINE_REACHED');
    expect(terminal.errorSurface).toBe('SYSTEM_CONFIG');
    expect(terminal.errorMessage, 'the deadline reason must name the key that changes it')
      .toContain('aria.tasks.deadline-minutes');
  });

  test('D2-2 cancel an executing opencode run → terminal within 15s', async ({ request }) => {
    test.skip(!HAS_LLM_KEY, 'requires an LLM key for a real opencode run');

    const agent = await seedAdkAgent(request, { adkProvider: 'opencode' });
    const run = await seedRun(request, agent.id, uniqueName('e2e-fault-cancel'), 3);
    await approveRunApproval(request, run.id);

    // Note: the run flips to RUNNING as soon as it leaves PENDING — the
    // approval gate and environment preparation happen while already RUNNING —
    // so this poll may catch it before the prompt actually starts. A cancel in
    // that window still goes through the coordinator's verified stop path and
    // must stop the run quickly.
    const started = await pollUntilAnyStatus(request, run.id, ['RUNNING', 'INITIALIZING', ...TERMINAL], 120_000);
    console.log(`D2-2 run ${run.id} status before cancel: ${started.status}`);
    if (TERMINAL.includes(started.status)) {
      // The run finished before we could cancel (fast LLM) — nothing to cancel;
      // the cancel semantics are only testable on a non-terminal run.
      console.log('D2-2 run already terminal before cancel; skipping cancel assertions');
      return;
    }

    const cancel = await apiCancelRun(request, run.id);
    if (cancel.status() >= 400) {
      // The run reached a terminal state between our poll and the cancel
      // (the state machine rejects transitions from terminal states). The
      // cancellation raced a natural completion — verify it is terminal.
      const final = await pollRunTerminal(request, run.id, 15_000);
      console.log(`D2-2 cancel raced a terminal state; run ended ${final.status}`);
      return;
    }
    expect([200, 202]).toContain(cancel.status());

    const final = await pollRunTerminal(request, run.id, 15_000);
    expect(final.status, `cancelled run should be CANCELLED, got ${final.status}: ${final.errorMessage ?? ''}`).toBe('CANCELLED');
    console.log(`D2-2 run ${run.id} terminal after cancel: ${final.status}`);
  });
});

// ── local helpers (kept spec-local to stay explicit) ────────────────────

async function pollUntilAnyStatus(
  request: APIRequestContext,
  runId: string,
  statuses: string[],
  timeoutMs: number,
): Promise<any> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = await getRun(request, runId);
    if (statuses.includes(r.status)) return r;
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
  throw new Error(`run ${runId} did not reach ${statuses.join('/')} within ${timeoutMs}ms`);
}

async function getRun(request: APIRequestContext, runId: string): Promise<any> {
  const resp = await request.fetch(`${BACKEND}/runs/${runId}`);
  if (!resp.ok()) throw new Error(`GET run ${runId} failed: HTTP ${resp.status()}`);
  return resp.json();
}

async function apiCancelRun(request: APIRequestContext, runId: string) {
  return request.fetch(`${BACKEND}/runs/${runId}/cancel`, {
    method: 'POST',
  });
}
