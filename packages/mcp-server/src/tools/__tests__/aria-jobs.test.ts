import { describe, it, expect, vi } from 'vitest';
import { http } from '../../http-client.js';

vi.mock('../../http-client.js', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
  qs: (p: Record<string, unknown>) => {
    const entries = Object.entries(p).filter(([, v]) => v != null);
    return entries.length ? '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString() : '';
  },
  toJsonResult: (r: unknown) => ({ content: [{ type: 'text', text: JSON.stringify(r) }] }),
}));

describe('aria.job tools', () => {
  it('list calls GET with filters', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] });
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.list')!;
    await tool.handler({ category: 'REMINDER', status: 'ACTIVE' });
    expect(http.get).toHaveBeenCalledWith('/api/v1/aria/jobs?category=REMINDER&status=ACTIVE');
  });

  it('create calls POST', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: {} });
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.create')!;
    await tool.handler({ scheduleType: 'RECURRING', category: 'REMINDER', title: 'Test', scheduleExpression: '3600', notificationTitle: 'Hi' });
    expect(http.post).toHaveBeenCalledWith('/api/v1/aria/jobs', expect.objectContaining({ title: 'Test' }));
  });

  it('pause calls PATCH', async () => {
    vi.mocked(http.patch).mockResolvedValue({ data: {} });
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.pause')!;
    await tool.handler({ id: 'j1' });
    expect(http.patch).toHaveBeenCalledWith('/api/v1/aria/jobs/j1/pause');
  });
});
