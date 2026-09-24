import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { OperatorAccessPanel } from '../OperatorAccessPanel';
import client from '../../api/client';
import { OPERATOR_CSRF_HEADER, OPERATOR_SESSION_STORAGE_KEY } from '../../api/operatorSession';
import { formatTimestamp } from '../../utils/formatTime';

// ---------------------------------------------------------------------------
// Task 15 fix rounds 1–2.
//
// Round 1: the panel must surface an expired stored record as such (the loader
// drops the record, so a first run and an expired session must remain
// distinguishable) and it must never persist the operator credential — on any
// outcome.
//
// Round 2: the panel used to call applyOperatorHeaders() before reading the
// state, and that call runs the loader, which deletes an expired record and
// clears the CSRF header — so operatorSessionState() afterwards reported a
// clean first run and the expiry notice was unreachable for a well-formed
// expired record. These tests therefore exercise the real api/operatorSession
// module over sessionStorage; only the network boundary is mocked (the shared
// axios adapter), never the two functions whose ordering is under test.
// ---------------------------------------------------------------------------

const OPERATOR_CREDENTIAL = 'fixture-operator-credential';

// A fixed future instant: the session the exchange returns must be genuinely
// unexpired (the real module drops an expired record on the next read) while
// the rendered expiry stays deterministic.
const ESTABLISHED_EXPIRES_AT = '2099-09-22T12:05:00Z';

const EXPIRED_RECORD = JSON.stringify({
  csrfToken: 'csrf-expired',
  expiresAt: '2000-01-01T00:00:00Z',
});

const EXPIRED_NOTICE = 'The previous operator session expired — establish a new one.';

/** The shared axios adapter: serves the session exchange and records every request. */
const originalAdapter = client.defaults.adapter;
const requests: InternalAxiosRequestConfig[] = [];
let sessionRefused = false;

beforeEach(() => {
  requests.length = 0;
  sessionRefused = false;
  sessionStorage.clear();
  delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    requests.push(config);
    if (config.method !== 'post' || config.url !== '/api/v1/operator/session') {
      throw new Error(`unexpected request: ${config.method} ${config.url}`);
    }
    if (sessionRefused) {
      throw { response: { status: 401, data: { error: 'Operator credential required' } } };
    }
    return {
      data: { csrfToken: 'csrf-established', expiresAt: ESTABLISHED_EXPIRES_AT },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    };
  };
});

afterEach(() => {
  client.defaults.adapter = originalAdapter;
  sessionStorage.clear();
  delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
});

function ui() {
  return render(<OperatorAccessPanel />);
}

describe('OperatorAccessPanel (Task 15 fix rounds 1–2)', () => {
  it('shows the established session with its expiry and clears the typed credential', async () => {
    ui();

    await userEvent.type(screen.getByLabelText('Operator credential'), OPERATOR_CREDENTIAL);
    await userEvent.click(screen.getByRole('button', { name: 'Establish operator session' }));

    expect(await screen.findByText('Operator session established')).toBeInTheDocument();
    expect(
      screen.getByText(`expires ${formatTimestamp(ESTABLISHED_EXPIRES_AT)}`),
    ).toBeInTheDocument();
    // The credential reaches the exchange as the Bearer token and leaves the DOM.
    expect(requests).toHaveLength(1);
    expect(requests[0].url).toBe('/api/v1/operator/session');
    expect(requests[0].headers.get('Authorization')).toBe(`Bearer ${OPERATOR_CREDENTIAL}`);
    expect(screen.queryByLabelText('Operator credential')).not.toBeInTheDocument();
    expect(screen.queryByText(OPERATOR_CREDENTIAL)).not.toBeInTheDocument();
    // The tab stores exactly the CSRF token + expiry — no byte of the credential.
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBe(
      JSON.stringify({ csrfToken: 'csrf-established', expiresAt: ESTABLISHED_EXPIRES_AT }),
    );
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBe('csrf-established');
  });

  it('notices an expired stored record and stays silent for a first run', () => {
    sessionStorage.setItem(OPERATOR_SESSION_STORAGE_KEY, EXPIRED_RECORD);

    const expired = ui();

    // The notice must come from the real record: the header-application path
    // drops that record, so the panel has to capture the expiry before it runs.
    expect(screen.getByText(EXPIRED_NOTICE)).toBeInTheDocument();
    // The expired record is dropped in the same read and is never authority.
    expect(sessionStorage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBeNull();
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBeUndefined();
    expired.unmount();

    // A first run (no record) must not claim an expiry.
    ui();
    expect(screen.queryByText(EXPIRED_NOTICE)).not.toBeInTheDocument();
  });

  it('re-applies the stored headers and reflects the stored session state on mount', () => {
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    sessionStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({ csrfToken: 'csrf-live', expiresAt }),
    );

    ui();

    // The stored CSRF token is on the shared client for this tab's operator calls…
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBe('csrf-live');
    // …and the stored session is reflected instead of a fresh form.
    expect(screen.getByText('Operator session established')).toBeInTheDocument();
    expect(screen.getByText(`expires ${formatTimestamp(expiresAt)}`)).toBeInTheDocument();
    // Mount only reads this tab's record — nothing is sent anywhere.
    expect(requests).toHaveLength(0);
  });

  it('clears a stale CSRF header when the stored record has expired', () => {
    client.defaults.headers.common[OPERATOR_CSRF_HEADER] = 'csrf-stale';
    sessionStorage.setItem(OPERATOR_SESSION_STORAGE_KEY, EXPIRED_RECORD);

    ui();

    expect(screen.getByText(EXPIRED_NOTICE)).toBeInTheDocument();
    // Ordering does not leak stale authority: reading first must not skip the
    // header-application path that clears a dead session's token.
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBeUndefined();
  });

  it('stays unestablished with no stored secret when the exchange is refused', async () => {
    sessionRefused = true;
    ui();

    await userEvent.type(screen.getByLabelText('Operator credential'), OPERATOR_CREDENTIAL);
    await userEvent.click(screen.getByRole('button', { name: 'Establish operator session' }));

    // The backend's exact wording, then a cleared form — never an established state.
    expect(await screen.findByText('Operator credential required')).toBeInTheDocument();
    expect(screen.queryByText('Operator session established')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Operator credential')).toHaveValue('');
    expect(screen.queryByText(OPERATOR_CREDENTIAL)).not.toBeInTheDocument();
    // No session record, no CSRF authority, and no byte of the credential in storage.
    expect(sessionStorage.getItem(OPERATOR_SESSION_STORAGE_KEY)).toBeNull();
    expect(sessionStorage.length).toBe(0);
    expect(client.defaults.headers.common[OPERATOR_CSRF_HEADER]).toBeUndefined();
  });
});
