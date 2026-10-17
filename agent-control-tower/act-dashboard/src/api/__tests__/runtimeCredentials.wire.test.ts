import { afterEach, describe, expect, it } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';

/**
 * Transport-level wire-format tests for the qoder credential calls.
 *
 * Unlike the other api tests (which mock `../client` and therefore never run
 * axios's `transformRequest`), these run the REAL shared client with a
 * capturing adapter, so the exact body that would travel the wire is what gets
 * asserted. This is the layer that hid the JSON-quoted-secret bug: the shared
 * client defaults to `Content-Type: application/json`, under which axios's
 * `transformRequest` routes string bodies through `stringifySafely` — JSON
 * data (e.g. `12345678`) is sent trimmed/unquoted, everything else (e.g.
 * `pat-secret-value-1234`) is sent JSON-quoted. The backend stores the body
 * verbatim, so a quoted wire body corrupts the stored secret.
 */
import client from '../client';
import { QODER_CREDENTIAL_PATH, putQoderCredential } from '../runtimeCredentials';

let captured: InternalAxiosRequestConfig | undefined;
const originalAdapter = client.defaults.adapter;

function captureAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  captured = config;
  return Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config });
}

function contentType(config: InternalAxiosRequestConfig): string {
  const raw = config.headers['Content-Type'] ?? config.headers['content-type'] ?? '';
  return String(raw).toLowerCase();
}

describe('qoder credential wire format (real axios transformRequest)', () => {
  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    captured = undefined;
  });

  it('putQoderCredential sends the raw secret untransformed with a non-JSON content type', async () => {
    client.defaults.adapter = captureAdapter;
    const secret = 'pat-secret-value-1234';

    await putQoderCredential(secret);

    expect(captured).toBeDefined();
    expect(captured?.url).toBe(QODER_CREDENTIAL_PATH);
    // Identity, not equality: no JSON quoting, no trimming, no wrapping.
    expect(captured?.data).toBe(secret);
    const type = contentType(captured!);
    expect(type.startsWith('text/plain')).toBe(true);
    expect(type).not.toContain('application/json');
  });
});
