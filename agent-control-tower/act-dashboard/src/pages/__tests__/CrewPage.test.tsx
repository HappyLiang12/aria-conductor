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
  getAdkProviderHealth: vi.fn().mockResolvedValue({ providerId: 'opencode', healthy: true }),
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

// ---------------------------------------------------------------------------
// Task 15: governed core + explicit execution mode + Host workspace binding.
// ---------------------------------------------------------------------------

/** The two governed cores as the (post-Task-3) provider inventory serves them. */
const GOVERNED_CORES = [
  {
    id: 'opencode',
    displayName: 'OpenCode',
    supportsTaskExecution: true,
    isDefault: true,
    executionModes: ['HOST', 'SANDBOX'],
  },
  {
    id: 'qoder',
    displayName: 'Qoder',
    supportsTaskExecution: true,
    isDefault: false,
    executionModes: ['HOST', 'SANDBOX'],
  },
];

describe('CrewPage core, mode and Host workspace selection (Task 15)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    const { listAdkProviders, getAdkProviderHealth } = await import('../../api/adk');
    (listAdkProviders as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (getAdkProviderHealth as ReturnType<typeof vi.fn>).mockResolvedValue({
      providerId: 'opencode',
      healthy: true,
    });
    // clearAllMocks keeps implementations; reset per-test so a template seeded
    // by one case never leaks into the next.
    const { getTemplates } = await import('../../api/agents');
    (getTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([]);
  });

  /** Open the Add Agent dialog against the governed core inventory. */
  async function openWithGovernedCores() {
    const { listAdkProviders } = await import('../../api/adk');
    (listAdkProviders as ReturnType<typeof vi.fn>).mockResolvedValue(GOVERNED_CORES);
    ui();
    fireEvent.click(screen.getByRole('button', { name: '+ Add Agent' }));
  }

  it('offers the governed cores with an explicit mode selector and no removed-core fallback', async () => {
    await openWithGovernedCores();

    expect(await screen.findByText('OpenCode')).toBeInTheDocument();
    expect(screen.getByLabelText('Execution mode').textContent).toBe('Sandbox');
    expect(screen.queryByText('LangChain ADK')).not.toBeInTheDocument();

    // The declared default core is applied explicitly (never an invented one).
    await waitFor(() => expect(screen.getByLabelText('Agent core')).toHaveValue('opencode'));
  });

  it('shows an explicit unavailable state instead of a removed-core fallback', async () => {
    const { listAdkProviders } = await import('../../api/adk');
    (listAdkProviders as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    ui();
    fireEvent.click(screen.getByRole('button', { name: '+ Add Agent' }));

    expect(
      await screen.findByText('Agent core inventory unavailable — no governed core was reported.'),
    ).toBeInTheDocument();
    expect(screen.queryByText('LangChain ADK')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hire Agent' })).toBeDisabled();
  });

  it('keeps submission closed while the core inventory is still loading', async () => {
    const { listAdkProviders } = await import('../../api/adk');
    (listAdkProviders as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    const { createAgent } = await import('../../api/agents');
    ui();
    fireEvent.click(screen.getByRole('button', { name: '+ Add Agent' }));
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'No-Core-Yet' } });

    // The inventory is the only core source: until it answers, no payload with
    // an empty `adkProvider` may leave the dialog.
    const hire = screen.getByRole('button', { name: 'Hire Agent' });
    expect(hire).toBeDisabled();
    fireEvent.click(hire);
    expect(createAgent).not.toHaveBeenCalled();
  });

  it('never preselects Host and reveals the Host workspace inputs only for Host', async () => {
    await openWithGovernedCores();
    await screen.findByText('OpenCode');

    expect(screen.getByLabelText('Execution mode').textContent).toBe('Sandbox');
    expect(screen.queryByLabelText('Repository path')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Direct directory')).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Execution mode'));
    await user.click(screen.getByRole('option', { name: 'Host' }));

    expect(screen.getByLabelText('Execution mode').textContent).toBe('Host');
    // Host workspace defaults to a managed worktree over an admitted repository.
    expect(screen.getByLabelText('Host workspace mode')).toHaveValue('WORKTREE');
    expect(screen.getByLabelText('Repository path')).toBeInTheDocument();
    expect(screen.getByLabelText('Base ref (optional)')).toBeInTheDocument();
    // A worktree without a repository path is not submittable.
    expect(screen.getByRole('button', { name: 'Hire Agent' })).toBeDisabled();

    await user.selectOptions(screen.getByLabelText('Host workspace mode'), 'DIRECT');
    // Direct is never inferred, demands an explicit directory and drops base ref.
    expect(screen.getByLabelText('Direct directory')).toBeInTheDocument();
    expect(screen.queryByLabelText('Repository path')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Base ref (optional)')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hire Agent' })).toBeDisabled();
  });

  it('requires an explicit choice for a template carrying a removed core', async () => {
    const { getTemplates } = await import('../../api/agents');
    (getTemplates as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 't-legacy',
        label: 'Legacy LangChain role',
        agentType: 'ADK',
        role: 'dev',
        model: 'gpt-4o-mini',
        provider: 'openai',
        adkProvider: 'langchain',
        description: 'pre-cutover template',
      },
    ]);
    await openWithGovernedCores();
    await screen.findByText('OpenCode');

    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'dev' } });

    expect(screen.getByLabelText('Agent core')).toHaveValue('langchain');
    expect(screen.getByText('Unsupported agent core: langchain')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hire Agent' })).toBeDisabled();
  });

  it('submits the explicit core, mode and Host workspace binding', async () => {
    const { createAgent } = await import('../../api/agents');
    (createAgent as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' });
    await openWithGovernedCores();
    await screen.findByText('OpenCode');
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'Atlas-7');
    await user.click(screen.getByLabelText('Execution mode'));
    await user.click(screen.getByRole('option', { name: 'Host' }));
    await user.type(screen.getByLabelText('Repository path'), '/srv/repos/atlas');
    await user.type(screen.getByLabelText('Base ref (optional)'), 'main');

    await user.click(screen.getByRole('button', { name: 'Hire Agent' }));

    await waitFor(() => expect(createAgent).toHaveBeenCalledTimes(1));
    expect(createAgent).toHaveBeenCalledWith({
      name: 'Atlas-7',
      agentType: 'NATIVE',
      role: 'dev',
      model: undefined,
      provider: 'openai',
      description: undefined,
      adkProvider: 'opencode',
      executionMode: 'HOST',
      workspaceMode: 'WORKTREE',
      workspacePath: '/srv/repos/atlas',
      workspaceBaseRef: 'main',
      config: { maxToolCallRounds: 15 },
    });
  });

  it('sends no workspace binding for a sandbox run', async () => {
    const { createAgent } = await import('../../api/agents');
    (createAgent as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-2' });
    await openWithGovernedCores();
    await screen.findByText('OpenCode');
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'Sandbox-1');
    await user.click(screen.getByRole('button', { name: 'Hire Agent' }));

    await waitFor(() => expect(createAgent).toHaveBeenCalledTimes(1));
    expect(createAgent).toHaveBeenCalledWith({
      name: 'Sandbox-1',
      agentType: 'NATIVE',
      role: 'dev',
      model: undefined,
      provider: 'openai',
      description: undefined,
      adkProvider: 'opencode',
      executionMode: 'SANDBOX',
      config: { maxToolCallRounds: 15 },
    });
  });
});

