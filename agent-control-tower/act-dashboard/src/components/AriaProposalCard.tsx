import { useEffect, useState } from 'react';
import { decideMemoryProposal, listMemoryProposals, type MemoryProposalDecision, type MemoryProposalView } from '../api/memory';
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';

/** Task 4's in-panel proposal item; this card adds no contract of its own. */
export type ProposalPanelItem = Extract<PanelItem, { kind: 'proposal' }>;

export interface AriaProposalCardProps {
  item: ProposalPanelItem;
  /**
   * Called once a decision committed, so the panel can settle the card through
   * the same state it merges every other plane into. A refused decision never
   * calls it — nothing was decided.
   */
  onSettled?: (proposalId: string, state: 'approved' | 'rejected') => void;
}

/** Human title per subject type; ENTITY and FACT are the two shipped today. */
function titleOf(subjectType: ProposalPanelItem['subjectType']): string {
  switch (subjectType) {
    case 'ENTITY':
      return 'New entity';
    case 'RELATION':
      return 'New relation';
    default:
      return 'New fact';
  }
}

/**
 * The review inbox page size (`MemoryProposalService.INBOX_LIMIT`): one
 * newest-first page across ALL entities, so a full page cannot prove that an
 * entity's own facts are on it.
 */
const INBOX_LIMIT = 50;

/** The batch read's outcome: the facts (and whether the page was capped), or that the inbox could not be read. */
type BatchRead =
  | { facts: MemoryProposalView[]; truncated: boolean }
  | { unreadable: true };

/**
 * The PENDING facts an ENTITY decision decides with the entity, read from the
 * proposals inbox the operator decides from (there is no per-entity PENDING
 * read: a pending entity 404s the detail route). Null while the read is in
 * flight, and undefined for any subject type that is not an entity — only the
 * entity proposal is a batch decision.
 */
function useEntityBatch(item: ProposalPanelItem): BatchRead | null | undefined {
  const entityId = item.entityId;
  const isEntity = item.subjectType === 'ENTITY';
  const [batch, setBatch] = useState<BatchRead | null | undefined>(undefined);

  useEffect(() => {
    // An entity proposal without its entity id cannot be attributed a batch;
    // listing every pending fact would misrepresent what this decision covers.
    if (!isEntity || !entityId) {
      setBatch(undefined);
      return undefined;
    }
    let cancelled = false;
    setBatch(null);
    listMemoryProposals('PENDING')
      .then((rows) => {
        if (cancelled) return;
        setBatch({
          facts: rows.filter((row) => row.subjectType === 'FACT' && row.entityId === entityId),
          // A page at the cap may have been cut; only a shorter page is provably
          // the whole inbox.
          truncated: rows.length >= INBOX_LIMIT,
        });
      })
      .catch(() => {
        // A refused or failed read proves nothing about the batch; say so rather
        // than present an empty one, which would claim the entity stands alone.
        if (!cancelled) setBatch({ unreadable: true });
      });
    return () => { cancelled = true; };
  }, [isEntity, entityId]);

  return batch;
}


/**
 * The in-conversation memory proposal card (spec C1/C2): Aria proposes a
 * memory entity or fact from inside the chat, and the operator decides it right
 * there through the operator-only memory route.
 *
 * The card is presentational over Task 4's `proposal` item: the item's
 * `factKey`/`valueText` carry `canonicalKey`/`displayName` for an ENTITY
 * proposal (Task 10's mapping), so one card renders both shapes. An ENTITY
 * decision is the whole batch — it promotes (or retires) every PENDING fact of
 * the entity — so that card lists the batch before the decision. A decision is
 * authoritative on the server; the card flips to the settled state only after
 * the call resolves, and a 401/403 renders the local operator-session notice
 * instead of pretending the proposal was decided.
 */
