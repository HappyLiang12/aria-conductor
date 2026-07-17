import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const reportTools = [
  {
    name: 'generate_report',
    description: 'Generate a new report from run or agent data.',
    inputSchema: z.object({
      runId: z.string().uuid().optional().describe('Run UUID to report on'),
      agentId: z.string().uuid().optional().describe('Agent UUID to report on'),
      template: z.string().optional().describe('Report template name'),
    }),
    handler: async (body: Record<string, unknown>) =>
      toJsonResult(await http.post('/api/v1/reports/generate', body)),
  },
  {
    name: 'list_reports',
    description: 'List all generated reports.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/reports')),
  },
  {
    name: 'get_report',
    description: 'Get details of a specific report.',
    inputSchema: z.object({ id: z.string().describe('Report ID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/reports/${id}`)),
  },
  {
    name: 'amend_report',
    description: 'Amend/update an existing report with new instructions.',
    inputSchema: z.object({
      id: z.string().describe('Report ID'),
      instructions: z.string().describe('Amendment instructions'),
    }),
    handler: async ({ id, ...body }: { id: string; instructions: string }) =>
      toJsonResult(await http.post(`/api/v1/reports/${id}/amend`, body)),
  },
];
