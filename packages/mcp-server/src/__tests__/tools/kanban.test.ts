import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Kanban tools', () => {
  it('list_kanban_items → GET /api/v1/kanban/items', async () => {
    fetchMock = mockFetch({ '/api/v1/kanban/items': { status: 200, body: [{ id: 'k1', title: 'task1' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_kanban_items', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });

  it('create_kanban_item → POST /api/v1/kanban/items', async () => {
    fetchMock = mockFetch({ '/api/v1/kanban/items': { status: 201, body: { id: 'k2', title: 'new' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_kanban_item', arguments: { title: 'new' } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).title).toBe('new');
  });

  it('transition_kanban_item → POST .../transition', async () => {
    fetchMock = mockFetch({ '/transition': { status: 200, body: { id: 'k1', status: 'DONE' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'transition_kanban_item', arguments: { id: 'k1', status: 'DONE' } });
    expect(fetchMock.calls[0].url).toContain('/transition');
    expect((fetchMock.calls[0].body as any).status).toBe('DONE');
  });
});

describe('Kanban tools — validation & error mapping', () => {
  it('list_kanban_items rejects an invalid status enum value', async () => {
    fetchMock = mockFetch({ '/api/v1/kanban/items': { status: 200, body: [] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_kanban_items', arguments: { status: 'ARCHIVED' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('create_kanban_item rejects missing required title', async () => {
    fetchMock = mockFetch({ '/api/v1/kanban/items': { status: 201, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_kanban_item', arguments: { description: 'no title' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('transition_kanban_item rejects an invalid target status', async () => {
    fetchMock = mockFetch({ '/transition': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'transition_kanban_item', arguments: { id: 'k1', status: 'FINISHED' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('update_kanban_item maps a backend 500 into isError', async () => {
    fetchMock = mockFetch({ '/api/v1/kanban/items/k1': { status: 500, body: { message: 'storage failure' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'update_kanban_item', arguments: { id: 'k1', title: 'new title' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('500');
    expect(resultText(r)).toContain('storage failure');
  });
});
