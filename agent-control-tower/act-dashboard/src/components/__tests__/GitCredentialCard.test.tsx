import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GitCredentialCard } from '../GitCredentialCard';
import { storePackCredential } from '../../api/packCredentials';

vi.mock('../../api/packCredentials', () => ({
  GIT_PACK_ID: 'pack-git-0001',
  GIT_PACK_CREDENTIAL_KEY: 'GITHUB_TOKEN',
  storePackCredential: vi.fn(),
}));

const mockedStore = vi.mocked(storePackCredential);

const RESTART_NOTICE =
  'Credential stored. The git token is resolved once at backend startup, so restart the backend '
  + 'before the next workflow run uses it.';

const IMMEDIATE_NOTICE = 'Credential stored — new workflow runs resolve it without a restart.';

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <GitCredentialCard />
    </QueryClientProvider>,
  );
}

describe('GitCredentialCard (F8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedStore.mockResolvedValue({ status: 'stored', key: 'GITHUB_TOKEN' });
  });

  it('renders a masked editor for the git pack credential and never reads it back', () => {
    ui();

    const input = screen.getByLabelText('Git credential (GITHUB_TOKEN)');
    expect(input).toHaveAttribute('type', 'password');
    expect(input).toHaveValue('');
    // GET is unsupported (405): the card must not offer a stored-state read it
    // cannot make, and says so.
    expect(screen.getByText(/no read route/i)).toBeInTheDocument();
    expect(screen.queryByText('••••••••')).not.toBeInTheDocument();
  });

  it('stores the GITHUB_TOKEN under pack-git-0001, clears the input and reports the restart requirement', async () => {
    ui();

    await userEvent.type(screen.getByLabelText('Git credential (GITHUB_TOKEN)'), 'ghp_fixture_secret');
    await userEvent.click(screen.getByRole('button', { name: 'Save git credential' }));

    await waitFor(() => expect(mockedStore).toHaveBeenCalledTimes(1));
    expect(mockedStore).toHaveBeenCalledWith('pack-git-0001', {
      key: 'GITHUB_TOKEN',
      value: 'ghp_fixture_secret',
    });
    // Today's backend resolves the token once at startup: the card must say a
    // restart is required, not that the next run already sees it.
    expect(await screen.findByText(RESTART_NOTICE)).toBeInTheDocument();
    expect(screen.queryByText(IMMEDIATE_NOTICE)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Git credential (GITHUB_TOKEN)')).toHaveValue('');
    expect(screen.queryByText('ghp_fixture_secret')).not.toBeInTheDocument();
  });

  it('reports an immediate effect only when the backend says no restart is needed', async () => {
    mockedStore.mockResolvedValue({ status: 'stored', key: 'GITHUB_TOKEN', restartRequired: false });
    ui();

    await userEvent.type(screen.getByLabelText('Git credential (GITHUB_TOKEN)'), 'ghp_fixture_secret');
    await userEvent.click(screen.getByRole('button', { name: 'Save git credential' }));

    expect(await screen.findByText(IMMEDIATE_NOTICE)).toBeInTheDocument();
    expect(screen.queryByText(RESTART_NOTICE)).not.toBeInTheDocument();
  });

  it('surfaces the backend error, keeps no secret in the DOM, and does not claim the store succeeded', async () => {
    mockedStore.mockRejectedValue({
      response: { status: 400, data: { error: 'key and value are required' } },
    });
    ui();

    await userEvent.type(screen.getByLabelText('Git credential (GITHUB_TOKEN)'), 'ghp_fixture_secret');
    await userEvent.click(screen.getByRole('button', { name: 'Save git credential' }));

    expect(await screen.findByText('key and value are required')).toBeInTheDocument();
    expect(screen.queryByText(RESTART_NOTICE)).not.toBeInTheDocument();
    expect(screen.queryByText(/Credential stored/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Git credential (GITHUB_TOKEN)')).toHaveValue('');
    expect(screen.queryByText('ghp_fixture_secret')).not.toBeInTheDocument();
  });

  it('requires a value and makes no call for a blank submission', async () => {
    ui();

    await userEvent.click(screen.getByRole('button', { name: 'Save git credential' }));

    expect(await screen.findByText('A git credential value is required')).toBeInTheDocument();
    expect(mockedStore).not.toHaveBeenCalled();
  });
});
