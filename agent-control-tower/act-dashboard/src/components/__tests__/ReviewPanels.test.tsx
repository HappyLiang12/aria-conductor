import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DecisionPanel, ShortApprovalView } from '../ReviewPanels';
import type { Approval, KanbanItem } from '../../types';

vi.mock('../../api/kanban', () => ({
  transitionKanbanItem: vi.fn(),
}));
vi.mock('../../api/approvals', () => ({
  answerAsk: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));

import { transitionKanbanItem } from '../../api/kanban';
import { approveApproval, rejectApproval, answerAsk } from '../../api/approvals';

const mockedTransition = vi.mocked(transitionKanbanItem);
const mockedApprove = vi.mocked(approveApproval);
const mockedReject = vi.mocked(rejectApproval);
const mockedAnswer = vi.mocked(answerAsk);

const item = { id: 'k-1', title: 'add CSV export', status: 'REVIEW', assignee: 'dev-agent', linkedRunId: 'run-abc' } as KanbanItem;

beforeEach(() => vi.clearAllMocks());

// The panels own their mutations, so they need a QueryClient context — same
// provider pattern as the component test suites.
function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderPanel(ui: ReactElement, qc: QueryClient = makeClient()) {
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe('DecisionPanel', () => {
  it('renders each pending ask and routes gate asks to /decide endpoints', async () => {
    mockedApprove.mockResolvedValue({} as Approval);
    renderPanel(<DecisionPanel item={item} pendingAsks={[
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' } as Approval,
      { id: 'a2', askType: 'QUESTION', content: 'BOM?', status: 'PENDING' } as Approval,
    ]} />);
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]);
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('a1', undefined));
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[1]);
    await waitFor(() => expect(mockedAnswer).toHaveBeenCalledWith('a2', expect.objectContaining({ approved: true })));
    expect(mockedReject).not.toHaveBeenCalled();
  });

  it('exposes the zone as a decision region for assistive tech', () => {
    renderPanel(<DecisionPanel item={item} pendingAsks={[]} />);
    expect(screen.getByRole('region', { name: 'Decision panel' })).toBeInTheDocument();
  });

  it('Request changes sends the card back to TODO with the typed feedback', async () => {
    mockedTransition.mockResolvedValue({ ...item, status: 'TODO' });
    renderPanel(<DecisionPanel item={item} pendingAsks={[
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' } as Approval,
    ]} />);
    await userEvent.type(screen.getByLabelText('Request-changes feedback'), 'redo the export');
    await userEvent.click(screen.getByRole('button', { name: /request changes/i }));
    await waitFor(() =>
      expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'TODO', feedback: 'redo the export' }),
    );
  });

  it('disables mutation buttons while a resolution is pending', async () => {
    mockedApprove.mockReturnValue(new Promise(() => {}));
    renderPanel(<DecisionPanel item={item} pendingAsks={[
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' } as Approval,
    ]} />);
    const approve = screen.getAllByRole('button', { name: 'Approve' })[0];
    await userEvent.click(approve);
    expect(approve).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /approve all/i })).toBeDisabled();
    // The card-level request-changes mutation is independent of ask resolution.
    expect(screen.getByRole('button', { name: /request changes/i })).toBeEnabled();
  });

  it('shows an error message when an approval is rejected', async () => {
    mockedApprove.mockRejectedValue(new Error('boom'));
    renderPanel(<DecisionPanel item={item} pendingAsks={[
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' } as Approval,
    ]} />);
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]);
    expect(await screen.findByText('Action rejected — please retry.')).toBeInTheDocument();
  });

  it('resolving an ask also invalidates the kanban-items cache', async () => {
    mockedApprove.mockResolvedValue({} as Approval);
    const qc = makeClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    renderPanel(<DecisionPanel item={item} pendingAsks={[
      { id: 'a1', askType: 'APPROVAL', content: 'spec v2', status: 'PENDING' } as Approval,
    ]} />, qc);
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]);
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban-items'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
    });
  });
});

describe('ShortApprovalView', () => {
  it('shows run summary and quick actions for ask-less Review cards', () => {
    renderPanel(<ShortApprovalView item={item} />);
    expect(screen.getByText(/Run completed/i)).toBeInTheDocument();
    expect(screen.getByText(/dev-agent/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Decision panel' })).toBeInTheDocument();
  });

  it('Approve transitions the card to DONE', async () => {
    mockedTransition.mockResolvedValue(item);
    renderPanel(<ShortApprovalView item={item} />);
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'DONE' }));
  });

  it('Request changes sends feedback and transitions to TODO', async () => {
    mockedTransition.mockResolvedValue({ ...item, status: 'TODO' });
    renderPanel(<ShortApprovalView item={item} />);
    await userEvent.type(screen.getByLabelText('Request-changes feedback'), 'use streaming');
    await userEvent.click(screen.getByRole('button', { name: /request changes/i }));
    await waitFor(() => expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'TODO', feedback: 'use streaming' }));
  });

  it('Deny transitions the card to CANCELLED', async () => {
    mockedTransition.mockResolvedValue({ ...item, status: 'CANCELLED' });
    renderPanel(<ShortApprovalView item={item} />);
    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));
    await waitFor(() => expect(mockedTransition).toHaveBeenCalledWith('k-1', { status: 'CANCELLED' }));
  });

  it('Approve invalidates both kanban-items and kanban caches', async () => {
    mockedTransition.mockResolvedValue(item);
    const qc = makeClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    renderPanel(<ShortApprovalView item={item} />, qc);
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban-items'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban'] });
    });
  });

  it('clears a previous error once a transition succeeds', async () => {
    mockedTransition.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(item);
    renderPanel(<ShortApprovalView item={item} />);
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Action rejected — the card is unchanged.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() =>
      expect(screen.queryByText('Action rejected — the card is unchanged.')).not.toBeInTheDocument(),
    );
  });
});
