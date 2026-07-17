import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from '../helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;
afterEach(async () => { if (fetchMock) { fetchMock.restore(); fetchMock = undefined; } if (ctx) { await ctx.cleanup(); ctx = undefined; } });

function resultText(r: any) { return (r.content as any[])[0].text; }

describe('Aria tools', () => {
  it('aria_chat sends POST /api/v1/aria/chat', async () => {
    fetchMock = mockFetch({ '/api/v1/aria/chat': { status: 200, body: { reply: 'Hello!', runId: 'r1' } } });
    ctx = await createTestClient();
    const r = await ctx.client.callTool({ name: 'aria_chat', arguments: { message: 'hi' } });
    expect(JSON.parse(resultText(r)).reply).toBe('Hello!');
    expect(fetchMock.calls[0].method).toBe('POST');
    expect((fetchMock.calls[0].body as any).message).toBe('hi');
  });

  it('aria_chat passes conversationId when provided', async () => {
    fetchMock = mockFetch({ '/api/v1/aria/chat': { status: 200, body: { reply: 'Hi', runId: 'r1', conversationId: 'conv-1' } } });
    ctx = await createTestClient();
    await ctx.client.callTool({ name: 'aria_chat', arguments: { message: 'hi', conversationId: 'conv-1' } });
    expect((fetchMock.calls[0].body as any).conversationId).toBe('conv-1');
  });
});
