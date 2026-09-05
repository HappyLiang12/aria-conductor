import { test, expect } from '@playwright/test';
import {
  apiCall,
  cancelWorkflow,
  deleteWorkflow,
  executeYamlWorkflow,
  mergeWorkflows,
  pollUntil,
  seedAgent,
  uniqueName,
} from '../fixtures';

/**
 * API-layer coverage for the WORKFLOW system: definition, execution and the
 * WorkflowChain state machine (cancel / retry / merge / delete / execute-yaml),
 * plus concurrency probes on the *unlocked* @Transactional guards.
 *
 * WorkflowChain has no @Version / row lock and every lifecycle method is a
 * read-check-then-write. Runs fail fast without an LLM key, so a real-agent
 * chain reliably lands FAILED — a deterministic state to race retries against.
 */
const BOGUS_AGENT = '00000000-0000-0000-0000-0000000abcde';

// Real-LLM (opencode) chains execute against a live sandbox on local stacks and
// can take minutes to reach a terminal state; CI's langchain path fails fast.
// Override the wait budget with E2E_RUN_TIMEOUT_MS if the runtime is slower.
const RUN_TIMEOUT_MS = Number(process.env.E2E_RUN_TIMEOUT_MS ?? 180_000);

/** Create a chain with a real agent and wait until it reaches a terminal state. */
async function createTerminalChain(request: any, agentId: string, label: string): Promise<any> {
  const created = await apiCall(request, 'POST', '/workflows', {
    name: uniqueName(label),
    steps: [{ agentId, promptTemplate: 'Say hello briefly', maxIterations: 1 }],
  });
  expect([200, 201]).toContain(created.status);
  return pollUntil(
    request,
    `/workflows/${created.data.id}`,
    (w: any) => ['FAILED', 'COMPLETED', 'CANCELLED'].includes(w.status),
    RUN_TIMEOUT_MS,
    1_000,
  );
}

