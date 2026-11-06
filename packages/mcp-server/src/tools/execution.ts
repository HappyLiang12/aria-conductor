import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const executionTools = [
  {
    name: 'start_execution',
    description: 'Start executing a run via the agent loop engine.',
    inputSchema: z.object({ runId: z.string().uuid().describe('Run UUID to start') }),
    handler: async ({ runId }: { runId: string }) =>
      toJsonResult(await http.post(`/api/v1/execution/start/${runId}`)),
  },
  {
    name: 'get_execution_status',
    description: 'Get the current execution status for a run (turn count, token usage, etc.).',
    inputSchema: z.object({ runId: z.string().uuid().describe('Run UUID') }),
    handler: async ({ runId }: { runId: string }) =>
      toJsonResult(await http.get(`/api/v1/execution/status/${runId}`)),
  },
  {
    name: 'pause_execution',
    description: 'Pause an active execution.',
    inputSchema: z.object({ runId: z.string().uuid().describe('Run UUID') }),
    handler: async ({ runId }: { runId: string }) =>
      toJsonResult(await http.post(`/api/v1/execution/pause/${runId}`)),
  },
  {
    name: 'resume_execution',
    description: 'Resume a paused execution.',
    inputSchema: z.object({ runId: z.string().uuid().describe('Run UUID') }),
    handler: async ({ runId }: { runId: string }) =>
      toJsonResult(await http.post(`/api/v1/execution/resume/${runId}`)),
  },
];
