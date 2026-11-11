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

  // A 409 from `decide` means the proposal was already decided somewhere else
  // (the Memory page, another operator, a re-proposal superseded). Retrying can
  // never succeed, so the card settles as `decided-elsewhere` — the same honest
  // state the panel's reconcile uses — instead of offering the decision again.
  it('settles a 409 as decided elsewhere instead of offering a retry', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({
      response: { status: 409, data: { message: 'proposal already decided' } },
    });
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    expect(await screen.findByTestId('aria-proposal-decided-elsewhere')).toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'decided-elsewhere');
    // Nothing is decided by this call, and no control that could only 409 again.
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument();
    expect(screen.queryByText('proposal already decided')).not.toBeInTheDocument();
    expect(onSettled).toHaveBeenCalledWith('e4d7', 'decided-elsewhere');
  });

  // Positive control for the 409 above: any other failure is still an error the
  // operator can retry, because the proposal may well still be pending.
  it('keeps a 500 as an error with a retry', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({
      response: { status: 500, data: { message: 'memory store unavailable' } },
    });
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    expect(await screen.findByText('memory store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(onSettled).not.toHaveBeenCalled();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'pending');
  });

  it('a restored settled proposal renders its outcome instead of a decision control', () => {
    render(<AriaProposalCard item={proposal({ state: 'rejected' })} onSettled={vi.fn()} />);

    expect(screen.getByText(/will never become memory/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
  });

  // T14 review m4: an entity proposed without a display name carries its
  // canonical key as the value too, and the card printed it twice — once as the
  // title's key pill, once as the value line.
  it('prints the canonical key once when there is no display name', async () => {
    render(
      <AriaProposalCard
        item={entityItem({ factKey: 'person:happy', valueText: 'person:happy' })}
        onSettled={vi.fn()}
      />,
    );

    expect(await screen.findByTestId('proposal-batch')).toBeInTheDocument();
    expect(screen.getAllByText('person:happy')).toHaveLength(1);
    expect(screen.queryByTestId('proposal-value')).not.toBeInTheDocument();
  });

  // Positive control: a real display name still gets its own line.
  it('keeps the value line when it differs from the canonical key', async () => {
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    expect(await screen.findByTestId('proposal-batch')).toBeInTheDocument();
    expect(screen.getByText('person:happy')).toBeInTheDocument();
    expect(screen.getByTestId('proposal-value')).toHaveTextContent('Happy');
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
    // The entity's OWN batch, asked for by id: the route answers it uncapped, so
    // the card no longer has to guess from a shared page.
    expect(listMemoryProposals).toHaveBeenCalledWith('PENDING', 'ent-7');
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
   * `GET /proposals?status=&entityId=` answers ONE entity's whole PENDING batch —
   * its facts, its relations in either direction and its own row — uncapped. The
   * card may therefore say the list is complete, and must not hedge with the
   * global inbox's page size.
   */
  it('states the batch is complete and lists every fact of it', async () => {
    vi.mocked(listMemoryProposals).mockResolvedValue([
      proposalRow(),
      proposalRow({ id: 'ent-7', subjectType: 'ENTITY', factKey: 'person:happy' }),
      proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
      proposalRow({ id: 'rel-1', subjectType: 'RELATION', factKey: 'reports_to', entityId: 'ent-7' }),
    ]);
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(listMemoryProposals).toHaveBeenCalledWith('PENDING', 'ent-7');
    expect(await within(batch).findByTestId('proposal-batch-complete')).toHaveTextContent(
      /complete/i,
    );
    expect(within(batch).getAllByRole('listitem')).toHaveLength(2);
    // No hedge survives: this read cannot have been cut by the inbox page.
    expect(within(batch).queryByText(/truncated|capped|may not be on it/i)).not.toBeInTheDocument();
  });

  it('claims completeness from the uncapped read, not from the page size', async () => {
    // A full inbox page is not proof of anything for the global inbox, but this
    // read is the per-entity one: an entity with this many facts gets all of them.
    const wholeBatch = Array.from({ length: 50 }, (_, i) =>
      proposalRow({ id: `f-${i}`, factKey: `fact_${i}`, valueText: `value_${i}` }),
    );
    vi.mocked(listMemoryProposals).mockResolvedValue(wholeBatch);
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(within(batch).getAllByRole('listitem')).toHaveLength(50);
    expect(await within(batch).findByTestId('proposal-batch-complete')).toHaveTextContent(
      /complete/i,
    );
    expect(within(batch).queryByText(/truncated|capped/i)).not.toBeInTheDocument();
  });

  it('says the entity stands alone when its own batch carries no facts', async () => {
    // The per-entity read answers with the entity's own row and nothing else: a
    // provable empty batch, not a guess from one shared page.
    vi.mocked(listMemoryProposals).mockResolvedValue([
      proposalRow({ id: 'ent-7', subjectType: 'ENTITY', factKey: 'person:happy', valueText: 'Happy' }),
    ]);
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(
      await within(batch).findByText(/no pending facts are attached/i),
    ).toBeInTheDocument();
    expect(within(batch).queryByText(/truncated|capped/i)).not.toBeInTheDocument();
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
