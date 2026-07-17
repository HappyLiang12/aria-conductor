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
