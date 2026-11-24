import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SidebarProvider } from '@/components/ui/sidebar';
import { ShellTopBar } from '../ShellTopBar';
import { WebSocketContext } from '@/components/Layout';
import { getSummary } from '@/api/dashboard';
import { getAdkProviderHealth, listAdkProviders } from '@/api/adk';
import { listAgents } from '@/api/agents';
import type { AdkProviderHealth, AdkProviderInfo, DashboardSummary } from '@/types';

vi.mock('@/api/dashboard', () => ({
  getSummary: vi.fn(),
}));
vi.mock('@/api/adk', () => ({
  listAdkProviders: vi.fn(),
  getAdkProviderHealth: vi.fn(),
}));
vi.mock('@/api/agents', () => ({ listAgents: vi.fn() }));

const mockedGetSummary = vi.mocked(getSummary);
const mockedListAdkProviders = vi.mocked(listAdkProviders);
const mockedGetAdkProviderHealth = vi.mocked(getAdkProviderHealth);
const mockedListAgents = vi.mocked(listAgents);

const OPENCODE: AdkProviderInfo = {
  id: 'opencode', displayName: 'OpenCode', supportsTaskExecution: true, isDefault: true,
};

const SUMMARY = {
  activeAgents: 4, healthyAgents: 3, degradedAgents: 1,
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
  mockedListAgents.mockResolvedValue([]);
});

function mockSummary(overrides: Partial<DashboardSummary>) {
  mockedGetSummary.mockResolvedValue({
    activeAgents: 4, healthyAgents: 3, degradedAgents: 1,
    runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0, runsWithoutUsage: 0,
    ...overrides,
  } as never);
}

function renderShellTopBar(isConnected?: boolean) {
  // A fresh cache per test: the shared query keys would otherwise leak verdicts
  // from one case into the next.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const bar = (
    <MemoryRouter>
      <SidebarProvider>
        <ShellTopBar />
      </SidebarProvider>
    </MemoryRouter>
  );
  const result = render(
    <QueryClientProvider client={queryClient}>
      {isConnected === undefined ? bar : (
        <WebSocketContext.Provider
          value={{ lastMessage: null, isConnected, subscribe: () => ({ unsubscribe: () => {} }) }}
        >
          {bar}
        </WebSocketContext.Provider>
      )}
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

describe('ShellTopBar health signals', () => {
  it('does not claim System Healthy when every provider is unhealthy', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: false });
    renderShellTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Providers Unavailable'));
    expect(document.body.textContent).not.toContain('System Healthy');
  });

  it('does not claim the agent roster is Online', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: false });
    renderShellTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Agents'));
    expect(document.body.textContent).not.toContain('Agents Online');
  });

  it('reports the healthy count when a provider probe resolves healthy', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: true });
    renderShellTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('1 of 1 Providers Healthy'));
    expect(document.body.textContent).not.toContain('Providers Unavailable');
  });

  it('claims neither health nor failure while a probe is still in flight', async () => {
    mockedGetAdkProviderHealth.mockReturnValue(pendingProbe());
    renderShellTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Checking providers'));
    expect(document.body.textContent).not.toContain('Providers Unavailable');
    expect(document.body.textContent).not.toContain('Providers Healthy');
  });

  it('claims neither health nor failure while the provider list is still pending', async () => {
    mockedListAdkProviders.mockReturnValue(new Promise<AdkProviderInfo[]>(() => {}));
    mockedGetAdkProviderHealth.mockReturnValue(pendingProbe());
    renderShellTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Checking providers'));
    expect(document.body.textContent).not.toContain('Providers Unavailable');
    expect(document.body.textContent).not.toContain('Providers Healthy');
  });
});

describe('ShellTopBar KPI honesty', () => {
  const ids = ['kpi-active-agents', 'kpi-running-runs', 'kpi-pending-approvals', 'kpi-tokens'] as const;

  it('renders an em dash for every KPI while the summary is loading', () => {
    mockedGetSummary.mockReturnValue(new Promise(() => {}) as never);
    renderShellTopBar();
    for (const id of ids) {
      expect(screen.getByTestId(id)).toHaveTextContent('—');
      expect(screen.getByTestId(id)).toHaveAttribute('title', 'Loading');
    }
  });

  it('renders an em dash and Unavailable for every KPI when the summary fails', async () => {
    mockedGetSummary.mockRejectedValue(new Error('summary down'));
    renderShellTopBar();
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
    renderShellTopBar();
    await waitFor(() => {
      for (const id of ids) expect(screen.getByTestId(id)).toHaveTextContent('0');
    });
    expect(screen.getByTestId('kpi-active-agents')).not.toHaveAttribute('title');
  });

  it('keeps last-known values with a stale title after a failed refetch', async () => {
    mockedGetSummary
      .mockResolvedValueOnce(SUMMARY as never)
      .mockRejectedValueOnce(new Error('refetch down'));
    const { queryClient } = renderShellTopBar();
    await waitFor(() => expect(screen.getByTestId('kpi-active-agents')).toHaveTextContent('4'));

    await queryClient.refetchQueries({ queryKey: ['dashboard-summary'] });
    await waitFor(() => {
      expect(screen.getByTestId('kpi-active-agents')).toHaveTextContent('4');
      expect(screen.getByTestId('kpi-active-agents')).toHaveAttribute('title', 'Last known data; refresh failed');
    });
  });

  it('labels the token KPI with retained-record scope, not a daily window', async () => {
    renderShellTopBar();
    await waitFor(() => expect(screen.getByText('Recorded tokens')).toBeInTheDocument());
    expect(screen.queryByText('Tokens Today')).not.toBeInTheDocument();
  });
});

