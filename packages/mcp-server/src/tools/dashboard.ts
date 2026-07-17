import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const dashboardTools = [
  {
    name: 'get_dashboard_summary',
    description: 'Get the ACT dashboard summary: active agents, running runs, pending approvals, and total tokens burned.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/dashboard/summary')),
  },
  {
    name: 'get_recent_activity',
    description: 'Get the 20 most recent activity events from the audit log.',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/dashboard/activity')),
  },
];
