import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { AriaProposalCard, PROPOSAL_INBOX_LIMIT } from '../AriaProposalCard';
import type { PanelItem } from '../ariaPanelState';
// T48 Item 2: the two sides of the inbox-page pin are read from the shipped
// sources (the same `?raw` technique `errorSurfaceContract.test.ts` uses), so a
// drift on either side fails here instead of settling a live card.
import proposalServiceSource from '../../../../act-memory/src/main/java/io/aria/conductor/memory/service/MemoryProposalService.java?raw';
import ariaPanelSource from '../AriaPanel.tsx?raw';

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
    // Task 35: one remedy, and it is one that can work — a reload does not
    // restore an operator session on a remote machine.
    const notice = screen.getByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/memory decisions are operator-only/i);
    expect(notice).toHaveTextContent(/open the dashboard on the machine that runs the backend/i);
    expect(notice).not.toHaveTextContent(/reload the page and retry/i);
    expect(notice).not.toHaveTextContent(/reload and try again/i);
    // Nothing was decided: the buttons are still there, the card is not flipped.
    expect(screen.getByRole('button', { name: /approve/i })).toBeInTheDocument();
    expect(screen.queryByText(/^approved/i)).not.toBeInTheDocument();
    expect(onSettled).not.toHaveBeenCalled();
  });

  // A 409 from `decide` means the proposal was already decided somewhere else
  // (the Memory page, another operator, a re-proposal superseded). Retrying can
  // never succeed, so the card settles as `decided-elsewhere` — the same honest
  // state the panel's reconcile uses — instead of offering the decision again.
  //
  // The body is the one `MemoryExceptionHandler` actually serves for that case:
  // `409 { ok:false, errorType:'CONFLICT', message }` with the fixed
  // "a decided proposal cannot be reopened" wording.
  it('settles an already-decided 409 as decided elsewhere instead of offering a retry', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({
      response: {
        status: 409,
        data: {
          ok: false,
          errorType: 'CONFLICT',
          message: 'memory entity ent-7 is already ACTIVE; a decided proposal cannot be reopened',
        },
      },
    });
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    expect(await screen.findByTestId('aria-proposal-decided-elsewhere')).toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'decided-elsewhere');
    // Positive control for the parent-PENDING branch: the already-decided body
    // does not carry that marker, so it must not settle as blocked on an entity.
    expect(screen.queryByTestId('aria-proposal-blocked-on-entity')).not.toBeInTheDocument();
    // Nothing is decided by this call, and no control that could only 409 again.
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reject/i })).not.toBeInTheDocument();
    expect(
      screen.queryByText(/a decided proposal cannot be reopened/),
    ).not.toBeInTheDocument();
    expect(onSettled).toHaveBeenCalledWith('e4d7', 'decided-elsewhere');
  });

  // Task 37 Item 1 / Task 41 Major 2: a FACT proposal whose parent entity is
  // still PENDING answers its own 409 only on the APPROVE path —
  // `MemoryProposalService.decideFact` returns for REJECT at :298-309, before the
  // parent-status check at :326-335, so rejecting that one fact is still accepted.
  // The card must remove only the control that can only fail again (Approve),
  // keep Reject working, and say exactly which refusal happened.
  it('settles a parent-still-PENDING 409 on the entity decision, naming it and keeping Reject', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({
      response: {
        status: 409,
        data: {
          ok: false,
          errorType: 'CONFLICT',
          message:
            'memory fact e4d7 belongs to entity ent-7, which is still PENDING; decide the entity '
            + 'proposal ent-7 to promote or retire the whole batch instead of approving one fact',
        },
      },
    });
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    const notice = await screen.findByTestId('aria-proposal-blocked-on-entity');
    // What happened, and the operator action that resolves it — the entity id
    // comes from the served message, not from a guess.
    expect(notice).toHaveTextContent(/ent-7/);
    expect(notice).toHaveTextContent(/decide the entity proposal/i);
    // The refusal is specific: approving THIS fact alone. Rejecting it alone still
    // works, and the copy must say so instead of claiming the whole card is dead.
    expect(notice).toHaveTextContent(/approving (this fact|it) on its own was refused/i);
    expect(notice).toHaveTextContent(/rejecting this fact alone still works/i);
    // The proposal itself is still pending on the server: this card must not
    // claim it left the queue (that copy belongs to `decided-elsewhere`).
    expect(notice).not.toHaveTextContent(/no longer pending/i);
    // No verdict was recorded, and no control that could only 409 again.
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute(
      'data-state',
      'blocked-on-entity',
    );
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^reject$/i })).toBeEnabled();
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(onSettled).toHaveBeenCalledWith('e4d7', 'blocked-on-entity');
  });

  // The surviving half: the same blocked card's Reject reaches the server (which
  // accepts it under a PENDING parent) and settles the card as rejected — never
  // as approved, and never back to pending.
  it('rejects the fact from the blocked state and settles it rejected', async () => {
    vi.mocked(decideMemoryProposal)
      .mockRejectedValueOnce({
        response: {
          status: 409,
          data: {
            ok: false,
            errorType: 'CONFLICT',
            message:
              'memory fact e4d7 belongs to entity ent-7, which is still PENDING; decide the entity '
              + 'proposal ent-7 to promote or retire the whole batch instead of approving one fact',
          },
        },
      })
      .mockResolvedValueOnce(proposalRow({ status: 'RETIRED' }));
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    await screen.findByTestId('aria-proposal-blocked-on-entity');

    fireEvent.click(screen.getByRole('button', { name: /^reject$/i }));

    await waitFor(() =>
      expect(vi.mocked(decideMemoryProposal)).toHaveBeenLastCalledWith('e4d7', 'REJECT'),
    );
    await waitFor(() => expect(onSettled).toHaveBeenLastCalledWith('e4d7', 'rejected'));
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'rejected');
    expect(screen.queryByTestId('aria-proposal-blocked-on-entity')).not.toBeInTheDocument();
  });

  // The same marker on the ENTITY batch card would be a contradiction (an entity
  // card cannot be blocked on its own decision), so the settled copy is chosen by
  // STATE, not by subject type: an entity card in this state still names the
  // entity decision and never claims the proposal left the PENDING queue.
  it('never tells an entity batch card its proposal is no longer pending', () => {
    render(
      <AriaProposalCard
        item={entityItem({ state: 'blocked-on-entity' })}
        onSettled={vi.fn()}
      />,
    );

    const notice = screen.getByTestId('aria-proposal-blocked-on-entity');
    expect(notice).toHaveTextContent(/entity proposal/i);
    expect(notice).not.toHaveTextContent(/no longer pending/i);
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approve/i })).not.toBeInTheDocument();
  });

  // Task 32 Item 2: the same handler maps a retryable optimistic-lock conflict to
  // 409 as well, with the same `errorType`. Reporting that as "decided outside
  // this panel" is false and throws away a retry that would have worked, so only
  // the already-decided wording settles the card.
  it('keeps a retryable lock-conflict 409 as an ordinary error with a retry', async () => {
    vi.mocked(decideMemoryProposal).mockRejectedValue({
      response: {
        status: 409,
        data: {
          ok: false,
          errorType: 'CONFLICT',
          message:
            'memory entity ent-7 changed while its proposal was being decided; re-read before deciding',
        },
      },
    });
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    // The server's own reason, the decision still open, and the card not settled.
    expect(
      await screen.findByText(/changed while its proposal was being decided/i),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('aria-proposal-decided-elsewhere')).not.toBeInTheDocument();
    expect(screen.queryByTestId('aria-proposal-blocked-on-entity')).not.toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'pending');
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /reject/i })).toBeEnabled();
    expect(onSettled).not.toHaveBeenCalled();
  });

  // Positive control for the retry above: the queue really does accept the second
  // attempt, and it flips the card then.
  it('decides on the retry after a lock-conflict 409', async () => {
    vi.mocked(decideMemoryProposal)
      .mockRejectedValueOnce({
        response: {
          status: 409,
          data: {
            ok: false,
            errorType: 'CONFLICT',
            message:
              'memory fact e4d7 changed while the approval was being written; re-read before deciding',
          },
        },
      })
      .mockResolvedValueOnce({
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
    const onSettled = vi.fn();
    render(<AriaProposalCard item={proposal()} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    await screen.findByText(/changed while the approval was being written/i);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    expect(await screen.findByText(/now active/i)).toBeInTheDocument();
    expect(onSettled).toHaveBeenCalledWith('e4d7', 'approved');
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
    expect(screen.queryByTestId('aria-proposal-blocked-on-entity')).not.toBeInTheDocument();
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
   * its FACTS plus its own row — uncapped. (A batch is the entity's pending facts
   * and nothing else: the relation graph is out of scope, spec :138/:453, so the
   * RELATION row below must not reach the list.) The card may therefore say the
   * list is complete, and must not hedge with the global inbox's page size.
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

  // Task 32 Item 1: the batch is read when the card mounts and the decision runs
  // when the operator clicks. A background extraction can add another PENDING
  // fact to the same entity in between, and `decideEntity` promotes EVERY PENDING
  // fact of it — so a mount-time list lets the operator approve a value the card
  // never showed. The card therefore re-reads the batch at click time and posts
  // only against the set on screen.
  it('re-reads the batch before deciding and shows a fact added since the card rendered', async () => {
    vi.mocked(listMemoryProposals)
      // The mount read: one fact.
      .mockResolvedValueOnce([proposalRow()])
      // The click-time read: a second PENDING fact landed in between.
      .mockResolvedValueOnce([
        proposalRow(),
        proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
      ])
      // The confirming click reads the same set the operator now sees.
      .mockResolvedValueOnce([
        proposalRow(),
        proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
      ]);
    const onSettled = vi.fn();
    render(<AriaProposalCard item={entityItem()} onSettled={onSettled} />);

    const batch = await screen.findByTestId('proposal-batch');
    await within(batch).findByTestId('proposal-batch-complete');
    expect(within(batch).getAllByRole('listitem')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    // Nothing is posted against the set the operator has not seen…
    await waitFor(() => expect(screen.getByTestId('proposal-batch-changed')).toBeInTheDocument());
    expect(decideMemoryProposal).not.toHaveBeenCalled();
    expect(onSettled).not.toHaveBeenCalled();
    // …and the updated batch is now on screen, so the next click confirms it.
    expect(within(batch).getAllByRole('listitem')).toHaveLength(2);
    expect(within(batch).getByText('release_channel')).toBeInTheDocument();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'pending');

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('ent-7', 'APPROVE'));
    expect(decideMemoryProposal).toHaveBeenCalledTimes(1);
    await screen.findByText(/now active/i);
    expect(onSettled).toHaveBeenCalledWith('ent-7', 'approved');
  });

  // Positive control for the re-read above: when the click-time set matches what
  // the card shows, the decision posts on the first click with no extra friction.
  it('posts on the first click when the re-read matches the batch on screen', async () => {
    vi.mocked(listMemoryProposals).mockResolvedValue([proposalRow()]);
    const onSettled = vi.fn();
    render(<AriaProposalCard item={entityItem()} onSettled={onSettled} />);

    const batch = await screen.findByTestId('proposal-batch');
    await within(batch).findByTestId('proposal-batch-complete');

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('ent-7', 'APPROVE'));
    // One read at mount, one at the decision: the decision was checked, not taken
    // on faith, and the operator was asked nothing extra.
    expect(listMemoryProposals).toHaveBeenCalledTimes(2);
    expect(screen.queryByTestId('proposal-batch-changed')).not.toBeInTheDocument();
    await screen.findByText(/now active/i);
    expect(onSettled).toHaveBeenCalledWith('ent-7', 'approved');
  });

  // A decision whose batch cannot be re-read proves nothing about what it would
  // promote, so it is not posted — the same posture the mount-time read takes.
  it('decides nothing when the batch cannot be re-read at decision time', async () => {
    vi.mocked(listMemoryProposals)
      .mockResolvedValueOnce([proposalRow()])
      .mockRejectedValueOnce({ response: { status: 503, data: {} } });
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    await within(batch).findByTestId('proposal-batch-complete');

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not be re-read/i);
    expect(decideMemoryProposal).not.toHaveBeenCalled();
    expect(screen.getByTestId('aria-proposal-card')).toHaveAttribute('data-state', 'pending');
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();
    // The batch it could not verify is no longer presented as a complete list.
    expect(screen.queryByTestId('proposal-batch-complete')).not.toBeInTheDocument();
    expect(screen.getByText(/could not be read/i)).toBeInTheDocument();
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

// ---------------------------------------------------------------------------
// T48 Item 2 (P2, re-review N2). `PROPOSAL_INBOX_LIMIT` is the client's copy of
// the server's PENDING-inbox page size (`MemoryProposalService.INBOX_LIMIT`), and
// the panel decides "this page is the whole inbox" from `rows.length <
// PROPOSAL_INBOX_LIMIT`. If the server page ever grows past the client's copy, a
// CAPPED page reads as complete and the panel settles still-PENDING cards as
// `decided-elsewhere` — the operator loses a live decision.
//
// The brief chooses a falsifiable cross-tier pin over raising the number, so the
// two constants are read from source, one per side: changing EITHER alone fails.
// The client constant is already single-sourced (the panel imports it), which the
// second test holds in place — a second literal in the panel is the other way
// this rule can go stale.
// ---------------------------------------------------------------------------

/** `MemoryProposalService.INBOX_LIMIT`, read from the shipped Java source. */
function serverInboxLimit(): number {
  const match = proposalServiceSource.match(/static final int INBOX_LIMIT = (\d+);/);
  if (!match) {
    throw new Error('no `static final int INBOX_LIMIT = <n>;` in MemoryProposalService.java');
  }
  return Number(match[1]);
}

describe('the client proposal-inbox cap equals the server page size', () => {
  it('pins both sides of the inbox page size to one another', () => {
    // Positive control: the parse really read the source, so the equality below
    // cannot pass by comparing two values that were never read (absence != 0).
    expect(proposalServiceSource).toContain('class MemoryProposalService');
    expect(serverInboxLimit()).toBeGreaterThan(1);

    expect(serverInboxLimit()).toBe(PROPOSAL_INBOX_LIMIT);
  });

  it('holds the client constant at one source, so the panel cannot carry a second copy', () => {
    // The panel's completeness rule must keep reading the shared constant…
    expect(ariaPanelSource).toContain('PROPOSAL_INBOX_LIMIT');
    expect(ariaPanelSource).toMatch(/rows\.length < PROPOSAL_INBOX_LIMIT/);
    // …and must not reintroduce the number it was copied from.
    expect(ariaPanelSource).not.toMatch(/rows\.length\s*<\s*\d+/);
  });
});

// ---------------------------------------------------------------------------
// T48 Item 3-8 (P3, re-review N5). The batch signature is null while the mount
// read has not answered and when that read FAILED, and the decision-time re-read
// was compared against that null — so the first click rendered "the pending facts
// this decision covers CHANGED while this card was open" when nothing had
// changed: the earlier read had simply not returned (or had failed). The decision
// was correctly withheld, so this is a wording and control defect:
//   - while the mount read is in flight the decision controls are disabled, so a
//     click cannot be interpreted against a set the operator has not seen;
//   - after a FAILED mount read the card says the earlier read failed, and the
//     list it now shows is what a second click confirms.
// The "really changed" case keeps its own copy (pinned above).
// ---------------------------------------------------------------------------
describe('a batch read in flight is not reported as a change (T48, seam Minor-2)', () => {
  it('disables the decision while the mount read is answering, and claims no change', async () => {
    let resolveMount: (rows: MemoryProposalView[]) => void = () => {};
    // The set the mount read finally answers with. The decision-time re-read is a
    // second call and MUST answer the same set here: otherwise this test would
    // exercise the "changed" branch instead of the control it is about.
    const onScreen = () => [
      proposalRow(),
      proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
    ];
    vi.mocked(listMemoryProposals)
      .mockReturnValueOnce(
        new Promise<MemoryProposalView[]>((resolve) => { resolveMount = resolve; }),
      )
      .mockResolvedValueOnce(onScreen());
    render(<AriaProposalCard item={entityItem()} onSettled={vi.fn()} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(within(batch).getByText(/reading its pending facts/i)).toBeInTheDocument();
    // The operator cannot click a decision over a batch that is still arriving.
    expect(screen.getByRole('button', { name: /approve/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /reject/i })).toBeDisabled();
    expect(screen.queryByTestId('proposal-batch-changed')).not.toBeInTheDocument();

    // Positive control: once the read answers, the same controls work and the
    // first click decides with no "changed" friction.
    await act(async () => {
      resolveMount(onScreen());
    });
    await within(batch).findByTestId('proposal-batch-complete');
    expect(screen.getByRole('button', { name: /approve/i })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('ent-7', 'APPROVE'));
    expect(screen.queryByTestId('proposal-batch-changed')).not.toBeInTheDocument();
  });

  it('says the earlier read failed instead of claiming the set changed', async () => {
    vi.mocked(listMemoryProposals)
      // The mount read fails: nothing was ever shown, so nothing can have changed.
      .mockRejectedValueOnce({ response: { status: 503, data: {} } })
      // The decision-time read answers with the real batch.
      .mockResolvedValueOnce([
        proposalRow(),
        proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
      ])
      // The confirming click reads the same set the operator now sees.
      .mockResolvedValueOnce([
        proposalRow(),
        proposalRow({ id: 'f2', factKey: 'release_channel', valueText: 'email' }),
      ]);
    const onSettled = vi.fn();
    render(<AriaProposalCard item={entityItem()} onSettled={onSettled} />);

    const batch = await screen.findByTestId('proposal-batch');
    expect(await within(batch).findByText(/could not be read/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));

    const notice = await screen.findByTestId('proposal-batch-unread-on-open');
    expect(notice).toHaveTextContent(/could not be read when this card opened/i);
    expect(notice).toHaveTextContent(/nothing has been decided/i);
    // The false claim this finding is about: nothing changed. The list is simply
    // the first one the operator can see.
    expect(notice).not.toHaveTextContent(/changed/i);
    expect(screen.queryByTestId('proposal-batch-changed')).not.toBeInTheDocument();
    expect(decideMemoryProposal).not.toHaveBeenCalled();
    expect(onSettled).not.toHaveBeenCalled();
    expect(within(batch).getAllByRole('listitem')).toHaveLength(2);

    // The confirming click decides exactly the set now on screen.
    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    await waitFor(() => expect(decideMemoryProposal).toHaveBeenCalledWith('ent-7', 'APPROVE'));
  });
});
