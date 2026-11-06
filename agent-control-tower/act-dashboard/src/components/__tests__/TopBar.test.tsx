import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TopBar } from '../TopBar';
import { getAdkProviderHealth, listAdkProviders } from '../../api/adk';
import type { AdkProviderHealth, AdkProviderInfo } from '../../types';

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({
    activeAgents: 3, healthyAgents: 3, degradedAgents: 1,
    runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0,
  }),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
  getAdkProviderHealth: vi.fn(),
}));

const mockedListAdkProviders = vi.mocked(listAdkProviders);
const mockedGetAdkProviderHealth = vi.mocked(getAdkProviderHealth);

const OPENCODE: AdkProviderInfo = {
  id: 'opencode', displayName: 'OpenCode', supportsTaskExecution: true, isDefault: true,
};

/** A probe that never settles: the honest badge must not guess while it is in flight. */
function pendingProbe(): Promise<AdkProviderHealth> {
  return new Promise<AdkProviderHealth>(() => {});
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedListAdkProviders.mockResolvedValue([OPENCODE]);
});

function renderTopBar() {
  // A fresh cache per test: the shared query keys would otherwise leak verdicts
  // from one case into the next.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TopBar health signals', () => {
  it('does not claim System Healthy when every provider is unhealthy', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: false });
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Providers Unavailable'));
    expect(document.body.textContent).not.toContain('System Healthy');
  });

  it('does not claim the agent roster is Online', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: false });
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Agents'));
    expect(document.body.textContent).not.toContain('Agents Online');
  });

  it('reports the healthy count when a provider probe resolves healthy', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: true });
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('1 of 1 Providers Healthy'));
    expect(document.body.textContent).not.toContain('Providers Unavailable');
  });

  it('claims neither health nor failure while a probe is still in flight', async () => {
    mockedGetAdkProviderHealth.mockReturnValue(pendingProbe());
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Checking providers'));
    expect(document.body.textContent).not.toContain('Providers Unavailable');
    expect(document.body.textContent).not.toContain('Providers Healthy');
  });

  it('claims neither health nor failure while the provider list is still pending', async () => {
    mockedListAdkProviders.mockReturnValue(new Promise<AdkProviderInfo[]>(() => {}));
    mockedGetAdkProviderHealth.mockReturnValue(pendingProbe());
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Checking providers'));
    expect(document.body.textContent).not.toContain('Providers Unavailable');
    expect(document.body.textContent).not.toContain('Providers Healthy');
  });
});
