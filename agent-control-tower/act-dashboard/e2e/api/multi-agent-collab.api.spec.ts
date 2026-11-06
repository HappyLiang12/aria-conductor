import { test, expect } from '@playwright/test';
import { apiCall, pollUntil, reviewKnowledge, seedAgent, seedKnowledgeItem, uniqueName } from '../fixtures';

/**
 * API-layer coverage for MULTI-AGENT COLLABORATION: a BA→Dev→QA chain sharing
 * approved knowledge, plus isolation between two concurrently-running chains and
 * the HITL approval-gate surface.
 *
 * The full handoff (each step's output feeding the next) requires a real LLM, so
 * that segment is skipped without a key. Structure, sharing, isolation and the
 * approval API are all verified LLM-free.
 */
const HAS_LLM_KEY = !!(
  process.env.LLM_API_KEY ||
  process.env.LLM_PROVIDER_API_KEY ||
  process.env.DEEPSEEK_API_KEY
);

test.describe('Multi-agent collaboration', () => {
  test('1. structure: a 3-role chain is defined with steps in BA→Dev→QA order', async ({ request }) => {
    const ba = await seedAgent(request, uniqueName('E2E-BA'));
    const dev = await seedAgent(request, uniqueName('E2E-Dev'));
    const qa = await seedAgent(request, uniqueName('E2E-QA'));

    const created = await apiCall(request, 'POST', '/workflows', {
      name: uniqueName('e2e-collab-structure'),
      steps: [
        { agentId: ba.id, promptTemplate: 'Draft 2 requirements. Brief.', maxIterations: 1 },
        { agentId: dev.id, promptTemplate: 'Given {previousOutput}, name 1 approach.', maxIterations: 1 },
        { agentId: qa.id, promptTemplate: 'Given {previousOutput}, write 1 test.', maxIterations: 1 },
      ],
    });
    expect([200, 201]).toContain(created.status);
    expect(created.data.totalSteps).toBe(3);
    expect(created.data.steps.map((s: any) => s.agentId)).toEqual([ba.id, dev.id, qa.id]);
    expect(created.data.currentStepIndex).toBe(0);
  });

  test('2. shared knowledge: an approved guideline is queryable by the crew', async ({ request }) => {
    const item = await seedKnowledgeItem(request, {
      type: 'GUIDELINE',
      name: uniqueName('e2e-shared-guideline'),
      content: 'Definition of Done: code + tests + docs before hand-off.',
    });
    const approved = await reviewKnowledge(request, item.id, 'APPROVED', 'shared for the crew');
    expect(approved.status).toBe(200);

    const approvedList = await apiCall(request, 'GET', '/knowledge?status=APPROVED');
    expect(approvedList.status).toBe(200);
    expect((approvedList.data as any[]).some((k) => k.id === item.id)).toBe(true);
  });

  test('3. isolation: two concurrent chains keep their own steps', async ({ request }) => {
    const [a1, a2] = await Promise.all([
      seedAgent(request, uniqueName('E2E-Iso-A')),
      seedAgent(request, uniqueName('E2E-Iso-B')),
    ]);

    const [w1, w2] = await Promise.all([
      apiCall(request, 'POST', '/workflows', {
        name: uniqueName('e2e-collab-iso-1'),
        steps: [{ agentId: a1.id, promptTemplate: 'Say hello briefly', maxIterations: 1 }],
      }),
      apiCall(request, 'POST', '/workflows', {
        name: uniqueName('e2e-collab-iso-2'),
        steps: [{ agentId: a2.id, promptTemplate: 'Say hello briefly', maxIterations: 1 }],
      }),
    ]);
    expect([200, 201]).toContain(w1.status);
    expect([200, 201]).toContain(w2.status);
    expect(w1.data.id).not.toBe(w2.data.id);

    // Each chain references only its own agent — no cross-contamination.
    const g1 = await apiCall(request, 'GET', `/workflows/${w1.data.id}`);
    const g2 = await apiCall(request, 'GET', `/workflows/${w2.data.id}`);
    expect(g1.data.steps.every((s: any) => s.agentId === a1.id)).toBe(true);
    expect(g2.data.steps.every((s: any) => s.agentId === a2.id)).toBe(true);
  });

  test('4. approval gate: list is an array and unknown decisions are rejected', async ({ request }) => {
    const pending = await apiCall(request, 'GET', '/approvals');
    expect(pending.status).toBe(200);
    expect(Array.isArray(pending.data)).toBe(true);

    const decide = await apiCall(
      request,
      'POST',
      '/approvals/00000000-0000-0000-0000-0000000abcde/decide',
      { approved: true, reason: 'nonexistent' },
    );
    expect(decide.status).toBeGreaterThanOrEqual(400);
  });

  test.describe('real-LLM handoff', () => {
    test.describe.configure({ timeout: 600_000 });

    test('5. BA→Dev→QA completes in order with per-step output', async ({ request }) => {
      test.skip(!HAS_LLM_KEY, 'requires a real LLM API key');

      const ba = await seedAgent(request, uniqueName('E2E-BA-live'));
      const dev = await seedAgent(request, uniqueName('E2E-Dev-live'));
      const qa = await seedAgent(request, uniqueName('E2E-QA-live'));

      const created = await apiCall(request, 'POST', '/workflows', {
        name: uniqueName('e2e-collab-live'),
        steps: [
          { agentId: ba.id, promptTemplate: 'List 2 requirements for a login page. One line each.', maxIterations: 2 },
          { agentId: dev.id, promptTemplate: 'Given {previousOutput}, name one approach in one sentence.', maxIterations: 2 },
          { agentId: qa.id, promptTemplate: 'Given {previousOutput}, write one test case in one sentence.', maxIterations: 2 },
        ],
      });
      expect([200, 201]).toContain(created.status);

      const done = await pollUntil(
        request,
        `/workflows/${created.data.id}`,
        (w: any) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(w.status),
        540_000,
        5_000,
      );
      expect(done.status).toBe('COMPLETED');
      expect(done.steps.every((s: any) => s.status === 'COMPLETED')).toBe(true);
      expect(done.steps.every((s: any) => (s.outputPreview ?? '').length > 0)).toBe(true);
    });
  });
});
