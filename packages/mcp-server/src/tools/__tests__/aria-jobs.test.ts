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

describe('aria.job tools — validation & error mapping', () => {
  it('create schema rejects a missing title', async () => {
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.create')!;
    const parsed = tool.inputSchema.safeParse({ scheduleType: 'RECURRING', category: 'REMINDER', scheduleExpression: '3600', notificationTitle: 'Hi' });
    expect(parsed.success).toBe(false);
    expect(parsed.success ? [] : parsed.error.issues.map(i => i.path[0])).toContain('title');
  });

  it('create schema rejects an invalid category enum value', async () => {
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.create')!;
    const parsed = tool.inputSchema.safeParse({ scheduleType: 'RECURRING', category: 'ALARM', title: 't', scheduleExpression: '3600', notificationTitle: 'Hi' });
    expect(parsed.success).toBe(false);
    expect(parsed.success ? [] : parsed.error.issues.map(i => i.path[0])).toContain('category');
  });

  it('create schema rejects an empty scheduleExpression', async () => {
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.create')!;
    const parsed = tool.inputSchema.safeParse({ scheduleType: 'ONE_SHOT', category: 'BRIEF', title: 't', scheduleExpression: '', notificationTitle: 'Hi' });
    expect(parsed.success).toBe(false);
    expect(parsed.success ? [] : parsed.error.issues.map(i => i.path[0])).toContain('scheduleExpression');
  });

  it('delete propagates backend errors from http.delete', async () => {
    vi.mocked(http.delete).mockRejectedValueOnce(new Error('ACT API Error [404]: Not Found'));
    const { ariaJobTools } = await import('../aria-jobs.js');
    const tool = ariaJobTools.find(t => t.name === 'aria.job.delete')!;
    await expect(tool.handler({ id: 'missing' })).rejects.toThrow('404');
    expect(http.delete).toHaveBeenCalledWith('/api/v1/aria/jobs/missing');
  });
});
