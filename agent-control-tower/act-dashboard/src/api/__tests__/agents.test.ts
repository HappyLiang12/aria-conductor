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
import { listAgents, getAgent, retireAgent, applyFleetRuntime } from '../agents';

const get = client.get as Mock;
const post = client.post as Mock;
const put = client.put as Mock;
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

  // T32 Item 5: the fleet-runtime write is operator-gated server-side (403 for a
  // worker, 401 without a session) but never carried the session's CSRF header,
  // so on any deployment whose peer is not the browser the write answered
  // 403 CSRF validation failed. Same idiom as retireAgent.
  it('applyFleetRuntime applies the operator session before the PUT', async () => {
    put.mockResolvedValue({
      data: { runtime: { adkProvider: 'opencode', executionMode: 'HOST' }, updated: 0 },
    });

    await applyFleetRuntime('opencode', 'HOST');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(put).toHaveBeenCalledWith('/api/v1/agents/runtime', {
      adkProvider: 'opencode',
      executionMode: 'HOST',
    });
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      put.mock.invocationCallOrder[0],
    );
  });

  it('inventory reads do not touch the operator session', async () => {
    get.mockResolvedValue({ data: [] });

    await listAgents();
    await getAgent('a-1');

    expect(applyHeaders).not.toHaveBeenCalled();
  });
});
