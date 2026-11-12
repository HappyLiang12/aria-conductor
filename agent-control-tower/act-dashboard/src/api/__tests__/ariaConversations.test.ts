import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../client';
import {
  CLEAR_CONVERSATION_TIMEOUT_MS,
  composeSynthesis,
  deleteConversation,
  getLatestConversation,
} from '../ariaConversations';
import { OPERATOR_CSRF_HEADER, OPERATOR_SESSION_STORAGE_KEY } from '../operatorSession';

/**
 * T48 Item 1 (P1). The conversation mutations were bare `fetch` calls, so they
 * carried neither the operator CSRF token the axios `client` holds in its
 * defaults (`operatorSession.ts:99-106`) nor the `X-Correlation-ID` the client
 * adds. The DELETE route resolves operator authority for a mutation
 * (`AriaConversationController.java:193-203`), and a browser carrying the
 * operator session cookie is refused 403 by `OperatorSessionService.validateMutation`
 * when the token is missing — the cookie branch runs before the loopback branch,
 * so auto-authority does not rescue it.
 *
 * The adapter below is the real axios merge (`client.defaults.adapter`), so a
 * header asserted here is the header that would leave the browser, not a value
 * some mock was told to echo. Absence is asserted only beside its positive
 * control, because an absent header is also what a broken request produces.
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function installAdapter(status = 200, data: unknown = null) {
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

/** The record `establishOperatorSession` persists: only the token and the expiry. */
function storeSession(csrfToken: string) {
  localStorage.setItem(
    OPERATOR_SESSION_STORAGE_KEY,
    JSON.stringify({ csrfToken, expiresAt: new Date(Date.now() + 3_600_000).toISOString() }),
  );
}

describe('ariaConversations mutations carry the operator CSRF header', () => {
  beforeEach(() => {
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sends the stored session token on the conversation DELETE', async () => {
    storeSession('csrf-token-abc');
    const seen = installAdapter(204);

    await deleteConversation('conv 1');

    expect(seen).toHaveLength(1);
    expect(String(seen[0].method).toLowerCase()).toBe('delete');
    expect(seen[0].url).toBe('/api/v1/aria/conversations/conv%201');
    // Presence AND value: a header set to the wrong token is refused by the
    // backend just as a missing one is.
    expect(String(seen[0].headers[OPERATOR_CSRF_HEADER])).toBe('csrf-token-abc');
    // The shared client's other behaviour still applies to this call.
    expect(String(seen[0].headers['X-Correlation-ID'])).toMatch(UUID_RE);
  });

  it('sends the stored session token on the synthesize POST', async () => {
    storeSession('csrf-token-xyz');
    const seen = installAdapter(200, { prompt: 'composed' });

    await expect(composeSynthesis('conv-9')).resolves.toEqual({ prompt: 'composed' });

    expect(seen).toHaveLength(1);
    expect(String(seen[0].method).toLowerCase()).toBe('post');
    expect(seen[0].url).toBe('/api/v1/aria/conversations/conv-9/synthesize');
    expect(String(seen[0].headers[OPERATOR_CSRF_HEADER])).toBe('csrf-token-xyz');
    expect(String(seen[0].headers['X-Correlation-ID'])).toMatch(UUID_RE);
  });

  // Positive control for the absence below: the same assertion run with a session
  // present sees the header, so "no header" is the unauthenticated case and not a
  // header the assertions above could never observe.
  it('sends no CSRF header when no session is stored, and the call still works', async () => {
    const seen = installAdapter(204);

    await deleteConversation('conv-1');

    expect(seen).toHaveLength(1);
    expect(seen[0].headers[OPERATOR_CSRF_HEADER]).toBeUndefined();
  });

  // T49 (N-T48-4). The DELETE synchronously drives RunService.cancelRun, whose
  // verified stop waits on the core, so on a slow or wedged backend it can hang.
  // The panel reports a timeout as its own outcome, so the call must actually be
  // bounded — an unbounded await would make that branch unreachable and the
  // destructive control look dead.
  it('bounds the DELETE so a hung Clear can answer, not hang the control', async () => {
    storeSession('csrf-token-abc');
    const seen = installAdapter(204);

    await deleteConversation('conv-1');

    expect(seen).toHaveLength(1);
    expect(seen[0].timeout).toBe(CLEAR_CONVERSATION_TIMEOUT_MS);
    // A bound, not zero/undefined: 0 means "no timeout" in axios.
    expect(CLEAR_CONVERSATION_TIMEOUT_MS).toBeGreaterThan(0);
    expect(CLEAR_CONVERSATION_TIMEOUT_MS).toBeLessThanOrEqual(30_000);
  });

  it('keeps treating a 404 as a successful delete (the conversation is already gone)', async () => {
    storeSession('csrf-token-abc');
    installAdapter(404, {});

    await expect(deleteConversation('conv-gone')).resolves.toBeUndefined();
  });

  it('propagates a refusal so the caller can surface it, carrying the server text', async () => {
    storeSession('stale-token');
    installAdapter(403, { error: 'CSRF validation failed' });

    const error = await deleteConversation('conv-1').catch((err: unknown) => err);

    expect(error).toMatchObject({ response: { status: 403 } });
    // The shape `apiErrorMessage` reads: a refusal must reach the panel with its
    // own wording, which a plain `new Error(statusText)` could not carry.
    expect(
      (error as { response: { data: { error: string } } }).response.data.error,
    ).toBe('CSRF validation failed');
  });
});

/**
 * T60 (F-O5). On the first load against a fresh database the console logged
 * `GET /api/v1/aria/conversations/latest → 404` plus `net::ERR_ABORTED` although
 * nothing was wrong: a 404 is the server's correct REST answer for "no
 * conversation exists yet", the same answer as the 204 the route sends once one
 * exists. This file already reads 404 as success for the DELETE, so the read gets
 * the same reading. Each non-throw is asserted by its resolved value; a throw's
 * absence alone is not enough.
 */
describe('getLatestConversation reads "no conversation yet" as null', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The fields `getLatestConversation` reads off the response. */
  function response(status: number, body?: unknown) {
    return {
      status,
      ok: status >= 200 && status < 300,
      statusText: `HTTP ${status}`,
      json: async () => body,
    };
  }

  it('resolves to null on a 404, which is the fresh-database first load', async () => {
    fetchMock.mockResolvedValue(response(404, { error: 'Not Found' }));

    await expect(getLatestConversation()).resolves.toBeNull();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/aria/conversations/latest');
  });

  // Positive control for the null above: the 204 path already produced null
  // before this change, so "null" is the empty answer and not a swallowed body.
  it('still resolves to null on a 204', async () => {
    fetchMock.mockResolvedValue(response(204));

    await expect(getLatestConversation()).resolves.toBeNull();
  });

  // Positive control: a conversation that does exist still yields the summary,
  // so a blanket "always null" would fail here.
  it('resolves to the summary on a 200 with a body', async () => {
    const summary = {
      conversationId: 'conv-1',
      lastMessageAt: '2026-11-11T00:00:00Z',
      runCount: 3,
    };
    fetchMock.mockResolvedValue(response(200, summary));

    await expect(getLatestConversation()).resolves.toEqual(summary);
  });

  // Positive control: only 404 (and 204) mean "none"; a real server fault must
  // still reach the caller, which the panel turns into its history-unavailable note.
  it('still rejects on a 500', async () => {
    fetchMock.mockResolvedValue(response(500));

    await expect(getLatestConversation()).rejects.toThrow(
      'Failed to fetch latest conversation: HTTP 500',
    );
  });
});
