import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AriaClarificationCard } from '../AriaClarificationCard';
import type { PanelItem } from '../ariaPanelState';
import { answerAsk } from '../../api/approvals';
import { applyOperatorHeaders } from '../../api/operatorSession';

vi.mock('../../api/approvals', () => ({ decideApproval: vi.fn(), answerAsk: vi.fn() }));
vi.mock('../../api/operatorSession', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/operatorSession')>();
  return { ...actual, applyOperatorHeaders: vi.fn() };
});

type ClarificationItem = Extract<PanelItem, { kind: 'clarification' }>;

const mockAnswer = answerAsk as Mock;
const mockApplyHeaders = applyOperatorHeaders as Mock;

const question: ClarificationItem = {
  kind: 'clarification',
  id: 'c1',
  approvalId: 'a2',
  runId: 'r1',
  question: 'Which PR should I review?',
  state: 'pending',
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAnswer.mockResolvedValue({ id: 'a2', status: 'PENDING' });
});

describe('AriaClarificationCard', () => {
  it('shows the question and sends the answer to the existing answer endpoint', async () => {
    const onSettled = vi.fn();
    render(<AriaClarificationCard item={question} onSettled={onSettled} />);

    expect(screen.getByText('Which PR should I review?')).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: /answer/i });
    // An empty answer cannot be sent…
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/your answer/i), { target: { value: 'PR #42' } });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);

    await waitFor(() => expect(mockAnswer).toHaveBeenCalledWith('a2', { approved: true, answer: 'PR #42' }));
    expect(mockApplyHeaders).toHaveBeenCalled();
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith('answered'));
  });

  it('surfaces an operator-session refusal and keeps the question actionable', async () => {
    const onSettled = vi.fn();
    mockAnswer.mockRejectedValueOnce({ response: { status: 401, data: { message: 'Operator session required' } } });
    render(<AriaClarificationCard item={question} onSettled={onSettled} />);

    fireEvent.change(screen.getByLabelText(/your answer/i), { target: { value: 'PR #42' } });
    fireEvent.click(screen.getByRole('button', { name: /answer/i }));

    expect(await screen.findByText(/operator session/i)).toBeInTheDocument();
    // Task 35: this card says "nothing was sent" where the approval card says
    // "nothing was decided" — the remedy is the one shared copy, not a reload.
    const refusal = screen.getByRole('alert');
    expect(refusal).toHaveTextContent(/open the dashboard on the machine that runs the backend/i);
    expect(refusal).toHaveTextContent(/nothing was sent by the refused call/i);
    expect(refusal).not.toHaveTextContent(/reload the page and retry/i);
    expect(refusal).not.toHaveTextContent(/reload and try again/i);
    expect(onSettled).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/your answer/i)).toBeEnabled();
  });

  // T45 Item 2: `/answer` serves 409 when the ask can no longer be answered — it
  // expired, the run was finalized, or a concurrent answer won
  // (ApprovalController.java:239-262). Not a transient failure: every retry
  // repeats the 409 and leaves the card pending with the composer locked.
  it('settles an unanswerable ask on 409 instead of offering a retry that cannot work', async () => {
    const onSettled = vi.fn();
    mockAnswer.mockRejectedValueOnce({
      response: { status: 409, data: { error: 'run is not waiting for operator input' } },
    });
    render(<AriaClarificationCard item={question} onSettled={onSettled} />);

    fireEvent.change(screen.getByLabelText(/your answer/i), { target: { value: 'PR #42' } });
    fireEvent.click(screen.getByRole('button', { name: /answer/i }));

    // Settled, with the backend's own wording — never "answered".
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith('expired', 'run is not waiting for operator input'));
    expect(onSettled).not.toHaveBeenCalledWith('answered', expect.anything());
    expect(onSettled).not.toHaveBeenCalledWith('answered');
    // No retry copy for a refusal a retry cannot fix, and nothing claims the
    // answer landed.
    expect(screen.queryByText(/still waiting; retry/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/answered/i)).not.toBeInTheDocument();
  });

  it('keeps a genuine fault actionable: it is not a settlement and offers the retry', async () => {
    const onSettled = vi.fn();
    mockAnswer.mockRejectedValueOnce(new Error('Network Error'));
    render(<AriaClarificationCard item={question} onSettled={onSettled} />);

    fireEvent.change(screen.getByLabelText(/your answer/i), { target: { value: 'PR #42' } });
    fireEvent.click(screen.getByRole('button', { name: /answer/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/still waiting; retry/i);
    expect(onSettled).not.toHaveBeenCalled();
    // The question is still the operator's to answer.
    expect(screen.getByLabelText(/your answer/i)).toBeEnabled();
    expect(screen.getByRole('button', { name: /answer/i })).toBeEnabled();
  });

  it('renders a settled question without an answer field', () => {
    render(<AriaClarificationCard item={{ ...question, state: 'expired' }} onSettled={vi.fn()} />);

    expect(screen.getByText(/expired/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/your answer/i)).not.toBeInTheDocument();
  });
});
