import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { AriaProposalCard } from '../AriaProposalCard';
import type { PanelItem } from '../ariaPanelState';

vi.mock('../../api/memory', () => ({
  decideMemoryProposal: vi.fn(),
  listMemoryProposals: vi.fn(),
}));
// Partial mock: the refusal classification and the backend message parse are the
// real contract; only the header application is observed.
vi.mock('../../api/operatorSession', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/operatorSession')>();
  return { ...actual, applyOperatorHeaders: vi.fn() };
});

import { decideMemoryProposal, listMemoryProposals } from '../../api/memory';
import type { MemoryProposalView } from '../../api/memory';
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

/** One row of `GET /api/v1/memory/proposals?status=PENDING`, as the server serves it. */
function proposalRow(over: Partial<MemoryProposalView> = {}): MemoryProposalView {
  return {
    id: 'f1',
    subjectType: 'FACT',
    entityId: 'ent-7',
    factKey: 'preferred_editor',
    valueText: 'vim',
    predicate: null,
    toEntityId: null,
    status: 'PENDING',
    origin: 'EXTRACTION',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: '2026-11-11T09:00:00Z',
    validFrom: null,
    ...over,
  };
}

function entityItem(over: Partial<ProposalItem> = {}): ProposalItem {
  return proposal({
    id: 'proposal:p7',
    proposalId: 'ent-7',
    subjectType: 'ENTITY',
    entityId: 'ent-7',
    factKey: 'person:happy',
    valueText: 'Happy',
    ...over,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  // The batch read is ENTITY-only; a card that reads it must be told what the
  // inbox holds. Default: nothing attached beyond the entity itself.
  vi.mocked(listMemoryProposals).mockResolvedValue([]);
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

  it('renders an entity proposal with its display name and key', async () => {
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
    // The batch read settles asynchronously; await its region so the card's own
    // state updates land before the assertions read the DOM.
    expect(await screen.findByTestId('proposal-batch')).toBeInTheDocument();
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

  // Spec C1/C2: one entity decision promotes the entity AND every PENDING fact of
  // it. The operator cannot approve a batch they cannot read, so the card lists
  // that batch before the decision — read from the same proposals inbox the page
  // decides from, filtered to this entity.
  it('lists the pending facts an entity decision also decides', async () => {
    vi.mocked(listMemoryProposals).mockResolvedValue([
      proposalRow(),
      proposalRow({ id: 'ent-7', subjectType: 'ENTITY', factKey: 'person:happy', valueText: 'Happy' }),
      proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
      proposalRow({ id: 'f3', entityId: 'ent-other', factKey: 'timezone', valueText: 'UTC' }),
    ]);
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(listMemoryProposals).toHaveBeenCalledWith('PENDING');
    // Exactly this entity's facts: not the entity's own row (already the title),
    // not another entity's fact.
    expect(within(batch).getAllByRole('listitem')).toHaveLength(2);
    expect(within(batch).getByText('preferred_editor')).toBeInTheDocument();
    expect(within(batch).getByText(/vim/)).toBeInTheDocument();
    expect(within(batch).getByText('release_channel')).toBeInTheDocument();
    expect(within(batch).queryByText(/timezone/)).not.toBeInTheDocument();
    expect(within(batch).queryByText(/person:happy/)).not.toBeInTheDocument();
    // Listing the batch does not decide anything: the card is still the operator's.
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
  });

  /**
   * `GET /proposals` serves one page capped at `MemoryProposalService.INBOX_LIMIT`
   * (50) newest-first across ALL entities. These rows are what a 60-fact inbox
   * returns: the page is full, and this entity's own facts are below it.
   */
  function cappedInboxPage(): MemoryProposalView[] {
    return Array.from({ length: 50 }, (_, i) =>
      proposalRow({ id: `other-${i}`, entityId: 'ent-other', factKey: `other_fact_${i}` }),
    );
  }

  it('never claims the entity stands alone when the inbox page hit its cap', async () => {
    vi.mocked(listMemoryProposals).mockResolvedValue(cappedInboxPage());
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    // The capped page cannot prove the batch is empty: the decision still
    // promotes every PENDING fact of the entity, so name the cap and the count.
    expect(await within(batch).findByText(/may be truncated|are not on it|capped/i)).toBeInTheDocument();
    expect(within(batch).getByText(/showing 0 pending fact/i)).toBeInTheDocument();
    expect(within(batch).queryByText(/adds the entity alone/i)).not.toBeInTheDocument();
  });

  it('says the entity stands alone only when the page is provably complete', async () => {
    vi.mocked(listMemoryProposals).mockResolvedValue([
      proposalRow({ id: 'other-1', entityId: 'ent-other', factKey: 'timezone' }),
    ]);
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(
      await within(batch).findByText(/no pending facts are attached/i),
    ).toBeInTheDocument();
    expect(within(batch).queryByText(/truncated|capped/i)).not.toBeInTheDocument();
  });

  it('reports how many of the batch it can show when a capped page carries some', async () => {
    const page = cappedInboxPage();
    page[0] = proposalRow({ id: 'f1', entityId: 'ent-7', factKey: 'preferred_editor', valueText: 'vim' });
    page[1] = proposalRow({ id: 'f2', entityId: 'ent-7', factKey: 'release_channel', valueText: 'email' });
    vi.mocked(listMemoryProposals).mockResolvedValue(page);
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(await within(batch).findByText(/may be truncated|capped/i)).toBeInTheDocument();
    expect(within(batch).getByText(/showing 2 pending fact/i)).toBeInTheDocument();
    expect(within(batch).getAllByRole('listitem')).toHaveLength(2);
    // Still no completeness claim in either direction.
    expect(within(batch).queryByText(/adds the entity alone/i)).not.toBeInTheDocument();
  });

  // Task 16 settled a proposal the PENDING inbox no longer lists, but the reducer
  // had no state for it, so the card could not say why it settled — and any
  // non-pending state rendered as "Rejected". The bounded inbox cannot tell an
  // approval from a rejection, so the card must say "decided elsewhere" and
  // never invent a verdict.
  it('renders a proposal decided elsewhere honestly, never as approved or rejected', () => {
    render(<AriaProposalCard item={proposal({ state: 'decided-elsewhere' })} onSettled={vi.fn()} />);

    const notice = screen.getByTestId('aria-proposal-decided-elsewhere');
    expect(notice).toHaveTextContent(/no longer pending/i);
    expect(notice).toHaveTextContent(/cannot say/i);
    // No invented verdict…
    expect(screen.queryByText(/will never become memory/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/now active/i)).not.toBeInTheDocument();
    // …and no control that could only 409.
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'decided-elsewhere');
  });

  it('does not read an entity batch for a proposal already decided elsewhere', () => {
    render(<AriaProposalCard item={entityItem({ state: 'decided-elsewhere' })} onSettled={vi.fn()} />);

    expect(listMemoryProposals).not.toHaveBeenCalled();
    expect(screen.queryByTestId('proposal-batch')).not.toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-decided-elsewhere')).toBeInTheDocument();
  });

  it('does not read the proposals inbox for a single fact', () => {
    render(<AriaProposalCard item={proposal()} onSettled={vi.fn()} />);

    expect(listMemoryProposals).not.toHaveBeenCalled();
    expect(screen.queryByTestId('proposal-batch')).not.toBeInTheDocument();
  });

  it('says an unreadable entity batch could not be read and stays decidable', async () => {
    vi.mocked(listMemoryProposals).mockRejectedValue({ response: { status: 403, data: {} } });
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    expect(await screen.findByText(/could not be read/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    // A refused read proves nothing about the entity itself.
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });
});
