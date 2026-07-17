import { z } from 'zod';
import { http, qs, toJsonResult } from '../http-client.js';

export const knowledgeTools = [
  {
    name: 'list_knowledge',
    description: 'List knowledge items, optionally filtered by type and status.',
    inputSchema: z.object({
      type: z.enum(['SOP', 'RUNBOOK', 'DECISION', 'LESSON', 'PATTERN', 'GUIDE']).optional().describe('Knowledge type filter'),
      status: z.enum(['DRAFT', 'REVIEW', 'APPROVED', 'PUBLISHED', 'RETIRED']).optional().describe('Knowledge status filter'),
    }),
    handler: async ({ type, status }: { type?: string; status?: string }) =>
      toJsonResult(await http.get(`/api/v1/knowledge${qs({ type, status })}`)),
  },
  {
    name: 'get_knowledge',
    description: 'Get a specific knowledge item by ID.',
    inputSchema: z.object({ id: z.string().uuid().describe('Knowledge item UUID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/knowledge/${id}`)),
  },
  {
    name: 'submit_knowledge',
    description: 'Submit a new knowledge item for review.',
    inputSchema: z.object({
      title: z.string().describe('Knowledge item title'),
      type: z.enum(['SOP', 'RUNBOOK', 'DECISION', 'LESSON', 'PATTERN', 'GUIDE']).describe('Knowledge type'),
      content: z.string().describe('Knowledge content body'),
      tags: z.array(z.string()).optional().describe('Classification tags'),
    }),
    handler: async (body: Record<string, unknown>) =>
      toJsonResult(await http.post('/api/v1/knowledge', body)),
  },
  {
    name: 'review_knowledge',
    description: 'Approve or reject a knowledge item under review.',
    inputSchema: z.object({
      id: z.string().uuid().describe('Knowledge item UUID'),
      approved: z.boolean().describe('true to approve, false to reject'),
      comment: z.string().optional().describe('Review comment'),
    }),
    handler: async ({ id, ...body }: { id: string; approved: boolean; comment?: string }) =>
      toJsonResult(await http.post(`/api/v1/knowledge/${id}/review`, body)),
  },
];
