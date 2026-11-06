import { z } from 'zod';
import { http, qs, toJsonResult } from '../http-client.js';

export const ariaJobTools = [
  {
    name: 'aria.job.list',
    description: 'List Aria scheduled jobs, optionally filtered by category and/or status.',
    inputSchema: z.object({
      category: z.string().optional().describe('Filter by category: REMINDER, MONITOR, BRIEF'),
      status: z.string().optional().describe('Filter by status: ACTIVE, PAUSED, COMPLETED'),
    }),
    handler: async ({ category, status }: { category?: string; status?: string }) =>
      toJsonResult(await http.get(`/api/v1/aria/jobs${qs({ category, status })}`)),
  },
  {
    name: 'aria.job.create',
    description: 'Create a new Aria scheduled job (reminder, monitor, or brief).',
    inputSchema: z.object({
      scheduleType: z.enum(['ONE_SHOT', 'RECURRING']).describe('ONE_SHOT for one-time, RECURRING for repeating'),
      category: z.enum(['REMINDER', 'MONITOR', 'BRIEF']).describe('REMINDER (time-based), MONITOR (condition watch), BRIEF (periodic summary)'),
      title: z.string().min(1).describe('Human-readable job label'),
      scheduleExpression: z.string().min(1).describe('Cron expression, ISO duration (e.g. PT30M), or seconds (e.g. 3600)'),
      notificationTitle: z.string().min(1).describe('Notification title when job fires'),
      notificationBody: z.string().optional().describe('Notification body when job fires'),
    }),
    handler: async (body: Record<string, unknown>) =>
      toJsonResult(await http.post('/api/v1/aria/jobs', body)),
  },
  {
    name: 'aria.job.update',
    description: 'Update an existing Aria scheduled job.',
    inputSchema: z.object({
      id: z.string().describe('Job ID'),
      scheduleType: z.enum(['ONE_SHOT', 'RECURRING']).optional(),
      category: z.enum(['REMINDER', 'MONITOR', 'BRIEF']).optional(),
      title: z.string().min(1).optional(),
      scheduleExpression: z.string().min(1).optional(),
      notificationTitle: z.string().min(1).optional(),
      notificationBody: z.string().optional(),
    }),
    handler: async ({ id, ...body }: { id: string; [k: string]: unknown }) =>
      toJsonResult(await http.put(`/api/v1/aria/jobs/${id}`, body)),
  },
  {
    name: 'aria.job.delete',
    description: 'Delete an Aria scheduled job.',
    inputSchema: z.object({
      id: z.string().describe('Job ID'),
    }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.delete(`/api/v1/aria/jobs/${id}`)),
  },
  {
    name: 'aria.job.pause',
    description: 'Pause an Aria scheduled job.',
    inputSchema: z.object({
      id: z.string().describe('Job ID'),
    }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.patch(`/api/v1/aria/jobs/${id}/pause`)),
  },
  {
    name: 'aria.job.resume',
    description: 'Resume a paused Aria scheduled job.',
    inputSchema: z.object({
      id: z.string().describe('Job ID'),
    }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.patch(`/api/v1/aria/jobs/${id}/resume`)),
  },
];
