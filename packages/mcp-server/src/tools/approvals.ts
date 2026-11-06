import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const approvalTools = [
  {
    name: 'list_pending_approvals',
    description: 'List all approval gates currently in PENDING status.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/approvals')),
  },
  {
    name: 'get_approval',
    description: 'Get details of a specific approval gate.',
    inputSchema: z.object({ id: z.string().uuid().describe('Approval UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/approvals/${id}`)),
  },
  {
    name: 'decide_approval',
    description: 'Approve or reject a pending approval gate to unblock agent execution.',
    inputSchema: z.object({
      id: z.string().uuid().describe('Approval UUID'),
      approved: z.boolean().describe('true to approve, false to reject'),
      reason: z.string().optional().describe('Reason for the decision'),
    }),
    handler: async ({ id, ...body }: { id: string; approved: boolean; reason?: string }) =>
      toJsonResult(await http.post(`/api/v1/approvals/${id}/decide`, body)),
  },
];
