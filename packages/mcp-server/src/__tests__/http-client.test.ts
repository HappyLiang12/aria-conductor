import { describe, it, expect, vi, afterEach } from 'vitest';
import { qs, toJsonResult, ActHttpError } from '../http-client.js';

describe('qs()', () => {
  it('returns empty string for empty params', () => {
    expect(qs({})).toBe('');
  });

  it('builds query string from single param', () => {
    expect(qs({ status: 'RUNNING' })).toBe('?status=RUNNING');
  });

  it('builds query string from multiple params', () => {
    const result = qs({ agentId: 'abc', status: 'DONE' });
    expect(result).toContain('agentId=abc');
    expect(result).toContain('status=DONE');
    expect(result).toMatch(/^\?/);
  });

  it('skips undefined values', () => {
    expect(qs({ agentId: undefined, status: 'PENDING' })).toBe('?status=PENDING');
  });

  it('returns empty when all values are undefined', () => {
    expect(qs({ a: undefined, b: undefined })).toBe('');
  });
});

describe('toJsonResult()', () => {
  it('serializes an object to JSON', () => {
    const result = toJsonResult({ hello: 'world' });
    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual({ hello: 'world' });
  });

  it('passes through a string as-is', () => {
    const result = toJsonResult('hello');
    expect(result.content[0].text).toBe('hello');
  });

  it('serializes arrays', () => {
    const result = toJsonResult([1, 2, 3]);
    expect(JSON.parse(result.content[0].text)).toEqual([1, 2, 3]);
  });

  it('serializes null', () => {
    const result = toJsonResult(null);
    expect(result.content[0].text).toBe('');
  });

  it('serializes undefined', () => {
    const result = toJsonResult(undefined);
    expect(result.content[0].text).toBe('');
  });
});

describe('ActHttpError', () => {
  it('stores status and message', () => {
    const err = new ActHttpError(404, 'Not Found');
    expect(err.status).toBe(404);
    expect(err.message).toContain('404');
    expect(err.message).toContain('Not Found');
    expect(err.name).toBe('ActHttpError');
  });

  it('stores optional details', () => {
    const err = new ActHttpError(500, 'Server Error', { reason: 'db down' });
    expect(err.details).toEqual({ reason: 'db down' });
  });

  it('is an instance of Error', () => {
    const err = new ActHttpError(400, 'Bad Request');
    expect(err).toBeInstanceOf(Error);
  });
});

describe('http request()', () => {
  const realFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = realFetch;
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  /** Re-import http-client so module-level BASE_URL is re-evaluated. */
  async function freshClient() {
    vi.resetModules();
    return import('../http-client.js');
  }

  function stubFetch(body: string | null, init: ResponseInit = { status: 200 }) {
    const fn = vi.fn(async () => new Response(body, init));
    globalThis.fetch = fn as any;
    return fn;
  }

  it('uses default base URL http://localhost:8080 when ACT_BASE_URL is unset', async () => {
    vi.stubEnv('ACT_BASE_URL', '');
    const { http } = await freshClient();
    const fetchFn = stubFetch(JSON.stringify({ ok: true }));
    await http.get('/api/v1/agents');
    expect(fetchFn).toHaveBeenCalledWith('http://localhost:8080/api/v1/agents', expect.anything());
  });

  it('strips a trailing slash from ACT_BASE_URL', async () => {
    vi.stubEnv('ACT_BASE_URL', 'http://act-backend:9090/');
    const { http } = await freshClient();
    const fetchFn = stubFetch(JSON.stringify({ ok: true }));
    await http.get('/api/v1/runs');
    expect(fetchFn).toHaveBeenCalledWith('http://act-backend:9090/api/v1/runs', expect.anything());
  });

  it('parses and returns the JSON response body', async () => {
    const { http } = await freshClient();
    stubFetch(JSON.stringify({ id: 'a1', name: 'bot' }));
    const result = await http.get<{ id: string; name: string }>('/api/v1/agents/a1');
    expect(result).toEqual({ id: 'a1', name: 'bot' });
  });

  it('POST serializes body and sets Content-Type header', async () => {
    const { http } = await freshClient();
    const fetchFn = stubFetch(JSON.stringify({ created: true }), { status: 201 });
    await http.post('/api/v1/agents', { name: 'bot' });
    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ name: 'bot' }));
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('POST without body omits the body entirely', async () => {
    const { http } = await freshClient();
    const fetchFn = stubFetch(JSON.stringify({ ok: true }));
    await http.post('/api/v1/agents/a1/retire');
    const [, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.body).toBeUndefined();
  });

  it('put/patch/delete send the corresponding HTTP methods', async () => {
    const { http } = await freshClient();
    const fetchFn = stubFetch(JSON.stringify({ ok: true }));
    await http.put('/x', { a: 1 });
    await http.patch('/x', { b: 2 });
    await http.delete('/x');
    const methods = fetchFn.mock.calls.map((c) => (c as unknown as [string, RequestInit])[1].method);
    expect(methods).toEqual(['PUT', 'PATCH', 'DELETE']);
  });

  it('throws ActHttpError with status and parsed details on non-OK response', async () => {
    const { http, ActHttpError: FreshError } = await freshClient();
    stubFetch(JSON.stringify({ message: 'nope' }), { status: 409, statusText: 'Conflict' });
    const err = await http.get('/api/v1/runs/x').catch((e) => e);
    expect(err).toBeInstanceOf(FreshError);
    expect(err.status).toBe(409);
    expect(err.message).toContain('Conflict');
    expect(err.details).toEqual({ message: 'nope' });
  });

  it('leaves details undefined when the error body is not JSON', async () => {
    const { http } = await freshClient();
    stubFetch('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' });
    const err = await http.get('/api/v1/agents').catch((e) => e);
    expect(err.status).toBe(502);
    expect(err.details).toBeUndefined();
  });

  it('resolves undefined for 204 No Content', async () => {
    const { http } = await freshClient();
    stubFetch(null, { status: 204 });
    await expect(http.delete('/api/v1/workflows/w1')).resolves.toBeUndefined();
  });

  it('propagates network-level fetch failures', async () => {
    const { http } = await freshClient();
    globalThis.fetch = vi.fn(async () => { throw new TypeError('fetch failed'); }) as any;
    await expect(http.get('/api/v1/agents')).rejects.toThrow('fetch failed');
  });
});
