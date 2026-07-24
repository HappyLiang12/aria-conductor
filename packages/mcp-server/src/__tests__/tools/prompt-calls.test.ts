import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Prompt call tools', () => {
  it('prompt_call.list → GET /api/v1/prompt-calls', async () => {
    fetchMock = mockFetch({ '/api/v1/prompt-calls': { status: 200, body: [{ id: 'pc1', model: 'deepseek-chat' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'prompt_call.list', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
    expect(fetchMock.calls[0].method).toBe('GET');
    expect(fetchMock.calls[0].url).toContain('/api/v1/prompt-calls');
  });

  it('prompt_call.list forwards agentId and runId as query params', async () => {
    fetchMock = mockFetch({ '/api/v1/prompt-calls': { status: 200, body: [] } });
    ctx = await createTestClient();
    await ctx.client.callTool({ name: 'prompt_call.list', arguments: { agentId: UUID, runId: 'r-9' } });
    expect(fetchMock.calls[0].url).toContain(`agentId=${UUID}`);
    expect(fetchMock.calls[0].url).toContain('runId=r-9');
  });

  it('prompt_call.stats → GET /api/v1/prompt-calls/stats with agentId', async () => {
    fetchMock = mockFetch({ '/api/v1/prompt-calls/stats': { status: 200, body: { totalCalls: 7, totalTokens: 999 } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'prompt_call.stats', arguments: { agentId: UUID } });
    expect(JSON.parse(resultText(r)).totalCalls).toBe(7);
    expect(fetchMock.calls[0].url).toContain(`agentId=${UUID}`);
  });

  it('prompt_call.stats rejects a missing agentId without hitting the backend', async () => {
    fetchMock = mockFetch({ '/api/v1/prompt-calls/stats': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'prompt_call.stats', arguments: {} });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('prompt_call.stats maps a backend 500 into isError', async () => {
    fetchMock = mockFetch({ '/api/v1/prompt-calls/stats': { status: 500, body: { message: 'aggregation failed' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'prompt_call.stats', arguments: { agentId: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('500');
    expect(resultText(r)).toContain('aggregation failed');
  });
});
