import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from './helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;

afterEach(async () => {
  if (fetchMock) { fetchMock.restore(); fetchMock = undefined; }
  if (ctx) { await ctx.cleanup(); ctx = undefined; }
});

describe('Error handling', () => {
  it('returns isError for HTTP 404', async () => {
    fetchMock = mockFetch({ '/api/v1/agents/00000000-0000-0000-0000-000000000001': { status: 404, body: { error: 'Not found' } } });
    ctx = await createTestClient();
    const result = await ctx.client.callTool({ name: 'get_agent', arguments: { id: '00000000-0000-0000-0000-000000000001' } });
    expect(result.isError).toBe(true);
    const text = (result.content as any[])[0].text;
    expect(text).toContain('404');
  });

  it('returns isError with details for HTTP 500', async () => {
    fetchMock = mockFetch({ '/api/v1/agents': { status: 500, body: { message: 'DB connection lost' } } });
    ctx = await createTestClient();
    const result = await ctx.client.callTool({ name: 'list_agents', arguments: {} });
    expect(result.isError).toBe(true);
    const text = (result.content as any[])[0].text;
    expect(text).toContain('500');
    expect(text).toContain('DB connection lost');
  });

  it('returns isError on network failure', async () => {
    const original = globalThis.fetch;
    globalThis.fetch = (() => { throw new TypeError('fetch failed'); }) as any;
    fetchMock = { calls: [], restore: () => { globalThis.fetch = original; } };
    ctx = await createTestClient();
    const result = await ctx.client.callTool({ name: 'get_dashboard_summary', arguments: {} });
    expect(result.isError).toBe(true);
    const text = (result.content as any[])[0].text;
    expect(text).toContain('fetch failed');
  });
});
