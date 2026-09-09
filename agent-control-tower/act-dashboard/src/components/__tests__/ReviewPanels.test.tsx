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
function renderPanel(ui: ReactElement) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
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
});

describe('ShortApprovalView', () => {
  it('shows run summary and quick actions for ask-less Review cards', () => {
    renderPanel(<ShortApprovalView item={item} />);
    expect(screen.getByText(/Run completed/i)).toBeInTheDocument();
    expect(screen.getByText(/dev-agent/)).toBeInTheDocument();
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
});
