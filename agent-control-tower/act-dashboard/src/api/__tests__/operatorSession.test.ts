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
//
// Task 41: the bodies below are the ones the controllers actually serve, not a
// shape invented by the test. Two shapes carry the marker:
//   1. `{ error }` — ApprovalController.decideApproval (the route
//      `decideApproval` posts to), MaintenanceController.initializeBuiltins,
//      OperatorSessionController.revoke, and RunController.finalizeRun
//      (act-agent, hardcoded literal).
//   2. `{ ok: false, errorType: 'FORBIDDEN', message }` — MemoryExceptionHandler,
//      which maps ForbiddenMutationException (a SecurityException) on the
//      memory routes.
// ---------------------------------------------------------------------------
describe('operator rejection classification (Task 37 / Task 41 producer bodies)', () => {
  function refusal(status: number, data: Record<string, unknown>) {
    return { response: { status, data } };
  }

  /** The memory envelope MemoryExceptionHandler serves (Task 37's original pin, real there). */
  function envelope(status: number, message?: string) {
    return refusal(status, message === undefined ? {} : { ok: false, errorType: 'FORBIDDEN', message });
  }

  it('classifies the {error} body the execution controllers serve for a cross-site mutation', () => {
    // ApprovalController.decideApproval, MaintenanceController.initializeBuiltins,
    // OperatorSessionController.revoke and RunController.finalizeRun all answer
    // 403 with the marker under `error` — not under `message`.
    const error = refusal(403, { error: 'Cross-origin operator mutation rejected' });

    expect(operatorRefusalKind(error)).toBe('cross-origin');
    expect(isCrossOriginRejection(error)).toBe(true);
    // Still a refusal: every caller that only asks "was it refused?" keeps working.
    expect(isOperatorRejection(error)).toBe(true);
  });

  it('classifies the memory envelope that carries the marker under message', () => {
    const error = envelope(403, 'Cross-origin operator mutation rejected');

    expect(operatorRefusalKind(error)).toBe('cross-origin');
    expect(isCrossOriginRejection(error)).toBe(true);
    expect(isOperatorRejection(error)).toBe(true);
  });

  it('keeps a genuine authority refusal on the same {error} route classified operator', () => {
    // Positive control on `/api/v1/approvals/{id}/decide`: the 401 branch and the
    // CSRF branch of the SAME controller serve `{error}` without the marker.
    const unauthorized = refusal(401, { error: 'Operator authority required' });
    const csrf = refusal(403, { error: 'CSRF validation failed' });

    for (const error of [unauthorized, csrf]) {
      expect(operatorRefusalKind(error)).toBe('operator');
      expect(isCrossOriginRejection(error)).toBe(false);
      expect(isOperatorRejection(error)).toBe(true);
    }
  });

  it('keeps the other {error} authority refusals classified operator', () => {
    // CoreCredentialController (`Operator authority required`),
    // HousekeepingController (`Operator authority required: executing`),
    // Agent/LlmProvider/Report controllers (`Operator authority required: …`).
    const error = refusal(403, { error: 'Operator authority required: executing' });

    expect(operatorRefusalKind(error)).toBe('operator');
    expect(isCrossOriginRejection(error)).toBe(false);
  });

  it('keeps a bodyless 403 an authority refusal', () => {
    // ActorAuthenticationFilter answers the session-cookie cross-origin refusal
    // with no body, so no marker exists to read; the remedy stays the operator
    // one rather than guessing.
    const error = refusal(403, {});

    expect(operatorRefusalKind(error)).toBe('operator');
    expect(isCrossOriginRejection(error)).toBe(false);
  });

  it('keeps a 401 an authority refusal even when its text carries the marker', () => {
    // 401 is always authority (spec §6.2); only 403 can be the origin refusal.
    const error = refusal(401, { error: 'Cross-origin operator mutation rejected' });

    expect(operatorRefusalKind(error)).toBe('operator');
    expect(isCrossOriginRejection(error)).toBe(false);
  });

  it('keeps a plain 403 and a 401 as genuine authority refusals', () => {
    for (const status of [401, 403]) {
      const error = envelope(status, 'operator session required');

      expect(operatorRefusalKind(error)).toBe('operator');
      expect(isCrossOriginRejection(error)).toBe(false);
      expect(isOperatorRejection(error)).toBe(true);
    }
  });

  it('never classifies anything else as a rejection', () => {
    // Positive controls: a fault, a bare error, an absent value and the 409
    // RunController serves for a run that is not waiting on input.
    expect(operatorRefusalKind(envelope(500, 'store unavailable'))).toBeNull();
    expect(operatorRefusalKind(new Error('network down'))).toBeNull();
    expect(operatorRefusalKind(undefined)).toBeNull();
    expect(operatorRefusalKind(refusal(409, { error: 'Run r-1 is not waiting for operator input' })))
      .toBeNull();
    expect(isOperatorRejection(envelope(500, 'store unavailable'))).toBe(false);
  });

  it('pins the marker to the wording the backend serves', () => {
    // rejectCrossSiteOrigin, validateMutation and RunController's origin guard
    // all serve exactly this string.
    expect(CROSS_ORIGIN_REJECTION_MARKER).toBe('Cross-origin operator mutation rejected');
  });
});
