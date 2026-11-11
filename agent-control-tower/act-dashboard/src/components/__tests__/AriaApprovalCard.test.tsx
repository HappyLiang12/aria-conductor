import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AriaApprovalCard } from '../AriaApprovalCard';
import type { PanelItem } from '../ariaPanelState';
import { decideApproval } from '../../api/approvals';
import { applyOperatorHeaders } from '../../api/operatorSession';

vi.mock('../../api/approvals', () => ({ decideApproval: vi.fn(), answerAsk: vi.fn() }));
// Keep the real rejection/error helpers; only the header application is spied so
// the card's "operator headers first" ordering is observable.
vi.mock('../../api/operatorSession', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/operatorSession')>();
  return { ...actual, applyOperatorHeaders: vi.fn() };
});

type ApprovalItem = Extract<PanelItem, { kind: 'approval' }>;

const mockDecide = decideApproval as Mock;
const mockApplyHeaders = applyOperatorHeaders as Mock;

const pendingAsk: ApprovalItem = {
  kind: 'approval',
  id: 'i1',
  approvalId: 'a1',
  runId: 'r1',
  toolName: 'mcp__aria-conductor__remember_fact',
  riskTier: 'WRITE_LOCAL',
  toolPolicy: 'WORKER_WRITE',
  args: '{"key":"preferred_channel"}',
  state: 'pending',
};

/** An operator-only refusal: the backend's 403 with its own actionable detail. */
function operatorRejection() {
  return {
    response: {
      status: 403,
      data: { message: 'Operator session required for this route' },
    },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockDecide.mockResolvedValue({ approvalId: 'a1', approved: true, status: 'processed' });
});

describe('AriaApprovalCard', () => {
  it('approves once with operator headers and reports the settled state', async () => {
    const onSettled = vi.fn();
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    expect(screen.getByText('WRITE_LOCAL')).toBeInTheDocument();
    expect(screen.getByText('WORKER_WRITE')).toBeInTheDocument();
    expect(screen.getByText('mcp__aria-conductor__remember_fact')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    await waitFor(() => expect(mockDecide).toHaveBeenCalledWith('a1', expect.objectContaining({ approved: true })));
    expect(mockApplyHeaders).toHaveBeenCalled();
    // The operator CSRF header must be applied before the mutation is issued.
    expect(mockApplyHeaders.mock.invocationCallOrder[0])
      .toBeLessThan(mockDecide.mock.invocationCallOrder[0]);
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith('approved'));
  });

  it('shows the refusal instead of retrying when the operator session is rejected', async () => {
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce(operatorRejection());
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    expect(await screen.findByText(/operator session/i)).toBeInTheDocument();
    // Task 35: the card keeps its own sentence structure ("nothing was
    // decided") but composes the one remedy that can work, not a reload.
    const refusal = screen.getByRole('alert');
    expect(refusal).toHaveTextContent(/open the dashboard on the machine that runs the backend/i);
    expect(refusal).toHaveTextContent(/nothing was decided by the refused call/i);
    expect(refusal).not.toHaveTextContent(/reload the page and retry/i);
    expect(refusal).not.toHaveTextContent(/reload and try again/i);
    expect(onSettled).not.toHaveBeenCalled();
    // The ask is still the operator's to decide: the control stays available.
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
  });

  it('denies with an explicit refusal and reports it', async () => {
    const onSettled = vi.fn();
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /^deny$/i }));

    await waitFor(() => expect(mockDecide).toHaveBeenCalledWith('a1', expect.objectContaining({ approved: false })));
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith('denied'));
  });

  it('issues one decision while the first is in flight', async () => {
    const onSettled = vi.fn();
    let resolve: (value: unknown) => void = () => {};
    mockDecide.mockReturnValueOnce(new Promise((r) => { resolve = r; }));
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    const allow = screen.getByRole('button', { name: /allow once/i });
    fireEvent.click(allow);
    fireEvent.click(allow);

    expect(mockDecide).toHaveBeenCalledTimes(1);
    resolve({ approvalId: 'a1', approved: true, status: 'processed' });
    await waitFor(() => expect(onSettled).toHaveBeenCalledTimes(1));
  });

  it('renders a settled card without decision controls', () => {
    render(<AriaApprovalCard item={{ ...pendingAsk, state: 'denied', reason: 'wrong tenant' }} onSettled={vi.fn()} />);

    expect(screen.getByText(/denied/i)).toBeInTheDocument();
    expect(screen.getByText(/wrong tenant/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /allow once/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^deny$/i })).not.toBeInTheDocument();
  });

  // A 409 is the backend saying the ask is no longer PENDING (expired, the run
  // was cancelled, or another window already decided it). Reporting that as
  // "still pending; retry" is a false claim, and the card would stay actionable
  // forever behind the composer gate.
  it('reports a state conflict as settled, not as "still pending"', async () => {
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'Approval is no longer PENDING' } },
    });
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    await waitFor(() =>
      expect(onSettled).toHaveBeenCalledWith('expired', expect.stringMatching(/no longer PENDING/)),
    );
    expect(screen.queryByText(/still pending; retry/i)).not.toBeInTheDocument();
  });

  it('keeps the ask actionable when the decision fails for another reason', async () => {
    // Positive control for the 409 mapping: a 500 proves nothing about the
    // ask's state, so the card reports the failure and stays the operator's.
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce({ response: { status: 500, data: { message: 'relay dead' } } });
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    expect(await screen.findByText(/relay dead/)).toBeInTheDocument();
    expect(onSettled).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
  });
});
