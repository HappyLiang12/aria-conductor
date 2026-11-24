import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Approval } from '../../../types';
import { ClarificationToolUI } from '../ClarificationToolUI';

vi.mock('../../../api/approvals', () => ({ answerAsk: vi.fn().mockResolvedValue({}) }));

describe('ClarificationToolUI', () => {
  const withArgs = (args: Record<string, unknown>) =>
    ({
      args, toolName: '__question', toolCallId: 'question-a2', argsText: '',
      status: { type: 'complete' as const }, addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
    }) as unknown as ComponentProps<typeof ClarificationToolUI>;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('posts the typed answer against the ask and shows the receipt', async () => {
    const { answerAsk } = await import('../../../api/approvals');
    render(<ClarificationToolUI {...withArgs({ approvalId: 'a2', question: 'Which PR?' })} />);
    expect(screen.getByText('Which PR?')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Your answer'), '#131');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    await waitFor(() => expect(answerAsk).toHaveBeenCalledWith('a2', { approved: true, answer: '#131' }));
    expect(screen.getByText(/answered/i)).toBeInTheDocument();
  });

  it('keeps Send disabled until an answer is typed', async () => {
    render(<ClarificationToolUI {...withArgs({ approvalId: 'a2', question: 'Which PR?' })} />);
    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Your answer'), '   ');
    expect(screen.getByRole('button', { name: /send/i })).toBeDisabled();
  });

  it('posts one answer when Send is double-clicked, and still shows the receipt', async () => {
    const { answerAsk } = await import('../../../api/approvals');
    // Hold the request open so the second click lands while the card is submitting.
    let settle!: (ask: Approval) => void;
    vi.mocked(answerAsk).mockImplementationOnce(
      () =>
        new Promise<Approval>((resolve) => {
          settle = resolve;
        }),
    );
    render(<ClarificationToolUI {...withArgs({ approvalId: 'a2', question: 'Which PR?' })} />);
    await userEvent.type(screen.getByLabelText('Your answer'), '#131');
    const send = screen.getByRole('button', { name: /send/i });
    await userEvent.dblClick(send);
    expect(answerAsk).toHaveBeenCalledTimes(1);
    expect(send).toBeDisabled();
    settle({} as Approval);
    expect(await screen.findByText(/answered/i)).toBeInTheDocument();
    expect(answerAsk).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('surfaces a failed answer', async () => {
    const { answerAsk } = await import('../../../api/approvals');
    vi.mocked(answerAsk).mockRejectedValueOnce(new Error('run already finished'));
    render(<ClarificationToolUI {...withArgs({ approvalId: 'a2', question: 'Which PR?' })} />);
    await userEvent.type(screen.getByLabelText('Your answer'), 'nope');
    await userEvent.click(screen.getByRole('button', { name: /send/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('run already finished');
  });
});
