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
import { listApprovals, decideApproval, approveApproval, rejectApproval } from '../approvals';

const get = client.get as Mock;
const post = client.post as Mock;

describe('approvals api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listApprovals GETs without params when no status filter is given', async () => {
    get.mockResolvedValue({ data: [] });

    await expect(listApprovals()).resolves.toEqual([]);
    expect(get).toHaveBeenCalledWith('/api/v1/approvals', { params: undefined });
  });

  it('listApprovals passes the status filter as a query param', async () => {
    const approvals = [{ id: 'ap-1', status: 'PENDING' }];
    get.mockResolvedValue({ data: approvals });

    await expect(listApprovals('PENDING')).resolves.toEqual(approvals);
    expect(get).toHaveBeenCalledWith('/api/v1/approvals', { params: { status: 'PENDING' } });
  });

  it('decideApproval POSTs the decision body to /decide', async () => {
    post.mockResolvedValue({ data: { id: 'ap-1', status: 'APPROVED' } });

    const decision = { approved: true, reason: 'looks safe' };
    await expect(decideApproval('ap-1', decision)).resolves.toEqual({
      id: 'ap-1',
      status: 'APPROVED',
    });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/approvals/ap-1/decide', decision);
  });

  it('approveApproval POSTs approved=true to /decide exactly once', async () => {
    post.mockResolvedValue({ data: { id: 'ap-2', status: 'APPROVED' } });

    await expect(approveApproval('ap-2', 'ok')).resolves.toEqual({
      id: 'ap-2',
      status: 'APPROVED',
    });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/approvals/ap-2/decide', {
      approved: true,
      reason: 'ok',
    });
  });

  it('approveApproval surfaces the failure instead of masking it with a second call', async () => {
    post.mockRejectedValueOnce(new Error('boom'));

    await expect(approveApproval('ap-3', 'legacy')).rejects.toThrow('boom');
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('rejectApproval POSTs approved=false to /decide exactly once', async () => {
    post.mockResolvedValue({ data: { id: 'ap-4', status: 'DENIED' } });

    await expect(rejectApproval('ap-4', 'too risky')).resolves.toEqual({
      id: 'ap-4',
      status: 'DENIED',
    });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/approvals/ap-4/decide', {
      approved: false,
      reason: 'too risky',
    });
  });

  it('rejectApproval omits the reason when none is given', async () => {
    post.mockResolvedValue({ data: { id: 'ap-5', status: 'DENIED' } });

    await expect(rejectApproval('ap-5')).resolves.toEqual({ id: 'ap-5', status: 'DENIED' });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/approvals/ap-5/decide', {
      approved: false,
      reason: undefined,
    });
  });
});
