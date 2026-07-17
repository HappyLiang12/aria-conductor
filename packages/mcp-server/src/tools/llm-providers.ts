import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const llmProviderTools = [
  {
    name: 'list_llm_providers',
    description: 'List all configured LLM providers.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/llm-providers')),
  },
  {
    name: 'get_llm_provider',
    description: 'Get details of a specific LLM provider.',
    inputSchema: z.object({ id: z.string().uuid().describe('LLM provider UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/llm-providers/${id}`)),
  },
  {
    name: 'create_llm_provider',
    description: 'Register a new LLM provider configuration.',
    inputSchema: z.object({
      name: z.string().describe('Provider display name'),
      type: z.string().describe('Provider type (e.g. openai, anthropic, azure)'),
      apiKey: z.string().describe('API key'),
      baseUrl: z.string().optional().describe('Custom base URL for the provider'),
      modelId: z.string().optional().describe('Default model identifier'),
    }),
    handler: async (body: Record<string, unknown>) =>
      toJsonResult(await http.post('/api/v1/llm-providers', body)),
  },
  {
    name: 'test_llm_provider',
    description: 'Test the connection to an LLM provider.',
    inputSchema: z.object({ id: z.string().uuid().describe('LLM provider UUID') }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.post(`/api/v1/llm-providers/${id}/test`)),
  },
  {
    name: 'activate_llm_provider',
    description: 'Set an LLM provider as the active provider for Aria.',
    inputSchema: z.object({ id: z.string().uuid().describe('LLM provider UUID') }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.post(`/api/v1/llm-providers/${id}/activate`)),
  },
];
