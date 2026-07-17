import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const agentTools = [
  {
    name: 'list_agents',
    description: 'List all registered agents in the Aria Conductor.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/agents')),
  },
  {
    name: 'get_agent',
    description: 'Get details of a specific agent by ID.',
    inputSchema: z.object({ id: z.string().uuid().describe('Agent UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/agents/${id}`)),
  },
  {
    name: 'create_agent',
    description: 'Register a new agent in the control tower.',
    inputSchema: z.object({
      name: z.string().describe('Agent display name'),
      agentType: z.enum(['ADK', 'NATIVE']).describe('Agent type: ADK or NATIVE'),
      description: z.string().optional().describe('Agent description'),
      systemPrompt: z.string().optional().describe('System prompt for the agent'),
      modelId: z.string().optional().describe('LLM model identifier'),
      adkProvider: z.enum(['langchain']).optional().describe('ADK runtime provider: langchain'),
    }),
    handler: async (body: Record<string, unknown>) => toJsonResult(await http.post('/api/v1/agents', body)),
  },
  {
    name: 'update_agent',
    description: 'Update an existing agent configuration.',
    inputSchema: z.object({
      id: z.string().uuid().describe('Agent UUID'),
      name: z.string().optional().describe('Agent display name'),
      description: z.string().optional().describe('Agent description'),
      systemPrompt: z.string().optional().describe('System prompt'),
      modelId: z.string().optional().describe('LLM model identifier'),
      adkProvider: z.enum(['langchain']).optional().describe('ADK runtime provider: langchain'),
    }),
    handler: async ({ id, ...body }: { id: string;[k: string]: unknown }) =>
      toJsonResult(await http.put(`/api/v1/agents/${id}`, body)),
  },
  {
    name: 'retire_agent',
    description: 'Retire (deactivate) an agent.',
    inputSchema: z.object({ id: z.string().uuid().describe('Agent UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.post(`/api/v1/agents/${id}/retire`)),
  },
  {
    name: 'list_templates',
    description: 'List available agent templates.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/agents/templates')),
  },
  {
    name: 'create_from_template',
    description: 'Create a new agent from a named template.',
    inputSchema: z.object({ templateName: z.string().describe('Template name') }),
    handler: async ({ templateName }: { templateName: string }) =>
      toJsonResult(await http.post(`/api/v1/agents/from-template/${templateName}`)),
  },
];
