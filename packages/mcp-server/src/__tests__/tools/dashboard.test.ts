import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Dashboard tools', () => {
  it('get_dashboard_summary → GET /api/v1/dashboard/summary', async () => {
    fetchMock = mockFetch({ '/dashboard/summary': { status: 200, body: { activeAgents: 3, runningRuns: 1, pendingApprovals: 0, totalTokensBurned: 500 } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_dashboard_summary', arguments: {} });
    const data = JSON.parse(resultText(r));
    expect(data.activeAgents).toBe(3);
    expect(data.runningRuns).toBe(1);
    expect(fetchMock.calls[0].url).toContain('/dashboard/summary');
  });

  it('get_recent_activity → GET /api/v1/dashboard/activity', async () => {
    fetchMock = mockFetch({ '/dashboard/activity': { status: 200, body: [{ eventType: 'RUN_STARTED' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_recent_activity', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
    expect(fetchMock.calls[0].url).toContain('/dashboard/activity');
  });
});
