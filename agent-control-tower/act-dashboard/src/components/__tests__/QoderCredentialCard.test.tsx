import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QoderCredentialCard } from '../QoderCredentialCard';
import type { QoderCredentialStatus } from '../../types';

vi.mock('../../api/qoderCredential', () => ({
  getQoderCredential: vi.fn(),
  saveQoderCredential: vi.fn(),
  deleteQoderCredential: vi.fn(),
  testQoderCredential: vi.fn(),
}));
vi.mock('../../api/adk', () => ({
  getAdkProviderHealth: vi.fn(),
}));

import {
  getQoderCredential,
  saveQoderCredential,
  deleteQoderCredential,
  testQoderCredential,
} from '../../api/qoderCredential';
import { getAdkProviderHealth } from '../../api/adk';

const UNCONFIGURED: QoderCredentialStatus = {
  providerId: 'qoder',
  configured: false,
  patMasked: null,
  updatedAt: null,
  model: 'auto',
};

const CONFIGURED: QoderCredentialStatus = {
  providerId: 'qoder',
  configured: true,
  patMasked: '****cdef',
  updatedAt: '2026-09-18T05:00:00Z',
  model: 'efficient',
};

/** Synthetic token only — never a real credential anywhere. */
const SYNTHETIC_PAT = 'qcp_test_1234567890';

const COST_NOTE =
  'This probe runs no billable inference: it only verifies that a stored credential is configured, decrypts and is non-blank.';

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    qc,
    ...render(
      <QueryClientProvider client={qc}>
        <QoderCredentialCard />
      </QueryClientProvider>,
    ),
  };
}

describe('QoderCredentialCard credential editor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getAdkProviderHealth).mockResolvedValue({ providerId: 'qoder', healthy: true });
    vi.mocked(saveQoderCredential).mockResolvedValue(CONFIGURED);
    vi.mocked(deleteQoderCredential).mockResolvedValue(undefined);
  });

  it('shows the unconfigured default state, then posts the PAT exactly once and masks it', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(UNCONFIGURED);
    const user = userEvent.setup();
    ui();

    expect(await screen.findByText('Not configured')).toBeInTheDocument();
    // No PUT on render.
    expect(saveQoderCredential).not.toHaveBeenCalled();

    const save = screen.getByRole('button', { name: /^save$/i });
    expect(save).toBeDisabled();

    const input = screen.getByLabelText(/personal access token/i);
    await user.type(input, SYNTHETIC_PAT);
    expect(save).toBeEnabled();

    // After the save resolves the query refetches and the masked status is shown.
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    await user.click(save);

    await waitFor(() => expect(saveQoderCredential).toHaveBeenCalledTimes(1));
    expect(saveQoderCredential).toHaveBeenCalledWith(SYNTHETIC_PAT);
    expect(await screen.findByText('Configured')).toBeInTheDocument();
    expect(screen.getByText('****cdef')).toBeInTheDocument();

    // The submitted secret is never echoed or retained client-side.
    expect(screen.queryByDisplayValue(SYNTHETIC_PAT)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(SYNTHETIC_PAT);
  });

  it('renders the masked configured state (masked PAT, timestamp, model) without posting', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    ui();

    expect(await screen.findByText('Configured')).toBeInTheDocument();
    expect(screen.getByText('****cdef')).toBeInTheDocument();
    expect(screen.getByText(/updated/i)).toBeInTheDocument();
    expect(screen.getByText(/model efficient/i)).toBeInTheDocument();
    expect(saveQoderCredential).not.toHaveBeenCalled();
  });

  it('replaces a credential through the same single-PUT path', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    const user = userEvent.setup();
    ui();

    expect(await screen.findByText('Configured')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^replace$/i }));

    const input = screen.getByLabelText(/personal access token/i);
    await user.type(input, 'qcp_test_replacement');
    await user.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(saveQoderCredential).toHaveBeenCalledTimes(1));
    expect(saveQoderCredential).toHaveBeenCalledWith('qcp_test_replacement');
  });

  it('shows the KEY_NOT_CONFIGURED error state and hides the editor', async () => {
    const err = Object.assign(new Error('Service Unavailable'), {
      response: {
        status: 503,
        data: {
          code: 'KEY_NOT_CONFIGURED',
          message:
            'Runtime credential encryption is not configured (PACK_CREDENTIAL_KEY is missing); this store does not accept the development fallback.',
        },
      },
    });
    vi.mocked(getQoderCredential).mockRejectedValue(err);
    ui();

    expect(await screen.findByText(/PACK_CREDENTIAL_KEY is missing/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument();
  });

  it('surfaces a failed save once and keeps the typed PAT out of the page', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(UNCONFIGURED);
    const err = Object.assign(new Error('Service Unavailable'), {
      response: {
        status: 503,
        data: { code: 'KEY_NOT_CONFIGURED', message: 'Runtime credential encryption is not configured.' },
      },
    });
    vi.mocked(saveQoderCredential).mockRejectedValue(err);
    const user = userEvent.setup();
    ui();

    await screen.findByText('Not configured');
    await user.type(screen.getByLabelText(/personal access token/i), SYNTHETIC_PAT);
    await user.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(saveQoderCredential).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/encryption is not configured\./)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(SYNTHETIC_PAT);
  });

  it('requires confirmation before removing the credential', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    const user = userEvent.setup();
    ui();

    await screen.findByText('Configured');
    await user.click(screen.getByRole('button', { name: /remove/i }));
    expect(deleteQoderCredential).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: /remove qoder credential/i })).toBeInTheDocument();

    // Cancel clears the gate without mutating.
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(deleteQoderCredential).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog', { name: /remove qoder credential/i })).not.toBeInTheDocument();

    // Confirm fires the DELETE exactly once; the refetch shows the default state.
    vi.mocked(getQoderCredential).mockResolvedValue(UNCONFIGURED);
    await user.click(screen.getByRole('button', { name: /remove/i }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(deleteQoderCredential).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Not configured')).toBeInTheDocument();
  });

  it('runs the bounded probe on demand and renders its success result', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    vi.mocked(testQoderCredential).mockResolvedValue({
      success: true,
      model: 'efficient',
      billable: false,
      costNote: COST_NOTE,
    });
    const user = userEvent.setup();
    ui();

    await screen.findByText('Configured');
    expect(testQoderCredential).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: /test credential/i }));

    await waitFor(() => expect(testQoderCredential).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/no billable inference/)).toBeInTheDocument();
    expect(screen.getByText(/probe passed/i)).toBeInTheDocument();
  });

  it('renders the probe failure message (NOT_CONFIGURED) verbatim', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    vi.mocked(testQoderCredential).mockResolvedValue({
      success: false,
      reason: 'NOT_CONFIGURED',
      model: 'efficient',
      billable: false,
      costNote: COST_NOTE,
      message: 'No usable credential is configured for provider qoder; save a PAT and retry.',
    });
    const user = userEvent.setup();
    ui();

    await screen.findByText('Configured');
    await user.click(screen.getByRole('button', { name: /test credential/i }));

    expect(await screen.findByText(/save a PAT and retry/)).toBeInTheDocument();
  });
});

