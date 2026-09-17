import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ProvidersPage } from '../ProvidersPage';
import type { AdkProviderInfo } from '../../types';

vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
  getAdkProviderHealth: vi.fn(),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../api/qoderCredential', () => ({
  getQoderCredential: vi.fn().mockResolvedValue({
    providerId: 'qoder',
    configured: false,
    patMasked: null,
    updatedAt: null,
    model: 'auto',
  }),
  saveQoderCredential: vi.fn().mockResolvedValue({}),
  deleteQoderCredential: vi.fn().mockResolvedValue(undefined),
  testQoderCredential: vi.fn().mockResolvedValue({}),
}));

import { listAdkProviders, getAdkProviderHealth } from '../../api/adk';

const PROVIDERS: AdkProviderInfo[] = [
  { id: 'langchain', displayName: 'LangChain ADK', supportsTaskExecution: false, isDefault: false },
  { id: 'opencode', displayName: 'OpenCode', supportsTaskExecution: true, isDefault: true },
];

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ProvidersPage />
    </QueryClientProvider>,
  );
}

/**
 * Guards the contract the Playwright spec e2e/adk-providers.spec.ts relies on:
 * the FIRST .data-table is the provider inventory, exactly one row carries the
 * `Default` type-badge, the Per-Agent Backends .card stays, and the B9 qoder
 * credential card is additive.
 */
describe('ProvidersPage contract (e2e/adk-providers.spec.ts guard)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listAdkProviders).mockResolvedValue(PROVIDERS);
    vi.mocked(getAdkProviderHealth).mockImplementation(async (id: string) => ({ providerId: id, healthy: true }));
  });

  it('keeps the provider inventory + Per-Agent Backends structure and adds the qoder card', async () => {
    const { container } = ui();

    expect(await screen.findByText('OpenCode')).toBeInTheDocument();

    const tables = container.querySelectorAll('table.data-table');
    expect(tables.length).toBeGreaterThan(0);
    expect(tables[0].textContent).toContain('OpenCode');
    expect(tables[0].textContent).toContain('LangChain ADK');

    const defaultBadges = [...container.querySelectorAll('span.type-badge')].filter(
      (b) => b.textContent === 'Default',
    );
    expect(defaultBadges).toHaveLength(1);
    expect(defaultBadges[0].closest('tr')?.textContent).toContain('opencode');

    expect(screen.getByText('Per-Agent Backends')).toBeInTheDocument();
    expect(await screen.findByTestId('qoder-credential-card')).toBeInTheDocument();
  });
});
