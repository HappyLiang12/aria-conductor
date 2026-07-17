import { test, expect, type Page } from '@playwright/test';

/**
 * E2E test: Workflow YAML execution endpoint.
 *
 * Covers POST /api/v1/workflows/execute-yaml:
 * - Valid YAML → 201 + workflow created
 * - Empty yamlContent → 400
 * - Invalid YAML format → error
 * - Created workflow has correct steps
 * - YAML with parameters → parameter substitution
 */

test.describe.configure({ mode: 'serial', timeout: 120_000 });

const BACKEND = 'http://localhost:8080/api/v1';

async function apiCall(
  page: Page,
  method: string,
  path: string,
  body?: object,
): Promise<{ status: number; data: any }> {
  const url = `${BACKEND}${path}`;
  const bodyStr = body ? JSON.stringify(body) : undefined;
  return page.evaluate(
    async ({ url, method, bodyStr }) => {
      const opts: RequestInit = { method, headers: { 'Content-Type': 'application/json' } };
      if (bodyStr) opts.body = bodyStr;
      const r = await fetch(url, opts);
      const ct = r.headers.get('content-type') ?? '';
      const data = ct.includes('json') ? await r.json().catch(() => null) : null;
      return { status: r.status, data };
    },
    { url, method, bodyStr },
  );
}

async function createAgent(page: Page): Promise<string> {
  const { status, data } = await apiCall(page, 'POST', '/agents', {
    name: `WfYamlAgent-${Date.now()}`,
    agentType: 'NATIVE',
    description: 'YAML E2E agent',
  });
  expect(status).toBe(201);
  return data.id;
}

let agentId: string;

test('0. setup — create test agent', async ({ page }) => {
  await page.goto('/');
  agentId = await createAgent(page);
  expect(agentId).toBeTruthy();
});

// ── 1. Valid YAML → 201 ────────────────────────────────────────────
test('1. execute-yaml with valid YAML → 201 + workflow created', async ({ page }) => {
  const yamlContent = `name: yaml-test-workflow
steps:
  - agent_id: ${agentId}
    prompt_template: "Analyze this data"
    max_iterations: 2
  - agent_id: ${agentId}
    prompt_template: "Report findings"
    max_iterations: 1
`;

  const { status, data } = await apiCall(page, 'POST', '/workflows/execute-yaml', {
    yamlContent,
  });

  expect(status).toBe(201);
  expect(data.id).toBeTruthy();
  expect(data.status).toBeTruthy();
});

// ── 2. Empty yamlContent → 400 ─────────────────────────────────────
test('2. execute-yaml with empty content → 400', async ({ page }) => {
  const { status, data } = await apiCall(page, 'POST', '/workflows/execute-yaml', {
    yamlContent: '',
  });

  expect(status).toBe(400);
  expect(data?.error).toBeTruthy();
});

// ── 3. Invalid YAML format → error ─────────────────────────────────
test('3. execute-yaml with invalid YAML → error', async ({ page }) => {
  const invalidYaml = 'name: test\nsteps: [\n  invalid: yaml: content: [';

  const { status } = await apiCall(page, 'POST', '/workflows/execute-yaml', {
    yamlContent: invalidYaml,
  });

  // Should fail to parse YAML
  expect(status).toBeGreaterThanOrEqual(400);
});

// ── 4. YAML execution → verify created workflow steps ──────────────
test('4. execute-yaml → GET workflow has correct step count', async ({ page }) => {
  const yamlContent = `name: yaml-verify-workflow
steps:
  - agent_id: ${agentId}
    prompt_template: "Step A"
    max_iterations: 1
  - agent_id: ${agentId}
    prompt_template: "Step B"
    max_iterations: 2
  - agent_id: ${agentId}
    prompt_template: "Step C"
    max_iterations: 3
`;

  const { status, data } = await apiCall(page, 'POST', '/workflows/execute-yaml', {
    yamlContent,
  });
  expect(status).toBe(201);

  // Verify the created workflow
  const { data: wf } = await apiCall(page, 'GET', `/workflows/${data.id}`);
  expect(wf.totalSteps).toBe(3);
  expect(wf.steps).toHaveLength(3);
});

// ── 5. YAML with parameters → substitution ─────────────────────────
test('5. execute-yaml with parameters → {key} substituted in prompts', async ({ page }) => {
  const yamlContent = `name: yaml-params-workflow
steps:
  - agent_id: ${agentId}
    prompt_template: "Analyze {topic} for {audience}"
    max_iterations: 1
`;

  const { status, data } = await apiCall(page, 'POST', '/workflows/execute-yaml', {
    yamlContent,
    parameters: { topic: 'market trends', audience: 'executives' },
  });
  expect(status).toBe(201);

  // Verify the prompt was substituted
  const { data: wf } = await apiCall(page, 'GET', `/workflows/${data.id}`);
  expect(wf.steps[0].promptTemplate).toContain('market trends');
  expect(wf.steps[0].promptTemplate).toContain('executives');
  expect(wf.steps[0].promptTemplate).not.toContain('{topic}');
  expect(wf.steps[0].promptTemplate).not.toContain('{audience}');
});
