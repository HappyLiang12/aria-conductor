import { useEffect, useState } from 'react';
import { decideMemoryProposal, listMemoryProposals, type MemoryProposalDecision, type MemoryProposalView } from '../api/memory';
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';

/** Task 4's in-panel proposal item; this card adds no contract of its own. */
export type ProposalPanelItem = Extract<PanelItem, { kind: 'proposal' }>;

export interface AriaProposalCardProps {
  item: ProposalPanelItem;
  /**
   * Called once the proposal settled locally, so the panel can settle the card
   * through the same state it merges every other plane into. `decided-elsewhere`
   * is reported for a 409 whose body says the proposal was ALREADY decided: the
   * server says it is settled, and the card cannot know by which verdict. A 409
   * the handler serves for a retryable lock conflict does not settle anything,
   * and a refused decision never calls it — nothing was decided.
   */
  onSettled?: (proposalId: string, state: 'approved' | 'rejected' | 'decided-elsewhere') => void;
}

/** The HTTP status a failed call answered with, or null when it did not answer. */
function responseStatus(error: unknown): number | null {
  const status = (error as { response?: { status?: number } } | undefined)?.response?.status;
  return typeof status === 'number' ? status : null;
}

/**
 * `MemoryExceptionHandler` serves ONE memory error envelope — `{ ok: false,
 * errorType, message }` — and maps BOTH `MemoryConflictException` and a
 * retryable `OptimisticLockingFailureException` to `409 CONFLICT`. Status and
 * `errorType` are therefore identical for the two cases, and the fixed wording
 * is the only discriminator the body carries:
 *
 * - already decided (retry cannot succeed): every such throw in
 *   `MemoryProposalService` ends "a decided proposal cannot be reopened";
 * - lost version race (retry can succeed): the row "changed while ... was being
 *   ...; re-read before deciding" / "another writer won the race".
 *
 * So a 409 settles the card only when the body says the proposal was already
 * decided. Anything else keeps the ordinary error path and its retry.
 */
const ALREADY_DECIDED_MARKER = 'a decided proposal cannot be reopened';

