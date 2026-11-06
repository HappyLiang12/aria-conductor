import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const trajectoryTools = [
  {
    name: 'trajectory.list',
    description: 'List the conversation trajectory (turn-by-turn messages) for a given run.',
    inputSchema: z.object({
      runId: z.string().describe('Run UUID'),
    }),
    handler: async ({ runId }: { runId: string }) =>
      toJsonResult(await http.get(`/api/v1/runs/${runId}/trajectory`)),
  },
];
