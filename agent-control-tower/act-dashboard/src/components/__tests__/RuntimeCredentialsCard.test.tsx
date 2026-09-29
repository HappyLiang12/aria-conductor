import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RuntimeCredentialsCard } from '../RuntimeCredentialsCard';
import type { QoderCredentialMetadata } from '../../api/runtimeCredentials';

vi.mock('../../api/runtimeCredentials', () => ({
  getQoderCredential: vi.fn(),
  putQoderCredential: vi.fn(),
  deleteQoderCredential: vi.fn(),
  testQoderCredential: vi.fn(),
}));

import {
  getQoderCredential,
  putQoderCredential,
  deleteQoderCredential,
  testQoderCredential,
} from '../../api/runtimeCredentials';

const mockedGet = vi.mocked(getQoderCredential);
const mockedPut = vi.mocked(putQoderCredential);
const mockedDelete = vi.mocked(deleteQoderCredential);
const mockedTest = vi.mocked(testQoderCredential);

/**
 * Masked metadata with one deliberate deviation from the backend contract:
 * `maskedSecret` carries raw credential material here (a masking regression, or
 * a hostile response). The card must still paint only its own fixed mask —
 * server-provided credential bytes are never rendered.
 */
const CONFIGURED: QoderCredentialMetadata = {
  credentialRef: 'qoder:operator',
  coreId: 'qoder',
  environmentVariable: 'QODER_PERSONAL_ACCESS_TOKEN',
  configured: true,
  encryptionKeyConfigured: true,
  testSupported: true,
  maskedSecret: 'fixture-secret-value',
  updatedAt: '2026-09-22T12:00:00Z',
};

const UNCONFIGURED: QoderCredentialMetadata = {
  credentialRef: 'qoder:operator',
  coreId: 'qoder',
  environmentVariable: 'QODER_PERSONAL_ACCESS_TOKEN',
  configured: false,
  encryptionKeyConfigured: true,
  testSupported: true,
  maskedSecret: null,
  updatedAt: null,
};

const COST_DISCLOSURE =
  'One bounded prompt through the pinned core with the configured credential; only '
  + 'provider-reported usage is reported, and unknown usage stays unknown.';

const NOT_WIRED_REASON =
  'The bounded Qoder credential test requires the run-owned core bridge, which is not '
  + 'wired in this component; no model call was made.';

const NO_PROBE_WIRED_REASON =
  'No credential probe is wired in this build: the bounded test needs the run-owned core '
  + 'bridge, so the dashboard cannot make that call.';

const NO_STORED_CREDENTIAL_TEST_REASON =
  'The bounded credential test needs a stored credential — save one first.';

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <RuntimeCredentialsCard />
    </QueryClientProvider>,
  );
}

