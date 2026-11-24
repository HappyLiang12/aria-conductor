import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MemoryProposalView } from '../../../api/memory';
import { MemoryProposalToolUI } from '../MemoryProposalToolUI';

vi.mock('../../../api/memory', () => ({ decideMemoryProposal: vi.fn().mockResolvedValue({}) }));

describe('MemoryProposalToolUI', () => {
  const withArgs = (args: Record<string, unknown>) =>
    ({
      args, toolName: '__memory', toolCallId: 'memory-p1', argsText: '',
      status: { type: 'complete' as const }, addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
    }) as unknown as ComponentProps<typeof MemoryProposalToolUI>;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('posts APPROVE against the proposal id', async () => {
    const { decideMemoryProposal } = await import('../../../api/memory');
    render(<MemoryProposalToolUI {...withArgs({ id: 'p1', subjectType: 'FACT', valueText: 'Ships on Fridays' })} />);
    expect(screen.getByText('Ships on Fridays')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /approve/i }));
    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('p1', 'APPROVE'));
    expect(screen.getByText(/remembered/i)).toBeInTheDocument();
  });

  it('posts REJECT against the proposal id', async () => {
    const { decideMemoryProposal } = await import('../../../api/memory');
    render(<MemoryProposalToolUI {...withArgs({ id: 'p1', factKey: 'deploy.day' })} />);
    await userEvent.click(screen.getByRole('button', { name: /reject/i }));
    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('p1', 'REJECT'));
  });

  it('posts one decision when Approve is double-clicked, and still shows the receipt', async () => {
    const { decideMemoryProposal } = await import('../../../api/memory');
    // Hold the request open so the second click lands while the card is submitting.
    let settle!: (proposal: MemoryProposalView) => void;
    vi.mocked(decideMemoryProposal).mockImplementationOnce(
      () =>
        new Promise<MemoryProposalView>((resolve) => {
          settle = resolve;
        }),
    );
    render(<MemoryProposalToolUI {...withArgs({ id: 'p1', valueText: 'Ships on Fridays' })} />);
    const approve = screen.getByRole('button', { name: /^approve$/i });
    await userEvent.dblClick(approve);
    expect(decideMemoryProposal).toHaveBeenCalledTimes(1);
    expect(approve).toBeDisabled();
    settle({} as MemoryProposalView);
    expect(await screen.findByText(/remembered/i)).toBeInTheDocument();
    expect(decideMemoryProposal).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('surfaces a failed decision and keeps the card retryable', async () => {
    const { decideMemoryProposal } = await import('../../../api/memory');
    vi.mocked(decideMemoryProposal).mockRejectedValueOnce(new Error('proposal already decided'));
    render(<MemoryProposalToolUI {...withArgs({ id: 'p1', factKey: 'deploy.day' })} />);
    await userEvent.click(screen.getByRole('button', { name: /approve/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('proposal already decided');
    expect(screen.getByRole('button', { name: /approve/i })).toBeInTheDocument();
  });
});
