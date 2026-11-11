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
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  testProvider,
  activateProvider,
} from '../llmProviders';
import type { CreateLlmProviderRequest } from '../../types';

const get = client.get as Mock;
const post = client.post as Mock;
const put = client.put as Mock;
const del = client.delete as Mock;
const applyHeaders = applyOperatorHeaders as Mock;

const REQUEST: CreateLlmProviderRequest = {
  name: 'prod',
  type: 'OPENAI',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: 'sk-secret1234',
  defaultModel: 'gpt-4o',
  maxTokens: 4096,
};

describe('llmProviders api operator boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('createProvider applies the operator session before the POST', async () => {
    post.mockResolvedValue({ data: {} });

    await createProvider(REQUEST);

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      post.mock.invocationCallOrder[0],
    );
  });

  it('updateProvider applies the operator session before the PUT', async () => {
    put.mockResolvedValue({ data: {} });

    await updateProvider('p-1', { name: 'renamed' });

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      put.mock.invocationCallOrder[0],
    );
  });

  it('deleteProvider applies the operator session before the DELETE', async () => {
    del.mockResolvedValue({ data: undefined });

    await deleteProvider('p-1');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      del.mock.invocationCallOrder[0],
    );
  });

  it('testProvider applies the operator session before the POST', async () => {
    post.mockResolvedValue({ data: { success: true, message: 'ok' } });

    await testProvider('p-1');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      post.mock.invocationCallOrder[0],
    );
  });

  it('activateProvider applies the operator session before the POST', async () => {
    post.mockResolvedValue({ data: {} });

    await activateProvider('p-1');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      post.mock.invocationCallOrder[0],
    );
  });

  it('the provider inventory read does not touch the operator session', async () => {
    get.mockResolvedValue({ data: [] });

    await listProviders();

    expect(applyHeaders).not.toHaveBeenCalled();
  });
});
