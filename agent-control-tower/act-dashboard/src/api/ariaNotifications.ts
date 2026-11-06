import client from './client';
import type { Notification, NotificationCount } from '../types';

export async function listNotifications(page = 0, size = 20): Promise<{
  content: Notification[];
  totalElements: number;
  totalPages: number;
}> {
  const { data } = await client.get('/api/v1/aria/notifications', {
    params: { page, size },
  });
  return data;
}

export async function getUnreadCount(): Promise<NotificationCount> {
  const { data } = await client.get<NotificationCount>('/api/v1/aria/notifications/count');
  return data;
}

export async function markRead(id: string): Promise<Notification> {
  const { data } = await client.patch<Notification>(`/api/v1/aria/notifications/${id}/read`);
  return data;
}

export async function markAllRead(): Promise<void> {
  await client.patch('/api/v1/aria/notifications/read-all');
}