describe('RuntimeCredentialsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedGet.mockResolvedValue(CONFIGURED);
  });

  it('renders the fixed mask and never the stored secret', async () => {
    ui();
    expect(await screen.findByText('••••••••')).toBeInTheDocument();
    expect(screen.queryByText('fixture-secret-value')).not.toBeInTheDocument();
    expect(screen.getByText('Configured')).toBeInTheDocument();
    expect(screen.getByText('QODER_PERSONAL_ACCESS_TOKEN')).toBeInTheDocument();
  });

  it('stores a replacement without echoing it anywhere', async () => {
    mockedPut.mockResolvedValue(CONFIGURED);
    ui();
    await screen.findByText('••••••••');

    const input = screen.getByLabelText('Runtime credential');
    await userEvent.type(input, 'fixture-secret-value');
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));

    await waitFor(() => expect(mockedPut).toHaveBeenCalledTimes(1));
    expect(mockedPut).toHaveBeenCalledWith('fixture-secret-value');
    await waitFor(() => expect(screen.getByLabelText('Runtime credential')).toHaveValue(''));
    expect(screen.queryByText('fixture-secret-value')).not.toBeInTheDocument();
    expect(await screen.findByText('••••••••')).toBeInTheDocument();
  });

  it('reports an unconfigured credential and an unusable key explicitly', async () => {
    mockedGet.mockResolvedValue({ ...UNCONFIGURED, encryptionKeyConfigured: false });
    ui();
    expect(await screen.findByText('Not configured')).toBeInTheDocument();
    expect(
      screen.getByText(
        'The credential encryption key is not configured; storing a credential is refused.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('••••••••')).not.toBeInTheDocument();
  });

  it('runs the explicit credential test and discloses provider-reported usage', async () => {
    mockedTest.mockResolvedValue({
      tested: true,
      authenticated: true,
      model: 'efficient',
      detail: 'provider acknowledged the pinned model',
      usage: { inputTokens: 12, outputTokens: 3 },
      costDisclosure: COST_DISCLOSURE,
    });
    ui();
    await screen.findByText('••••••••');

    await userEvent.click(screen.getByRole('button', { name: 'Test credential' }));

    expect(
      await screen.findByText('Credential test passed — authenticated with model efficient'),
    ).toBeInTheDocument();
    expect(screen.getByText('provider acknowledged the pinned model')).toBeInTheDocument();
    expect(screen.getByText('Provider usage: 12 input / 3 output tokens')).toBeInTheDocument();
    expect(screen.getByText(COST_DISCLOSURE)).toBeInTheDocument();
    expect(mockedTest).toHaveBeenCalledTimes(1);
  });

  it('keeps unknown usage unknown instead of reporting zero', async () => {
    mockedTest.mockResolvedValue({
      tested: true,
      authenticated: true,
      model: 'efficient',
      detail: 'provider acknowledged the pinned model',
      usage: null,
      costDisclosure: COST_DISCLOSURE,
    });
    ui();
    await screen.findByText('••••••••');

    await userEvent.click(screen.getByRole('button', { name: 'Test credential' }));

    expect(
      await screen.findByText('Provider usage: not reported by the provider (unknown, not zero)'),
    ).toBeInTheDocument();
  });

  it('surfaces provider-reported credits with the usage line', async () => {
    mockedTest.mockResolvedValue({
      tested: true,
      authenticated: true,
      model: 'efficient',
      detail: 'provider acknowledged the pinned model',
      usage: { inputTokens: 12, outputTokens: 3, credits: 0.42 },
      costDisclosure: COST_DISCLOSURE,
    });
    ui();
    await screen.findByText('••••••••');

    await userEvent.click(screen.getByRole('button', { name: 'Test credential' }));

    expect(
      await screen.findByText('Provider usage: 12 input / 3 output tokens · 0.42 credits'),
    ).toBeInTheDocument();
  });

  it('reports a refused test with the backend reason and no fabricated result', async () => {
    mockedTest.mockRejectedValue({
      response: { status: 503, data: { tested: false, reason: NOT_WIRED_REASON } },
    });
    ui();
    await screen.findByText('••••••••');

    await userEvent.click(screen.getByRole('button', { name: 'Test credential' }));

    expect(await screen.findByText(NOT_WIRED_REASON)).toBeInTheDocument();
    expect(
      screen.queryByText('Credential test passed — authenticated with model efficient'),
    ).not.toBeInTheDocument();
  });

  it('does not offer the bounded test when this build has no probe, and says why', async () => {
    mockedGet.mockResolvedValue({ ...CONFIGURED, testSupported: false });
    ui();
    await screen.findByText('••••••••');

    expect(screen.getByRole('button', { name: 'Test credential' })).toBeDisabled();
    expect(screen.getByText(NO_PROBE_WIRED_REASON)).toBeInTheDocument();
    expect(mockedTest).not.toHaveBeenCalled();
  });

  it('does not offer the bounded test before a credential is stored, and says why', async () => {
    mockedGet.mockResolvedValue(UNCONFIGURED);
    ui();
    await screen.findByText('Not configured');

    expect(screen.getByRole('button', { name: 'Test credential' })).toBeDisabled();
    expect(screen.getByText(NO_STORED_CREDENTIAL_TEST_REASON)).toBeInTheDocument();
    expect(mockedTest).not.toHaveBeenCalled();
  });

  it('surfaces the backend operator rejection verbatim', async () => {
    mockedGet.mockRejectedValue({
      response: { status: 401, data: { error: 'Operator credential required' } },
    });
    ui();
    expect(await screen.findByText('Operator credential required')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Operator-only surface — establish the operator session in the Operator access panel first.',
      ),
    ).toBeInTheDocument();
  });

  it('does not append the operator-session hint to a non-operator read failure', async () => {
    mockedGet.mockRejectedValue({
      response: { status: 503, data: { error: 'Credential store unavailable' } },
    });
    ui();
    expect(await screen.findByText('Credential store unavailable')).toBeInTheDocument();
    expect(
      screen.queryByText(
        'Operator-only surface — establish the operator session in the Operator access panel first.',
      ),
    ).not.toBeInTheDocument();
  });

  it('removes the credential only after the operator confirms', async () => {
    mockedGet.mockResolvedValueOnce(CONFIGURED).mockResolvedValueOnce(UNCONFIGURED);
    mockedDelete.mockResolvedValue(undefined);
    ui();
    await screen.findByText('••••••••');

    await userEvent.click(screen.getByRole('button', { name: 'Remove credential' }));
    expect(mockedDelete).not.toHaveBeenCalled();
    expect(
      screen.getByRole('dialog', { name: 'Remove the Qoder runtime credential?' }),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(mockedDelete).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Not configured')).toBeInTheDocument();
  });
});
