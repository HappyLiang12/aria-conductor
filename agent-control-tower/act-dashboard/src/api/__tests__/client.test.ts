import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../client';
import { setTokenPrompt, setApiToken } from '../auth';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Install a fake axios adapter that records configs and returns a canned response. */
function installAdapter(status = 200, data: unknown = { ok: true }) {
  const seen: InternalAxiosRequestConfig[] = [];
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    seen.push(config);
    if (status >= 400) {
      const response = { data, status, statusText: `HTTP ${status}`, headers: {}, config };
      throw Object.assign(new Error(`Request failed with status code ${status}`), {
        config,
        response,
        request: {},
        isAxiosError: true,
      });
    }
    return { data, status, statusText: 'OK', headers: {}, config };
  };
  return seen;
}

/** Adapter that returns a fixed sequence of statuses (last one repeats). */
function sequenceAdapter(statuses: number[]) {
  const seen: InternalAxiosRequestConfig[] = [];
  const authHeaders: Array<string | undefined> = [];
  let calls = 0;
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    seen.push(config);
    authHeaders.push(config.headers['Authorization'] as string | undefined);
    const status = statuses[Math.min(calls, statuses.length - 1)];
    calls += 1;
    if (status >= 400) {
      const response = { data: { message: 'nope' }, status, statusText: `HTTP ${status}`, headers: {}, config };
      throw Object.assign(new Error(`Request failed with status code ${status}`), {
        config,
        response,
        request: {},
        isAxiosError: true,
      });
    }
    return { data: { ok: true }, status, statusText: 'OK', headers: {}, config };
  };
  return { seen, authHeaders };
}

describe('api client', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    sessionStorage.clear();
    setTokenPrompt(() => null);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('attaches a UUID X-Correlation-ID header to every request', async () => {
    const seen = installAdapter();
    await client.get('/api/v1/agents');

    expect(seen).toHaveLength(1);
    expect(String(seen[0].headers['X-Correlation-ID'])).toMatch(UUID_RE);
  });

  it('generates a fresh correlation id per request', async () => {
    const seen = installAdapter();
    await client.get('/a');
    await client.get('/b');

    const [first, second] = seen.map((c) => String(c.headers['X-Correlation-ID']));
    expect(first).toMatch(UUID_RE);
    expect(second).toMatch(UUID_RE);
    expect(first).not.toBe(second);
  });

  it('sends JSON content type by default and returns response data', async () => {
    const seen = installAdapter(200, { id: 'x-1' });
    const res = await client.post('/api/v1/runs', { goal: 'g' });

    expect(res.data).toEqual({ id: 'x-1' });
    expect(String(seen[0].headers['Content-Type'])).toContain('application/json');
  });

  it('does not attach an Authorization header when no token is stored', async () => {
    const seen = installAdapter();
    await client.get('/api/v1/agents');

    expect(seen[0].headers['Authorization']).toBeUndefined();
  });

  it('attaches the stored token as a Bearer Authorization header', async () => {
    setApiToken('operator-token-123');
    const seen = installAdapter();
    await client.get('/api/v1/agents');

    expect(seen[0].headers['Authorization']).toBe('Bearer operator-token-123');
  });

  it('rejects and logs a warning on 401 responses when the operator dismisses the prompt', async () => {
    installAdapter(401, { error: 'unauthorized' });

    await expect(client.get('/api/v1/secure')).rejects.toThrow('401');
    expect(console.warn).toHaveBeenCalledWith('[API] Unauthorized');
  });

  it('prompts for a token on 401, stores it, and transparently retries the request', async () => {
    const { seen, authHeaders } = sequenceAdapter([401, 200]);
    setTokenPrompt(() => 'operator-token-456');

    const res = await client.get('/api/v1/agents');

    expect(res.data).toEqual({ ok: true });
    expect(seen).toHaveLength(2);
    expect(authHeaders[0]).toBeUndefined();
    expect(authHeaders[1]).toBe('Bearer operator-token-456');
    expect(sessionStorage.getItem('aria-api-token')).toBe('operator-token-456');
  });

  it('clears a wrong token when the retried request still 401s, without re-prompting', async () => {
    sequenceAdapter([401, 401]);
    setTokenPrompt(() => 'wrong-token');

    await expect(client.get('/api/v1/agents')).rejects.toThrow('401');
    expect(console.warn).toHaveBeenCalledWith('[API] Unauthorized');
    expect(sessionStorage.getItem('aria-api-token')).toBeNull();
  });

  it('rejects and logs the server payload on 5xx responses', async () => {
    installAdapter(500, { message: 'boom' });

    await expect(client.get('/api/v1/broken')).rejects.toThrow('500');
    expect(console.error).toHaveBeenCalledWith('[API] Server error:', { message: 'boom' });
  });

  it('logs and rejects when no response is received (network failure)', async () => {
    client.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      throw Object.assign(new Error('Network Error'), { config, request: {}, isAxiosError: true });
    };

    await expect(client.get('/api/v1/unreachable')).rejects.toThrow('Network Error');
    expect(console.error).toHaveBeenCalledWith('[API] No response received:', 'Network Error');
  });
});
