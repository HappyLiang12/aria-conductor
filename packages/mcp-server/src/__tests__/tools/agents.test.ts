import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Agent tools', () => {
  it('list_agents → GET /api/v1/agents', async () => {
    fetchMock = mockFetch({ '/api/v1/agents': { status: 200, body: [{ id: UUID, name: 'bot1' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_agents', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
    expect(fetchMock.calls[0].method).toBe('GET');
    expect(fetchMock.calls[0].url).toContain('/api/v1/agents');
  });

  it('get_agent → GET /api/v1/agents/:id', async () => {
    fetchMock = mockFetch({ [`/api/v1/agents/${UUID}`]: { status: 200, body: { id: UUID, name: 'bot1' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_agent', arguments: { id: UUID } });
    expect(JSON.parse(resultText(r)).name).toBe('bot1');
  });

  it('create_agent → POST /api/v1/agents with body', async () => {
    fetchMock = mockFetch({ '/api/v1/agents': { status: 201, body: { id: UUID, name: 'new' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_agent', arguments: { name: 'new', agentType: 'NATIVE' } });
    expect(JSON.parse(resultText(r)).name).toBe('new');
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).name).toBe('new');
  });

  it('create_agent with adkProvider=langchain → POST with adkProvider in body', async () => {
    fetchMock = mockFetch({ '/api/v1/agents': { status: 201, body: { id: UUID, name: 'lc-agent', adkProvider: 'langchain' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_agent', arguments: { name: 'lc-agent', agentType: 'ADK', adkProvider: 'langchain' } });
    expect(JSON.parse(resultText(r)).adkProvider).toBe('langchain');
    expect((fetchMock.calls[0].body as any).adkProvider).toBe('langchain');
  });

  it('update_agent with adkProvider → PUT with adkProvider in body', async () => {
    fetchMock = mockFetch({ [`/api/v1/agents/${UUID}`]: { status: 200, body: { id: UUID, name: 'updated', adkProvider: 'langchain' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'update_agent', arguments: { id: UUID, adkProvider: 'langchain' } });
    expect(JSON.parse(resultText(r)).adkProvider).toBe('langchain');
    expect((fetchMock.calls[0].body as any).adkProvider).toBe('langchain');
  });

  it('update_agent → PUT /api/v1/agents/:id', async () => {
    fetchMock = mockFetch({ [`/api/v1/agents/${UUID}`]: { status: 200, body: { id: UUID, name: 'updated' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'update_agent', arguments: { id: UUID, name: 'updated' } });
    expect(JSON.parse(resultText(r)).name).toBe('updated');
    expect(fetchMock.calls[0].method).toBe('PUT');
  });

  it('retire_agent → POST /api/v1/agents/:id/retire', async () => {
    fetchMock = mockFetch({ '/retire': { status: 200, body: { id: UUID, status: 'RETIRED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'retire_agent', arguments: { id: UUID } });
    expect(fetchMock.calls[0].url).toContain('/retire');
    expect(fetchMock.calls[0].method).toBe('POST');
  });
});
