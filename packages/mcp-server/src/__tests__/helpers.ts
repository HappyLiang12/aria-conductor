import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createServer } from '../server.js';
import { vi } from 'vitest';

/**
 * Create a paired MCP Client + Server connected via InMemoryTransport.
 * No stdio or network needed — purely in-process.
 */
export async function createTestClient() {
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const server = createServer();
  await server.connect(serverTransport);

  const client = new Client({ name: 'test-client', version: '1.0.0' });
  await client.connect(clientTransport);

  return {
    client,
    async cleanup() {
      await client.close();
      await server.close();
    },
  };
}

/** A single recorded fetch call. */
export interface FetchCall {
  url: string;
  method: string;
  body?: unknown;
}

/**
 * Replace `globalThis.fetch` with a mock that returns canned responses
 * keyed by URL path substring. Returns a `calls` array for assertions.
 *
 * Each entry in `responses` maps a URL substring to { status, body }.
 * If no match is found, returns 404.
 */
export function mockFetch(responses: Record<string, { status: number; body: unknown }>) {
  const calls: FetchCall[] = [];

  const original = globalThis.fetch;
  globalThis.fetch = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const method = init?.method ?? 'GET';
    let body: unknown;
    if (init?.body) {
      try { body = JSON.parse(init.body as string); } catch { body = init.body; }
    }
    calls.push({ url, method, body });

    // Find matching response by URL substring
    for (const [pattern, resp] of Object.entries(responses)) {
      if (url.includes(pattern)) {
        const respBody = resp.status === 204 ? null : JSON.stringify(resp.body);
        return new Response(respBody, {
          status: resp.status,
          statusText: resp.status === 204 ? 'No Content' : 'OK',
          headers: { 'Content-Type': 'application/json' },
        });
      }
    }

    // Default: 404
    return new Response(JSON.stringify({ error: 'Not found' }), {
      status: 404,
      statusText: 'Not Found',
      headers: { 'Content-Type': 'application/json' },
    });
  }) as any;

  return {
    calls,
    restore() {
      globalThis.fetch = original;
    },
  };
}
