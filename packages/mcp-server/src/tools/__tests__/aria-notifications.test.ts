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

describe('aria.notification tools — validation & error mapping', () => {
  it('list defaults page=0 and size=20 when omitted', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] });
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.list')!;
    await tool.handler({});
    expect(http.get).toHaveBeenCalledWith('/api/v1/aria/notifications?page=0&size=20');
  });

  it('list schema rejects a non-numeric page', async () => {
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.list')!;
    const parsed = tool.inputSchema.safeParse({ page: 'first' });
    expect(parsed.success).toBe(false);
    expect(parsed.success ? [] : parsed.error.issues.map(i => i.path[0])).toContain('page');
  });

  it('mark_read schema rejects a missing id', async () => {
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.mark_read')!;
    const parsed = tool.inputSchema.safeParse({});
    expect(parsed.success).toBe(false);
    expect(parsed.success ? [] : parsed.error.issues.map(i => i.path[0])).toContain('id');
  });

  it('count propagates backend errors from http.get', async () => {
    vi.mocked(http.get).mockRejectedValueOnce(new Error('ACT API Error [500]: Internal Server Error'));
    const { ariaNotificationTools } = await import('../aria-notifications.js');
    const tool = ariaNotificationTools.find(t => t.name === 'aria.notification.count')!;
    await expect(tool.handler({})).rejects.toThrow('500');
  });
});
