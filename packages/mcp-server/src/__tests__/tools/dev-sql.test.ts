import { afterEach, describe, expect, it } from 'vitest';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

import { createTestClient, mockFetch } from '../helpers.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;

afterEach(async () => {
  if (fetchMock) {
    fetchMock.restore();
    fetchMock = undefined;
  }
  if (ctx) {
    await ctx.cleanup();
    ctx = undefined;
  }
});

function resultText(result: any) {
  return (result.content as Array<{ text: string }>)[0].text;
}

describe('Dev SQL tools', () => {
  it('sql_execute forwards SQL to the backend and returns formatted content', async () => {
    fetchMock = mockFetch({
      '/api/v1/dev/sql/execute': {
        status: 200,
        body: {
          statementType: 'SELECT',
          rowCount: 1,
          columns: ['value'],
          rows: [{ value: 1 }],
        },
      },
    });
    ctx = await createTestClient();

    const result = await ctx.client.callTool({
      name: 'sql_execute',
      arguments: { sql: 'select 1 as value' },
    });

    expect(result.isError).toBeUndefined();
    expect(fetchMock.calls[0].method).toBe('POST');
    expect(fetchMock.calls[0].url).toContain('/api/v1/dev/sql/execute');
    expect(fetchMock.calls[0].body).toEqual({ sql: 'select 1 as value' });
    expect(resultText(result)).toContain('SELECT');
    expect(resultText(result)).toContain('"value": 1');
  });

  it('sql_execute surfaces unavailable-endpoint errors', async () => {
    fetchMock = mockFetch({
      '/api/v1/dev/sql/execute': {
        status: 404,
        body: { message: 'not available in current profile' },
      },
    });
    ctx = await createTestClient();

    const result = await ctx.client.callTool({
      name: 'sql_execute',
      arguments: { sql: 'select 1' },
    });

    expect(result.isError).toBe(true);
    expect(resultText(result)).toContain('404');
    expect(resultText(result)).toContain('not available in current profile');
  });
});
