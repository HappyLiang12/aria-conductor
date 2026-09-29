import client from './client';

/**
 * Local operator session (spec §6.2, Task 4's boundary).
 *
 * The operator credential is a separately provisioned single-operator secret
 * (`aria.operator.bearer-token` / `ARIA_OPERATOR_BEARER_TOKEN`). It is never
 * shipped in frontend assets: the operator types it into the local setup
 * surface, it is exchanged once for an HttpOnly/SameSite session cookie plus a
 * CSRF token, and it is dropped again — nothing about it is stored.
 *
 * `POST /api/v1/operator/session` answers 200 with `{ csrfToken, expiresAt }`
 * and sets the cookie; without a valid credential the backend fails closed
 * (401). Mutations authenticated by the cookie must carry `X-CSRF-Token` (the
 * cookie is HttpOnly, so the token is kept in per-tab sessionStorage) and an
 * allowed Origin, which the browser adds for non-GET requests.
 *
 * Only the CSRF token + expiry are persisted (sessionStorage, this tab); the
 * token is applied to the shared axios defaults so every operator-only call —
 * approvals, credentials, agent/host selection — carries it without touching
 * the shared client module.
 */

/** Per-tab storage key of the local operator session record. */
export const OPERATOR_SESSION_STORAGE_KEY = 'aria.operator.session';

/** CSRF header name the backend validates (`OperatorSessionService.CSRF_HEADER`). */
export const OPERATOR_CSRF_HEADER = 'X-CSRF-Token';

export interface OperatorSessionInfo {
  csrfToken: string;
  expiresAt: string;
}

export interface OperatorSessionState {
  established: boolean;
  expiresAt: string | null;
  /**
   * True when this tab held a session record that had already expired (the
   * loader drops such a record, so only this flag distinguishes "the previous
   * session expired" from a first run). A malformed record is not an expiry.
   */
  expired: boolean;
}

/**
 * The stored record as this tab holds it, before any expiry handling. Null for
 * an absent, unreadable or malformed record.
 */
function readStoredRecord(): OperatorSessionInfo | null {
  try {
    const raw = sessionStorage.getItem(OPERATOR_SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<OperatorSessionInfo>;
    if (!parsed?.csrfToken || !parsed?.expiresAt) return null;
    return { csrfToken: parsed.csrfToken, expiresAt: parsed.expiresAt };
  } catch {
    return null;
  }
}

/** Reads the stored session, dropping a record that has already expired. */
export function loadOperatorSession(): OperatorSessionInfo | null {
  const record = readStoredRecord();
  if (!record) return null;
  if (!(new Date(record.expiresAt).getTime() > Date.now())) {
    try {
      sessionStorage.removeItem(OPERATOR_SESSION_STORAGE_KEY);
    } catch {
      // Storage unavailable: there is nothing to drop.
    }
    return null;
  }
  return record;
}

/** Local (this-tab) view of the operator session; not a server-side probe. */
export function operatorSessionState(): OperatorSessionState {
  // The stored record is read before the loader drops an expired one, so the
  // expiry is reported as such instead of as an empty (first-run) tab.
  const stored = readStoredRecord();
  const session = loadOperatorSession();
  return {
    established: session !== null,
    expiresAt: session?.expiresAt ?? null,
    expired:
      session === null && stored !== null && !(new Date(stored.expiresAt).getTime() > Date.now()),
  };
}

/**
 * Applies the stored session's CSRF token to the shared axios defaults so every
 * mutation (approvals, credentials) is authenticated by the operator cookie.
 * Clears the header when no unexpired session exists — a stale header must not
 * masquerade as authority.
 */
export function applyOperatorHeaders(): void {
  const session = loadOperatorSession();
  if (session) {
    client.defaults.headers.common[OPERATOR_CSRF_HEADER] = session.csrfToken;
  } else {
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  }
}

/** Forgets the local session record and drops the CSRF header. */
export function clearOperatorSession(): void {
  try {
    sessionStorage.removeItem(OPERATOR_SESSION_STORAGE_KEY);
  } catch {
    // Storage unavailable (private mode): there is nothing stored to forget.
  }
  delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
}

/**
 * Exchanges the operator credential for a local session. The credential itself
 * is used for this one request only and is never stored.
 */
export async function establishOperatorSession(
  operatorCredential: string,
): Promise<OperatorSessionInfo> {
  const { data } = await client.post<OperatorSessionInfo>(
    '/api/v1/operator/session',
    undefined,
    { headers: { Authorization: `Bearer ${operatorCredential.trim()}` } },
  );
  const session: OperatorSessionInfo = { csrfToken: data.csrfToken, expiresAt: data.expiresAt };
  try {
    sessionStorage.setItem(OPERATOR_SESSION_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Storage unavailable: the session cookie still authenticates reads, but
    // mutations cannot carry CSRF — stay honest and fail closed below.
  }
  applyOperatorHeaders();
  return session;
}

/** Revokes the local session (204) and forgets it locally. Core credentials untouched. */
export async function revokeOperatorSession(): Promise<void> {
  applyOperatorHeaders();
  try {
    await client.delete('/api/v1/operator/session');
  } finally {
    clearOperatorSession();
  }
}

/**
 * The backend's own error message when one was served (`{ error }` for
 * rejections, `{ reason }` for a refused credential test, `{ detail }` as the
 * last resort), otherwise the caller's fallback. Operator-only routes explain
 * themselves (401 `Operator credential required`, 403 `Operator authority
 * required`, CSRF/Origin refusals), so the exact backend wording is surfaced
 * instead of a generic retry line.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  const data = (error as { response?: { data?: Record<string, unknown> } } | undefined)?.response
    ?.data;
  for (const key of ['error', 'reason', 'detail']) {
    const value = data?.[key];
    if (typeof value === 'string' && value.trim() !== '') {
      return value;
    }
  }
  return fallback;
}

/** True when an operator-only route refused the call for a missing/expired session (401/403). */
export function isOperatorRejection(error: unknown): boolean {
  const status = (error as { response?: { status?: number } } | undefined)?.response?.status;
  return status === 401 || status === 403;
}
