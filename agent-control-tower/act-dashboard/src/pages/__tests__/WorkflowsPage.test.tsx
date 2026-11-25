import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { WorkflowsPage } from '../WorkflowsPage';

vi.mock('../../api/workflows', () => ({
  listWorkflows: vi.fn(),
  cancelWorkflow: vi.fn(),
  retryWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
  mergeWorkflows: vi.fn(),
  executeYaml: vi.fn(),
  resubmitApproval: vi.fn(),
}));
vi.mock('../../api/runs', () => ({ listRuns: vi.fn() }));
vi.mock('../../api/agents', () => ({ listAgents: vi.fn() }));
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => ({ lastMessage: null, isConnected: true }),
}));
// The Templates tab mounts TemplatesPanel, which reads workflow templates from
// the knowledge API. Only the list call is exercised here; the rest keep their
// real implementations so the module graph resolves unchanged.
vi.mock('../../api/knowledge', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/knowledge')>();
  return { ...actual, listKnowledge: vi.fn() };
});

import { listWorkflows, deleteWorkflow, retryWorkflow } from '../../api/workflows';
import { listRuns } from '../../api/runs';
import { listAgents } from '../../api/agents';
import { listKnowledge } from '../../api/knowledge';

const mockListWorkflows = listWorkflows as Mock;
const mockDeleteWorkflow = deleteWorkflow as Mock;
const mockRetryWorkflow = retryWorkflow as Mock;
const mockListRuns = listRuns as Mock;
const mockListAgents = listAgents as Mock;
const mockListKnowledge = listKnowledge as Mock;

function chain() {
  return {
    id: 'wf-1',
    name: 'Demo chain',
    status: 'COMPLETED',
    currentStepIndex: 1,
    totalSteps: 1,
    steps: [
      { index: 0, agentId: 'a-1', promptTemplate: 'draft the spec', status: 'COMPLETED', runId: null, outputPreview: null },
    ],
    createdAt: '2026-09-01T10:00:00Z',
    completedAt: '2026-09-01T10:05:00Z',
  };
}

function failedChain() {
  return {
    ...chain(),
    status: 'FAILED',
    currentStepIndex: 0,
    steps: [
      { index: 0, agentId: 'a-1', promptTemplate: 'draft the spec', status: 'FAILED', runId: null, outputPreview: null },
    ],
  };
}

function renderPage(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

async function clickDelete() {
  const del = await screen.findByRole('button', { name: 'Delete' });
  await userEvent.click(del);
}

describe('WorkflowsPage delete confirmation (Task 10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListWorkflows.mockResolvedValue([chain()]);
    mockListRuns.mockResolvedValue([]);
    mockListAgents.mockResolvedValue([]);
    mockDeleteWorkflow.mockResolvedValue(undefined);
  });

  it('deletes only after the operator confirms in the shared dialog', async () => {
    renderPage(<WorkflowsPage />);
    await clickDelete();

    // Nothing may be deleted from the first click — the dialog only opens.
    expect(mockDeleteWorkflow).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: /Delete/ })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockDeleteWorkflow).not.toHaveBeenCalled();

    await clickDelete();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(mockDeleteWorkflow).toHaveBeenCalledTimes(1));
    expect(mockDeleteWorkflow).toHaveBeenCalledWith('wf-1');
    // Completion clears the pending state.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('backdrop click dismisses the dialog without deleting', async () => {
    renderPage(<WorkflowsPage />);
    await clickDelete();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.click(document.querySelector('.modal-overlay') as HTMLElement);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockDeleteWorkflow).not.toHaveBeenCalled();
  });

  it('surfaces the failure when the confirmed delete is rejected', async () => {
    // The backend's own refusal wording is surfaced, not the axios status line.
    mockDeleteWorkflow.mockRejectedValue({
      response: { data: { message: 'Workflow delete refused: an active run owns it' } },
    });

    renderPage(<WorkflowsPage />);
    await clickDelete();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    // The dialog must never strand open on a rejection...
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    // ...and the failure must not be silent: the page's inline error banner shows it.
    expect(await screen.findByRole('alert'))
      .toHaveTextContent('Workflow delete refused: an active run owns it');
  });

  it('clears the failure banner after a later retry succeeds', async () => {
    mockListWorkflows.mockResolvedValue([failedChain()]);
    mockRetryWorkflow.mockRejectedValueOnce({
      response: { data: { message: 'Retry refused: the step is still running.' } },
    });

    renderPage(<WorkflowsPage />);
    await userEvent.click(await screen.findByRole('button', { name: /Retry Step/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Retry refused: the step is still running.',
    );

    mockRetryWorkflow.mockResolvedValueOnce(undefined);
    await userEvent.click(screen.getByRole('button', { name: /Retry Step/ }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
});

// ---------------------------------------------------------------------------
// Wave 2 C4: the Workflows page and its Templates panel render on the shadcn
// token surfaces. The hooks the operator/E2E contracts depend on stay put.
// ---------------------------------------------------------------------------
describe('WorkflowsPage token surfaces (Wave 2 C4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListWorkflows.mockResolvedValue([chain()]);
    mockListRuns.mockResolvedValue([]);
    mockListAgents.mockResolvedValue([]);
    mockListKnowledge.mockResolvedValue([
      {
        id: 't1', name: 'development-workflow', type: 'WORKFLOW', status: 'APPROVED',
        description: 'SDD loop', currentVersion: 'v1.0.0', createdAt: '2026-01-01T00:00:00Z',
      },
    ]);
  });

  it('renders each workflow chain on a token card surface', async () => {
    renderPage(<WorkflowsPage />);

    const name = await screen.findByText('Demo chain');
    const card = name.closest('[data-slot="card"]');
    expect(card).not.toBeNull();
    // Compatibility shims: select-for-merge, step copy, created stamp.
    expect(card!.querySelector('input[type="checkbox"]')).not.toBeNull();
    expect(card).toHaveTextContent('1/1 steps');
    expect(card).toHaveTextContent('Created:');
    expect(card).toHaveTextContent('Step 1:');
  });

  it('renders the Templates panel on token card surfaces', async () => {
    renderPage(<WorkflowsPage />);

    await userEvent.click(await screen.findByRole('tab', { name: 'Templates' }));

    const name = await screen.findByText('development-workflow');
    expect(name.closest('[data-slot="card"]')).not.toBeNull();
    expect(screen.getByText('1 template')).toBeInTheDocument();
  });
});
