import { test, expect } from '@playwright/test';
import { apiCall, uniqueName } from './fixtures';

/**
 * DoD lifecycle E2E — REST-driven.
 *
 * ADAPTATION NOTE: DoDPage.tsx exists in src/pages but is NOT wired into the
 * router (App.tsx has no /dod route; RailNav has no entry), so the DoD UI is
 * unreachable in the shipped app. Per the honesty rule this spec exercises the
 * full REST lifecycle (init → evidence → review → status) instead of faking
 * UI assertions against an orphaned page.
 */
test.describe.configure({ mode: 'serial' });

const taskId = uniqueName('e2e-dod-task');

test('1. init creates a DoD record at its first stage', async ({ request }) => {
  const { status, data } = await apiCall(request, 'POST', '/dod/init', { taskId });
  expect(status).toBe(200);
  expect(data.currentStage).toBeTruthy();
});

test('2. status endpoint reports stages and overall status', async ({ request }) => {
  const { status, data } = await apiCall(request, 'GET', `/dod/${taskId}`);
  expect(status).toBe(200);
  expect(Array.isArray(data.stages)).toBe(true);
  expect(data.stages.length).toBeGreaterThan(0);
  expect(data.currentStage).toBeTruthy();
  expect(data.overallStatus).toBeTruthy();
});

test('3. evidence can be attached and listed', async ({ request }) => {
  const post = await apiCall(request, 'POST', `/dod/${taskId}/evidence`, {
    type: 'COMMENT',
    title: 'e2e evidence',
    content: 'Attached by the Phase E DoD lifecycle spec.',
  });
  expect([200, 201]).toContain(post.status);

  const list = await apiCall(request, 'GET', `/dod/${taskId}/evidence`);
  expect(list.status).toBe(200);
  expect(list.data.length).toBeGreaterThanOrEqual(1);
});

test('4. passing review is recorded against the current stage', async ({ request }) => {
  const { status } = await apiCall(request, 'POST', '/dod/review', {
    taskId,
    reviewerId: '11111111-1111-1111-1111-111111111111',
    reviewerName: 'E2E Reviewer',
    passed: true,
    comment: 'lgtm — phase-e spec',
  });
  expect(status).toBe(200);

  const after = await apiCall(request, 'GET', `/dod/${taskId}`);
  expect(after.status).toBe(200);
  expect((after.data.reviews ?? []).length).toBeGreaterThanOrEqual(1);
});

test('5. negative: reviewing an unknown task is rejected', async ({ request }) => {
  const { status } = await apiCall(request, 'POST', '/dod/review', {
    taskId: uniqueName('e2e-dod-missing'),
    reviewerId: '11111111-1111-1111-1111-111111111111',
    reviewerName: 'E2E Reviewer',
    passed: true,
  });
  expect(status).toBeGreaterThanOrEqual(400);
});

test('6. negative: status for an unknown task → 404', async ({ request }) => {
  const { status } = await apiCall(request, 'GET', `/dod/${uniqueName('e2e-dod-void')}`);
  expect(status).toBe(404);
});
