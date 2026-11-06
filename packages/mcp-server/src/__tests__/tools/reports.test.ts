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

  it('amend_report → POST /api/v1/reports/:id/amend with instructions', async () => {
    fetchMock = mockFetch({ '/amend': { status: 200, body: { id: 'r1', status: 'AMENDED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'amend_report', arguments: { id: 'r1', instructions: 'add totals' } });
    expect(fetchMock.calls[0].url).toContain('/api/v1/reports/r1/amend');
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).instructions).toBe('add totals');
  });
});

describe('Report tools — validation & error mapping', () => {
  it('amend_report rejects missing required instructions', async () => {
    fetchMock = mockFetch({ '/amend': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'amend_report', arguments: { id: 'r1' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('generate_report rejects a non-UUID runId', async () => {
    fetchMock = mockFetch({ '/reports/generate': { status: 201, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'generate_report', arguments: { runId: 'run-1' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('get_report maps a backend 404 into isError', async () => {
    fetchMock = mockFetch({ '/api/v1/reports/missing': { status: 404, body: { error: 'Report not found' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_report', arguments: { id: 'missing' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('404');
    expect(resultText(r)).toContain('Report not found');
  });
});
