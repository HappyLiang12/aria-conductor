import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigureModal } from '../ConfigureModal';
import { listAgents, applyFleetRuntime } from '../../api/agents';
import { listAdkProviders } from '../../api/adk';
import { listSkills } from '../../api/skills';
import type { Agent, AdkProviderInfo } from '../../types';

vi.mock('../../api/agents', async () => ({
  ...(await vi.importActual<typeof import('../../api/agents')>('../../api/agents')),
  listAgents: vi.fn(),
  applyFleetRuntime: vi.fn(),
}));
vi.mock('../../api/adk', () => ({
  listAdkProviders: vi.fn(),
}));
vi.mock('../../api/skills', () => ({
  listSkills: vi.fn(),
}));
vi.mock('../../api/systemConfig', () => ({
  listConfig: vi.fn().mockResolvedValue([]),
  updateConfig: vi.fn(),
  resetConfig: vi.fn(),
}));

const mockedAgents = vi.mocked(listAgents);
const mockedApply = vi.mocked(applyFleetRuntime);
const mockedProviders = vi.mocked(listAdkProviders);
const mockedSkills = vi.mocked(listSkills);

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

    // findBy: the agents query resolves asynchronously (slower under coverage).
    expect(
      await screen.findByText(/Fleet runtime: qoder\/HOST - 2 agents \(uniform\)/),
    ).toBeTruthy();
  });

  it('lists only the selected core\u2019s declared modes', async () => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1' })]);
    mockedProviders.mockResolvedValue([
      { id: 'qoder', displayName: 'Qoder', supportsTaskExecution: true, isDefault: true, executionModes: ['HOST'] },
    ]);
    await renderOpenedModal();

    // findBy: the providers query resolves asynchronously (slower under coverage).
    await screen.findByText('Qoder (qoder)');
    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    const modeSelect = screen.getByLabelText('Execution mode') as HTMLSelectElement;
    const optionTexts = Array.from(modeSelect.options).map((o) => o.text);
    expect(optionTexts).toEqual(['Select a mode...', 'Host']);
  });
});

// ---------------------------------------------------------------------------
// Task 12: Configure states what the build can actually enforce. Skills are a
// read-only catalog (no invented permission model); gates stay visible as an
// inert reference; Runtime / LLM / System Config keep their real mutations.
// ---------------------------------------------------------------------------

async function renderTab(tab: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ConfigureModal initialTab={tab} />
    </QueryClientProvider>,
  );
  window.dispatchEvent(new CustomEvent('act:open-configure', { detail: { tab } }));
  await screen.findByRole('dialog', { hidden: true });
}

describe('ConfigureModal honesty (Task 12)', () => {
  beforeEach(() => {
    mockedAgents.mockResolvedValue([]);
    mockedSkills.mockResolvedValue([
      { id: 's-1', name: 'browser-use', category: 'Web', description: 'Drive a browser' },
    ] as never);
  });

  it('renders skills as a read-only catalog and keeps System Config', async () => {
    await renderTab('skills');

    expect(await screen.findByText('browser-use')).toBeInTheDocument();
    expect(
      screen.getByText('Read-only catalog. No skill permission is editable or enforced in this build.'),
    ).toBeInTheDocument();
    // No permission column, select or static RO/USE/RW value may exist.
    expect(screen.queryByRole('combobox', { name: /permission/i })).not.toBeInTheDocument();
    expect(screen.queryByText('USE')).not.toBeInTheDocument();
    expect(screen.getByText('1 skills in the read-only catalog')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reset to defaults/i })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /system config/i })).toBeInTheDocument();
  });

  it('keeps Gates present but disabled with an honest reference note', async () => {
    await renderTab('gates');

    expect(screen.getByText(/Reference only — gates are not enforced/)).toBeInTheDocument();
    const switches = screen.getAllByRole('switch');
    expect(switches.length).toBeGreaterThan(0);
    switches.forEach((s) => expect(s).toHaveAttribute('aria-disabled', 'true'));

    const before = switches.map((s) => s.getAttribute('aria-checked'));
    fireEvent.click(switches[0]);
    fireEvent.keyDown(switches[0], { key: 'Enter' });
    expect(switches.map((s) => s.getAttribute('aria-checked'))).toEqual(before);

    screen.getAllByRole('combobox').forEach((c) => expect(c).toBeDisabled());
    screen.getAllByRole('spinbutton').forEach((i) => expect(i).toBeDisabled());
    expect(screen.queryByRole('button', { name: /reset to defaults/i })).not.toBeInTheDocument();

    // Row copy must not claim runtime behaviour the build does not enforce.
    expect(screen.queryByText(/Human approval required before work flows on/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Blocks until approved/)).not.toBeInTheDocument();
    expect(screen.getAllByText(/Policy target:/).length).toBeGreaterThan(0);
  });

  it('keeps the real apply control on Runtime and offers no no-op Reset', async () => {
    await renderTab('runtime');

    expect(await screen.findByRole('button', { name: /apply to all agents/i })).toBeInTheDocument();
    // There is no editable local policy state, so a Reset button could only lie.
    expect(screen.queryByRole('button', { name: /reset to defaults/i })).not.toBeInTheDocument();
  });

  it('keeps the System Config panel and its per-key save/reset controls', async () => {
    await renderTab('config');

    expect((await screen.findAllByTitle(/^Save /)).length).toBeGreaterThan(0);
    expect(screen.getAllByTitle('Reset to default').length).toBeGreaterThan(0);
  });
});
