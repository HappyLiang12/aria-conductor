import { z } from 'zod';

import { http, toJsonResult } from '../http-client.js';

export const devSqlTools = [
  {
    name: 'sql_execute',
    description: 'Execute one SQL statement against the live ACT datasource. H2 local profile only. Dangerous: may modify data.',
    inputSchema: z.object({
      sql: z.string().min(1).describe('Single SQL statement to execute against the live ACT datasource'),
    }),
    handler: async ({ sql }: { sql: string }) =>
      toJsonResult(await http.post('/api/v1/dev/sql/execute', { sql })),
  },
];
