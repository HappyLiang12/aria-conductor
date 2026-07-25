import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Approval tools', () => {
  it('list_pending_approvals → GET /api/v1/approvals', async () => {
    fetchMock = mockFetch({ '/api/v1/approvals': { status: 200, body: [{ id: UUID, status: 'PENDING' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_pending_approvals', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });

  it('decide_approval → POST /api/v1/approvals/:id/decide', async () => {
    fetchMock = mockFetch({ '/decide': { status: 200, body: { approvalId: UUID, approved: true, status: 'processed' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'decide_approval', arguments: { id: UUID, approved: true, reason: 'looks good' } });
    expect(fetchMock.calls[0].url).toContain('/decide');
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).approved).toBe(true);
  });
});

describe('Approval tools — validation & error mapping', () => {
  it('decide_approval rejects missing required approved flag', async () => {
    fetchMock = mockFetch({ '/decide': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'decide_approval', arguments: { id: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('decide_approval rejects a string in place of the boolean approved flag', async () => {
    fetchMock = mockFetch({ '/decide': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'decide_approval', arguments: { id: UUID, approved: 'yes' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('get_approval rejects a non-UUID id', async () => {
    fetchMock = mockFetch({ '/api/v1/approvals': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_approval', arguments: { id: '123' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('decide_approval maps a backend 404 into isError', async () => {
    fetchMock = mockFetch({ '/decide': { status: 404, body: { error: 'Approval not found' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'decide_approval', arguments: { id: UUID, approved: false } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('404');
    expect(resultText(r)).toContain('Approval not found');
  });
});
