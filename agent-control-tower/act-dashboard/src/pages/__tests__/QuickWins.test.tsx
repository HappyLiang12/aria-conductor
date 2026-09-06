import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { RunsPage } from '../RunsPage';
import { WorkflowsPage } from '../WorkflowsPage';
import { listRuns } from '../../api/runs';
import { listAgents } from '../../api/agents';
import { listWorkflows } from '../../api/workflows';

vi.mock('../../api/runs', () => ({
  listRuns: vi.fn(),
  createRun: vi.fn(),
  cancelRun: vi.fn(),
  pauseRun: vi.fn(),
  resumeRun: vi.fn(),
  getRunTrajectory: vi.fn().mockResolvedValue([]),
  getRunToolCalls: vi.fn().mockResolvedValue([]),
  injectRunMessage: vi.fn(),
}));
vi.mock('../../api/agents', () => ({ listAgents: vi.fn() }));
vi.mock('../../api/workflows', () => ({
  listWorkflows: vi.fn(),
  cancelWorkflow: vi.fn(),
  retryWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
  mergeWorkflows: vi.fn(),
  executeYaml: vi.fn(),
  resubmitApproval: vi.fn(),
}));
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => ({ lastMessage: null, isConnected: true }),
}));

const mockListRuns = listRuns as Mock;
const mockListAgents = listAgents as Mock;
const mockListWorkflows = listWorkflows as Mock;

const agent = (id: string, name: string) => ({
  id,
  name,
  description: '',
  agentType: 'LANGCHAIN',
  role: 'dev',
  model: 'test-model',
  provider: 'langchain',
  healthStatus: 'HEALTHY',
});

const run = (id: string, agentId: string, status: string) => ({
  id,
  agentId,
  status,
  promptSeed: 'do the thing',
  maxIterations: 10,
  iterationCount: 1,
  totalTokensUsed: 0,
  errorMessage: null,
  finalOutput: null,
  createdAt: '2026-09-01T10:00:00Z',
  completedAt: null,
  conversationId: null,
});

const step = (status: string, runId: string | null) => ({
  index: 0,
  agentId: 'a-1',
  promptTemplate: 'draft the spec',
  status,
  runId,
  outputPreview: null,
});

const chain = (steps: ReturnType<typeof step>[]) => ({
  id: 'wf-1',
  name: 'Demo chain',
  status: 'COMPLETED',
  currentStepIndex: 1,
  totalSteps: steps.length,
  steps,
  createdAt: '2026-09-01T10:00:00Z',
  completedAt: '2026-09-01T10:05:00Z',
});

function renderPage(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RunsPage agent display names', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListAgents.mockResolvedValue([]);
    mockListRuns.mockResolvedValue([]);
  });

  it('renders the agent name when the agents list contains the run agentId', async () => {
    mockListAgents.mockResolvedValue([agent('a-known', 'Scribe')]);
    mockListRuns.mockResolvedValue([run('r-1', 'a-known', 'COMPLETED')]);

    renderPage(<RunsPage />);

    // Target the table cell specifically (the filter dropdown also has options).
    expect(await screen.findByRole('cell', { name: 'Scribe' })).toBeInTheDocument();
  });

  it('falls back to a short UUID when the agent is not in the agents list (deleted)', async () => {
    mockListAgents.mockResolvedValue([]);
    mockListRuns.mockResolvedValue([run('r-2', 'deadbeef-1234-5678-9abc-def012345678', 'FAILED')]);

    renderPage(<RunsPage />);

    expect(await screen.findByText('deadbeef')).toBeInTheDocument();
  });
});

describe('WorkflowsPage stale step status', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListAgents.mockResolvedValue([agent('a-1', 'Dev')]);
    mockListRuns.mockResolvedValue([]);
    mockListWorkflows.mockResolvedValue([]);
  });

  it('derives the terminal run status for a step stuck on RUNNING', async () => {
    mockListWorkflows.mockResolvedValue([chain([step('RUNNING', 'run-7')])]);
    mockListRuns.mockResolvedValue([run('run-7', 'a-1', 'COMPLETED')]);

    renderPage(<WorkflowsPage />);

    // The step card must show the run's terminal status instead of a stale RUNNING.
    // Two matches: the workflow status badge plus the derived step status.
    expect((await screen.findAllByText('COMPLETED')).length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText('RUNNING')).not.toBeInTheDocument();
  });

  it('keeps RUNNING when the run is not terminal yet', async () => {
    mockListWorkflows.mockResolvedValue([chain([step('RUNNING', 'run-8')])]);
    mockListRuns.mockResolvedValue([run('run-8', 'a-1', 'RUNNING')]);

    renderPage(<WorkflowsPage />);

    expect(await screen.findByText(/Step 1: Dev/)).toBeInTheDocument();
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
  });
});