function isAlreadyDecidedConflict(error: unknown): boolean {
  if (responseStatus(error) !== 409) return false;
  const data = (error as {
    response?: { data?: { errorType?: unknown; message?: unknown } };
  } | undefined)?.response?.data;
  if (data?.errorType !== 'CONFLICT') return false;
  return typeof data.message === 'string' && data.message.includes(ALREADY_DECIDED_MARKER);
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
 * `MemoryProposalService.INBOX_LIMIT` — the PENDING proposal inbox is ONE
 * newest-first page of at most this many rows across ALL entities.
 *
 * This is the single client-side source of that number: `AriaPanel` imports it
 * for the reconcile's completeness rule instead of keeping a second copy, so a
 * lowered server cap cannot leave one surface claiming a truncated page is the
 * whole inbox. This card's own batch read is the per-entity one, which the
 * server does not cap — the number bounds the global inbox, not this card.
 */
export const PROPOSAL_INBOX_LIMIT = 50;

/**
 * The batch read's outcome: the facts of the decision, or that they could not be
 * read. There is no "truncated" case: this read is the per-entity one, which the
 * server answers complete (see `PROPOSAL_INBOX_LIMIT` for the page the GLOBAL
 * inbox is bounded to, which this read is not).
 */
type BatchRead = { facts: MemoryProposalView[] } | { unreadable: true };

/**
 * This entity's own PENDING facts out of one `GET /proposals?status=PENDING&entityId=`
 * read. The entity's own row (and, before the server's batch correction, a
 * relation) is not part of what a decision promotes, so only FACT rows of THIS
 * entity are kept.
 */
function entityFactsOf(rows: MemoryProposalView[], entityId: string): MemoryProposalView[] {
  return rows.filter((row) => row.subjectType === 'FACT' && row.entityId === entityId);
}

/**
 * What the operator can actually see of the batch: the identity and value of
 * every fact the decision would promote. Two reads with the same signature
 * decide the same thing, so a decision may proceed against them; a different one
 * has to be shown first. Null when no list is on screen (still reading, no
 * entity id, or unreadable) — nothing was seen, so nothing may be decided.
 */
function batchSignature(batch: BatchRead | null | undefined): string | null {
  if (!batch || !('facts' in batch)) return null;
  return batch.facts
    .map((fact) => `${fact.id}\u0000${fact.factKey ?? ''}\u0000${fact.valueText ?? ''}`)
    .sort()
    .join('\u0001');
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
 * the entity — so that card lists the batch before the decision, and re-reads it
 * when the operator clicks: the list is a mount-time snapshot while the decision
 * runs at click time, and a background extraction can add a PENDING fact to the
 * same entity in between (Task 32). A set that changed since it was rendered is
 * shown and the decision waits for a fresh confirmation, so no value is ever
 * promoted that the operator did not see. A decision is authoritative on the
 * server; the card flips to the settled state only after the call resolves, and
 * a 401/403 renders the local operator-session notice instead of pretending the
 * proposal was decided. A proposal settled as `decided-elsewhere` (the panel's
 * PENDING reconcile no longer listed it) has no known outcome; the card reports
 * exactly that and offers no decision.
 */
export function AriaProposalCard({ item, onSettled }: AriaProposalCardProps) {
  // Local settlement, layered over the item's own state: a re-render with a
  // settled item wins, so the panel stays the single owner of restored history.
  const [local, setLocal] = useState<'approved' | 'rejected' | 'decided-elsewhere' | null>(null);
  const [inFlight, setInFlight] = useState<MemoryProposalDecision | null>(null);
  const [error, setError] = useState<{ text: string; operatorRejected: boolean } | null>(null);
  const entityId = item.entityId;
  // Only a PENDING entity proposal decides a batch; a settled one (including
  // `decided-elsewhere`, whose outcome this panel cannot know) must not read it.
  const isBatchDecision = item.subjectType === 'ENTITY' && item.state === 'pending';
  // The batch as rendered. Null while the read is in flight, undefined when this
  // card decides no batch (a fact/relation, or an entity with no id to read).
  const [batch, setBatch] = useState<BatchRead | null | undefined>(undefined);
  // Set when the decision-time re-read found a different set than the card
  // showed: the new set is on screen and the next click is its confirmation.
  const [batchChanged, setBatchChanged] = useState(false);

  useEffect(() => {
    // An entity proposal without its entity id cannot be attributed a batch;
    // listing every pending fact would misrepresent what this decision covers.
    if (!isBatchDecision || !entityId) {
      setBatch(undefined);
      return undefined;
    }
    let cancelled = false;
    setBatch(null);
    listMemoryProposals('PENDING', entityId)
      .then((rows) => {
        if (cancelled) return;
        setBatch({ facts: entityFactsOf(rows, entityId) });
      })
      .catch(() => {
        // A refused or failed read proves nothing about the batch; say so rather
        // than present an empty one, which would claim the entity stands alone.
        if (!cancelled) setBatch({ unreadable: true });
      });
    return () => { cancelled = true; };
  }, [isBatchDecision, entityId]);

  const state = local ?? item.state;

  const decide = async (decision: MemoryProposalDecision) => {
    setInFlight(decision);
    setError(null);
    try {
      // The batch is read at mount, the decision runs at click, and `decideEntity`
      // promotes every PENDING fact of the entity — so re-read the batch HERE and
      // post only against the set the card is showing. A set that changed since
      // it was rendered is displayed and the decision waits for a fresh
      // confirmation; a set that cannot be re-read is not decided at all.
      if (isBatchDecision && entityId) {
        let fresh: BatchRead;
        try {
          fresh = { facts: entityFactsOf(await listMemoryProposals('PENDING', entityId), entityId) };
        } catch (err: unknown) {
          setBatch({ unreadable: true });
          setError({
            text: apiErrorMessage(
              err,
              'The pending facts this decision also decides could not be re-read, so nothing was decided — retry.',
            ),
            operatorRejected: isOperatorRejection(err),
          });
          return;
        }
        if (batchSignature(fresh) !== batchSignature(batch)) {
          setBatch(fresh);
          setBatchChanged(true);
          return;
        }
        setBatchChanged(false);
      }
      // Operator-only route: carry the locally established session's CSRF token
      // at the moment of the click (the mutation also applies it; this is the
      // same belt-and-braces posture ReviewPanels takes for approvals).
      applyOperatorHeaders();
      await decideMemoryProposal(item.proposalId, decision);
      const next = decision === 'APPROVE' ? 'approved' : 'rejected';
      setLocal(next);
      onSettled?.(item.proposalId, next);
    } catch (err: unknown) {
      // A 409 saying the proposal is ALREADY settled — decided on the Memory
      // page, by another operator, or superseded by a re-proposal — cannot be
      // retried and leaves the panel's item wrong: settle both as
      // `decided-elsewhere`, the verdict-free state the reconcile already uses.
      // The handler serves a retryable optimistic-lock conflict as a 409 too, so
      // the body decides (see `isAlreadyDecidedConflict`): that one, and every
      // other status, is a real failure the proposal may survive, and keeps the
      // error with its retry.
      if (isAlreadyDecidedConflict(err)) {
        setLocal('decided-elsewhere');
        onSettled?.(item.proposalId, 'decided-elsewhere');
        return;
      }
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
      {/* An entity proposed without a display name carries its canonical key as
          the value too; printing it again here would say the same thing twice. */}
      {item.valueText && item.valueText !== item.factKey && (
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
              <div className="ask-ctx">No pending facts are attached — approving adds the entity alone.</div>
            ) : (
              <>
                <div className="ask-ctx" data-testid="proposal-batch-complete">
                  This is the entity&apos;s complete pending batch: {batch.facts.length}{' '}
                  fact{batch.facts.length === 1 ? '' : 's'} — one decision decides all of it.
                </div>
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
      {/* The decision-time re-read (Task 32) found a set the operator has not
          confirmed: the list above is the corrected one, and this click did not
          decide anything. A second click re-reads and, if the set still matches,
          decides exactly what is on screen. */}
      {batchChanged && state === 'pending' && (
        <div className="ask-ctx" role="alert" data-testid="proposal-batch-changed">
          The pending facts this decision covers changed while this card was open — the list
          above is what it covers now. Nothing has been decided: choose Approve or Reject again
          to confirm it.
        </div>
      )}
      <div className="ask-meta">
        <span className={`pill ${state === 'pending' ? 'warn' : state === 'approved' ? 'ok' : 'dim'}`}>
          {state === 'decided-elsewhere' ? 'decided elsewhere' : state}
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
      ) : state === 'decided-elsewhere' ? (
        // A PENDING inbox read no longer listed it, and that inbox is a bounded
        // page with no by-id outcome read: it cannot say whether the departure
        // was an approval, a rejection, or a later retirement. Saying so is the
        // honest report; "Approved" or "Rejected" here would be invented. No
        // decision control survives either — a decision would only 409.
        <div className="ask-ctx" role="status" data-testid="aria-proposal-decided-elsewhere">
          This proposal is no longer pending — it was decided outside this panel (the Memory
          page or another operator). The inbox cannot say whether it was approved or rejected;
          open the Memory page to see its outcome.
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
