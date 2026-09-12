import { test, expect } from '@playwright/test';
import { apiCall } from './fixtures';

/**
 * Gaps 4: scheduled jobs driven against the REAL backend.
 *
 * The previous version stubbed the api/v1/aria/jobs route to [] and only asserted
 * the header, the empty state and that the modal opens — a mock proves nothing
 * about the integration. This version creates a job, asserts it persists, then
 * pauses and resumes it.
 *
 * Not covered: a manual trigger. ScheduledJobController.java:20-52 exposes list,
 * create, update, delete, pause and resume only; no trigger endpoint exists and
 * the page imports no trigger call. There is nothing to assert.
 */
test.describe.configure({ mode: 'serial' });

const jobTitle = `e2e-job-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;

test('1. a created job persists and can be paused and resumed', async ({ page }) => {
  await page.goto('/scheduled-jobs');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.page-header h2')).toContainText('Scheduled Jobs');

  await page.locator('button:has-text("+ New Job")').click();
  const modal = page.locator('.modal-dialog');
  await expect(modal).toBeVisible();
  await expect(modal.locator('h3')).toContainText('New Job');

  await modal.getByPlaceholder('Daily summary brief').fill(jobTitle);
  await modal.locator('input[name="scheduleType"][value="RECURRING"]').check();
  await modal.getByPlaceholder('0 9 * * *').fill('0 9 * * MON-FRI');
  await modal.getByPlaceholder('Daily brief ready').fill(`${jobTitle} notification`);

  await modal.locator('button[type="submit"]').click();

  // Persists against the real backend.
  const card = page.locator('.job-card').filter({ hasText: jobTitle }).first();
  await expect(card).toBeVisible({ timeout: 20_000 });
  await expect(card.locator('.job-card-title')).toContainText(jobTitle);

  // Pause then resume, asserting the status pill actually changes.
  const pause = card.locator('button:has-text("Pause")');
  if (await pause.isVisible()) {
    await pause.click();
    await expect(card.locator('.pill')).toContainText(/PAUSED/i, { timeout: 20_000 });
    await card.locator('button:has-text("Resume")').click();
    await expect(card.locator('.pill')).toContainText(/ACTIVE/i, { timeout: 20_000 });
  } else {
    throw new Error('expected the created job card to offer a Pause control');
  }
});

test('2. the job survives a reload, proving it is server-side state', async ({
  page,
  request,
}) => {
  await page.goto('/scheduled-jobs');
  await page.waitForLoadState('networkidle');
  const card = page.locator('.job-card').filter({ hasText: jobTitle }).first();
  await expect(card).toBeVisible({ timeout: 20_000 });

  // Teardown: remove the job this spec created. Left ACTIVE it would be re-armed
  // on every backend start (TaskSchedulerSchedulerPort.recoverActiveJobs) and fire
  // every weekday at 09:00, pushing a notification into the shared stack without
  // bound, and the rows would accumulate across runs.
  const { data: jobs } = await apiCall(request, 'GET', '/aria/jobs');
  const created = (Array.isArray(jobs) ? jobs : []).find((j: any) => j.title === jobTitle);
  expect(created, 'the created job must be resolvable by title so teardown can remove it').toBeTruthy();
  const removed = await apiCall(request, 'DELETE', `/aria/jobs/${created.id}`);
  expect([200, 204]).toContain(removed.status);
});
