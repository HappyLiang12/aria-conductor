import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Execution tools', () => {
  it('start_execution → POST /api/v1/execution/start/:runId', async () => {
    fetchMock = mockFetch({ '/execution/start': { status: 202, body: { runId: UUID, status: 'STARTED' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'start_execution', arguments: { runId: UUID } });
    expect(JSON.parse(resultText(r)).status).toBe('STARTED');
    expect(fetchMock.calls[0].method).toBe('POST');
  });

  it('get_execution_status → GET /api/v1/execution/status/:runId', async () => {
    fetchMock = mockFetch({ '/execution/status': { status: 200, body: { runId: UUID, turnCount: 3 } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_execution_status', arguments: { runId: UUID } });
    expect(JSON.parse(resultText(r)).turnCount).toBe(3);
    expect(fetchMock.calls[0].method).toBe('GET');
  });
});

describe('Execution tools — validation & error mapping', () => {
  it('start_execution rejects a non-UUID runId', async () => {
    fetchMock = mockFetch({ '/execution/start': { status: 202, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'start_execution', arguments: { runId: 'run-1' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('get_execution_status maps a backend 404 into isError', async () => {
    fetchMock = mockFetch({ '/execution/status': { status: 404, body: { error: 'No execution for run' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_execution_status', arguments: { runId: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('404');
    expect(resultText(r)).toContain('No execution for run');
  });

  it('pause_execution surfaces a network failure as isError', async () => {
    const original = globalThis.fetch;
    globalThis.fetch = (() => { throw new TypeError('fetch failed'); }) as any;
    fetchMock = { calls: [], restore: () => { globalThis.fetch = original; } };
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'pause_execution', arguments: { runId: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('fetch failed');
  });
});
