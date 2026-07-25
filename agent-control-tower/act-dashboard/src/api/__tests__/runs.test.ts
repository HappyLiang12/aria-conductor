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
  listRuns,
  getRun,
  createRun,
  pauseRun,
  resumeRun,
  cancelRun,
  getRunTrajectory,
  getRunToolCalls,
  injectRunMessage,
} from '../runs';
import type { CreateRunRequest } from '../../types';

const get = client.get as Mock;
const post = client.post as Mock;

describe('runs api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listRuns GETs /api/v1/runs and returns the payload', async () => {
    const runs = [{ id: 'r-1', status: 'RUNNING' }];
    get.mockResolvedValue({ data: runs });

    await expect(listRuns()).resolves.toEqual(runs);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/api/v1/runs');
  });

  it('getRun GETs the run by id', async () => {
    get.mockResolvedValue({ data: { id: 'r-42' } });

    await expect(getRun('r-42')).resolves.toEqual({ id: 'r-42' });
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/api/v1/runs/r-42');
  });

  it('createRun POSTs the request body and returns the created run', async () => {
    const req = { agentId: 'a-1', goal: 'build tests' } as unknown as CreateRunRequest;
    post.mockResolvedValue({ data: { id: 'r-new' } });

    await expect(createRun(req)).resolves.toEqual({ id: 'r-new' });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/runs', req);
  });

  it('pause/resume/cancel POST to their lifecycle endpoints', async () => {
    post.mockResolvedValue({ data: undefined });

    await pauseRun('r-1');
    await resumeRun('r-1');
    await cancelRun('r-1');

    expect(post.mock.calls.map((c) => c[0])).toEqual([
      '/api/v1/runs/r-1/pause',
      '/api/v1/runs/r-1/resume',
      '/api/v1/runs/r-1/cancel',
    ]);
  });

  it('getRunTrajectory and getRunToolCalls GET run sub-resources', async () => {
    get.mockResolvedValueOnce({ data: [{ turnNumber: 1 }] });
    await expect(getRunTrajectory('r-7')).resolves.toEqual([{ turnNumber: 1 }]);
    expect(get).toHaveBeenLastCalledWith('/api/v1/runs/r-7/trajectory');

    get.mockResolvedValueOnce({ data: [{ toolName: 'git_clone' }] });
    await expect(getRunToolCalls('r-7')).resolves.toEqual([{ toolName: 'git_clone' }]);
    expect(get).toHaveBeenLastCalledWith('/api/v1/runs/r-7/tool-calls');
  });

  it('injectRunMessage defaults the role to user', async () => {
    post.mockResolvedValue({ data: { id: 'm-1', turnNumber: 3 } });

    await expect(injectRunMessage('r-9', 'do this next')).resolves.toEqual({
      id: 'm-1',
      turnNumber: 3,
    });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/runs/r-9/inject', {
      content: 'do this next',
      role: 'user',
    });
  });

  it('injectRunMessage forwards a custom role', async () => {
    post.mockResolvedValue({ data: { id: 'm-2', turnNumber: 4 } });

    await injectRunMessage('r-9', 'context', 'system');
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/runs/r-9/inject', {
      content: 'context',
      role: 'system',
    });
  });

  it('propagates HTTP errors from the client', async () => {
    const err = Object.assign(new Error('Request failed with status code 500'), {
      response: { status: 500 },
    });
    get.mockRejectedValue(err);

    await expect(listRuns()).rejects.toThrow('Request failed with status code 500');
  });
});
