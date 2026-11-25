import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ProvidersPage } from '../ProvidersPage';

vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
  getAdkProviderHealth: vi.fn(),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn(),
}));

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ProvidersPage />
    </QueryClientProvider>,
  );
}

describe('ProvidersPage (Wave 2 B2)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    const { listAdkProviders, getAdkProviderHealth } = await import('../../api/adk');
    const { listAgents } = await import('../../api/agents');
    (listAdkProviders as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 'opencode',
        displayName: 'OpenCode',
        supportsTaskExecution: true,
        isDefault: true,
        executionModes: ['HOST', 'SANDBOX'],
      },
    ]);
    (getAdkProviderHealth as ReturnType<typeof vi.fn>).mockResolvedValue({
      providerId: 'opencode',
      healthy: true,
    });
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 'a-1',
        name: 'Atlas-7',
        adkProvider: 'opencode',
        executionMode: 'SANDBOX',
        healthStatus: 'HEALTHY',
      },
    ]);
  });

  it('renders the provider inventory on a token card surface', async () => {
    ui();

    const heading = await screen.findByRole('heading', { name: 'Agent Providers' });
    expect(heading).toBeInTheDocument();
    const [table] = await screen.findAllByRole('table');
    expect(table.className).toContain('data-table');
    expect(table.closest('[data-slot="card"]')).not.toBeNull();
  });

  it('keeps the per-agent backends block and its stored core/mode', async () => {
    ui();

    const block = await screen.findByText('Per-Agent Backends');
    expect(block.closest('[data-slot="card"]')).not.toBeNull();
    const row = (await screen.findByText('Atlas-7')).closest('tr') as HTMLElement;
    expect(within(row).getByText('opencode')).toBeInTheDocument();
    expect(within(row).getByText('SANDBOX')).toBeInTheDocument();
    expect(await within(row).findByText('Healthy')).toBeInTheDocument();
  });

  it('names an unregistered stored core as unsupported instead of substituting one', async () => {
    const { listAgents } = await import('../../api/agents');
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 'a-legacy',
        name: 'Legacy-1',
        adkProvider: 'langchain',
        executionMode: 'SANDBOX',
        healthStatus: 'HEALTHY',
      },
    ]);
    ui();

    expect(await screen.findByText('Unsupported core: langchain')).toBeInTheDocument();
  });
});
