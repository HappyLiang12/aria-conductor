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
