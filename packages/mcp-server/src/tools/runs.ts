import { z } from 'zod';
import { http, qs, toJsonResult } from '../http-client.js';

export const runTools = [
  {
    name: 'list_runs',
    description: 'List agent runs, optionally filtered by agentId and/or status.',
    inputSchema: z.object({
      agentId: z.string().uuid().optional().describe('Filter by agent UUID'),
      status: z.enum(['PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED']).optional().describe('Filter by run status'),
    }),
    handler: async ({ agentId, status }: { agentId?: string; status?: string }) =>
      toJsonResult(await http.get(`/api/v1/runs${qs({ agentId, status })}`)),
  },
  {
    name: 'get_run',
    description: 'Get details of a specific run by ID.',
    inputSchema: z.object({ id: z.string().uuid().describe('Run UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/runs/${id}`)),
  },
  {
    name: 'create_run',
    description: 'Create and optionally start a new run for an agent.',
    inputSchema: z.object({
      agentId: z.string().uuid().describe('Agent UUID to run'),
      promptSeed: z.string().describe('Prompt/task for the agent'),
      maxIterations: z.number().optional().describe('Maximum iterations (default: 50)'),
    }),
    handler: async (body: Record<string, unknown>) => toJsonResult(await http.post('/api/v1/runs', body)),
  },
  {
    name: 'pause_run',
    description: 'Pause a running agent run.',
    inputSchema: z.object({ id: z.string().uuid().describe('Run UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.post(`/api/v1/runs/${id}/pause`)),
  },
  {
    name: 'resume_run',
    description: 'Resume a paused agent run.',
    inputSchema: z.object({ id: z.string().uuid().describe('Run UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.post(`/api/v1/runs/${id}/resume`)),
  },
  {
    name: 'cancel_run',
    description: 'Cancel a running or paused agent run.',
    inputSchema: z.object({ id: z.string().uuid().describe('Run UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.post(`/api/v1/runs/${id}/cancel`)),
  },
  {
    name: 'get_run_tool_calls',
    description: 'Get tool call history for a run',
    inputSchema: z.object({
      id: z.string().uuid().describe('Run UUID'),
    }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.get(`/api/v1/runs/${id}/tool-calls`)),
  },
];
