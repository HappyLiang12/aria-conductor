import { z } from 'zod';
import { http, qs, toJsonResult } from '../http-client.js';

export const ariaNotificationTools = [
  {
    name: 'aria.notification.list',
    description: 'List Aria notifications with pagination support.',
    inputSchema: z.object({
      page: z.number().optional().describe('Page number (0-based)'),
      size: z.number().optional().describe('Page size'),
    }),
    handler: async ({ page, size }: { page?: number; size?: number }) =>
      toJsonResult(await http.get(`/api/v1/aria/notifications${qs({ page: page ?? 0, size: size ?? 20 })}`)),
  },
  {
    name: 'aria.notification.count',
    description: 'Get the count of Aria notifications (unread count, etc.).',
    inputSchema: z.object({}),
    handler: async () => toJsonResult(await http.get('/api/v1/aria/notifications/count')),
  },
  {
    name: 'aria.notification.mark_read',
    description: 'Mark a single Aria notification as read.',
    inputSchema: z.object({
      id: z.string().describe('Notification ID'),
    }),
    handler: async ({ id }: { id: string }) =>
      toJsonResult(await http.patch(`/api/v1/aria/notifications/${id}/read`)),
  },
  {
    name: 'aria.notification.mark_all_read',
    description: 'Mark all Aria notifications as read.',
    inputSchema: z.object({}),
    handler: async () =>
      toJsonResult(await http.patch('/api/v1/aria/notifications/read-all')),
  },
];