describe('ShellTopBar usage truth', () => {
  it('shows not reported instead of a zero token cost', async () => {
    mockSummary({ totalTokensBurned: 0, runsWithoutUsage: 9 });
    renderShellTopBar();
    await waitFor(() =>
      expect(screen.getByTestId('kpi-tokens')).toHaveTextContent('not reported'),
    );
  });

  it('shows the measured total when at least one run reported usage', async () => {
    mockSummary({ totalTokensBurned: 120, runsWithoutUsage: 1 });
    renderShellTopBar();
    const kpi = screen.getByTestId('kpi-tokens');
    await waitFor(() => expect(kpi).toHaveTextContent('120'));
    expect(kpi).not.toHaveTextContent('not reported');
  });

  it('discloses the silent runs beside a measured total', async () => {
    mockSummary({ totalTokensBurned: 120, runsWithoutUsage: 1 });
    renderShellTopBar();
    const kpi = screen.getByTestId('kpi-tokens');
    await waitFor(() => expect(kpi).toHaveTextContent('120'));
    expect(kpi.getAttribute('title')).toContain('1 run reported no usage');
  });

  it('keeps a real zero when no run was silent about usage', async () => {
    mockSummary({ totalTokensBurned: 0, runsWithoutUsage: 0 });
    renderShellTopBar();
    const kpi = screen.getByTestId('kpi-tokens');
    await waitFor(() => expect(kpi).toHaveTextContent('0'));
    expect(kpi).not.toHaveTextContent('not reported');
  });

  it('names how many runs reported nothing in the token title', async () => {
    mockSummary({ totalTokensBurned: 0, runsWithoutUsage: 9 });
    renderShellTopBar();
    const kpi = screen.getByTestId('kpi-tokens');
    await waitFor(() =>
      expect(kpi).toHaveAttribute('title', '9 runs reported no usage'),
    );
  });
});

describe('ShellTopBar live and runtime signals', () => {
  it('reports the live feed state without inventing a connection', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: true });
    const { unmount } = renderShellTopBar(false);
    expect(screen.getByTestId('ws-indicator')).toHaveTextContent('Live feed offline');
    expect(screen.getByTestId('ws-indicator')).toHaveAccessibleName('Live feed offline');
    unmount();

    renderShellTopBar(true);
    expect(screen.getByTestId('ws-indicator')).toHaveTextContent('Live');
    expect(screen.getByTestId('ws-indicator')).toHaveAccessibleName('Live feed connected');
  });

  it('shows the default core and its declared mode, never an undeclared one', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: true });
    mockedListAdkProviders.mockResolvedValue([
      { ...OPENCODE, executionModes: ['HOST', 'SANDBOX'] },
    ]);
    renderShellTopBar();
    await waitFor(() => expect(screen.getByTestId('runtime-badge')).toHaveTextContent('opencode/HOST'));
  });

  it('reports the fleet runtime from the agents the app already knows', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: true });
    mockedListAgents.mockResolvedValue([
      { id: 'a1', name: 'Aria', healthStatus: 'HEALTHY', adkProvider: 'opencode', executionMode: 'SANDBOX' },
    ] as never);
    renderShellTopBar();
    await waitFor(() => expect(screen.getByTestId('runtime-badge')).toHaveTextContent('opencode/SANDBOX'));
  });

  it('says runtime unknown when no provider catalog has loaded', async () => {
    mockedGetAdkProviderHealth.mockResolvedValue({ providerId: 'opencode', healthy: true });
    mockedListAdkProviders.mockReturnValue(new Promise<AdkProviderInfo[]>(() => {}));
    renderShellTopBar();
    expect(screen.getByTestId('runtime-badge')).toHaveTextContent('runtime unknown');
  });
});

describe('ShellTopBar theme toggle', () => {
  it('keeps the persisted light choice readable and writes it back', async () => {
    const user = userEvent.setup();
    localStorage.setItem('aria-theme', 'light');
    renderShellTopBar();
    const toggle = screen.getByRole('button', { name: 'Toggle theme' });
    await waitFor(() => expect(toggle).toHaveAttribute('aria-pressed', 'true'));
    expect(document.body.classList.contains('light')).toBe(true);

    await user.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-pressed', 'false'));
    expect(localStorage.getItem('aria-theme')).toBe('dark');
    expect(document.body.classList.contains('light')).toBe(false);
    localStorage.removeItem('aria-theme');
  });
});
