import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../client';
import {
  CROSS_ORIGIN_REJECTION_MARKER,
  OPERATOR_CSRF_HEADER,
  OPERATOR_SESSION_STORAGE_KEY,
  applyOperatorHeaders,
  clearOperatorSession,
  establishOperatorSession,
  isCrossOriginRejection,
  isOperatorRejection,
  loadOperatorSession,
  operatorRefusalKind,
  operatorSessionState,
} from '../operatorSession';

// ---------------------------------------------------------------------------
// Task 15 fix round 1 (I2): the loader drops an already-expired record, so the
// local state must report the expiry as such instead of reporting a first run.
// ---------------------------------------------------------------------------

const EXPIRED_RECORD = JSON.stringify({
  csrfToken: 'csrf-expired',
  expiresAt: '2000-01-01T00:00:00Z',
});

describe('operator session local record', () => {
  beforeEach(() => {
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  afterEach(() => {
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  it('reports an expired record as expired and drops it in the same read', () => {
    localStorage.setItem(OPERATOR_SESSION_STORAGE_KEY, EXPIRED_RECORD);

    expect(operatorSessionState()).toEqual({ established: false, expiresAt: null, expired: true });
    expect(localStorage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('never reports a tab without a record as an expired session', () => {
    expect(operatorSessionState()).toEqual({ established: false, expiresAt: null, expired: false });
  });

  it('never reports a malformed record as an expired session', () => {
    localStorage.setItem(OPERATOR_SESSION_STORAGE_KEY, JSON.stringify({ csrfToken: 'csrf-only' }));

    expect(operatorSessionState()).toEqual({ established: false, expiresAt: null, expired: false });
    expect(loadOperatorSession()).toBeNull();
  });

  it('reports an unexpired record as established with its expiry', () => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    localStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({ csrfToken: 'csrf-live', expiresAt }),
    );

    expect(operatorSessionState()).toEqual({ established: true, expiresAt, expired: false });
    expect(loadOperatorSession()).toEqual({ csrfToken: 'csrf-live', expiresAt });
    expect(localStorage.getItem(OPERATOR_SESSION_STORAGE_KEY)).not.toBeNull();
  });

  it('applies the unexpired session CSRF token and clears it when the record is gone', () => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    localStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({ csrfToken: 'csrf-live', expiresAt }),
    );
    applyOperatorHeaders();
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBe('csrf-live');

    clearOperatorSession();
    applyOperatorHeaders();
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Local-authority-simplification Task 3: the record moves from per-tab
// sessionStorage to localStorage so every tab of this browser profile reuses
// the same CSRF session instead of each tab establishing its own.
// ---------------------------------------------------------------------------

const ORIGINAL_ADAPTER = client.defaults.adapter;

describe('operator session storage move (local-authority Task 3)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  afterEach(() => {
    client.defaults.adapter = ORIGINAL_ADAPTER;
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  it('persists the session record in localStorage so other tabs reuse it', async () => {
    client.defaults.adapter = async (
      config: InternalAxiosRequestConfig,
    ): Promise<AxiosResponse> => ({
      data: { csrfToken: 'csrf-1', expiresAt: new Date(Date.now() + 3600_000).toISOString() },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    });

    await establishOperatorSession('secret');

    expect(localStorage.getItem(OPERATOR_SESSION_STORAGE_KEY)).not.toBeNull();
    expect(loadOperatorSession()?.csrfToken).toBe('csrf-1');
  });
});

// ---------------------------------------------------------------------------
// Task 37 / Item 2: a 403 has TWO producers on the operator routes, and they do
// not mean the same thing. OperatorSessionService.rejectCrossSiteOrigin refuses
// a mutation whose Origin names a page the backend does not trust
// ("Cross-origin operator mutation rejected"), while a genuine authority refusal
// means the caller is not the operator. The status alone cannot tell them apart,
// so the served marker does — and it is the marker the Java lane freezes
// server-side.
// ---------------------------------------------------------------------------
describe('operator rejection classification (Task 37)', () => {
  function denied(status: number, message?: string) {
    return {
      response: {
        status,
        data: message === undefined ? {} : { ok: false, errorType: 'FORBIDDEN', message },
      },
    };
  }

  it('classifies the cross-origin marker as a cross-site rejection, not an authority refusal', () => {
    const error = denied(403, 'Cross-origin operator mutation rejected');

    expect(operatorRefusalKind(error)).toBe('cross-origin');
    expect(isCrossOriginRejection(error)).toBe(true);
    // Still a refusal: every caller that only asks "was it refused?" keeps working.
    expect(isOperatorRejection(error)).toBe(true);
  });

  it('keeps a plain 403 and a 401 as genuine authority refusals', () => {
    for (const status of [401, 403]) {
      const error = denied(status, 'operator session required');

      expect(operatorRefusalKind(error)).toBe('operator');
      expect(isCrossOriginRejection(error)).toBe(false);
      expect(isOperatorRejection(error)).toBe(true);
    }
  });

  it('never classifies anything else as a rejection', () => {
    // Positive controls: a fault, a bare error and an absent value.
    expect(operatorRefusalKind(denied(500, 'store unavailable'))).toBeNull();
    expect(operatorRefusalKind(new Error('network down'))).toBeNull();
    expect(operatorRefusalKind(undefined)).toBeNull();
    expect(isOperatorRejection(denied(500, 'store unavailable'))).toBe(false);
  });

  it('pins the marker to the wording the backend serves', () => {
    // rejectCrossSiteOrigin, validateMutation and RunController's origin guard
    // all serve exactly this string.
    expect(CROSS_ORIGIN_REJECTION_MARKER).toBe('Cross-origin operator mutation rejected');
  });
});