describe('QoderCredentialCard separate provider states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(saveQoderCredential).mockResolvedValue(CONFIGURED);
    vi.mocked(deleteQoderCredential).mockResolvedValue(undefined);
  });

  it('does not claim readiness from a configured PAT alone (service unreachable)', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    vi.mocked(getAdkProviderHealth).mockResolvedValue({ providerId: 'qoder', healthy: false });
    ui();

    // Credential state and sandbox-service state are rendered separately.
    expect(await screen.findByText('Configured')).toBeInTheDocument();
    expect(screen.getByText('Unreachable')).toBeInTheDocument();
    expect(screen.getByText('Blocked')).toBeInTheDocument();
    expect(screen.getByText(/sandbox service unreachable/i)).toBeInTheDocument();
  });

  it('blocks readiness with an explicit reason when no credential is stored', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(UNCONFIGURED);
    vi.mocked(getAdkProviderHealth).mockResolvedValue({ providerId: 'qoder', healthy: true });
    ui();

    expect(await screen.findByText('Not configured')).toBeInTheDocument();
    expect(screen.getByText('Healthy')).toBeInTheDocument();
    expect(screen.getByText('Blocked')).toBeInTheDocument();
    expect(screen.getByText(/no credential saved/i)).toBeInTheDocument();
  });

  it('reports ready only when the sandbox service is healthy and a credential is configured', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(CONFIGURED);
    vi.mocked(getAdkProviderHealth).mockResolvedValue({ providerId: 'qoder', healthy: true });
    ui();

    expect(await screen.findByText('Ready')).toBeInTheDocument();
    expect(screen.getByText('Healthy')).toBeInTheDocument();
    expect(screen.getByText('Configured')).toBeInTheDocument();
  });

  it('reports the provider as not registered when the health API 404s (B6 not shipped)', async () => {
    vi.mocked(getQoderCredential).mockResolvedValue(UNCONFIGURED);
    vi.mocked(getAdkProviderHealth).mockRejectedValue(
      Object.assign(new Error('Not Found'), { response: { status: 404 } }),
    );
    ui();

    expect(await screen.findByText('Not registered')).toBeInTheDocument();
    expect(screen.getByText('Unknown')).toBeInTheDocument();
  });
});
