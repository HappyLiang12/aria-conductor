import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DecisionPanel, ShortApprovalView } from '../ReviewPanels';
import { formatTimestamp } from '../../utils/formatTime';
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
    mockedApprove.mockResolvedValue({ approvalId: 'a1', approved: true, status: 'processed' });
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
    mockedApprove.mockResolvedValue({ approvalId: 'a1', approved: true, status: 'processed' });
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

// ---------------------------------------------------------------------------
// Task 15: normalized native permission asks (kind + expiry) and batch safety.
// ---------------------------------------------------------------------------

/** The normalized correlation the backend registers for a native ask
 *  (PermissionCoordinator.registrationReason: target = NATIVE_TOOL). */
const NATIVE_ASK_REASON =
  'Native permission request req-42 from session sess-7 for tool write_file (NATIVE_TOOL)';
const NATIVE_ASK_EXPIRES_AT = '2026-09-22T12:05:00Z';

function nativeAsk(): Approval {
  return {
    id: 'a-native',
    runId: 'run-abc',
    toolCallId: null,
    status: 'PENDING',
    reason: NATIVE_ASK_REASON,
    requestedAt: '2026-09-22T11:55:00Z',
    decidedAt: null,
    expiresAt: NATIVE_ASK_EXPIRES_AT,
    askType: 'APPROVAL',
  } as Approval;
}

function gateAsk(): Approval {
  return {
    id: 'a-gate',
    runId: 'run-abc',
    toolCallId: 'tc-1',
    status: 'PENDING',
    reason: 'Task-level approval',
    requestedAt: '2026-09-22T11:55:00Z',
    decidedAt: null,
    expiresAt: NATIVE_ASK_EXPIRES_AT,
    askType: 'APPROVAL',
  } as Approval;
}

describe('DecisionPanel normalized asks (Task 15)', () => {
  it('renders the permission kind and the expiry of a normalized native ask', () => {
    renderPanel(<DecisionPanel item={item} pendingAsks={[nativeAsk()]} />);
    expect(screen.getByText('Native permission · NATIVE_TOOL')).toBeInTheDocument();
    expect(screen.getByText('tool write_file')).toBeInTheDocument();
    expect(screen.getByText('request req-42')).toBeInTheDocument();
    expect(screen.getByText(`expires ${formatTimestamp(NATIVE_ASK_EXPIRES_AT)}`)).toBeInTheDocument();
  });

  it('never routes a native permission ask through the batch approval', async () => {
    mockedApprove.mockResolvedValue({ approvalId: 'a-gate', approved: true, status: 'processed' });
    renderPanel(<DecisionPanel item={item} pendingAsks={[gateAsk(), nativeAsk()]} />);

    expect(
      screen.getByText(
        '1 native permission ask not included in Approve all: each is an allow-once decision and must be resolved individually.',
      ),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /approve all/i }));
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledTimes(1));
    expect(mockedApprove).toHaveBeenCalledWith('a-gate', undefined);
    expect(mockedApprove).not.toHaveBeenCalledWith('a-native', undefined);

    // The native ask stays individually decidable (allow-once through /decide).
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[1]);
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledTimes(2));
    expect(mockedApprove).toHaveBeenLastCalledWith('a-native', undefined);
  });

  it('leaves a shared reason that is not a normalized correlation as a gate approval', () => {
    renderPanel(<DecisionPanel item={item} pendingAsks={[
      { ...gateAsk(), reason: 'Native permission request text without the correlation' } as Approval,
    ]} />);
    expect(screen.getByText('TOOL_CALL')).toBeInTheDocument();
    expect(screen.queryByText('Native permission · NATIVE_TOOL')).not.toBeInTheDocument();
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
