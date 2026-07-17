import { z } from 'zod';
import { http, qs, toJsonResult } from '../http-client.js';

export const promptCallTools = [
  {
    name: 'prompt_call.list',
    description: 'List prompt calls (LLM invocations), optionally filtered by agent or run.',
    inputSchema: z.object({
      agentId: z.string().optional().describe('Filter by agent UUID'),
      runId: z.string().optional().describe('Filter by run UUID'),
    }),
    handler: async ({ agentId, runId }: { agentId?: string; runId?: string }) =>
      toJsonResult(await http.get(`/api/v1/prompt-calls${qs({ agentId, runId })}`)),
  },
  {
    name: 'prompt_call.stats',
    description: 'Get aggregated prompt call statistics for an agent.',
    inputSchema: z.object({
      agentId: z.string().describe('Agent UUID'),
    }),
    handler: async ({ agentId }: { agentId: string }) =>
      toJsonResult(await http.get(`/api/v1/prompt-calls/stats${qs({ agentId })}`)),
  },
];
