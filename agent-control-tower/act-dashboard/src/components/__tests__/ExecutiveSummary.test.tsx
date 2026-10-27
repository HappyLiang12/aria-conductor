import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ExecutiveSummary from '../ExecutiveSummary';
import { getSummary } from '../../api/dashboard';
import { listKanbanItems } from '../../api/kanban';
import { listRuns } from '../../api/runs';
import { listKnowledge } from '../../api/knowledge';
import { listAgents } from '../../api/agents';
import type { Agent, Run } from '../../types';

vi.mock('../../api/dashboard', () => ({ getSummary: vi.fn() }));
vi.mock('../../api/kanban', () => ({ listKanbanItems: vi.fn() }));
vi.mock('../../api/runs', () => ({ listRuns: vi.fn() }));
vi.mock('../../api/knowledge', () => ({ listKnowledge: vi.fn() }));
vi.mock('../../api/agents', () => ({ listAgents: vi.fn() }));
vi.mock('./DrawerContext', () => ({ dispatchOpenTaskDrawer: vi.fn() }));

const mockedAgents = vi.mocked(listAgents);
const mockedRuns = vi.mocked(listRuns);
const mockedSummary = vi.mocked(getSummary);
const mockedKanban = vi.mocked(listKanbanItems);
const mockedKnowledge = vi.mocked(listKnowledge);

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

function run(overrides: Partial<Run>): Run {
  return {
    id: overrides.id ?? 'r1',
    agentId: overrides.agentId ?? 'a1',
    status: 'RUNNING',
    promptSeed: 'seed',
    maxIterations: 10,
    totalTokensUsed: 0,
    iterationCount: 0,
    errorMessage: null,
    finalOutput: null,
    createdAt: '2026-10-11T00:00:00Z',
    completedAt: null,
    coreId: 'qoder',
    executionMode: 'HOST',
    ...overrides,
  } as Run;
}

function renderSummary() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ExecutiveSummary />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedSummary.mockResolvedValue({ activeAgents: 2, degradedAgents: 0, runningRuns: 0, totalTokensBurned: 0 } as never);
  mockedKanban.mockResolvedValue([] as never);
  mockedKnowledge.mockResolvedValue([] as never);
});

describe('ExecutiveSummary runtime card', () => {
  it('shows the uniform runtime in green with an agent count', async () => {
    mockedAgents.mockResolvedValue([
      agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' }),
      agent({ id: 'a2', adkProvider: 'qoder', executionMode: 'HOST' }),
    ]);
    mockedRuns.mockResolvedValue([] as never);
    renderSummary();

    expect(await screen.findByText('qoder/HOST')).toBeTruthy();
    expect(screen.getByText('2 agents')).toBeTruthy();
  });

  it('flags a mixed fleet in amber with the per-pair breakdown', async () => {
    mockedAgents.mockResolvedValue([
      agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' }),
      agent({ id: 'a2', adkProvider: 'opencode', executionMode: 'SANDBOX' }),
    ]);
    mockedRuns.mockResolvedValue([] as never);
    renderSummary();

    expect(await screen.findByText('Mixed')).toBeTruthy();
    expect(screen.getByText(/opencode\/SANDBOX x1, qoder\/HOST x1/)).toBeTruthy();
  });

  it('counts non-terminal runs frozen on a runtime their agent moved off as draining', async () => {
    mockedAgents.mockResolvedValue([
      agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' }),
    ]);
    mockedRuns.mockResolvedValue([
      run({ id: 'r-drain', status: 'WAITING_INPUT', coreId: 'opencode', executionMode: 'SANDBOX' }),
      run({ id: 'r-aligned', status: 'RUNNING', coreId: 'qoder', executionMode: 'HOST' }),
      run({ id: 'r-terminal', status: 'COMPLETED', coreId: 'opencode', executionMode: 'SANDBOX' }),
      run({ id: 'r-legacy', status: 'RUNNING', coreId: null, executionMode: null }),
    ]);
    renderSummary();

    expect(await screen.findByText('qoder/HOST')).toBeTruthy();
    expect(
      screen.getByText((content) => /1 agent/.test(content) && /1 draining/.test(content)),
    ).toBeTruthy();
  });

  it('opens the configure modal on the runtime tab when clicked', async () => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1' })]);
    mockedRuns.mockResolvedValue([] as never);
    renderSummary();

    const dispatched: string[] = [];
    const listener = (e: Event) => dispatched.push((e as CustomEvent).detail?.tab);
    window.addEventListener('act:open-configure', listener);
    await userEvent.click(screen.getByText('Runtime'));
    window.removeEventListener('act:open-configure', listener);

    expect(dispatched).toEqual(['runtime']);
  });
});

describe('ExecutiveSummary honest states', () => {
  it('does not claim health or an empty queue while the summary is loading', () => {
    mockedSummary.mockReturnValue(new Promise(() => {}) as never);
    mockedAgents.mockReturnValue(new Promise(() => {}) as never);
    mockedRuns.mockResolvedValue([] as never);
    renderSummary();

    expect(screen.queryByText('All healthy')).not.toBeInTheDocument();
    expect(screen.queryByText('Nothing pending')).not.toBeInTheDocument();
    expect(screen.queryByText('Waiting on you')).not.toBeInTheDocument();
    expect(screen.getAllByText('Loading').length).toBeGreaterThan(0);
  });

  it('shows Unavailable instead of zero when the summary fails', async () => {
    mockedSummary.mockRejectedValue(new Error('summary down'));
    mockedAgents.mockResolvedValue([agent({ id: 'a1' })]);
    mockedRuns.mockResolvedValue([] as never);
    renderSummary();

    expect((await screen.findAllByText('Unavailable')).length).toBeGreaterThan(0);
    expect(screen.queryByText('All healthy')).not.toBeInTheDocument();
    expect(screen.queryByText('Cumulative spend')).not.toBeInTheDocument();
  });

  it('labels retained-record token scope without a spend claim', async () => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1' })]);
    mockedRuns.mockResolvedValue([] as never);
    renderSummary();

    expect(await screen.findByText('Recorded tokens')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByText('Cumulative recorded input + output over retained records')).toBeInTheDocument(),
    );
    expect(screen.queryByText('Tokens Used')).not.toBeInTheDocument();
  });

  it('marks the runtime and the run count as last-known after a failed refresh', async () => {
    mockedAgents.mockResolvedValue([agent({ id: 'a1', adkProvider: 'qoder', executionMode: 'HOST' })]);
    mockedRuns.mockResolvedValue([run({ id: 'r1' })] as never);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ExecutiveSummary />
      </QueryClientProvider>,
    );
    expect(await screen.findByText('qoder/HOST')).toBeTruthy();

    mockedAgents.mockRejectedValue(new Error('agents down'));
    mockedRuns.mockRejectedValue(new Error('runs down'));
    await act(async () => {
      await client.refetchQueries({ queryKey: ['agents'] });
      await client.refetchQueries({ queryKey: ['runs-all'] });
    });

    // Values stay (last known) and say so instead of passing for fresh data.
    expect(screen.getByText('qoder/HOST')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getAllByText(/Last known data; refresh failed/).length).toBeGreaterThanOrEqual(2),
    );
  });
});
