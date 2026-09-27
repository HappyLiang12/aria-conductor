import { test, expect } from '@playwright/test';
import {
  apiCall,
  dispatchSeededCard,
  pollUntil,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from '../fixtures';

/**
 * Gap 2: sending a review card back to the agent is the feedback loop.
 *
 * KanbanTransitionService.java:225-229 — REVIEW -> TODO marks the card's
 * pending approvals stale, transitions the card, then picks it up again,
 * which creates a fresh run for the new attempt. The comment carries the
 * feedback. Uses the same LLM-free reach path as kanban-hitl.spec.ts:133-158.
 */
test.describe('request changes loop', () => {
  test('sending a reviewed card back resolves the ask and creates a new attempt', async ({
    request,
  }) => {
    const agent = await seedAdkAgent(request, {
      name: uniqueName('e2e-oc-changes'),
      adkProvider: 'opencode',
    });
    const card = await seedKanbanItem(request, {
      title: `changes-${uniqueName('card')}`,
      agentTemplateId: agent.name,
    });
    // Same auto-dispatch race: the explicit move may lose to the listener and answer 409.
    await dispatchSeededCard(request, card.id);

    const asks = await pollUntil<any[]>(
      request,
      `/approvals?kanbanItemId=${card.id}`,
      (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
      60_000,
      2_000,
    );
    const firstAsk = asks.find((a) => a.status === 'PENDING');
    expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);

    const before = await apiCall(request, 'GET', `/kanban/items/${card.id}`);
    const firstRunId = before.data?.linkedRunId;
    expect(firstRunId).toBeTruthy();

    // REVIEW -> TODO with feedback is "request changes". `feedback` is the field
    // that reaches the re-dispatch prompt (TransitionRequest.java:20-21, consumed
    // by buildPromptSeed at KanbanTransitionService.java:273-284); `comment` is
    // the card's transition note.
    const feedback = `address the missing error handling ${uniqueName('fb')}`;
    const changes = await transitionKanban(request, card.id, 'TODO', {
      comment: 'Sent back to the agent.',
      feedback,
    });
    expect(changes.status).toBe(200);

    // The stale ask must reach its observed terminal status EXPIRED (reason
    // "superseded by request changes"), not merely leave PENDING — a loose
    // negative would accept any other status. The callback must *throw* on a
    // non-200 so the poll fails: returning a sentinel like `HTTP_404` would
    // satisfy a status assertion and let a 404/500 pass vacuously.
    await expect
      .poll(
        async () => {
          const { status, data } = await apiCall(request, 'GET', `/approvals/${firstAsk.id}`);
          if (status !== 200) {
            throw new Error(`GET /approvals/${firstAsk.id} failed: HTTP ${status}`);
          }
          return data?.status;
        },
        { timeout: 30_000 },
      )
      .toBe('EXPIRED');

    // A new attempt is created: the card is picked up again with a new run, and
    // the operator feedback is embedded in that run's prompt seed.
    const relinked = await pollUntil<any>(
      request,
      `/kanban/items/${card.id}`,
      (c) => !!c?.linkedRunId && c.linkedRunId !== firstRunId,
      60_000,
      2_000,
    );
    const newRun = (await apiCall(request, 'GET', `/runs/${relinked.linkedRunId}`)).data;

    // Guards against the feedback leaking into the wrong run: the original
    // attempt's prompt seed must be untouched.
    const firstRun = (await apiCall(request, 'GET', `/runs/${firstRunId}`)).data;
    expect(firstRun?.promptSeed).toBeTruthy();
    expect(String(firstRun.promptSeed)).not.toContain(feedback);
    expect(String(newRun.promptSeed)).toContain('Operator feedback on the previous attempt');
    expect(String(newRun.promptSeed)).toContain(feedback);

    // Cleanup: the request-changes pickup leaves the card IN_PROGRESS with a
    // fresh run. Cancel it so the board is not left with a dispatched card
    // behind (same teardown as kanban-hitl.spec.ts:174-177).
    const cancelled = await transitionKanban(request, card.id, 'CANCELLED');
    expect(cancelled.status).toBe(200);
  });
});
