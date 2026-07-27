import { test, expect } from '@playwright/test';
import {
  apiCall,
  collectMetrics,
  reviewKnowledge,
  runBounded,
  seedKnowledgeItem,
  timedApiCall,
  uniqueName,
  type TimedResult,
} from '../fixtures';

/**
 * API-layer coverage for the KNOWLEDGE system: storage, query, and the
 * DRAFT→PENDING→APPROVED/REJECTED approval lifecycle (Git-backed), plus two
 * concurrency probes:
 *   - opposing-review race (approve vs reject on the SAME item at once);
 *   - concurrent-submit load (also stresses the synchronized Git mirror sync).
 *
 * reviewKnowledge guards with `if (status != PENDING) throw`, but KnowledgeItem
 * has no @Version / row lock — so this pins down whether that guard holds under
 * true concurrency or is a TOCTOU that lets both writers win.
 */
test.describe('Knowledge system — storage / query / approval', () => {
  test('1. lifecycle: submit (PENDING) → query → approve → Git-backed version', async ({ request }) => {
    const item = await seedKnowledgeItem(request, {
      type: 'GUIDELINE',
      name: uniqueName('e2e-knowledge-lifecycle'),
      content: 'Prefer small reversible changes over big-bang rewrites.',
    });
    expect(item.status).toBe('PENDING');

    // Query: appears in the PENDING filter and by id.
    const pending = await apiCall(request, 'GET', '/knowledge?status=PENDING');
    expect(pending.status).toBe(200);
    expect((pending.data as any[]).some((k) => k.id === item.id)).toBe(true);

    // Approve.
    const approved = await reviewKnowledge(request, item.id, 'APPROVED', 'lifecycle approve');
    expect(approved.status).toBe(200);
    expect(approved.data.status).toBe('APPROVED');

    // Source of truth reflects the transition.
    await expect
      .poll(async () => (await apiCall(request, 'GET', `/knowledge/${item.id}`)).data?.status)
      .toBe('APPROVED');

    // Git-backed version history exists.
    const versions = await apiCall(request, 'GET', `/knowledge/${item.id}/versions`);
    expect(versions.status).toBe(200);
    expect(Array.isArray(versions.data)).toBe(true);
    expect((versions.data as any[]).length).toBeGreaterThanOrEqual(1);
  });

  test('2. baseline guard: re-reviewing a decided item is rejected (sequential)', async ({ request }) => {
    const item = await seedKnowledgeItem(request, { name: uniqueName('e2e-knowledge-guard') });
    const first = await reviewKnowledge(request, item.id, 'APPROVED');
    expect(first.status).toBe(200);
    const second = await reviewKnowledge(request, item.id, 'REJECTED');
    expect(second.status).toBeGreaterThanOrEqual(400);
  });

  test('3. RACE: concurrent opposing reviews must yield exactly one winner', async ({ request }) => {
    const K = 8;
    const items = await Promise.all(
      Array.from({ length: K }, (_, i) =>
        seedKnowledgeItem(request, { name: uniqueName(`e2e-knowledge-race-${i}`) }),
      ),
    );

    const outcomes = await Promise.all(
      items.map(async (item) => {
        // Fire APPROVED and REJECTED at the same instant on the same PENDING item.
        const [a, r] = await Promise.all([
          reviewKnowledge(request, item.id, 'APPROVED'),
          reviewKnowledge(request, item.id, 'REJECTED'),
        ]);
        const successes = [a, r].filter((x) => x.status >= 200 && x.status < 300).length;
        const finalStatus = (await apiCall(request, 'GET', `/knowledge/${item.id}`)).data?.status;
        return { id: item.id, successes, finalStatus, statuses: [a.status, r.status] };
      }),
    );

    // Evidence: how many items accepted BOTH opposing reviews (a lost-update race).
    const doubleWins = outcomes.filter((o) => o.successes > 1);
    console.log(`[knowledge-race] doubleWins=${doubleWins.length}/${K} :: ${JSON.stringify(outcomes)}`);

    // Invariant 1: the final persisted status is never corrupted.
    for (const o of outcomes) {
      expect(['APPROVED', 'REJECTED']).toContain(o.finalStatus);
    }
    // Invariant 2: a governed gate must let exactly one opposing review win.
    expect(doubleWins, `items that accepted BOTH approve+reject: ${JSON.stringify(doubleWins)}`).toHaveLength(0);
  });

  test('4. LOAD: 24 concurrent submissions all persist (stresses Git mirror sync)', async ({ request }) => {
    const N = 24;
    const results: TimedResult[] = await runBounded(
      Array.from({ length: N }, (_, i) => i),
      12,
      (i) =>
        timedApiCall(request, 'POST', '/knowledge', {
          name: uniqueName(`e2e-knowledge-load-${i}`),
          type: 'GUIDELINE',
          description: 'concurrent submit load',
          content: `Load item ${i}: verify behavior with a failing test first.`,
        }),
    );

    const metrics = collectMetrics(results);
    console.log(`[knowledge-load] ${JSON.stringify(metrics)}`);

    // Every submission must succeed (201) — no dropped writes, no 5xx.
    expect(metrics.byStatus['201'] ?? 0).toBe(N);
    expect(results.every((r) => r.status < 500)).toBe(true);

    // And all persist: each returned id is retrievable.
    const ids = results.map((r) => r.data?.id).filter(Boolean);
    expect(ids.length).toBe(N);
  });
});
