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
import { listAgents, getAgent, retireAgent } from '../agents';

const get = client.get as Mock;
const post = client.post as Mock;
const applyHeaders = applyOperatorHeaders as Mock;

describe('agents api operator boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('retireAgent applies the operator session before the POST', async () => {
    post.mockResolvedValue({ data: undefined });

    await retireAgent('a-1');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/agents/a-1/retire');
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      post.mock.invocationCallOrder[0],
    );
  });

  it('inventory reads do not touch the operator session', async () => {
    get.mockResolvedValue({ data: [] });

    await listAgents();
    await getAgent('a-1');

    expect(applyHeaders).not.toHaveBeenCalled();
  });
});
