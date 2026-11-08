import { afterEach, describe, expect, it } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../client';
import { GIT_PACK_ID, storePackCredential } from '../packCredentials';

/**
 * Transport-level wire test for the git pack credential (F8): the shared client
 * is the real axios instance with a capturing adapter, so the exact body that
 * would travel the wire is asserted — the secret must arrive verbatim as JSON
 * (the controller reads a `Map<String,String>` body), and never in the path.
 */
let captured: InternalAxiosRequestConfig | undefined;
const originalAdapter = client.defaults.adapter;

function captureAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  captured = config;
  return Promise.resolve({ data: { status: 'stored' }, status: 200, statusText: 'OK', headers: {}, config });
}

describe('git pack credential wire format (F8)', () => {
  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    captured = undefined;
  });

  it('sends {key, value} as JSON to the pack credential route with the secret verbatim', async () => {
    client.defaults.adapter = captureAdapter;
    const secret = 'ghp_secret_value_1234';

    await storePackCredential(GIT_PACK_ID, { key: 'GITHUB_TOKEN', value: secret });

    expect(captured?.method).toBe('post');
    expect(captured?.url).toBe('/api/v1/packs/pack-git-0001/credentials');
    expect(captured?.url).not.toContain(secret);
    expect(JSON.parse(String(captured?.data))).toEqual({
      key: 'GITHUB_TOKEN',
      value: secret,
    });
  });
});
