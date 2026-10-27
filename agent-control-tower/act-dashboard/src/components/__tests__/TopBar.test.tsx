import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TopBar } from '../TopBar';
import { getSummary } from '../../api/dashboard';
import { getAdkProviderHealth, listAdkProviders } from '../../api/adk';
import type { AdkProviderHealth, AdkProviderInfo } from '../../types';

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn(),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
  getAdkProviderHealth: vi.fn(),
}));

const mockedGetSummary = vi.mocked(getSummary);
const mockedListAdkProviders = vi.mocked(listAdkProviders);
const mockedGetAdkProviderHealth = vi.mocked(getAdkProviderHealth);

const OPENCODE: AdkProviderInfo = {
  id: 'opencode', displayName: 'OpenCode', supportsTaskExecution: true, isDefault: true,
};

const SUMMARY = {
  activeAgents: 3, healthyAgents: 3, degradedAgents: 1,
  runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0,
};

/** A probe that never settles: the honest badge must not guess while it is in flight. */
function pendingProbe(): Promise<AdkProviderHealth> {
  return new Promise<AdkProviderHealth>(() => {});
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGetSummary.mockResolvedValue(SUMMARY as never);
  mockedListAdkProviders.mockResolvedValue([OPENCODE]);
});

function renderTopBar() {
  // A fresh cache per test: the shared query keys would otherwise leak verdicts
  // from one case into the next.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TopBar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
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

describe('TopBar KPI honesty', () => {
  const ids = ['kpi-active-agents', 'kpi-running-runs', 'kpi-pending-approvals', 'kpi-tokens'] as const;

  it('renders an em dash for every KPI while the summary is loading', () => {
    mockedGetSummary.mockReturnValue(new Promise(() => {}) as never);
    renderTopBar();
    for (const id of ids) {
      expect(screen.getByTestId(id)).toHaveTextContent('—');
      expect(screen.getByTestId(id)).toHaveAttribute('title', 'Loading');
    }
  });

  it('renders an em dash and Unavailable for every KPI when the summary fails', async () => {
    mockedGetSummary.mockRejectedValue(new Error('summary down'));
    renderTopBar();
    await waitFor(() => {
      for (const id of ids) {
        expect(screen.getByTestId(id)).toHaveTextContent('—');
        expect(screen.getByTestId(id)).toHaveAttribute('title', 'Unavailable');
      }
    });
  });

  it('renders a real zero only for an authoritative zero', async () => {
    mockedGetSummary.mockResolvedValue({
      activeAgents: 0, healthyAgents: 0, degradedAgents: 0,
      runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0,
    } as never);
    renderTopBar();
    await waitFor(() => {
      for (const id of ids) expect(screen.getByTestId(id)).toHaveTextContent('0');
    });
    expect(screen.getByTestId('kpi-active-agents')).not.toHaveAttribute('title');
  });

  it('keeps last-known values with a stale title after a failed refetch', async () => {
    mockedGetSummary
      .mockResolvedValueOnce(SUMMARY as never)
      .mockRejectedValueOnce(new Error('refetch down'));
    const { queryClient } = renderTopBar();
    await waitFor(() => expect(screen.getByTestId('kpi-active-agents')).toHaveTextContent('3'));

    await queryClient.refetchQueries({ queryKey: ['dashboard-summary'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-active-agents')).toHaveTextContent('3');
      expect(screen.getByTestId('kpi-active-agents')).toHaveAttribute('title', 'Last known data; refresh failed');
    });
  });

  it('labels the token KPI with retained-record scope, not a daily window', async () => {
    renderTopBar();
    await waitFor(() => expect(screen.getByText('Recorded tokens')).toBeInTheDocument());
    expect(screen.queryByText('Tokens Today')).not.toBeInTheDocument();
  });
});
