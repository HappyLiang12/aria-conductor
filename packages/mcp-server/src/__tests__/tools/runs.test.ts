import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Run tools', () => {
  it('list_runs → GET /api/v1/runs', async () => {
    fetchMock = mockFetch({ '/api/v1/runs': { status: 200, body: [{ id: UUID }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_runs', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });

  it('list_runs with filter → includes query params', async () => {
    fetchMock = mockFetch({ '/api/v1/runs': { status: 200, body: [] } });
    ctx = await createTestClient();
    await ctx.client.callTool({ name: 'list_runs', arguments: { status: 'RUNNING' } });
    expect(fetchMock.calls[0].url).toContain('status=RUNNING');
  });

  it('get_run → GET /api/v1/runs/:id', async () => {
    fetchMock = mockFetch({ [`/api/v1/runs/${UUID}`]: { status: 200, body: { id: UUID, status: 'COMPLETED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_run', arguments: { id: UUID } });
    expect(JSON.parse(resultText(r)).status).toBe('COMPLETED');
  });

  it('create_run → POST /api/v1/runs', async () => {
    fetchMock = mockFetch({ '/api/v1/runs': { status: 201, body: { id: UUID, status: 'PENDING' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_run', arguments: { agentId: UUID, promptSeed: 'do stuff' } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).agentId).toBe(UUID);
  });

  it('pause_run → POST /api/v1/runs/:id/pause', async () => {
    fetchMock = mockFetch({ '/pause': { status: 200, body: { id: UUID, status: 'PAUSED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'pause_run', arguments: { id: UUID } });
    expect(fetchMock.calls[0].url).toContain('/pause');
  });

  it('cancel_run → POST /api/v1/runs/:id/cancel', async () => {
    fetchMock = mockFetch({ '/cancel': { status: 200, body: { id: UUID, status: 'CANCELLED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'cancel_run', arguments: { id: UUID } });
    expect(fetchMock.calls[0].url).toContain('/cancel');
  });

  it('get_run_tool_calls calls correct endpoint', async () => {
    fetchMock = mockFetch({ '/tool-calls': { status: 200, body: [{ toolName: 'search' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_run_tool_calls', arguments: { id: UUID } });
    expect(fetchMock.calls[0].url).toContain(`/api/v1/runs/${UUID}/tool-calls`);
    expect(JSON.parse((r.content as any[])[0].text)).toHaveLength(1);
  });
});
