import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from './helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '123e4567-e89b-12d3-a456-426614174000';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;

afterEach(async () => {
  if (fetchMock) { fetchMock.restore(); fetchMock = undefined; }
  if (ctx) { await ctx.cleanup(); ctx = undefined; }
});

describe('SDD fixes — MCP-layer verification', () => {
  // R-F4 note: the MCP `create_workflow` tool cannot express step kinds — its inputSchema
  // only exposes name/agentIds/initialPrompt (Zod strips unknown keys, so a `steps`/`kind`
  // payload never reaches the backend). The SDD-kind rejection itself ("must be created via
  // instantiate_template") is proven at the REST/service layer (WorkflowServiceSddGuardTest).
  // What the MCP layer MUST prove is that a backend 400 is surfaced to the caller — not
  // swallowed — so the operator sees the governance message. This test covers that.
  it('create_workflow surfaces a backend SDD rejection through MCP (R-F4)', async () => {
    fetchMock = mockFetch({
      '/api/v1/workflows': {
        status: 400,
        body: {
          message: 'SDD workflow steps (BA/DEV/QA) must be created via instantiate_template to ensure the SPEC_REVIEW gate.',
        },
      },
    });
    ctx = await createTestClient();

    const result = await ctx.client.callTool({
      name: 'create_workflow',
      arguments: { name: 'sdd-chain', agentIds: [UUID] },
    });

    expect(result.isError).toBe(true);
    expect((result.content as Array<{ type: string; text: string }>)[0].text).toContain('instantiate_template');
  });

  // D6 feedback loop via MCP: the operator's REJECT reason must round-trip to the backend so
  // SpecReviewCoordinator can reschedule the BA step with the reviewer's answers.
  it('decide_approval REJECT round-trips the reason to the backend (D6)', async () => {
    fetchMock = mockFetch({
      '/api/v1/approvals/': { status: 200, body: { approvalId: 'a1', status: 'processed' } },
    });
    ctx = await createTestClient();

    const result = await ctx.client.callTool({
      name: 'decide_approval',
      arguments: { id: UUID, approved: false, reason: 'Use postgres instead' },
    });

    expect(result.isError).toBeFalsy();
    const decide = fetchMock.calls.find(
      (c) => c.method === 'POST' && c.url.includes('/api/v1/approvals/') && c.url.endsWith('/decide'),
    );
    expect(decide).toBeDefined();
    expect(decide!.body).toEqual({ approved: false, reason: 'Use postgres instead' });
  });

  // Workflow state observation via MCP: used to watch the BA run without waiting blind.
  it('get_workflow exposes chain state through MCP', async () => {
    fetchMock = mockFetch({
      '/api/v1/workflows/': {
        status: 200,
        body: {
          id: UUID,
          status: 'RUNNING',
          currentStepIndex: 0,
          totalSteps: 3,
          steps: [{ index: 0, status: 'RUNNING' }],
        },
      },
    });
    ctx = await createTestClient();

    const result = await ctx.client.callTool({ name: 'get_workflow', arguments: { id: UUID } });

    expect(result.isError).toBeFalsy();
    const text = (result.content as Array<{ type: string; text: string }>)[0].text;
    const parsed = JSON.parse(text) as { id: string; status: string; steps: unknown[] };
    expect(parsed.id).toBe(UUID);
    expect(parsed.status).toBe('RUNNING');
    expect(parsed.steps).toHaveLength(1);
  });
});
