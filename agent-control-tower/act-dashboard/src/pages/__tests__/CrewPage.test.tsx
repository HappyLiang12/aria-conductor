import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CrewPage from '../CrewPage';

vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
  createAgent: vi.fn().mockResolvedValue({ id: 'x' }),
  getTemplates: vi.fn().mockResolvedValue([]),
  getRoleDefaults: vi.fn().mockResolvedValue({ tools: [], skills: [] }),
  setAgentTools: vi.fn().mockResolvedValue({}),
  setAgentSkills: vi.fn().mockResolvedValue({}),
  retireAgent: vi.fn().mockResolvedValue({}),
}));
vi.mock('../../api/dashboard', () => ({
  getAgentTelemetry: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../components/AgentCard', () => ({
  AgentCard: ({ agent, onSelect, selected }: {
    agent: { id: string; name: string };
    onSelect?: () => void;
    selected?: boolean;
  }) => (
    <div data-testid="agent-card">
      {onSelect && (
        <input
          aria-label={`select ${agent.name}`}
          type="checkbox"
          checked={!!selected}
          onChange={onSelect}
        />
      )}
    </div>
  ),
}));
vi.mock('../../components/AgentCatalog', () => ({
  AgentCatalog: () => <div data-testid="agent-catalog" />,
}));
vi.mock('../../components/ManageToolsDialog', () => ({
  ManageToolsDialog: () => null,
}));

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CrewPage />
    </QueryClientProvider>,
  );
}

describe('CrewPage Add Agent dialog (F3 regression)', () => {
  it('exposes no Add Agent dialog to the a11y tree before interaction', () => {
    ui();
    // Previously the closed dialog stayed mounted with role="dialog" and was
    // visible to assistive tech / automation on page load.
    expect(screen.queryByRole('dialog', { name: /Add Agent/ })).not.toBeInTheDocument();
  });

  it('exposes the dialog after clicking + Add Agent', () => {
    ui();
    fireEvent.click(screen.getByRole('button', { name: '+ Add Agent' }));
    expect(screen.getByRole('dialog', { name: /Add Agent/ })).toBeInTheDocument();
  });
});

describe('CrewPage bulk retire (H3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not fire the bulk retire until the operator confirms', async () => {
    const { listAgents, retireAgent } = await import('../../api/agents');
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'a-1', name: 'e2e-agent-1', healthStatus: 'HEALTHY' },
      { id: 'a-2', name: 'SDD BA Agent', healthStatus: 'HEALTHY' },
    ]);
    const { container } = ui();
    await waitFor(() => expect(screen.getAllByTestId('agent-card')).toHaveLength(2));

    const user = userEvent.setup();
    fireEvent.click(screen.getByRole('button', { name: /select leftovers/i }));
    expect(screen.getByText(/1 selected/i)).toBeInTheDocument();

    // The destructive control alone must open the gate, not retire anything.
    await user.click(screen.getByRole('button', { name: /retire selected/i }));
    expect(retireAgent).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: /Retire selected agents/ })).toBeInTheDocument();

    // Backdrop click clears the pending state and mutates nothing.
    fireEvent.click(container.querySelector('.modal-overlay') as Element);
    expect(retireAgent).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog', { name: /Retire selected agents/ })).not.toBeInTheDocument();

    // Escape does the same (shared ConfirmDialog behaviour).
    await user.click(screen.getByRole('button', { name: /retire selected/i }));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(retireAgent).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog', { name: /Retire selected agents/ })).not.toBeInTheDocument();

    // Cancel clears the pending state and mutates nothing.
    await user.click(screen.getByRole('button', { name: /retire selected/i }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(retireAgent).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog', { name: /Retire selected agents/ })).not.toBeInTheDocument();

    // Confirm fires the action exactly once for the whole selected set.
    await user.click(screen.getByRole('button', { name: /retire selected/i }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(retireAgent).toHaveBeenCalledTimes(1));
    expect(retireAgent).toHaveBeenCalledWith('a-1');
    // …and the pending state is cleared on completion.
    expect(screen.queryByRole('dialog', { name: /Retire selected agents/ })).not.toBeInTheDocument();
  });

  it('clears the retire confirmation even when the retire fails', async () => {
    const { listAgents, retireAgent } = await import('../../api/agents');
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'a-1', name: 'e2e-agent-1', healthStatus: 'HEALTHY' },
    ]);
    (retireAgent as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'));
    ui();
    await waitFor(() => expect(screen.getAllByTestId('agent-card')).toHaveLength(1));

    const user = userEvent.setup();
    fireEvent.click(screen.getByRole('button', { name: /select leftovers/i }));
    await user.click(screen.getByRole('button', { name: /retire selected/i }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(retireAgent).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('dialog', { name: /Retire selected agents/ })).not.toBeInTheDocument();
    expect(await screen.findByText(/some retires failed/i)).toBeInTheDocument();
  });

  it('shows the leftover count on the button and a note when none exist', async () => {
    const { listAgents } = await import('../../api/agents');
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'a-2', name: 'SDD BA Agent', healthStatus: 'HEALTHY' },
    ]);
    ui();
    await waitFor(() => expect(screen.getAllByTestId('agent-card')).toHaveLength(1));

    // count badge shows 0 when the crew is clean
    expect(screen.getByRole('button', { name: /select leftovers \(0\)/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /select leftovers/i }));
    // pressing with nothing to select must give visible feedback
    expect(await screen.findByText(/no leftover agents found/i)).toBeInTheDocument();
  });

  it('select-leftovers preset picks e2e/unhealthy agents and retires them', async () => {
    const { listAgents, retireAgent } = await import('../../api/agents');
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'a-1', name: 'e2e-agent-1', healthStatus: 'HEALTHY' },
      { id: 'a-2', name: 'SDD BA Agent', healthStatus: 'HEALTHY' },
      { id: 'a-3', name: 'sick-agent', healthStatus: 'UNHEALTHY' },
    ]);
    ui();
    await waitFor(() => expect(screen.getAllByTestId('agent-card')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: /select leftovers/i }));
    expect(screen.getByText(/2 selected/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /retire selected/i }));
    // Retire is now gated: the confirmation's Confirm fires it for the whole set.
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(retireAgent).toHaveBeenCalledTimes(2));
    expect(retireAgent).toHaveBeenCalledWith('a-1');
    expect(retireAgent).toHaveBeenCalledWith('a-3');
    expect(retireAgent).not.toHaveBeenCalledWith('a-2');
  });
});

