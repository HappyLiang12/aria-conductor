import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CrewPage from '../CrewPage';

vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
  createAgent: vi.fn().mockResolvedValue({ id: 'a-qoder' }),
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
  listAdkProviders: vi.fn(),
}));
vi.mock('../../components/AgentCard', () => ({
  AgentCard: () => <div data-testid="agent-card" />,
}));
vi.mock('../../components/AgentCatalog', () => ({
  AgentCatalog: () => <div data-testid="agent-catalog" />,
}));
vi.mock('../../components/ManageToolsDialog', () => ({
  ManageToolsDialog: () => null,
}));

import { listAdkProviders } from '../../api/adk';
import { createAgent, getTemplates } from '../../api/agents';
import type { AdkProviderInfo, AgentTemplate } from '../../types';

const PROVIDERS: AdkProviderInfo[] = [
  { id: 'langchain', displayName: 'LangChain ADK', supportsTaskExecution: false, isDefault: false },
  { id: 'opencode', displayName: 'OpenCode', supportsTaskExecution: true, isDefault: true },
  { id: 'qoder', displayName: 'Qoder', supportsTaskExecution: true, isDefault: false },
];

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CrewPage />
    </QueryClientProvider>,
  );
}

async function openAddAgent() {
  fireEvent.click(screen.getByRole('button', { name: '+ Add Agent' }));
  const select = await screen.findByLabelText('ADK Provider');
  await waitFor(() => expect(select.querySelectorAll('option').length).toBeGreaterThan(1));
  return select as HTMLSelectElement;
}

describe('CrewPage ADK provider selector (B9)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listAdkProviders).mockResolvedValue(PROVIDERS);
  });

  it('renders qoder from the providers API without changing the existing defaults', async () => {
    ui();
    const select = await openAddAgent();

    const values = [...select.querySelectorAll('option')].map((o) => (o as HTMLOptionElement).value);
    expect(values).toEqual(['langchain', 'opencode', 'qoder']);
    const labels = [...select.querySelectorAll('option')].map((o) => o.textContent);
    expect(labels).toContain('Qoder');

    // B9 adds no default flip. Both lines below pin PRE-EXISTING behavior, not a
    // B9 contract: `CrewPage` has always started a fresh agent form on
    // 'langchain' (the `select.value` expectation below), and this fixture's
    // `isDefault` row has always been 'opencode' (the second expectation, a
    // fixture guard only — it keeps the form's own initial value and the
    // API-marked platform default from silently collapsing into one). A
    // deliberate change of the form's initial default updates only the first
    // expectation; a failure here is not a B9 regression.
    expect(select.value).toBe('langchain');
    expect(PROVIDERS.find((p) => p.isDefault)?.id).toBe('opencode');
  });

  it('creates an agent on qoder when the operator explicitly selects it', async () => {
    const user = userEvent.setup();
    ui();
    const select = await openAddAgent();

    fireEvent.change(select, { target: { value: 'qoder' } });
    expect(select.value).toBe('qoder');

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: 'Qoder Pilot' } });
    await user.click(screen.getByRole('button', { name: /hire agent/i }));

    await waitFor(() => expect(createAgent).toHaveBeenCalledTimes(1));
    expect(createAgent).toHaveBeenCalledWith(expect.objectContaining({ adkProvider: 'qoder' }));
  });
});

/* -------------------------------------------------------------------------- */
/*  UX-4: a role change must not silently reset an explicit provider pick      */
/* -------------------------------------------------------------------------- */

const DEV_TEMPLATE: AgentTemplate = {
  id: 'tpl-dev',
  label: 'Dev Agent',
  agentType: 'ADK',
  role: 'dev',
  model: 'gpt-4o-mini',
  provider: 'openai',
  adkProvider: 'langchain',
  description: 'Developer role preset',
};

describe('CrewPage role change vs ADK provider (UX-4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listAdkProviders).mockResolvedValue(PROVIDERS);
    vi.mocked(getTemplates).mockResolvedValue([DEV_TEMPLATE]);
  });

  it('keeps an explicit qoder pick when a role is chosen afterwards', async () => {
    ui();
    const select = await openAddAgent();

    fireEvent.change(select, { target: { value: 'qoder' } });
    expect(select.value).toBe('qoder');

    const roleSelect = screen.getByLabelText('Role') as HTMLSelectElement;
    await waitFor(() => expect(roleSelect.querySelectorAll('option').length).toBeGreaterThan(0));
    fireEvent.change(roleSelect, { target: { value: 'dev' } });

    // The user touched the provider select: the role's template back-fill
    // (every catalog template pins langchain) must not clobber their choice.
    expect(select.value).toBe('qoder');
  });

  it('back-fills the template provider when a role is chosen before any provider pick', async () => {
    ui();
    const select = await openAddAgent();

    const roleSelect = screen.getByLabelText('Role') as HTMLSelectElement;
    await waitFor(() => expect(roleSelect.querySelectorAll('option').length).toBeGreaterThan(0));
    fireEvent.change(roleSelect, { target: { value: 'dev' } });

    // Untouched provider: the template-driven back-fill applies as today
    // (the catalog pins langchain, so a fresh role pick lands on langchain).
    expect(select.value).toBe('langchain');
  });
});
