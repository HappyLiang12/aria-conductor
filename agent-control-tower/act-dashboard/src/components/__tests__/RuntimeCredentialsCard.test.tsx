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
 * The reason the backend serves on `testUnavailableReason` when no probe is
 * wired (T53 F3, `CoreCredentialController.NO_PROBE_REASON`). The card must
 * render THIS text, not invent one.
 */
const BACKEND_SERVED_REASON =
  'Save-time validation is unavailable: the pinned Qoder core only validates the token when it '
  + 'opens an ACP session (session/new), which requires starting a run-owned core. A rejected '
  + 'saved credential is reported on the next run with the marker E_CREDENTIAL_REJECTED.';

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
  testSupported: true,
  maskedSecret: 'fixture-secret-value',
  updatedAt: '2026-09-22T12:00:00Z',
};

/**
 * The exact payload the shipped backend serves for an unconfigured credential:
 * the production wiring has no `CredentialProbe`, so `testSupported` is false
 * and `testUnavailableReason` carries NO_PROBE_REASON (`CoreCredentialController`
 * `74-77`, `110`). It is independent of `configured` — which is why the "save
 * one first" guidance cannot come from the served text.
 */
const UNCONFIGURED: QoderCredentialMetadata = {
  credentialRef: 'qoder:operator',
  coreId: 'qoder',
  environmentVariable: 'QODER_PERSONAL_ACCESS_TOKEN',
  configured: false,
  testSupported: false,
  testUnavailableReason: BACKEND_SERVED_REASON,
  maskedSecret: null,
  updatedAt: null,
};

const COST_DISCLOSURE =
  'One bounded prompt through the pinned core with the configured credential; only '
  + 'provider-reported usage is reported, and unknown usage stays unknown.';

const NOT_WIRED_REASON =
  'The bounded Qoder credential test requires the run-owned core bridge, which is not '
  + 'wired in this component; no model call was made.';

