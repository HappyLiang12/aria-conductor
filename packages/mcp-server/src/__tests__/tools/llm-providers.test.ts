import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

const UUID = '00000000-0000-0000-0000-000000000001';
let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('LLM Provider tools', () => {
  it('list_llm_providers → GET /api/v1/llm-providers', async () => {
    fetchMock = mockFetch({ '/api/v1/llm-providers': { status: 200, body: [{ id: UUID, name: 'openai' }] } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'list_llm_providers', arguments: {} });
    expect(JSON.parse(resultText(r))).toHaveLength(1);
  });

  it('test_llm_provider → POST /api/v1/llm-providers/:id/test', async () => {
    fetchMock = mockFetch({ '/test': { status: 200, body: { success: true, message: 'Connection successful' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'test_llm_provider', arguments: { id: UUID } });
    expect(JSON.parse(resultText(r)).success).toBe(true);
    expect(fetchMock.calls[0].url).toContain('/test');
    expect(fetchMock.calls[0].method).toBe('POST');
  });

  it('activate_llm_provider → POST /api/v1/llm-providers/:id/activate', async () => {
    fetchMock = mockFetch({ '/activate': { status: 200, body: { id: UUID, active: true } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'activate_llm_provider', arguments: { id: UUID } });
    expect(fetchMock.calls[0].url).toContain('/activate');
    expect(fetchMock.calls[0].method).toBe('POST');
  });
});

describe('LLM Provider tools — validation & error mapping', () => {
  it('create_llm_provider rejects missing required apiKey', async () => {
    fetchMock = mockFetch({ '/api/v1/llm-providers': { status: 201, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'create_llm_provider', arguments: { name: 'p', type: 'openai' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('get_llm_provider rejects a non-UUID id', async () => {
    fetchMock = mockFetch({ '/api/v1/llm-providers': { status: 200, body: {} } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'get_llm_provider', arguments: { id: 'openai' } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('Validation error');
    expect(fetchMock.calls).toHaveLength(0);
  });

  it('test_llm_provider maps a backend 502 into isError', async () => {
    fetchMock = mockFetch({ '/test': { status: 502, body: { message: 'provider unreachable' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'test_llm_provider', arguments: { id: UUID } });
    expect(r.isError).toBe(true);
    expect(resultText(r)).toContain('502');
    expect(resultText(r)).toContain('provider unreachable');
  });
});
