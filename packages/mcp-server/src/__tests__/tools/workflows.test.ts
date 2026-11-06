import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
const UUID2 = '00000000-0000-0000-0000-000000000002';
const UUID3 = '00000000-0000-0000-0000-000000000003';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Workflow tools', () => {
  // ── Original tools ───────────────────────────────────────────────────

  it('list_workflows → GET /api/v1/workflows', async () => {
    fetchMock = mockFetch({ '/api/v1/workflows': { status: 200, body: [{ id: UUID }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_workflows', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });

  it('get_workflow → GET /api/v1/workflows/:id', async () => {
    fetchMock = mockFetch({ [`/api/v1/workflows/${UUID}`]: { status: 200, body: { id: UUID, name: 'wf1' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_workflow', arguments: { id: UUID } });
    expect(JSON.parse(resultText(r)).id).toBe(UUID);
    expect(fetchMock.calls[0].method).toBe('GET');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/workflows/${UUID}`);
  });

  it('create_workflow → POST /api/v1/workflows', async () => {
    fetchMock = mockFetch({ '/api/v1/workflows': { status: 201, body: { id: UUID, name: 'wf1' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_workflow', arguments: { name: 'wf1', agentIds: [UUID] } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).name).toBe('wf1');
  });

  // ── cancel_workflow ──────────────────────────────────────────────────

  it('cancel_workflow → POST /api/v1/workflows/:id/cancel', async () => {
    fetchMock = mockFetch({ '/cancel': { status: 200, body: { id: UUID, status: 'CANCELLED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'cancel_workflow', arguments: { id: UUID } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/workflows/${UUID}/cancel`);
    expect(JSON.parse(resultText(r)).status).toBe('CANCELLED');
  });

  // ── retry_workflow_step ──────────────────────────────────────────────

  it('retry_workflow_step → POST /api/v1/workflows/:id/retry with stepIndex', async () => {
    fetchMock = mockFetch({ '/retry': { status: 200, body: { id: UUID, status: 'RUNNING' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'retry_workflow_step', arguments: { id: UUID, stepIndex: 2 } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/workflows/${UUID}/retry`);
    expect((fetchMock.calls[0].body as any).stepIndex).toBe(2);
    expect(JSON.parse(resultText(r)).status).toBe('RUNNING');
  });

  // ── update_workflow ─────────────────────────────────────────────────

  it('update_workflow → PUT /api/v1/workflows/:id with body', async () => {
    fetchMock = mockFetch({ [`/api/v1/workflows/${UUID}`]: { status: 200, body: { id: UUID, name: 'new-name' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'update_workflow', arguments: { id: UUID, name: 'new-name' } });
    expect(fetchMock.calls[0].method).toBe('PUT');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/workflows/${UUID}`);
    expect((fetchMock.calls[0].body as any).name).toBe('new-name');
    expect(JSON.parse(resultText(r)).name).toBe('new-name');
  });

  // ── delete_workflow ─────────────────────────────────────────────────

  it('delete_workflow → DELETE /api/v1/workflows/:id', async () => {
    fetchMock = mockFetch({ [`/api/v1/workflows/${UUID}`]: { status: 204, body: null } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'delete_workflow', arguments: { id: UUID } });
    expect(fetchMock.calls[0].method).toBe('DELETE');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/workflows/${UUID}`);
    expect(JSON.parse(resultText(r)).success).toBe(true);
  });

  // ── merge_workflows ─────────────────────────────────────────────────

  it('merge_workflows → POST /api/v1/workflows/merge with sourceIds', async () => {
    fetchMock = mockFetch({ '/merge': { status: 200, body: { id: UUID, totalSteps: 5 } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'merge_workflows', arguments: { sourceIds: [UUID2, UUID3], name: 'merged' } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(fetchMock.calls[0].url).toContain('/api/v1/workflows/merge');
    expect((fetchMock.calls[0].body as any).sourceIds).toEqual([UUID2, UUID3]);
    expect((fetchMock.calls[0].body as any).name).toBe('merged');
    expect(JSON.parse(resultText(r)).totalSteps).toBe(5);
  });

  // ── execute_yaml ────────────────────────────────────────────────────

  it('execute_yaml → POST /api/v1/workflows/execute-yaml with yamlContent', async () => {
    fetchMock = mockFetch({ '/execute-yaml': { status: 200, body: { id: UUID, status: 'PENDING' } } });
    ctx = await createTestClient();
    const yaml = 'schema_version: "1.0"';
    const r = await ctx.client.callTool({ name: 'execute_yaml', arguments: { yamlContent: yaml } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(fetchMock.calls[0].url).toContain('/api/v1/workflows/execute-yaml');
    expect((fetchMock.calls[0].body as any).yamlContent).toBe(yaml);
    expect(JSON.parse(resultText(r)).status).toBe('PENDING');
  });

  // ── reuse_workflow ──────────────────────────────────────────────────

  it('reuse_workflow → POST /api/v1/workflows/templates/:id/reuse with parameters', async () => {
    fetchMock = mockFetch({ '/reuse': { status: 200, body: { id: UUID, name: 'instance' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'reuse_workflow', arguments: { templateId: UUID2, parameters: { topic: 'AI' } } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/workflows/templates/${UUID2}/reuse`);
    expect((fetchMock.calls[0].body as any).parameters).toEqual({ topic: 'AI' });
    expect(JSON.parse(resultText(r)).name).toBe('instance');
  });
});

describe('Workflow tools — validation & error mapping', () => {
  it('create_workflow rejects agentIds containing non-UUID entries', async () => {
    fetchMock = mockFetch({ '/api/v1/workflows': { status: 201, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_workflow', arguments: { name: 'wf', agentIds: ['not-a-uuid'] } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('retry_workflow_step rejects a negative stepIndex', async () => {
    fetchMock = mockFetch({ '/retry': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'retry_workflow_step', arguments: { id: UUID, stepIndex: -1 } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('merge_workflows rejects missing required name', async () => {
    fetchMock = mockFetch({ '/merge': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'merge_workflows', arguments: { sourceIds: [UUID2, UUID3] } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('execute_yaml rejects missing yamlContent', async () => {
    fetchMock = mockFetch({ '/execute-yaml': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'execute_yaml', arguments: {} });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('delete_workflow maps a backend 409 (RUNNING) into isError', async () => {
    fetchMock = mockFetch({ [`/api/v1/workflows/${UUID}`]: { status: 409, body: { message: 'Cannot delete RUNNING workflow' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'delete_workflow', arguments: { id: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('409');
    expect(resultText(r)).toContain('Cannot delete RUNNING workflow');
  });
});
