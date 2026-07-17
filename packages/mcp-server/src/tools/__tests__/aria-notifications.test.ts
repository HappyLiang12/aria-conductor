import { describe, it, expect, vi } from 'vitest';
import { http } from '../../http-client.js';

vi.mock('../../http-client.js', () => ({
  http: { get: vi.fn(), patch: vi.fn() },
  qs: (p: Record<string, unknown>) => {
    const entries = Object.entries(p).filter(([, v]) => v != null);
    return entries.length ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : '';
  },
  toJsonResult: (r: unknown) => ({ content: [{ type: 'text', text: JSON.stringify(r) }] }),
}));

describe('aria.notification tools', () => {
  it('list calls GET with page and size', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] });
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.list')!;
    await tool.handler({ page: 0, size: 20 });
    expect(http.get).toHaveBeenCalledWith('/api/v1/aria/notifications?page=0&size=20');
  });

  it('count calls GET /count', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { unreadCount: 5 } });
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.count')!;
    await tool.handler({});
    expect(http.get).toHaveBeenCalledWith('/api/v1/aria/notifications/count');
  });

  it('mark_read calls PATCH', async () => {
    vi.mocked(http.patch).mockResolvedValue({ data: {} });
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.mark_read')!;
    await tool.handler({ id: 'n1' });
    expect(http.patch).toHaveBeenCalledWith('/api/v1/aria/notifications/n1/read');
  });
});
