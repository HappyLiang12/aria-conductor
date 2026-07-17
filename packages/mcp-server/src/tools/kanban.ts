import { z } from 'zod';
import { http, qs, toJsonResult } from '../http-client.js';

export const kanbanTools = [
  {
    name: 'list_kanban_items',
    description: 'List kanban board items, optionally filtered by status.',
    inputSchema: z.object({
      status: z.enum(['BACKLOG', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE', 'BLOCKED']).optional().describe('Filter by kanban status'),
    }),
    handler: async ({ status }: { status?: string }) =>
      toJsonResult(await http.get(`/api/v1/kanban/items${qs({ status })}`)),
  },
  {
    name: 'get_kanban_item',
    description: 'Get a specific kanban item by ID.',
    inputSchema: z.object({ id: z.string().describe('Kanban item ID') }),
    handler: async ({ id }: { id: string }) => toJsonResult(await http.get(`/api/v1/kanban/items/${id}`)),
  },
  {
    name: 'create_kanban_item',
    description: 'Create a new kanban board item.',
    inputSchema: z.object({
      title: z.string().describe('Item title'),
      description: z.string().optional().describe('Item description'),
      assignee: z.string().optional().describe('Assigned agent or person'),
      priority: z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']).optional().describe('Priority level'),
    }),
    handler: async (body: Record<string, unknown>) =>
      toJsonResult(await http.post('/api/v1/kanban/items', body)),
  },
  {
    name: 'update_kanban_item',
    description: 'Update an existing kanban item.',
    inputSchema: z.object({
      id: z.string().describe('Kanban item ID'),
      title: z.string().optional(),
      description: z.string().optional(),
      assignee: z.string().optional(),
      priority: z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']).optional(),
    }),
    handler: async ({ id, ...body }: { id: string;[k: string]: unknown }) =>
      toJsonResult(await http.put(`/api/v1/kanban/items/${id}`, body)),
  },
  {
    name: 'transition_kanban_item',
    description: 'Move a kanban item to a new status column.',
    inputSchema: z.object({
      id: z.string().describe('Kanban item ID'),
      status: z.enum(['BACKLOG', 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE', 'BLOCKED']).describe('Target status'),
      comment: z.string().optional().describe('Optional transition comment'),
    }),
    handler: async ({ id, ...body }: { id: string; status: string; comment?: string }) =>
      toJsonResult(await http.post(`/api/v1/kanban/items/${id}/transition`, body)),
  },
];
