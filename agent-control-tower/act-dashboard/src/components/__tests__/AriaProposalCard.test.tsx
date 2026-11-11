import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AriaProposalCard } from '../AriaProposalCard';
import type { PanelItem } from '../ariaPanelState';

vi.mock('../../api/memory', () => ({ decideMemoryProposal: vi.fn() }));
// Partial mock: the refusal classification and the backend message parse are the
// real contract; only the header application is observed.
vi.mock('../../api/operatorSession', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/operatorSession')>();
  return { ...actual, applyOperatorHeaders: vi.fn() };
});

import { decideMemoryProposal } from '../../api/memory';
import { applyOperatorHeaders } from '../../api/operatorSession';

type ProposalItem = Extract<PanelItem, { kind: 'proposal' }>;

function proposal(over: Partial<ProposalItem> = {}): ProposalItem {
  return {
    kind: 'proposal',
    id: 'proposal:p1',
    proposalId: 'e4d7',
    subjectType: 'FACT',
    factKey: 'preferred_editor',
    valueText: 'IntelliJ IDEA',
    sensitivity: 'INTERNAL',
    state: 'pending',
    ...over,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(decideMemoryProposal).mockResolvedValue({
    id: 'e4d7',
    subjectType: 'FACT',
    entityId: 'ent-1',
    factKey: 'preferred_editor',
    valueText: 'IntelliJ IDEA',
    predicate: null,
    toEntityId: null,
    status: 'ACTIVE',
    origin: 'OPERATOR',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: null,
    validFrom: null,
  });
});

describe('AriaProposalCard', () => {
  it('approves a proposal with operator headers and flips the card', async () => {
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('e4d7', 'APPROVE'));
    expect(applyOperatorHeaders).toHaveBeenCalled();
    // The card flips: no decision control survives on a settled proposal.
    expect(await screen.findByRole('status')).toHaveTextContent(/approved/i);
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(onSettled).toHaveBeenCalledWith('e4d7', 'approved');
  });

  it('renders an entity proposal with its display name and key', () => {
    render(
      <AriaProposalCard
        item={proposal({
          id: 'proposal:p2',
          proposalId: '7fae',
          subjectType: 'ENTITY',
          entityId: '7fae',
          factKey: 'person:happy',
          valueText: 'Happy',
        })}
        onSettled={vi.fn()}
      />,
    );
    expect(screen.getByText('person:happy')).toBeInTheDocument();
    expect(screen.getByText(/new entity/i)).toBeInTheDocument();
    expect(screen.getByText('Happy')).toBeInTheDocument();
  });

  it('rejects through the same operator-authenticated surface and flips to rejected', async () => {
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /reject/i }));

    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('e4d7', 'REJECT'));
    await screen.findByText(/will never become memory/i);
    expect(onSettled).toHaveBeenCalledWith('e4d7', 'rejected');
  });

  it('renders the operator-session notice on a 403 and leaves the decision open', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({ response: { status: 403, data: {} } });
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    await screen.findByTestId('operator-session-notice');
    // Nothing was decided: the buttons are still there, the card is not flipped.
    expect(screen.getByRole('button', { name: /approve/i })).toBeInTheDocument();
    expect(screen.queryByText(/^approved/i)).not.toBeInTheDocument();
    expect(onSettled).not.toHaveBeenCalled();
  });

  it('shows a non-authority failure as the backend message and keeps the card decidable', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({
      response: { status: 409, data: { message: 'proposal already decided' } },
    });
    render(<AriaProposalCard item={proposal()} onSettled={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    expect(await screen.findByText('proposal already decided')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeInTheDocument();
  });

  it('a restored settled proposal renders its outcome instead of a decision control', () => {
    render(<AriaProposalCard item={proposal({ state: 'rejected' })} onSettled={vi.fn()} />);

    expect(screen.getByText(/will never become memory/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
  });
});