/** The local fallback for a backend older than the T53 contract (no field). */
const NO_PROBE_FALLBACK_REASON =
  'No credential probe is wired in this build: the bounded test needs the run-owned core '
  + 'bridge, so the dashboard cannot make that call. To verify the token, dispatch a Qoder '
  + 'run — a rejected credential fails it with E_CREDENTIAL_REJECTED at session/new.';

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

  // T54 F3: `testSupported:false` means a bad token cannot be caught at save
  // time (the live bar's credential was stored correctly and rejected by the
  // core at session/new). The save outcome must say "stored, not verified" and
  // name the action that verifies it — never imply the token works.
  it('says a stored credential was not validated, and what verifies it', async () => {
    const UNPROBEABLE = {
      ...CONFIGURED,
      testSupported: false,
      testUnavailableReason: BACKEND_SERVED_REASON,
    };
    mockedPut.mockResolvedValue(UNPROBEABLE);
    mockedGet.mockResolvedValue(UNPROBEABLE);
    ui();
    await screen.findByText('••••••••');

    await userEvent.type(screen.getByLabelText('Runtime credential'), 'fixture-secret-value');
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));

    const notice = await screen.findByTestId('credential-save-notice');
    expect(notice).toHaveTextContent(/stored/i);
    expect(notice).toHaveTextContent(/not validated|not verified/i);
    // The backend's own reason, quoted rather than replaced.
    expect(notice).toHaveTextContent(BACKEND_SERVED_REASON);
    expect(notice).toHaveTextContent(/Configure → LLM Providers/);
    expect(notice).toHaveTextContent(/Qoder runtime credential/);
    expect(notice).toHaveTextContent(/dispatch a Qoder run/i);
    expect(notice).toHaveTextContent(/E_CREDENTIAL_REJECTED/);
    // The secret is never echoed, not even in the notice.
    expect(notice.textContent).not.toContain('fixture-secret-value');
    expect(screen.queryByText('fixture-secret-value')).not.toBeInTheDocument();
  });

  // The operator must learn that save-time validation is impossible BEFORE
  // pressing Save, from the reason the backend served — not after a run fails.
  it('shows the backend\'s testUnavailableReason before any save', async () => {
    mockedGet.mockResolvedValue({
      ...CONFIGURED,
      testSupported: false,
      testUnavailableReason: BACKEND_SERVED_REASON,
    });
    ui();

    const reason = await screen.findByTestId('credential-test-unavailable');
    expect(reason).toHaveTextContent(BACKEND_SERVED_REASON);
    expect(screen.getByRole('button', { name: 'Test credential' })).toBeDisabled();
    expect(mockedPut).not.toHaveBeenCalled();
    expect(mockedTest).not.toHaveBeenCalled();
  });

  // Positive control for the reason display: a backend whose probe IS wired
  // serves `testUnavailableReason: null`, so nothing is rendered and the test
  // stays available.
  it('renders no unavailability reason when the backend serves null', async () => {
    mockedGet.mockResolvedValue({ ...CONFIGURED, testSupported: true, testUnavailableReason: null });
    ui();
    await screen.findByText('••••••••');

    expect(screen.queryByTestId('credential-test-unavailable')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Test credential' })).toBeEnabled();
  });

  // Backend older than the T53 contract: the field is absent. The card must
  // still explain the disabled test instead of drawing a bare "not supported".
  it('falls back to a local reason when an older backend omits the field', async () => {
    mockedGet.mockResolvedValue({ ...CONFIGURED, testSupported: false });
    ui();

    const reason = await screen.findByTestId('credential-test-unavailable');
    expect(reason).toHaveTextContent(NO_PROBE_FALLBACK_REASON);
  });

  it('reports an unconfigured credential explicitly', async () => {
    mockedGet.mockResolvedValue(UNCONFIGURED);
    ui();
    expect(await screen.findByText('Not configured')).toBeInTheDocument();
    expect(
      screen.getByText('Qoder runs fail admission until an operator stores the runtime credential.'),
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
    mockedGet.mockResolvedValue({
      ...CONFIGURED,
      testSupported: false,
      testUnavailableReason: BACKEND_SERVED_REASON,
    });
    ui();
    await screen.findByText('••••••••');

    expect(screen.getByRole('button', { name: 'Test credential' })).toBeDisabled();
    expect(screen.getByTestId('credential-test-unavailable')).toHaveTextContent(
      BACKEND_SERVED_REASON,
    );
    expect(mockedTest).not.toHaveBeenCalled();
  });

  // T56 F2: the shipped payload for an unconfigured credential ALSO carries
  // the served probe reason (the production wiring has no probe), and the card
  // used to render it first — hiding the only actionable step. "Save one
  // first" must win over the served reason when nothing is stored; the served
  // reason takes over once a credential exists but cannot be probed (next
  // test), and a wired probe still offers the test (positive control below).
  it('does not offer the bounded test before a credential is stored, and says why', async () => {
    mockedGet.mockResolvedValue(UNCONFIGURED);
    ui();
    await screen.findByText('Not configured');

    expect(screen.getByRole('button', { name: 'Test credential' })).toBeDisabled();
    const reason = screen.getByTestId('credential-test-unavailable');
    expect(reason).toHaveTextContent(NO_STORED_CREDENTIAL_TEST_REASON);
    // The served probe explanation is real, but it is not the next action.
    expect(reason).not.toHaveTextContent(BACKEND_SERVED_REASON);
    expect(reason).not.toHaveTextContent(/session\/new/);
    expect(mockedTest).not.toHaveBeenCalled();
  });

  it('surfaces a read rejection verbatim from the backend', async () => {
    mockedGet.mockRejectedValue({
      response: { status: 401, data: { error: 'Operator credential required' } },
    });
    ui();
    expect(await screen.findByText('Operator credential required')).toBeInTheDocument();
    expect(screen.queryByText('••••••••')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Test credential' })).not.toBeInTheDocument();
  });

  it('surfaces a non-rejection read failure verbatim', async () => {
    mockedGet.mockRejectedValue({
      response: { status: 503, data: { error: 'Credential store unavailable' } },
    });
    ui();
    expect(await screen.findByText('Credential store unavailable')).toBeInTheDocument();
    expect(screen.queryByText('••••••••')).not.toBeInTheDocument();
  });

  it('keeps the retained metadata and controls when a background refetch fails', async () => {
    // Any failure (401, 503, network) leaves the last known mask and the
    // controls usable instead of emptying the card, because nothing refetches
    // it back on its own; the error block shows what happened.
    mockedGet
      .mockResolvedValueOnce(CONFIGURED)
      .mockRejectedValueOnce({ response: { status: 503, data: {} } });
    mockedPut.mockResolvedValue(CONFIGURED);
    ui();
    await screen.findByText('••••••••');

    await userEvent.type(screen.getByLabelText('Runtime credential'), 'replacement-value');
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));

    expect(
      await screen.findByText('Failed to load the Qoder runtime credential.'),
    ).toBeInTheDocument();
    expect(screen.getByText('••••••••')).toBeInTheDocument();
    expect(screen.getByLabelText('Runtime credential')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove credential' })).toBeInTheDocument();
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
