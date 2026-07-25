import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import WorkflowStepper from '../WorkflowStepper';

vi.mock('../../api/runs', () => ({
  getRunToolCalls: vi.fn(),
}));

import { getRunToolCalls } from '../../api/runs';

const getToolCalls = getRunToolCalls as Mock;

function renderStepper(runId: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkflowStepper runId={runId} />
    </QueryClientProvider>,
  );
}

describe('WorkflowStepper', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders all seven workflow steps as numbered pending chips', async () => {
    getToolCalls.mockResolvedValue([]);
    renderStepper('run-1');

    const chips = screen.getAllByRole('listitem');
    expect(chips.map((c) => c.textContent)).toEqual([
      '1. Discover',
      '2. Delegate',
      '3. Clone',
      '4. Edit',
      '5. Commit',
      '6. Push',
      '7. Open PR',
    ]);
    expect(getToolCalls).toHaveBeenCalledWith('run-1');
  });

  it('marks steps done based on the tools used in the trajectory', async () => {
    getToolCalls.mockResolvedValue([
      { toolName: 'git_clone' },
      { toolName: 'write_file' },
      { toolName: 'git_commit' },
    ]);
    renderStepper('run-2');

    expect(await screen.findByText('✓ Clone')).toHaveClass('step-chip', 'done');
    expect(screen.getByText('✓ Edit')).toHaveClass('done');
    expect(screen.getByText('✓ Commit')).toHaveClass('done');
    // untouched steps stay numbered and not done
    expect(screen.getByText('1. Discover')).not.toHaveClass('done');
    expect(screen.getByText('6. Push')).not.toHaveClass('done');
  });

  it('any tool of a step group completes that step', async () => {
    getToolCalls.mockResolvedValue([{ toolName: 'query_knowledge' }, { toolName: 'run_agent' }]);
    renderStepper('run-3');

    expect(await screen.findByText('✓ Discover')).toBeInTheDocument();
    expect(screen.getByText('✓ Delegate')).toBeInTheDocument();
    expect(screen.getByText('3. Clone')).toBeInTheDocument();
  });

  it('does not fetch tool calls when runId is empty', () => {
    renderStepper('');
    expect(getToolCalls).not.toHaveBeenCalled();
    // all steps render as pending
    expect(screen.getAllByRole('listitem')).toHaveLength(7);
  });
});
