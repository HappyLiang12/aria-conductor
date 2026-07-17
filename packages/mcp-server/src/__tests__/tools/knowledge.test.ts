import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Knowledge tools', () => {
  it('list_knowledge → GET /api/v1/knowledge', async () => {
    fetchMock = mockFetch({ '/api/v1/knowledge': { status: 200, body: [{ id: UUID, title: 'SOP-1' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_knowledge', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });

  it('submit_knowledge → POST /api/v1/knowledge', async () => {
    fetchMock = mockFetch({ '/api/v1/knowledge': { status: 201, body: { id: UUID, title: 'new SOP' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'submit_knowledge', arguments: { title: 'new SOP', type: 'SOP', content: 'body' } });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).title).toBe('new SOP');
  });

  it('review_knowledge → POST /api/v1/knowledge/:id/review', async () => {
    fetchMock = mockFetch({ '/review': { status: 200, body: { id: UUID, status: 'APPROVED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'review_knowledge', arguments: { id: UUID, approved: true } });
    expect(fetchMock.calls[0].url).toContain('/review');
    expect((fetchMock.calls[0].body as any).approved).toBe(true);
  });
});
