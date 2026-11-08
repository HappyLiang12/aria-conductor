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
import {
  GIT_PACK_CREDENTIAL_KEY,
  GIT_PACK_ID,
  storePackCredential,
} from '../packCredentials';

const post = client.post as Mock;

describe('pack credential api (F8)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs the documented global credential shape to the git pack route', async () => {
    post.mockResolvedValue({ data: { status: 'stored', key: GIT_PACK_CREDENTIAL_KEY } });

    const result = await storePackCredential(GIT_PACK_ID, {
      key: GIT_PACK_CREDENTIAL_KEY,
      value: 'ghp_fixture',
    });

    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/packs/pack-git-0001/credentials', {
      key: 'GITHUB_TOKEN',
      value: 'ghp_fixture',
    });
    expect(result).toEqual({ status: 'stored', key: 'GITHUB_TOKEN' });
  });

  it('forwards an optional per-agent override', async () => {
    post.mockResolvedValue({ data: { status: 'stored' } });

    await storePackCredential(GIT_PACK_ID, {
      key: GIT_PACK_CREDENTIAL_KEY,
      value: 'ghp_fixture',
      agentId: 'agent-1',
    });

    expect(post).toHaveBeenCalledWith('/api/v1/packs/pack-git-0001/credentials', {
      key: 'GITHUB_TOKEN',
      value: 'ghp_fixture',
      agentId: 'agent-1',
    });
  });

  it('propagates an HTTP refusal instead of reporting a store', async () => {
    const err = Object.assign(new Error('Request failed with status code 500'), {
      response: { status: 500, data: { error: 'credential store unavailable' } },
    });
    post.mockRejectedValue(err);

    await expect(
      storePackCredential(GIT_PACK_ID, { key: GIT_PACK_CREDENTIAL_KEY, value: 'x' }),
    ).rejects.toThrow('500');
  });
});
