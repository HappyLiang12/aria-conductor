import { describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TopBar } from '../TopBar';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('../../api/dashboard', () => ({
  getSummary: vi.fn().mockResolvedValue({
    activeAgents: 3, healthyAgents: 3, degradedAgents: 1,
    runningRuns: 0, pendingApprovals: 0, totalTokensBurned: 0,
  }),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn().mockResolvedValue([{ id: 'opencode', isDefault: true }]),
  getAdkProviderHealth: vi.fn().mockResolvedValue({ providerId: 'opencode', healthy: false }),
}));

function renderTopBar() {
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
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Providers Unavailable'));
    expect(document.body.textContent).not.toContain('System Healthy');
  });

  it('does not claim the agent roster is Online', async () => {
    renderTopBar();
    await waitFor(() => expect(document.body.textContent).toContain('Agents'));
    expect(document.body.textContent).not.toContain('Agents Online');
  });
});
