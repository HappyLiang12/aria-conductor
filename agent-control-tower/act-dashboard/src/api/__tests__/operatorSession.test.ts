import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../client';
import {
  OPERATOR_CSRF_HEADER,
  OPERATOR_SESSION_STORAGE_KEY,
  applyOperatorHeaders,
  clearOperatorSession,
  establishOperatorSession,
  loadOperatorSession,
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