export function AriaProposalCard({ item, onSettled }: AriaProposalCardProps) {
  // Local settlement, layered over the item's own state: a re-render with a
  // settled item wins, so the panel stays the single owner of restored history.
  const [local, setLocal] = useState<'approved' | 'rejected' | null>(null);
  const [inFlight, setInFlight] = useState<MemoryProposalDecision | null>(null);
  const [error, setError] = useState<{ text: string; operatorRejected: boolean } | null>(null);
  const batch = useEntityBatch(item);

  const state = local ?? item.state;

  const decide = async (decision: MemoryProposalDecision) => {
    setInFlight(decision);
    setError(null);
    try {
      // Operator-only route: carry the locally established session's CSRF token
      // at the moment of the click (the mutation also applies it; this is the
      // same belt-and-braces posture ReviewPanels takes for approvals).
      applyOperatorHeaders();
      await decideMemoryProposal(item.proposalId, decision);
      const next = decision === 'APPROVE' ? 'approved' : 'rejected';
      setLocal(next);
      onSettled?.(item.proposalId, next);
    } catch (err: unknown) {
      setError({
        text: apiErrorMessage(err, 'The proposal was not decided — retry.'),
        operatorRejected: isOperatorRejection(err),
      });
    } finally {
      setInFlight(null);
    }
  };

  return (
    <div
      className="ask-card"
      data-testid="aria-proposal-card"
      data-subject-type={item.subjectType}
      data-state={state}
      data-proposal-id={item.proposalId}
    >
      <div className="ask-q">
        {titleOf(item.subjectType)} <span className="pill">{item.factKey ?? '—'}</span>
      </div>
      {item.valueText && (
        <div className="ask-ctx">
          <span data-testid="proposal-value">{item.valueText}</span>
        </div>
      )}
      {item.subjectType === 'ENTITY' && state === 'pending' && (
        <div className="ask-facts" data-testid="proposal-batch">
          <div className="ask-ctx">
            One decision decides this entity and every pending fact of it.
          </div>
          {batch === null && <div className="ask-ctx">Reading its pending facts…</div>}
          {batch !== null && batch !== undefined && 'unreadable' in batch && (
            <div className="ask-ctx">
              The pending facts this decision also decides could not be read.
            </div>
          )}
          {batch !== null && batch !== undefined && 'facts' in batch && (
            batch.facts.length === 0 ? (
              batch.truncated ? (
                // A page at the cap cannot prove the batch is empty. Naming the
                // cap and the count is honest; "adds the entity alone" is not.
                <div className="ask-ctx" data-testid="proposal-batch-truncated">
                  The proposal inbox page is capped at {INBOX_LIMIT}, so this entity&apos;s
                  pending facts may not be on it. Showing 0 pending facts of this entity here —
                  approving still promotes its whole batch.
                </div>
              ) : (
                <div className="ask-ctx">No pending facts are attached — approving adds the entity alone.</div>
              )
            ) : (
              <>
                {batch.truncated && (
                  <div className="ask-ctx" data-testid="proposal-batch-truncated">
                    The proposal inbox page is capped at {INBOX_LIMIT}, so this list may be
                    truncated. Showing {batch.facts.length} pending facts of this entity here —
                    approving promotes its whole batch.
                  </div>
                )}
                <ul className="ask-facts-list">
                  {batch.facts.map((fact) => (
                    <li key={fact.id}>
                      <code>{fact.factKey ?? '—'}</code>
                      {' = '}
                      <span>{fact.valueText ?? '—'}</span>
                    </li>
                  ))}
                </ul>
              </>
            )
          )}
        </div>
      )}
      <div className="ask-meta">
        <span className={`pill ${state === 'pending' ? 'warn' : state === 'approved' ? 'ok' : 'dim'}`}>
          {state}
        </span>
        {item.sensitivity && <span className="pill dim">{item.sensitivity}</span>}
      </div>

      {state === 'pending' ? (
        <div className="ask-actions">
          <button
            type="button"
            className="btn primary"
            disabled={inFlight !== null}
            onClick={() => void decide('APPROVE')}
          >
            {inFlight === 'APPROVE' ? 'Approving…' : 'Approve'}
          </button>
          <button
            type="button"
            className="btn"
            disabled={inFlight !== null}
            onClick={() => void decide('REJECT')}
          >
            {inFlight === 'REJECT' ? 'Rejecting…' : 'Reject'}
          </button>
        </div>
      ) : (
        <div className="ask-ctx" role="status">
          {state === 'approved'
            ? 'Approved — this memory is now active.'
            : 'Rejected — this proposal will never become memory.'}
        </div>
      )}

      {error && (
        <>
          <div className="error-text" role="alert">
            {error.text}
          </div>
          {error.operatorRejected && <OperatorSessionNotice />}
        </>
      )}
    </div>
  );
}

/**
 * The operator-session notice shared by this lane's decision surfaces
 * (`ReviewPanels.tsx`'s posture, reused verbatim in meaning). It lives here
 * because the split-task file ownership gives this lane no shared-component
 * file; MemoryPage imports it from this module.
 */
export function OperatorSessionNotice() {
  return (
    <div className="ask-ctx" data-testid="operator-session-notice">
      Memory decisions are operator-only. On this machine, loopback requests are the
      operator automatically — reload the page and retry. From a remote machine, the
      operator who runs the backend must grant access (the server's operator
      credential); the dashboard has no remote token entry. Nothing was decided by the
      refused call.
    </div>
  );
}

export default AriaProposalCard;
