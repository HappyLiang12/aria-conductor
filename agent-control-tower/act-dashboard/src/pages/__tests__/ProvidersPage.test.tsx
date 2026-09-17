import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
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

const QODER: AdkProviderInfo = {
  id: 'qoder',
  displayName: 'Qoder',
  supportsTaskExecution: true,
  isDefault: false,
};

/**
 * The `['adk-provider-health','qoder']` key is shared by the inventory table
 * (`useQueries`) and the credential card. query-core takes the retry policy from
 * the observer that triggers the fetch (`queryObserver.js:179` →
 * `query.js:285`), so an option present on only one side is order-dependent: the
 * table's observer re-fetches the key once the provider list resolves. This test
 * runs with a client that permits one 0 ms retry and asserts the shared key
 * settles with exactly one probe per observer.
 */
describe('ProvidersPage qoder provider-health retry policy', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listAdkProviders).mockResolvedValue([...PROVIDERS, QODER]);
    vi.mocked(getAdkProviderHealth).mockImplementation(async (id: string) => {
      if (id === 'qoder') {
        throw Object.assign(new Error('Not Found'), { response: { status: 404 } });
      }
      return { providerId: id, healthy: true };
    });
  });

  it('settles the shared qoder health key without an inherited retry', async () => {
    // Fake timers: time only advances where this test advances it, so "a 0 ms
    // retry scheduled on failure would have fired by now" is deterministic
    // instead of a measured real-time window (the previous 400 ms window was
    // machine-dependent).
    vi.useFakeTimers();
    try {
      const qc = new QueryClient({ defaultOptions: { queries: { retry: 1, retryDelay: 0 } } });
      render(
        <QueryClientProvider client={qc}>
          <ProvidersPage />
        </QueryClientProvider>,
      );

      // Let both observers (the card at mount, the table once the provider list
      // resolves) probe the shared key; a retry scheduled with 0 delay would be
      // queued on the fake clock here. The refetch wipes the error state while
      // it runs, hence the terminal state is asserted after it settles.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      // Past any 0 ms retry; nothing on this page runs on an interval.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_000);
      });
      expect(screen.getByText('Not registered')).toBeInTheDocument();

      const qoderCalls = vi.mocked(getAdkProviderHealth).mock.calls.filter(([id]) => id === 'qoder');
      // Exactly two probes: the card's mount probe and the table's probe once
      // the provider list resolved — a retry from any observer makes it three.
      expect(qoderCalls).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
