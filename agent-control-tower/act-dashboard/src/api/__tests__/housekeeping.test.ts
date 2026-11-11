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

vi.mock('../operatorSession', () => ({ applyOperatorHeaders: vi.fn() }));

import client from '../client';
import { applyOperatorHeaders } from '../operatorSession';
import { scanHousekeeping, executeHousekeeping } from '../housekeeping';

const get = client.get as Mock;
const post = client.post as Mock;
const applyHeaders = applyOperatorHeaders as Mock;

describe('housekeeping api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('scanHousekeeping GETs the scan endpoint with includeStuck param', async () => {
    get.mockResolvedValue({ data: { categories: [], scannedAt: 't' } });

    await expect(scanHousekeeping(true)).resolves.toEqual({ categories: [], scannedAt: 't' });
    expect(get).toHaveBeenCalledWith('/api/v1/housekeeping/scan', {
      params: { includeStuck: true },
    });
  });

  it('executeHousekeeping POSTs the request body verbatim', async () => {
    post.mockResolvedValue({ data: { categories: [], executedAt: 't' } });
    const req = {
      categories: ['kanban'],
      includeStuck: false,
      exclusions: { kanbanItemIds: ['k-1'] },
      confirm: true,
    };

    await executeHousekeeping(req);
    expect(post).toHaveBeenCalledWith('/api/v1/housekeeping/execute', req);
  });

  it('executeHousekeeping applies the operator session before the POST', async () => {
    post.mockResolvedValue({ data: { categories: [], executedAt: 't' } });

    await executeHousekeeping({ categories: ['kanban'], confirm: true });

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      post.mock.invocationCallOrder[0],
    );
  });

  it('scanHousekeeping (read-only) does not touch the operator session', async () => {
    get.mockResolvedValue({ data: { categories: [], scannedAt: 't' } });

    await scanHousekeeping(false);

    expect(applyHeaders).not.toHaveBeenCalled();
  });
});
