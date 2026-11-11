import { test, expect } from '@playwright/test';
import { establishOperatorSession, operatorApiCall, uniqueName } from './fixtures';

/**
 * Task 12 memory onboarding — two tracks (the controller's ruling, mirroring
 * tool-event-contract.md's sibling ruling recorded in progress.md).
 *
 * Track A (this file) asserts only what the deterministic core harness can
 * genuinely drive: the `/memory` route renders and the rail owns the
 * destination, an empty surface is honest, a refused read renders the
 * operator-session notice instead of an empty list, and an entity created
 * through the page's own form is read back from the real operator endpoint.
 *
 * Track B is the real chat-only onboarding and is executed LIVE in the
 * acceptance phase, not faked here: on a fresh database with no PERSON entity,
 * a new operator asks Aria to remember a preference, approves the entity
 * proposal card and then the fact card in the conversation, asks for the
 * preference back and is answered — with no navigation away from the chat and
 * no REST call from the test. The harness cannot fabricate a run, an approval
 * or a proposal row, so that sequence has no deterministic fixture and is
 * deliberately absent rather than skipped.
 */
test.describe('Memory surface — deterministic track', () => {
  test('the /memory route renders and the rail marks it current', async ({ page }) => {
    await page.goto('/');
    const railLink = page.locator('.rail-btn', { hasText: 'Memory' });
    await expect(railLink).toHaveAttribute('href', '/memory');

    await railLink.click();

    await expect(page).toHaveURL(/\/memory$/);
    await expect(page.locator('h1').filter({ hasText: 'Memory' })).toBeVisible();
    await expect(page.locator('.rail-btn.active')).toContainText('Memory');
    await expect(page.getByRole('heading', { name: /proposal inbox/i })).toBeVisible();
  });

  test('an empty memory surface is an honest empty state, never a decision surface', async ({
    page,
  }) => {
    // No proposal row is fabricated: the two reads are answered with the empty
    // page a freshly provisioned database serves, and the UI must say so.
    await page.route('**/api/v1/memory/entities**', (route) => route.fulfill({ json: [] }));
    await page.route('**/api/v1/memory/proposals**', (route) => route.fulfill({ json: [] }));

    await page.goto('/memory');

    await expect(page.getByText('No memory entities yet')).toBeVisible();
    await expect(page.getByText('No proposals awaiting a decision')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve', exact: true })).toHaveCount(0);
    await expect(page.getByTestId('operator-session-notice')).toHaveCount(0);
  });

  test('a refused read renders the operator-session notice, never an empty list', async ({
    page,
  }) => {
    await page.route('**/api/v1/memory/entities**', (route) =>
      route.fulfill({ status: 403, json: {} }),
    );
    await page.route('**/api/v1/memory/proposals**', (route) =>
      route.fulfill({ status: 403, json: {} }),
    );

    await page.goto('/memory');

    await expect(page.getByTestId('operator-session-notice')).toBeVisible();
    // The false claim this guards against: an empty inbox on a refusal.
    await expect(page.getByText('No memory entities yet')).toHaveCount(0);
    await expect(page.getByText('No proposals awaiting a decision')).toHaveCount(0);
  });

  test('an entity created through the form is read back from the operator route', async ({
    page,
    request,
  }) => {
    // The create mutation is operator-only: establish the browser operator
    // session first (the harness exchanges the synthetic credential).
    await establishOperatorSession(page);
    // A per-run key, so the assertion cannot be satisfied by an earlier run's row.
    const canonicalKey = `person:${uniqueName('e2e-memory')}`;
    let createdId: string | null = null;

    try {
      await page.goto('/memory');
      await page.getByRole('button', { name: '+ New Entity' }).click();
      await page.getByLabel('Kind').fill('PERSON');
      await page.getByLabel('Canonical key').fill(canonicalKey);
      await page.getByLabel('Display name').fill('E2E Memory Entity');

      const created = page.waitForResponse(
        (response) =>
          response.url().includes('/api/v1/memory/entities')
          && response.request().method() === 'POST',
      );
      await page.getByRole('button', { name: 'Create entity' }).click();
      const createdBody = (await (await created).json()) as { id?: string };
      createdId = createdBody.id ?? null;

      // The row only appears if the POST was accepted AND the list refetched.
      await expect(page.locator('table.data-table')).toContainText(canonicalKey, {
        timeout: 15_000,
      });
      await expect(page.getByRole('dialog', { name: 'Create memory entity' })).toHaveCount(0);
    } finally {
      // Self-contained: a degraded run must not leave an ACTIVE entity behind to
      // accumulate across runs. Retire (not delete) the row this test created.
      if (createdId !== null) {
        const retired = await operatorApiCall(
          request,
          'PATCH',
          `/memory/entities/${createdId}`,
          { retire: true },
        );
        expect(retired.status).toBe(200);
      }
    }
  });
});
