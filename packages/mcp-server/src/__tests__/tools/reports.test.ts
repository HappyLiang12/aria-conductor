import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Report tools', () => {
  it('generate_report → POST /api/v1/reports/generate', async () => {
    fetchMock = mockFetch({ '/reports/generate': { status: 201, body: { id: 'r1', title: 'Report' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'generate_report', arguments: {} });
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(JSON.parse(resultText(r)).id).toBe('r1');
  });

  it('list_reports → GET /api/v1/reports', async () => {
    fetchMock = mockFetch({ '/api/v1/reports': { status: 200, body: [{ id: 'r1' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_reports', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });
});
