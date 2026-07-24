import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Trajectory tools', () => {
  it('trajectory.list → GET /api/v1/runs/:runId/trajectory', async () => {
    fetchMock = mockFetch({ '/trajectory': { status: 200, body: [{ turn: 1, role: 'user', content: 'go' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'trajectory.list', arguments: { runId: UUID } });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
    expect(fetchMock.calls[0].method).toBe('GET');
    expect(fetchMock.calls[0].url).toContain(`/api/v1/runs/${UUID}/trajectory`);
  });

  it('trajectory.list rejects a missing runId without hitting the backend', async () => {
    fetchMock = mockFetch({ '/trajectory': { status: 200, body: [] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'trajectory.list', arguments: {} });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('trajectory.list maps a backend 404 into isError', async () => {
    fetchMock = mockFetch({ '/trajectory': { status: 404, body: { error: 'Run not found' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'trajectory.list', arguments: { runId: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('404');
    expect(resultText(r)).toContain('Run not found');
  });
});
