import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const workflowTools = [
  {
    name: 'list_workflows',
    description: 'List all multi-agent workflow chains.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/workflows')),
  },
  {
    name: 'get_workflow',
    description: 'Get details of a specific workflow by ID.',
    inputSchema: z.object({ id: z.string().uuid().describe('Workflow UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/workflows/${id}`)),
  },
  {
    name: 'create_workflow',
    description: 'Create and start a new multi-agent workflow. Provide an ordered list of agent IDs that will execute sequentially.',
    inputSchema: z.object({
      name: z.string().describe('Workflow name'),
      agentIds: z.array(z.string().uuid()).describe('Ordered list of agent UUIDs to chain'),
      initialPrompt: z.string().optional().describe('Initial prompt passed to the first agent'),
    }),
    handler: async (body: Record<string, unknown>) => toJsonResult(await http.post('/api/v1/workflows', body)),
  },
  {
    name: 'cancel_workflow',
    description: 'Cancel a running or pending workflow chain',
    inputSchema: z.object({
      id: z.string().uuid().describe('Workflow chain UUID'),
    }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.post(`/api/v1/workflows/${id}/cancel`)),
  },
  {
    name: 'retry_workflow_step',
    description: 'Retry a failed step in a workflow chain',
    inputSchema: z.object({
      id: z.string().uuid().describe('Workflow chain UUID'),
      stepIndex: z.number().int().min(0).describe('Step index to retry'),
    }),
    handler: async ({ id, stepIndex }: { id: string; stepIndex: number }) =>
      toJsonResult(await http.post(`/api/v1/workflows/${id}/retry`, { stepIndex })),
  },
  {
    name: 'update_workflow',
    description: 'Update workflow name, description, or append steps. Only works on PENDING or FAILED workflows.',
    inputSchema: z.object({
      id: z.string().uuid().describe('Workflow chain UUID'),
      name: z.string().optional().describe('New name'),
      description: z.string().optional().describe('New description'),
    }),
    handler: async ({ id, ...body }: { id: string; name?: string; description?: string }) =>
      toJsonResult(await http.put(`/api/v1/workflows/${id}`, body)),
  },
  {
    name: 'delete_workflow',
    description: 'Delete a workflow chain. Cannot delete RUNNING workflows.',
    inputSchema: z.object({
      id: z.string().uuid().describe('Workflow chain UUID'),
    }),
    handler: async ({ id }: { id: string }) => {
      await http.delete(`/api/v1/workflows/${id}`);
      return toJsonResult({ success: true, id });
    },
  },
  {
    name: 'merge_workflows',
    description: 'Merge multiple workflow chains into a single new workflow',
    inputSchema: z.object({
      sourceIds: z.array(z.string().uuid()).describe('UUIDs of workflows to merge'),
      name: z.string().describe('Name for the merged workflow'),
    }),
    handler: async ({ sourceIds, name }: { sourceIds: string[]; name: string }) =>
      toJsonResult(await http.post('/api/v1/workflows/merge', { sourceIds, name })),
  },
  {
    name: 'execute_yaml',
    description: 'Execute a workflow directly from a YAML template, bypassing LLM orchestration',
    inputSchema: z.object({
      yamlContent: z.string().describe('YAML workflow template content'),
      parameters: z.record(z.string()).optional().describe('Parameter substitutions for key placeholders'),
    }),
    handler: async ({ yamlContent, parameters }: { yamlContent: string; parameters?: Record<string, string> }) =>
      toJsonResult(await http.post('/api/v1/workflows/execute-yaml', { yamlContent, parameters })),
  },
  {
    name: 'reuse_workflow',
    description: 'Instantiate and execute an APPROVED workflow template with parameters',
    inputSchema: z.object({
      templateId: z.string().uuid().describe('Knowledge item UUID of the APPROVED template'),
      parameters: z.record(z.string()).optional().describe('Parameter values for template substitution'),
    }),
    handler: async ({ templateId, parameters }: { templateId: string; parameters?: Record<string, string> }) =>
      toJsonResult(await http.post(`/api/v1/workflows/templates/${templateId}/reuse`, { parameters })),
  },
];
