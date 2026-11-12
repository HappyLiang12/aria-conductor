import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
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
  // No `hidden: true` (Task 37, Item 5): a dialog the accessibility tree hides is
  // not a dialog the operator is looking at, so the probe must not accept one.
  const dialog = await screen.findByRole('dialog', { name: /configure/i });
  expect(dialog).toBeVisible();
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
    // findBy: the fleet read starts when the modal opens (T54 F2 — never from a
    // cache taken before), so the banner arrives asynchronously.
    expect(
      await screen.findByText(/Mixed runtime - opencode\/SANDBOX x1 - qoder\/HOST x2/),
    ).toBeTruthy();

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

  it('hosts the git pack credential editor on the Runtime tab (F8)', async () => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1' })]);
    await renderOpenedModal();

    // F8: the pack's stored git token had no editor anywhere in Configure.
    expect(await screen.findByTestId('git-credential-card')).toBeInTheDocument();
    expect(screen.getByLabelText('Git credential (GITHUB_TOKEN)')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Task 30 / Item 1: PUT /api/v1/agents/runtime is gated server-side now (the
// fleet-runtime write). A refusal has to say who may apply it and what to do.
// ---------------------------------------------------------------------------
describe('ConfigureModal runtime apply refusals (Task 30)', () => {
  beforeEach(() => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' })]);
  });

  async function applyRuntime() {
    await renderOpenedModal();
    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    await userEvent.selectOptions(screen.getByLabelText('Execution mode'), 'HOST');
    await userEvent.click(screen.getByRole('button', { name: /Apply to all agents/i }));
    await userEvent.click(screen.getByRole('button', { name: /^Confirm$/ }));
  }

  it.each([401, 403])('renders the operator-session notice for a %i', async (status) => {
    mockedApply.mockRejectedValue({
      response: { status, data: { message: 'RUNTIME_RAW_SERVER_TEXT' } },
    });

    await applyRuntime();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/applying the fleet runtime is operator-only/i);
    expect(notice).toHaveTextContent(/loopback requests are the operator automatically/i);
    // The old pane printed `Apply failed: <axios message>`; neither half may survive.
    expect(screen.queryByText(/Apply failed/)).not.toBeInTheDocument();
    expect(screen.queryByText(/RUNTIME_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
  });

  it('keeps the ordinary error path for a 500', async () => {
    mockedApply.mockRejectedValue({
      response: { status: 500, data: { message: 'runtime store unavailable' } },
    });

    await applyRuntime();

    expect(await screen.findByText('runtime store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });
});

// Task 33 / Item 2: `ConfirmDialog` is fully controlled and never closed on
// failure, so the refusal notice rendered in the runtime pane sat behind the
// dialog's own `z-index: 2000` overlay — the operator saw an unchanged confirm
// dialog. `getByTestId` on the document cannot see that; these pins scope the
// notice to the Configure dialog and assert the confirm dialog is gone.
describe('ConfigureModal runtime apply refusal visibility (Task 33)', () => {
  beforeEach(() => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' })]);
  });

  async function confirmApply() {
    await renderOpenedModal();
    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    await userEvent.selectOptions(screen.getByLabelText('Execution mode'), 'HOST');
    await userEvent.click(screen.getByRole('button', { name: /Apply to all agents/i }));
    await userEvent.click(screen.getByRole('button', { name: /^Confirm$/ }));
  }

  /** The dialog the operator is left in once the confirmation closes. */
  const configureDialog = () => {
    // Task 37, Item 5: this used `hidden: true`, which can resolve to a dialog
    // the operator cannot see — and then a notice inside it proves nothing.
    // Query the accessible dialog and assert it is really on screen.
    const dialog = screen.getByRole('dialog', { name: /configure/i });
    expect(dialog).toBeVisible();
    return dialog;
  };
  /** The confirmation that used to stay open, empty, over the refusal. */
  const applyConfirmDialog = () =>
    screen.queryByRole('dialog', { name: /apply runtime/i, hidden: true });

  it.each([401, 403])(
    'closes the confirm dialog and puts the %i refusal in the Configure pane',
    async (status) => {
      mockedApply.mockRejectedValue({
        response: { status, data: { message: 'RUNTIME_RAW_SERVER_TEXT' } },
      });

      await confirmApply();

      const notice = await within(configureDialog()).findByTestId('operator-session-notice');
      // In the pane the operator is left in, and really visible there.
      expect(notice).toBeVisible();
      expect(notice).toHaveTextContent(/applying the fleet runtime is operator-only/i);
      expect(applyConfirmDialog()).not.toBeInTheDocument();
    },
  );

  // Positive control: a 500 is a fault, not an authority problem — it keeps the
  // ordinary error path, and the dialog must not strand it behind the overlay.
  it('shows the ordinary 500 error in the pane with the confirm dialog closed', async () => {
    mockedApply.mockRejectedValue({
      response: { status: 500, data: { message: 'runtime store unavailable' } },
    });

    await confirmApply();

    expect(await within(configureDialog()).findByText('runtime store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    expect(applyConfirmDialog()).not.toBeInTheDocument();
  });

  // Positive control: a successful apply closes the confirmation and leaves no
  // notice behind either.
  it('closes the confirm dialog on success and shows no notice', async () => {
    await confirmApply();

    await waitFor(() => expect(applyConfirmDialog()).not.toBeInTheDocument());
    expect(mockedApply).toHaveBeenCalledWith('qoder', 'HOST');
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
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
  const dialog = await screen.findByRole('dialog', { name: /configure/i });
  expect(dialog).toBeVisible();
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

// ---------------------------------------------------------------------------
// T54 F2: the Runtime banner is the operator's only statement of what the
// fleet is configured to run, and it must be the persisted configuration. The
// modal stays mounted while closed, so a read taken once at mount could be
// presented as current truth minutes later (live bar: the banner said
// qoder/HOST while the API said opencode/SANDBOX).
// ---------------------------------------------------------------------------
describe('ConfigureModal fleet banner truthfulness (T54 F2)', () => {
  const fleet = (core: 'qoder' | 'opencode', mode: 'HOST' | 'SANDBOX') => [
    agent({ id: 'a1', adkProvider: core, executionMode: mode }),
    agent({ id: 'a2', adkProvider: core, executionMode: mode }),
  ];

  it('re-reads the fleet on every open instead of showing the read from before', async () => {
    mockedAgents.mockResolvedValueOnce(fleet('qoder', 'HOST'));
    await renderOpenedModal();
    expect(
      await screen.findByText(/Fleet runtime: qoder\/HOST - 2 agents \(uniform\)/),
    ).toBeTruthy();

    // Another seat (or the API) converged the fleet after that read.
    mockedAgents.mockResolvedValue(fleet('opencode', 'SANDBOX'));
    fireEvent.click(screen.getByRole('button', { name: /^Done$/ }));

    window.dispatchEvent(new Event('act:open-configure'));
    expect(
      await screen.findByText(/Fleet runtime: opencode\/SANDBOX - 2 agents \(uniform\)/),
    ).toBeTruthy();
    expect(screen.queryByText(/Fleet runtime: qoder\/HOST/)).not.toBeInTheDocument();
  });

  // Positive control for the test above: a fleet that really does store the
  // documented default (no core, no mode) still reads as opencode/SANDBOX —
  // the fix must not turn a genuine default into an unknown.
  it('still reads a fleet with no stored core or mode as the documented default', async () => {
    mockedAgents.mockResolvedValue([
      agent({ id: 'a1', adkProvider: undefined, executionMode: undefined }),
      agent({ id: 'a2', adkProvider: undefined, executionMode: undefined }),
    ]);
    await renderOpenedModal();

    expect(
      await screen.findByText(/Fleet runtime: opencode\/SANDBOX - 2 agents \(uniform\)/),
    ).toBeTruthy();
  });

  it('says the fleet could not be read instead of claiming there are no agents', async () => {
    mockedAgents.mockRejectedValue(new Error('Network Error'));
    await renderOpenedModal();

    expect(await screen.findByText(/fleet configuration could not be read/i)).toBeInTheDocument();
    expect(screen.queryByText(/No agents yet/i)).not.toBeInTheDocument();

    // The confirmation quotes the same unknown, never a fabricated "none x0".
    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    await userEvent.selectOptions(screen.getByLabelText('Execution mode'), 'HOST');
    await userEvent.click(screen.getByRole('button', { name: /Apply to all agents/i }));
    const confirmation = screen.getByRole('dialog', { name: /apply runtime/i });
    expect(confirmation.textContent).toMatch(/could not be read/i);
    expect(confirmation.textContent).not.toMatch(/none x0/);
    expect(mockedApply).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// T56 F1: a failed RE-read after a successful read. TanStack Query keeps the
// last successful data when a refetch rejects, so `agents !== undefined` cannot
// tell "read now" from "read once, then failed": the pane painted that cached
// composition in the uniform (green) banner and quoted it in the confirm dialog
// as the current fleet. The last read must be labelled as the last read, and
// the confirmation must not present it as current.
// ---------------------------------------------------------------------------
describe('ConfigureModal fleet re-read truthfulness (T56 F1)', () => {
  const fleet = (core: 'qoder' | 'opencode', mode: 'HOST' | 'SANDBOX') => [
    agent({ id: 'a1', adkProvider: core, executionMode: mode }),
    agent({ id: 'a2', adkProvider: core, executionMode: mode }),
  ];

  async function choosePair() {
    await userEvent.selectOptions(screen.getByLabelText('Core'), 'qoder');
    await userEvent.selectOptions(screen.getByLabelText('Execution mode'), 'HOST');
  }

  it('withdraws the current-composition claim when the re-read fails', async () => {
    mockedAgents.mockResolvedValueOnce(fleet('qoder', 'HOST'));
    await renderOpenedModal();
    expect(
      await screen.findByText(/Fleet runtime: qoder\/HOST - 2 agents \(uniform\)/),
    ).toBeInTheDocument();

    // The fleet changed under us and the fresh read rejects. The query still
    // carries the data from the successful read.
    mockedAgents.mockRejectedValue(new Error('Network Error'));
    fireEvent.click(screen.getByRole('button', { name: /^Done$/ }));
    window.dispatchEvent(new Event('act:open-configure'));

    const banner = await screen.findByText(/could not be re-read/i);
    // The cached value is still available, but only as the last known one.
    expect(banner).toHaveTextContent(/last known/i);
    expect(banner).toHaveTextContent(/qoder\/HOST x2/);
    expect(banner).toHaveTextContent(/not claim it is current|may be out of date/i);
    // The uniform claim (the green banner's wording) is gone.
    expect(
      screen.queryByText(/Fleet runtime: qoder\/HOST - 2 agents \(uniform\)/),
    ).not.toBeInTheDocument();

    // The confirmation can never quote the cached read as the current fleet.
    await choosePair();
    await userEvent.click(screen.getByRole('button', { name: /Apply to all agents/i }));
    const confirmation = screen.getByRole('dialog', { name: /apply runtime/i });
    expect(confirmation.textContent).toMatch(/could not be re-read/i);
    expect(confirmation.textContent).toMatch(/last known/i);
    expect(confirmation.textContent).not.toMatch(/Current composition: qoder\/HOST x2/);
    expect(mockedApply).not.toHaveBeenCalled();
  });

  // The other side of the same window: nothing was ever read.
  it('says the fleet could not be read when the first read fails', async () => {
    mockedAgents.mockRejectedValue(new Error('Network Error'));
    await renderOpenedModal();

    const banner = await screen.findByText(/fleet configuration could not be read/i);
    expect(banner).toHaveTextContent(/composition is unknown/i);
    expect(screen.queryByText(/Fleet runtime:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No agents yet/i)).not.toBeInTheDocument();
  });

  // Positive control: a re-read that succeeds still states the real composition
  // and quotes it in the confirmation — the fix must not turn every re-open
  // into "stale".
  it('still states the composition when the re-read succeeds', async () => {
    mockedAgents.mockResolvedValueOnce(fleet('qoder', 'HOST'));
    await renderOpenedModal();
    await screen.findByText(/Fleet runtime: qoder\/HOST - 2 agents \(uniform\)/);

    mockedAgents.mockResolvedValue(fleet('opencode', 'SANDBOX'));
    fireEvent.click(screen.getByRole('button', { name: /^Done$/ }));
    window.dispatchEvent(new Event('act:open-configure'));

    expect(
      await screen.findByText(/Fleet runtime: opencode\/SANDBOX - 2 agents \(uniform\)/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/could not be re-read/i)).not.toBeInTheDocument();

    await choosePair();
    await userEvent.click(screen.getByRole('button', { name: /Apply to all agents/i }));
    const confirmation = screen.getByRole('dialog', { name: /apply runtime/i });
    expect(confirmation.textContent).toMatch(/Current composition: opencode\/SANDBOX x2/);
  });
});
