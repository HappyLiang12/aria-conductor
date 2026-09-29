import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { OperatorAccessPanel } from '../OperatorAccessPanel';
import { RuntimeCredentialsCard } from '../RuntimeCredentialsCard';
import client from '../../api/client';
import { OPERATOR_CSRF_HEADER, OPERATOR_SESSION_STORAGE_KEY } from '../../api/operatorSession';
import type { QoderCredentialMetadata } from '../../api/runtimeCredentials';

vi.mock('../../api/runtimeCredentials', () => ({
  getQoderCredential: vi.fn(),
  putQoderCredential: vi.fn(),
  deleteQoderCredential: vi.fn(),
  testQoderCredential: vi.fn(),
}));

import { getQoderCredential } from '../../api/runtimeCredentials';

const mockedGet = vi.mocked(getQoderCredential);

const OPERATOR_CREDENTIAL = 'fixture-operator-credential';
const ESTABLISHED_EXPIRES_AT = '2099-09-22T12:05:00Z';

const REFUSAL = 'Operator credential required';
const OPERATOR_HINT =
  'Operator-only surface — establish the operator session in the Operator access panel first.';

const CONFIGURED: QoderCredentialMetadata = {
  credentialRef: 'qoder:operator',
  coreId: 'qoder',
  environmentVariable: 'QODER_PERSONAL_ACCESS_TOKEN',
  configured: true,
  encryptionKeyConfigured: true,
  testSupported: true,
  maskedSecret: null,
  updatedAt: '2026-09-29T11:21:00Z',
};

/** The shared axios adapter: serves the operator session exchange and revocation only. */
const originalAdapter = client.defaults.adapter;
const requests: InternalAxiosRequestConfig[] = [];

beforeEach(() => {
  requests.length = 0;
  sessionStorage.clear();
  delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    requests.push(config);
    if (config.url !== '/api/v1/operator/session') {
      throw new Error(`unexpected request: ${config.method} ${config.url}`);
    }
    if (config.method === 'delete') {
      return { data: undefined, status: 204, statusText: 'No Content', headers: {}, config };
    }
    return {
      data: { csrfToken: 'csrf-established', expiresAt: ESTABLISHED_EXPIRES_AT },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    };
  };
  mockedGet.mockReset();
});

afterEach(() => {
  client.defaults.adapter = originalAdapter;
  sessionStorage.clear();
  delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
});

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <OperatorAccessPanel />
      <RuntimeCredentialsCard />
    </QueryClientProvider>,
  );
}

/**
 * The Provider surface renders the operator panel above the Qoder credential
 * card. Before any session exists the card's read is refused (401), so the card
 * caches that refusal; establishing the session in the panel must make the card
 * refetch under the new authority in the same page — not require a manual
 * reload, which is the defect this file pins.
 */
describe('operator authority refresh (Providers surface)', () => {
  it('refetches the refused credential card once the operator session is established', async () => {
    mockedGet.mockRejectedValueOnce({
      response: { status: 401, data: { error: REFUSAL } },
    });
    mockedGet.mockResolvedValue(CONFIGURED);

    const user = userEvent.setup();
    ui();

    expect(await screen.findByText(REFUSAL)).toBeTruthy();
    expect(screen.getByText(OPERATOR_HINT)).toBeTruthy();
    expect(screen.queryByLabelText('Runtime credential')).toBeNull();
    expect(mockedGet).toHaveBeenCalledTimes(1);

    await user.type(screen.getByLabelText('Operator credential'), OPERATOR_CREDENTIAL);
    await user.click(screen.getByRole('button', { name: 'Establish operator session' }));

    expect(await screen.findByText('Operator session established')).toBeTruthy();
    await waitFor(() => expect(mockedGet).toHaveBeenCalledTimes(2));

    expect(await screen.findByText('Configured')).toBeTruthy();
    expect(screen.getByLabelText('Runtime credential')).toBeTruthy();
    expect(screen.queryByText(REFUSAL)).toBeNull();
    expect(screen.queryByText(OPERATOR_HINT)).toBeNull();
    expect(requests.map((r) => `${r.method} ${r.url}`)).toEqual(['post /api/v1/operator/session']);
  });

  it('returns the card to the refused state after the operator signs out', async () => {
    mockedGet.mockResolvedValueOnce(CONFIGURED);
    mockedGet.mockRejectedValue({
      response: { status: 401, data: { error: REFUSAL } },
    });

    // A tab that already holds this session (the panel reads it back on mount).
    sessionStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({ csrfToken: 'csrf-established', expiresAt: ESTABLISHED_EXPIRES_AT }),
    );

    const user = userEvent.setup();
    ui();

    expect(await screen.findByText('Configured')).toBeTruthy();
    expect(mockedGet).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(mockedGet).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(REFUSAL)).toBeTruthy();
    expect(screen.queryByText('Configured')).toBeNull();
    expect(requests.map((r) => `${r.method} ${r.url}`)).toEqual([
      'delete /api/v1/operator/session',
    ]);
  });
});
