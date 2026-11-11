import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../client';
import { composeSynthesis, deleteConversation } from '../ariaConversations';
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
