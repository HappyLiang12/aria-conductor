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

describe('Knowledge tools — validation & error mapping', () => {
  it('submit_knowledge rejects an invalid type enum value', async () => {
    fetchMock = mockFetch({ '/api/v1/knowledge': { status: 201, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'submit_knowledge', arguments: { title: 't', type: 'WIKI', content: 'c' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('submit_knowledge rejects missing required content', async () => {
    fetchMock = mockFetch({ '/api/v1/knowledge': { status: 201, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'submit_knowledge', arguments: { title: 't', type: 'SOP' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('review_knowledge rejects missing required approved flag', async () => {
    fetchMock = mockFetch({ '/review': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'review_knowledge', arguments: { id: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('get_knowledge maps a backend 404 into isError', async () => {
    fetchMock = mockFetch({ [`/api/v1/knowledge/${UUID}`]: { status: 404, body: { error: 'Knowledge item not found' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_knowledge', arguments: { id: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('404');
    expect(resultText(r)).toContain('Knowledge item not found');
  });
});
