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

import { listWorkflows, deleteWorkflow } from '../../api/workflows';
import { listRuns } from '../../api/runs';
import { listAgents } from '../../api/agents';

const mockListWorkflows = listWorkflows as Mock;
const mockDeleteWorkflow = deleteWorkflow as Mock;
const mockListRuns = listRuns as Mock;
const mockListAgents = listAgents as Mock;

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
    mockDeleteWorkflow.mockRejectedValue(new Error('boom'));

    renderPage(<WorkflowsPage />);
    await clickDelete();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    // The dialog must never strand open on a rejection...
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    // ...and the failure must not be silent: the page's inline error banner shows it.
    expect(await screen.findByRole('alert')).toHaveTextContent('Operation failed: boom');
  });
});
