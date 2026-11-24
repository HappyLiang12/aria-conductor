import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApprovalDecisionReceipt } from '../../../types';
import { ApprovalToolUI } from '../ApprovalToolUI';

vi.mock('../../../api/approvals', () => ({ decideApproval: vi.fn().mockResolvedValue({}) }));

describe('ApprovalToolUI', () => {
  const props = {
    args: { approvalId: 'a1', toolName: 'bash', riskTier: 'DESTRUCTIVE', expiresAt: new Date(Date.now() + 60_000).toISOString() },
    toolName: '__approval', toolCallId: 'approval-a1', argsText: '', status: { type: 'complete' as const },
    addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
  } as unknown as ComponentProps<typeof ApprovalToolUI>;

  /** The renderer only reads `args`; the rest of the part shape is inert here. */
  const withArgs = (args: Record<string, unknown>) =>
    ({
      args, toolName: '__approval', toolCallId: 'approval-a1', argsText: '',
      status: { type: 'complete' as const }, addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
    }) as unknown as ComponentProps<typeof ApprovalToolUI>;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('posts an approval decision and shows the receipt', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
    expect(screen.getByText(/allowed/i)).toBeInTheDocument();
  });

  it('posts a rejection', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /deny/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });

  it('posts one decision when Allow is double-clicked, and still shows the receipt', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    // Hold the request open so the second click lands while the card is submitting.
    let settle!: (receipt: ApprovalDecisionReceipt) => void;
    vi.mocked(decideApproval).mockImplementationOnce(
      () =>
        new Promise<ApprovalDecisionReceipt>((resolve) => {
          settle = resolve;
        }),
    );
    render(<ApprovalToolUI {...props} />);
    const allow = screen.getByRole('button', { name: /^allow$/i });
    await userEvent.dblClick(allow);
    expect(decideApproval).toHaveBeenCalledTimes(1);
    expect(allow).toBeDisabled();
    settle({} as ApprovalDecisionReceipt);
    expect(await screen.findByText(/allowed/i)).toBeInTheDocument();
    expect(decideApproval).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the governed tool name, risk tier and expiry', () => {
    render(<ApprovalToolUI {...props} />);
    expect(screen.getByText(/approve bash/i)).toBeInTheDocument();
    expect(screen.getByText('DESTRUCTIVE')).toBeInTheDocument();
    expect(screen.getByText(/expires/i)).toBeInTheDocument();
  });

  it('renders one button per offered option and maps allow/deny to the decision', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow once' },
            { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
          ]),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Allow once' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
  });

  it('posts a rejection from the offered reject option', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow once' },
            { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
          ]),
        })}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });

  it('refuses to guess a decision for an always-scope option', () => {
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_always', kind: 'allow_always', name: 'Always allow' },
          ]),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Always allow' })).toBeDisabled();
  });

  it('maps an ALLOW_ONCE backend option to an approval', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
            { optionId: 'cancel', choice: 'DENY' },
          ]),
        })}
      />,
    );
    // No label field: the button is labelled from the option id, humanized.
    await userEvent.click(screen.getByRole('button', { name: /proceed once/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
    expect(screen.getByText(/allowed/i)).toBeInTheDocument();
  });

  it('maps a DENY backend option to a rejection', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
            { optionId: 'cancel', choice: 'DENY' },
          ]),
        })}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });

  it('falls back to allow/deny when optionsJson is unparseable', () => {
    render(<ApprovalToolUI {...withArgs({ approvalId: 'a1', toolName: 'bash', optionsJson: 'not json' })} />);
    expect(screen.getByRole('button', { name: /^allow$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^deny$/i })).toBeInTheDocument();
  });

  it('surfaces a failed decision and keeps the card retryable', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    vi.mocked(decideApproval).mockRejectedValueOnce(new Error('ask expired'));
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ask expired');
    expect(screen.getByRole('button', { name: /allow/i })).toBeInTheDocument();
  });
});
