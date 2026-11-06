import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import client from '../client';
import { listNotifications, getUnreadCount, markRead, markAllRead } from '../ariaNotifications';

const get = client.get as Mock;
const patch = client.patch as Mock;

describe('ariaNotifications api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listNotifications defaults to page 0 with size 20', async () => {
    const page = { content: [{ id: 'n-1' }], totalElements: 1, totalPages: 1 };
    get.mockResolvedValue({ data: page });

    await expect(listNotifications()).resolves.toEqual(page);
    expect(get).toHaveBeenCalledWith('/api/v1/aria/notifications', {
      params: { page: 0, size: 20 },
    });
  });

  it('listNotifications forwards custom pagination', async () => {
    get.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0 } });

    await listNotifications(3, 50);
    expect(get).toHaveBeenCalledWith('/api/v1/aria/notifications', {
      params: { page: 3, size: 50 },
    });
  });

  it('getUnreadCount GETs the count endpoint', async () => {
    get.mockResolvedValue({ data: { unread: 7 } });

    await expect(getUnreadCount()).resolves.toEqual({ unread: 7 });
    expect(get).toHaveBeenCalledWith('/api/v1/aria/notifications/count');
  });

  it('markRead PATCHes the notification by id', async () => {
    patch.mockResolvedValue({ data: { id: 'n-9', read: true } });

    await expect(markRead('n-9')).resolves.toEqual({ id: 'n-9', read: true });
    expect(patch).toHaveBeenCalledTimes(1);
    expect(patch).toHaveBeenCalledWith('/api/v1/aria/notifications/n-9/read');
  });

  it('markAllRead PATCHes the read-all endpoint and resolves to void', async () => {
    patch.mockResolvedValue({ data: { updated: 12 } });

    await expect(markAllRead()).resolves.toBeUndefined();
    expect(patch).toHaveBeenCalledWith('/api/v1/aria/notifications/read-all');
  });

  it('propagates backend failures', async () => {
    patch.mockRejectedValue(new Error('Request failed with status code 503'));

    await expect(markAllRead()).rejects.toThrow('503');
  });
});
