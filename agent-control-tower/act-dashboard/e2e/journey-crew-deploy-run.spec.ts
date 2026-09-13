import { test, expect } from '@playwright/test';

/**
 * Gap 7: one continuous UI-only journey — deploy an agent from the Crew catalog,
 * then start a run for that agent from the Runs page.
 *
 * journey-agent-run-report.spec.ts covers agent -> run -> report, but the agent
 * is REST-seeded and the run is API-started, so no single spec proves the UI can
 * do both. That is the gap.
 *
 * No approval is asserted here on purpose: the catalog deploys from
 * POST /agents/from-template/{name}, whose templates (AgentTemplateService) are
 * ADK/langchain roles that are not guaranteed to be task-capable, so a PENDING
 * gate approval is not deterministically reachable from this path. The
 * decision-zone half is covered by review-decision-zone.spec.ts.
 */
test('deploy from the Crew catalog, then start a run from the Runs page', async ({ page }) => {
  await page.goto('/crew');
  await page.waitForLoadState('networkidle');

  const catalog = page.locator('[data-testid="agent-catalog"]');
  await expect(catalog).toBeVisible({ timeout: 15_000 });

  const firstCard = catalog.locator('.tmpl').first();
  const templateId = await firstCard.getAttribute('data-template');
  const templateName = (await firstCard.locator('.nm').innerText()).trim();
  expect(templateName).not.toBe('');

  // The catalog shell and the roster are two independent queries, and the
  // catalog can paint while the roster is still loading — so wait for the
  // roster's loading placeholder to clear before reading any roster count,
  // otherwise the "before" count can be read as 0 on a populated DB.
  await expect(page.locator('.crew-empty', { hasText: 'Loading crew' })).toHaveCount(0, {
    timeout: 15_000,
  });

  // Roster cards are addressable by name only, and a template always deploys the
  // SAME name (AgentTemplateService uses a fixed map: "Business Analyst Agent",
  // "Developer Agent", "QA Agent"). The local DB is dirty with agents from
  // earlier runs, so a bare `.first()`/`getByText()` lookup can resolve to a
  // stale same-named card and pass for the wrong reason. Counting this name
  // before the click turns the assertion into "exactly one MORE card of that
  // name", which cannot be satisfied by a pre-existing card.
  const rosterCardsNamed = (name: string) =>
    page.locator('.crew-grid').getByRole('button', { name: `Open details for ${name}`, exact: true });
  const nameCountBefore = await rosterCardsNamed(templateName).count();

  const [deployResponse] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().includes('/api/v1/agents/from-template/'),
    ),
    firstCard.locator('.add-btn').click(),
  ]);
  expect(deployResponse.status(), 'the Deploy click should create the agent').toBe(201);
  const deployed = await deployResponse.json();
  expect(deployed.id).toBeTruthy();
  // The card that was clicked is the template that was actually deployed.
  expect(deployResponse.url()).toContain(`/from-template/${templateId}`);

  // The deployed agent appears on the roster — as a new card, not as a
  // pre-existing card that merely shares its name.
  await expect
    .poll(() => rosterCardsNamed(deployed.name).count(), { timeout: 20_000 })
    .toBe(nameCountBefore + 1);

  // Start a run for it from the Runs page.
  await page.goto('/runs');
  await page.waitForLoadState('networkidle');
  await page.getByRole('button', { name: '+ Start Run' }).click();

  const form = page.locator('.card.form-card');
  await expect(form).toBeVisible({ timeout: 15_000 });
  // The option label is "<name> (<HEALTH>)" — neither unique across runs nor the
  // catalog label — so select by value: the option value is exactly the agent id
  // returned by the deploy the UI just performed.
  await form.locator('select').selectOption(deployed.id);
  await form.getByPlaceholder('Describe what the agent should do...').fill('Say hello briefly.');

  const [runResponse] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/runs')),
    form.locator('button[type="submit"]').click(),
  ]);
  expect(runResponse.status(), 'the Start Run click should create the run').toBe(201);
  const run = await runResponse.json();
  // The form was driven with the agent this journey just deployed. The runs
  // table only prints the agent's name, which collides across runs, so the
  // submitted agentId is the one place that link is unambiguous.
  expect(JSON.parse(runResponse.request().postData() ?? '{}').agentId).toBe(deployed.id);

  // The run surfaces in the table with a real, non-placeholder status.
  const row = page.locator('.data-table tbody tr').filter({ hasText: run.id.slice(0, 8) }).first();
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row.locator('td').nth(2)).toContainText(
    /PENDING|INITIALIZING|RUNNING|PAUSED|COMPLETED|FAILED|CANCELLED/,
  );
});
