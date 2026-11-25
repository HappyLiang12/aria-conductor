import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import DelegationTree from '../DelegationTree';

vi.mock('../../api/runs', () => ({
  getRunToolCalls: vi.fn(),
}));

import { getRunToolCalls } from '../../api/runs';

const getToolCalls = getRunToolCalls as Mock;

function renderTree(agentName?: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <DelegationTree runId="run-1" agentName={agentName} />
    </QueryClientProvider>,
  );
}

describe('DelegationTree', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // Wave 2 D2: the delegation panel is a token Card surface; the
  // `.delegation-tree` hook and the worker copy stay unchanged.
  it('renders the orchestrator and its delegated workers on a token card', async () => {
    getToolCalls.mockResolvedValue([
      {
        id: 't1',
        toolName: 'create_agent',
        arguments: JSON.stringify({ role: 'qa' }),
        status: 'COMPLETED',
      },
      {
        id: 't2',
        toolName: 'run_agent',
        arguments: JSON.stringify({ agentId: 'abcdef1234' }),
        status: 'RUNNING',
      },
    ]);
    const { container } = renderTree('Aria');

    expect(await screen.findByText(/created worker · qa/)).toBeInTheDocument();
    expect(screen.getByText('Aria')).toBeInTheDocument();
    expect(screen.getByText(/ran agent · abcdef12/)).toBeInTheDocument();

    const tree = container.querySelector('.delegation-tree') as HTMLElement;
    expect(tree).not.toBeNull();
    expect(tree).toHaveAttribute('data-slot', 'card');
    expect(tree).toHaveTextContent('Delegation');
  });

  it('renders nothing when the run never delegated', async () => {
    getToolCalls.mockResolvedValue([
      { id: 't3', toolName: 'write_file', arguments: '{}', status: 'COMPLETED' },
    ]);
    const { container } = renderTree();

    await waitFor(() => expect(getToolCalls).toHaveBeenCalled());
    expect(container.querySelector('.delegation-tree')).toBeNull();
    expect(screen.queryByText('Delegation')).not.toBeInTheDocument();
  });
});
