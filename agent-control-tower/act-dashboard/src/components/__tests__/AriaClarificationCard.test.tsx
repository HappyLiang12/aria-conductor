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
    expect(onSettled).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/your answer/i)).toBeEnabled();
  });

  it('renders a settled question without an answer field', () => {
    render(<AriaClarificationCard item={{ ...question, state: 'expired' }} onSettled={vi.fn()} />);

    expect(screen.getByText(/expired/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/your answer/i)).not.toBeInTheDocument();
  });
});