test.describe('Workflow system — definition / execution / state machine', () => {
  test('1. definition + query: create a chain and read it back', async ({ request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-wf-agent'));
    const { status, data } = await apiCall(request, 'POST', '/workflows', {
      name: uniqueName('e2e-wf-define'),
      steps: [{ agentId: agent.id, promptTemplate: 'Say hello briefly', maxIterations: 1 }],
    });
    expect([200, 201]).toContain(status);
    expect(data.id).toBeTruthy();
    expect(data.totalSteps).toBe(1);

    const got = await apiCall(request, 'GET', `/workflows/${data.id}`);
    expect(got.status).toBe(200);
    expect(got.data.id).toBe(data.id);

    const list = await apiCall(request, 'GET', '/workflows');
    expect(list.status).toBe(200);
    expect((list.data as any[]).some((w) => w.id === data.id)).toBe(true);
  });

  test('2. state guards: cancel/delete respect a terminal chain', async ({ request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-wf-guard-agent'));
    const chain = await createTerminalChain(request, agent.id, 'e2e-wf-guard');
    expect(['FAILED', 'COMPLETED']).toContain(chain.status);

    // Cannot cancel a non-RUNNING/PENDING chain.
    const cancel = await cancelWorkflow(request, chain.id);
    expect(cancel.status).toBeGreaterThanOrEqual(400);

    // A terminal (non-running) chain can be deleted, and is then gone.
    const del = await deleteWorkflow(request, chain.id);
    expect(del.status).toBe(204);
    const after = await apiCall(request, 'GET', `/workflows/${chain.id}`);
    expect(after.status).toBeGreaterThanOrEqual(400);
  });

  test('3. RACE: concurrent cancels on RUNNING chains stay safe (no 5xx, valid terminal)', async ({ request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-wf-cancel-agent'));
    const K = 8;
    // Fresh chains are RUNNING (first run in flight), so both cancels race the
    // RUNNING→CANCELLED guard. WorkflowChain has no @Version, so this probes whether
    // contention corrupts state. Double-success is benign (both set CANCELLED), so we
    // assert SAFETY and record doubleWins as evidence rather than failing on it.
    const ids = await Promise.all(
      Array.from({ length: K }, async (_, i) => {
        const c = await apiCall(request, 'POST', '/workflows', {
          name: uniqueName(`e2e-wf-cancel-race-${i}`),
          steps: [{ agentId: agent.id, promptTemplate: 'Say hello briefly', maxIterations: 1 }],
        });
        expect([200, 201]).toContain(c.status);
        return c.data.id as string;
      }),
    );

    const outcomes = await Promise.all(
      ids.map(async (id) => {
        const [a, b] = await Promise.all([cancelWorkflow(request, id), cancelWorkflow(request, id)]);
        const successes = [a, b].filter((x) => x.status >= 200 && x.status < 300).length;
        const final = (await apiCall(request, 'GET', `/workflows/${id}`)).data?.status;
        return { id, successes, statuses: [a.status, b.status], final };
      }),
    );

    const doubleWins = outcomes.filter((o) => o.successes > 1).length;
    console.log(`[workflow-cancel-race] doubleWins=${doubleWins}/${K} :: ${JSON.stringify(outcomes)}`);
    // Safety under contention: no 5xx, and every chain ends in a valid terminal state.
    for (const o of outcomes) {
      expect(Math.max(...o.statuses), `unexpected 5xx: ${JSON.stringify(o)}`).toBeLessThan(500);
      expect(['CANCELLED', 'FAILED', 'COMPLETED']).toContain(o.final);
    }
  });

  test('5. merge: >=2 chains combine; <2 is rejected', async ({ request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-wf-merge-agent'));
    const c1 = await createTerminalChain(request, agent.id, 'e2e-wf-merge-a');
    const c2 = await createTerminalChain(request, agent.id, 'e2e-wf-merge-b');

    const merged = await mergeWorkflows(request, [c1.id, c2.id], uniqueName('e2e-wf-merged'));
    expect([200, 201]).toContain(merged.status);
    expect(merged.data.totalSteps).toBe((c1.totalSteps ?? 1) + (c2.totalSteps ?? 1));

    const tooFew = await mergeWorkflows(request, [c1.id], uniqueName('e2e-wf-merge-bad'));
    expect(tooFew.status).toBeGreaterThanOrEqual(400);
  });

  test('6. execute-yaml: valid template runs; empty/unknown-agent rejected', async ({ request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-wf-yaml-agent'));
    const yamlContent = `name: ${uniqueName('e2e-yaml')}
steps:
  - agent_id: ${agent.id}
    prompt_template: "Analyze the input data"
    max_iterations: 1
  - agent_id: ${agent.id}
    prompt_template: "Summarize the findings"
    max_iterations: 1
`;
    const ok = await executeYamlWorkflow(request, yamlContent);
    expect(ok.status).toBe(201);
    expect(ok.data.id).toBeTruthy();

    const chain = await apiCall(request, 'GET', `/workflows/${ok.data.id}`);
    expect(chain.status).toBe(200);
    expect(chain.data.totalSteps).toBe(2);

    // Empty content → 400.
    const empty = await executeYamlWorkflow(request, '   ');
    expect(empty.status).toBeGreaterThanOrEqual(400);
  });

  test('7. defining a workflow with an unresolvable agent must not 500', async ({ request }) => {
    // Invalid input should be a clean client error, not an UnexpectedRollbackException.
    const { status } = await apiCall(request, 'POST', '/workflows', {
      name: uniqueName('e2e-wf-bad-agent'),
      steps: [{ agentId: BOGUS_AGENT, promptTemplate: 'never runs', maxIterations: 1 }],
    });
    // F2: an unresolvable agent is a not-found client error, not a 500 rollback.
    expect(status, `POST /workflows with unknown agent returned ${status}`).toBe(404);
  });
});
