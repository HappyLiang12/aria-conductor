import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigureModal } from '../ConfigureModal';
import { listAgents, applyFleetRuntime } from '../../api/agents';
import { listAdkProviders } from '../../api/adk';
import type { Agent, AdkProviderInfo } from '../../types';

vi.mock('../../api/agents', async () => ({
  ...(await vi.importActual<typeof import('../../api/agents')>('../../api/agents')),
  listAgents: vi.fn(),
  applyFleetRuntime: vi.fn(),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
}));

const mockedAgents = vi.mocked(listAgents);
const mockedApply = vi.mocked(applyFleetRuntime);
const mockedProviders = vi.mocked(listAdkProviders);

function agent(overrides: Partial<Agent>): Agent {
  return {
    id: overrides.id ?? 'a1',
    name: overrides.name ?? 'Agent',
    description: null,
    agentType: 'NATIVE',
    role: null,
    model: null,
    provider: null,
    adkProvider: 'qoder',
    executionMode: 'HOST',
    workspaceMode: null,
    workspacePath: null,
    workspaceBaseRef: null,
    config: {},
    healthStatus: 'HEALTHY',
    createdAt: '2026-10-11T00:00:00Z',
    ...overrides,
  } as Agent;
}

const providers: AdkProviderInfo[] = [
  { id: 'qoder', displayName: 'Qoder', supportsTaskExecution: true, isDefault: true, executionModes: ['HOST', 'SANDBOX'] },
  { id: 'opencode', displayName: 'OpenCode', supportsTaskExecution: true, isDefault: false, executionModes: ['HOST', 'SANDBOX'] },
];

async function renderOpenedModal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ConfigureModal initialTab="runtime" />
    </QueryClientProvider>,
  );
  // The rail nav opens the modal through this window event.
  window.dispatchEvent(new Event('act:open-configure'));
  // findBy waits through the state flush the raw event dispatch leaves pending.
  await screen.findByRole('dialog', { hidden: true });
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedProviders.mockResolvedValue(providers);
  mockedApply.mockResolvedValue({ runtime: { adkProvider: 'qoder', executionMode: 'HOST' }, updated: 6 });
});

describe('ConfigureModal runtime tab', () => {
  it('shows the mixed composition and applies the chosen pair to all agents', async () => {
    mockedAgents.mockResolvedValue([
      agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' }),
      agent({ id: 'a2', adkProvider: 'qoder', executionMode: 'HOST' }),
      agent({ id: 'a3', adkProvider: 'opencode', executionMode: 'SANDBOX' }),
      agent({ id: 'a4', adkProvider: 'opencode', executionMode: 'SANDBOX', healthStatus: 'RETIRED' }),
    ]);
    await renderOpenedModal();

    // Mixed banner counts live agents only (the retired one is invisible).
    expect(screen.getByText(/Mixed runtime - opencode\/SANDBOX x1 - qoder\/HOST x2/)).toBeTruthy();

    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    await userEvent.selectOptions(screen.getByLabelText('Execution mode'), 'HOST');
    await userEvent.click(screen.getByRole('button', { name: /Apply to all agents/i }));
    // The confirm dialog names the target and the current composition.
    expect(screen.getByText(/Every agent will run/i)).toBeTruthy();
    expect(
      screen.getByText((content) => /opencode\/SANDBOX x1, qoder\/HOST x2/.test(content)),
    ).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: /^Confirm$/ }));

    expect(mockedApply).toHaveBeenCalledWith('qoder', 'HOST');
  });

  it('renders a uniform fleet in the single-pair wording', async () => {
    mockedAgents.mockResolvedValue([
      agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' }),
      agent({ id: 'a2', adkProvider: 'qoder', executionMode: 'HOST' }),
    ]);
    await renderOpenedModal();

    expect(screen.getByText(/Fleet runtime: qoder\/HOST - 2 agents \(uniform\)/)).toBeTruthy();
  });

  it('lists only the selected core\u2019s declared modes', async () => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1' })]);
    mockedProviders.mockResolvedValue([
      { id: 'qoder', displayName: 'Qoder', supportsTaskExecution: true, isDefault: true, executionModes: ['HOST'] },
    ]);
    await renderOpenedModal();

    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    const modeSelect = screen.getByLabelText('Execution mode') as HTMLSelectElement;
    const optionTexts = Array.from(modeSelect.options).map((o) => o.text);
    expect(optionTexts).toEqual(['Select a mode...', 'Host']);
  });
});
