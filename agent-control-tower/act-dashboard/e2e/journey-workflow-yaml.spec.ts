import { test, expect } from '@playwright/test';
import { apiCall, seedAgent, uniqueName } from './fixtures';

/**
 * Journey (no-LLM adapted): YAML template import → chain created → steps
 * visible in the Workflows UI → cancel/governance assertions → terminal state
 * verified in UI + API. Without an LLM key the chain's runs fail fast, so the
 * journey asserts reachable terminal states (CANCELLED/FAILED/COMPLETED).
 */
test.describe.configure({ mode: 'serial' });

let agentId = '';
let agentName = '';
let chainId = '';
let chainName = '';

test('0. seed a runnable agent', async ({ request }) => {
  const agent = await seedAgent(request);
  agentId = agent.id;
  agentName = agent.name;
  expect(agentId).toBeTruthy();
});

test('1. YAML import creates a two-step chain', async ({ request }) => {
  const yamlContent = `name: ${uniqueName('e2e-yaml-journey')}
steps:
  - agent_id: ${agentId}
    prompt_template: "Analyze the input data"
    max_iterations: 1
  - agent_id: ${agentId}
    prompt_template: "Summarize the findings"
    max_iterations: 1
`;
  const { status, data } = await apiCall(request, 'POST', '/workflows/execute-yaml', { yamlContent });
  expect(status).toBe(201);
  chainId = data.id;
  expect(chainId).toBeTruthy();
  // The engine assigns its own chain name (yaml-workflow-{ts}) and ignores the
  // YAML `name:` field — track the persisted name for the UI assertions.
  chainName = data.name;
  expect(chainName).toBeTruthy();

  const chain = await apiCall(request, 'GET', `/workflows/${chainId}`);
  expect(chain.status).toBe(200);
  expect(chain.data.totalSteps).toBe(2);
  expect(chain.data.steps.map((s: { promptTemplate: string }) => s.promptTemplate)).toEqual([
    'Analyze the input data',
    'Summarize the findings',
  ]);
});

test('2. chain and its steps are visible in the Workflows UI', async ({ page }) => {
  await page.goto('/workflows');
  await page.waitForLoadState('networkidle');
  // The chains list refetches every 5s; allow for the first poll to land.
  await expect(page.getByText(chainName, { exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(`Step 1: ${agentName}`)).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(`Step 2: ${agentName}`)).toBeVisible({ timeout: 20_000 });
});

test('3. governance: cancel drives the chain to a terminal state (UI + API)', async ({ page, request }) => {
  const cancel = await apiCall(request, 'POST', `/workflows/${chainId}/cancel`);
  if (cancel.status !== 200) {
    // Without an LLM key runs fail fast — the chain may already be terminal,
    // in which case cancel must be rejected by the state machine.
    expect(cancel.status).toBeGreaterThanOrEqual(400);
  }
  await expect
    .poll(async () => (await apiCall(request, 'GET', `/workflows/${chainId}`)).data?.status, {
      timeout: 30_000,
    })
    .toMatch(/CANCELLED|FAILED|COMPLETED/);

  await page.goto('/workflows');
  await page.waitForLoadState('networkidle');
  // WorkflowCard header renders [status][name] as sibling spans.
  const statusBadge = page
    .getByText(chainName, { exact: true })
    .locator('xpath=preceding-sibling::span[1]');
  await expect(statusBadge).toHaveText(/CANCELLED|FAILED|COMPLETED/);
});

test('4. negative: YAML referencing an unknown agent is rejected', async ({ request }) => {
  const yamlContent = `name: ${uniqueName('e2e-yaml-bad-agent')}
steps:
  - agent_id: 00000000-0000-0000-0000-000000000000
    prompt_template: "Never runs"
    max_iterations: 1
`;
  const { status } = await apiCall(request, 'POST', '/workflows/execute-yaml', { yamlContent });
  expect(status).toBeGreaterThanOrEqual(400);
});
