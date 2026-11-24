import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WebSocketContext, ConnectionBanner, Layout } from '../Layout';

vi.mock('@/api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({
    activeAgents: 0, healthyAgents: 0, degradedAgents: 0,
    runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0, runsWithoutUsage: 0,
  }),
}));
vi.mock('@/api/adk', () => ({
  listAdkProviders: vi.fn().mockResolvedValue([]),
  getAdkProviderHealth: vi.fn(),
}));
// The overlays are mounted by the shell but belong to their own suites; this
// test only proves the shell composes them alongside a page.
vi.mock('../AriaPanel', () => ({ AriaPanel: () => null }));
vi.mock('../ConfigureModal', () => ({ ConfigureModal: () => null }));
vi.mock('../TaskDrawer', () => ({ TaskDrawer: () => null }));
vi.mock('../AgentDrawer', () => ({ AgentDrawer: () => null }));
vi.mock('../Toast', () => ({ Toast: () => null }));

function renderBanner(isConnected: boolean) {
  const value = {
    lastMessage: null,
    isConnected,
    subscribe: () => ({ unsubscribe: () => {} }),
  };
  const view = render(
    <WebSocketContext.Provider value={value}>
      <ConnectionBanner />
    </WebSocketContext.Provider>,
  );
  const setConnected = (next: boolean) =>
    view.rerender(
      <WebSocketContext.Provider value={{ ...value, isConnected: next }}>
        <ConnectionBanner />
      </WebSocketContext.Provider>,
    );
  return { ...view, setConnected };
}

describe('ConnectionBanner', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('stays hidden for a blip shorter than the grace period', () => {
    const { setConnected } = renderBanner(false);

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.queryByRole('status')).toBeNull();

    setConnected(true);
    act(() => {
      vi.advanceTimersByTime(10000);
    });
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('warns only after three seconds offline', () => {
    renderBanner(false);

    act(() => {
      vi.advanceTimersByTime(2999);
    });
    expect(screen.queryByRole('status')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByRole('status')).toHaveTextContent('Live updates paused — reconnecting…');
  });

  it('clears the warning as soon as the feed reconnects', () => {
    const { setConnected } = renderBanner(false);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.getByRole('status')).toBeInTheDocument();

    setConnected(true);
    expect(screen.queryByRole('status')).toBeNull();
  });
});

function renderShell(subscribe = vi.fn(() => ({ unsubscribe: vi.fn() }))) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <WebSocketContext.Provider value={{ lastMessage: null, isConnected: true, subscribe }}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<p>page body</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </WebSocketContext.Provider>
    </QueryClientProvider>,
  );
  return { ...result, subscribe };
}

describe('Layout shell', () => {
  it('renders the page inside the new sidebar shell with the top bar KPIs', async () => {
    renderShell();

    expect(document.querySelectorAll('.rail-btn[data-view]')).toHaveLength(12);
    expect(document.querySelector('.top-stack')).not.toBeNull();
    expect(screen.getByTestId('kpi-active-agents')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-running-runs')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-pending-approvals')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-tokens')).toBeInTheDocument();

    const page = await screen.findByText('page body');
    // The page mounts inside the content region, not beside the shell.
    expect(page.closest('[data-slot="sidebar-inset"]')).not.toBeNull();
  });

  it('subscribes to ambient events exactly once', async () => {
    const { subscribe } = renderShell();

    await waitFor(() => expect(subscribe).toHaveBeenCalledTimes(1));
  });
});
