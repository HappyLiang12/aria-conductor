import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

import client from '../client';
import {
  getQoderCredential,
  saveQoderCredential,
  deleteQoderCredential,
  testQoderCredential,
} from '../qoderCredential';
import type { QoderCredentialStatus, QoderCredentialTestResult } from '../../types';

const get = client.get as Mock;
const put = client.put as Mock;
const post = client.post as Mock;
const del = client.delete as Mock;

/** Pinned B8 route (QoderCredentialController @RequestMapping). */
const BASE = '/api/v1/adk/providers/qoder/credential';

const CONFIGURED: QoderCredentialStatus = {
  providerId: 'qoder',
  configured: true,
  patMasked: '****cdef',
  updatedAt: '2026-09-18T05:00:00Z',
  model: 'efficient',
};

describe('qoderCredential api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getQoderCredential GETs the pinned masked-status route', async () => {
    get.mockResolvedValue({ data: CONFIGURED });

    await expect(getQoderCredential()).resolves.toEqual(CONFIGURED);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith(BASE);
  });

  it('saveQoderCredential PUTs exactly {pat} and returns the masked status (no echo)', async () => {
    // Synthetic token only — never a real credential in tests.
    const pat = 'qcp_test_1234567890';
    put.mockResolvedValue({ data: CONFIGURED });

    await expect(saveQoderCredential(pat)).resolves.toEqual(CONFIGURED);
    expect(put).toHaveBeenCalledTimes(1);
    expect(put).toHaveBeenCalledWith(BASE, { pat });
  });

  it('deleteQoderCredential DELETEs the route (204) and resolves undefined', async () => {
    del.mockResolvedValue({ status: 204 });

    await expect(deleteQoderCredential()).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledTimes(1);
    expect(del).toHaveBeenCalledWith(BASE);
  });

  it('testQoderCredential POSTs the bounded probe route and returns the result', async () => {
    const result: QoderCredentialTestResult = {
      success: true,
      model: 'efficient',
      billable: false,
      costNote: 'This probe runs no billable inference.',
    };
    post.mockResolvedValue({ data: result });

    await expect(testQoderCredential()).resolves.toEqual(result);
    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith(`${BASE}/test`);
  });

  it('propagates the KEY_NOT_CONFIGURED 503 rejection unchanged', async () => {
    const err = Object.assign(new Error('Service Unavailable'), {
      response: { status: 503, data: { code: 'KEY_NOT_CONFIGURED', message: 'encryption not configured' } },
    });
    put.mockRejectedValue(err);

    await expect(saveQoderCredential('qcp_test_1234567890')).rejects.toBe(err);
  });
});
