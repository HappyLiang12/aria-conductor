import { useEffect, useState } from 'react';
import { decideMemoryProposal, listMemoryProposals, type MemoryProposalDecision, type MemoryProposalView } from '../api/memory';
import { OPERATOR_REMEDY_COPY, crossSiteSessionMessage, operatorSessionMessage } from '../api/errorPresentation';
import {
  apiErrorMessage,
  applyOperatorHeaders,
  isCrossOriginRejection,
  isOperatorRejection,
} from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';

/** Task 4's in-panel proposal item; this card adds no contract of its own. */
export type ProposalPanelItem = Extract<PanelItem, { kind: 'proposal' }>;

/** The settled reasons a proposal card can report without a verdict. */
export type ProposalSettledState = 'approved' | 'rejected' | 'decided-elsewhere' | 'blocked-on-entity';

export interface AriaProposalCardProps {
  item: ProposalPanelItem;
  /**
   * Called once the proposal settled locally, so the panel can settle the card
   * through the same state it merges every other plane into. `decided-elsewhere`
   * is reported for a 409 whose body says the proposal was ALREADY decided: the
   * server says it is settled, and the card cannot know by which verdict. A 409
   * the handler serves for a retryable lock conflict does not settle anything,
   * `blocked-on-entity` is reported for the non-retryable parent-still-PENDING
   * refusal (the entity proposal owns the decision), and a refused decision
   * never calls it — nothing was decided.
   */
  onSettled?: (proposalId: string, state: ProposalSettledState) => void;
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

/**
 * The parent-still-PENDING refusal, also a non-retryable 409 (same status, same
 * `errorType`): `MemoryProposalService.decideFact` refuses to promote one fact
 * under a PENDING entity, because the ENTITY proposal is the batch that owns it.
 * The message names that entity ("... which is still PENDING; decide the entity
 * proposal <entityId> to promote or retire the whole batch"). Retrying this
 * card's decision re-sends the same refused call, so it settles instead — on the
 * entity decision, which is the action that actually resolves it.
 */
const PARENT_PENDING_MARKER = 'which is still PENDING; decide the entity proposal';
const PARENT_PENDING_ENTITY = /decide the entity proposal\s+(\S+)/;

function isAlreadyDecidedConflict(error: unknown): boolean {
  if (responseStatus(error) !== 409) return false;
  const data = (error as {
    response?: { data?: { errorType?: unknown; message?: unknown } };
  } | undefined)?.response?.data;
  if (data?.errorType !== 'CONFLICT') return false;
  return typeof data.message === 'string' && data.message.includes(ALREADY_DECIDED_MARKER);
}

/**
 * The entity id named by a parent-still-PENDING 409, or null when the body is not
 * that refusal. An empty string means the marker matched but the id could not be
 * read: the card still says the entity decision owns it, without inventing one.
 */
function parentPendingEntityId(error: unknown): string | null {
  if (responseStatus(error) !== 409) return null;
  const data = (error as {
    response?: { data?: { errorType?: unknown; message?: unknown } };
  } | undefined)?.response?.data;
  if (data?.errorType !== 'CONFLICT') return null;
  if (typeof data.message !== 'string' || !data.message.includes(PARENT_PENDING_MARKER)) return null;
  const match = PARENT_PENDING_ENTITY.exec(data.message);
  return match ? match[1] : '';
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
 * The state pill's wording. `decided-elsewhere` and `blocked-on-entity` are not
 * the raw state names an operator can read: one says the proposal left the
 * PENDING queue without a known verdict, the other says its own decision is
 * owned by its entity's proposal.
 */
function stateLabel(state: ProposalPanelItem['state'] | ProposalSettledState): string {
  switch (state) {
    case 'decided-elsewhere':
      return 'decided elsewhere';
    case 'blocked-on-entity':
      return 'blocked on its entity';
    default:
      return state;
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
 * read. Only FACT rows of THIS entity are kept: the entity's own row is already
 * the card's title, and a relation is not part of a batch (the decision promotes
 * and retires facts; spec :138/:453 puts the relation graph out of scope).
 */
function entityFactsOf(rows: MemoryProposalView[], entityId: string): MemoryProposalView[] {
  return rows.filter((row) => row.subjectType === 'FACT' && row.entityId === entityId);
}

/**
 * What the operator can actually see of the batch: the identity and value of
 * every fact the decision would promote. Two reads with the same signature
 * decide the same thing, so a decision may proceed; a different one has to be
 * shown first. Null when no list is on screen (still reading, or the read
 * failed) — the operator has seen no set, so no fresh read can match it.
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
  const [local, setLocal] = useState<ProposalSettledState | null>(null);
  const [inFlight, setInFlight] = useState<MemoryProposalDecision | null>(null);
  const [error, setError] = useState<{
    text: string;
    operatorRejected: boolean;
    crossOriginRejected: boolean;
  } | null>(null);
  // The entity whose pending proposal owns this card's decision, when the server
  // refused it for that reason. Empty string: the marker matched, the id did not.
  const [blockedEntityId, setBlockedEntityId] = useState<string | null>(null);
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

  // The panel's item state wins once it is no longer `pending`: the reconcile
  // settles a card the PENDING inbox no longer lists (Task 41 Major 3), and a
  // local settle layered over it would keep a stale `blocked-on-entity` claim on
  // screen. While the item is still pending, the card's own click result wins.
  const state = item.state === 'pending' ? local ?? 'pending' : item.state;

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
            crossOriginRejected: isCrossOriginRejection(err),
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
      // The parent-still-PENDING refusal is the other non-retryable 409: the
      // proposal is still pending, but the ENTITY proposal owns the decision, so
      // this card settles on that action instead of keeping a Retry that can
      // only be refused again (and would keep the composer gated behind it).
      const blockedOnEntity = parentPendingEntityId(err);
      if (blockedOnEntity !== null) {
        setBlockedEntityId(blockedOnEntity);
        setLocal('blocked-on-entity');
        onSettled?.(item.proposalId, 'blocked-on-entity');
        return;
      }
      setError({
        text: apiErrorMessage(err, 'The proposal was not decided — retry.'),
        operatorRejected: isOperatorRejection(err),
        crossOriginRejected: isCrossOriginRejection(err),
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
          {stateLabel(state)}
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
      ) : state === 'blocked-on-entity' ? (
        // The server refused the APPROVAL because the proposal's parent entity
        // is still PENDING: the ENTITY proposal is the batch that owns the
        // promotion. But only approval is refused — `decideFact` returns for
        // REJECT before its parent-status check, so retiring this one fact is
        // still accepted. Keep that control, state exactly what was refused and
        // what still works, and never claim the proposal left the queue (which
        // is what `decided-elsewhere` means and would be false here).
        <>
          <div className="ask-ctx" role="status" data-testid="aria-proposal-blocked-on-entity">
            {blockedEntityId
              ? <>Approving this fact on its own was refused: its entity{' '}
                <span className="cell-mono">{blockedEntityId}</span> is still pending. Decide the
                entity proposal {blockedEntityId} to promote or retire its whole batch. Rejecting
                this fact alone still works — it retires only this fact.</>
              : <>Approving this fact on its own was refused: its entity is still pending. Decide
                the entity proposal to promote or retire its whole batch. Rejecting this fact alone
                still works — it retires only this fact.</>}
          </div>
          <div className="ask-actions">
            <button
              type="button"
              className="btn"
              disabled={inFlight !== null}
              onClick={() => void decide('REJECT')}
            >
              {inFlight === 'REJECT' ? 'Rejecting…' : 'Reject'}
            </button>
          </div>
        </>
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
          {error.operatorRejected && (
            // The operator case keeps the memory-decision wording this card has
            // always shown (T35's pin); the cross-site case names no action it
            // cannot attribute and uses the origin remedy.
            <OperatorSessionNotice crossOrigin={error.crossOriginRejected} />
          )}
        </>
      )}
    </div>
  );
}

/**
 * The operator-session notice every refusal surface shows: one component, one
 * remedy (`OPERATOR_REMEDY_COPY`), so the copy can no longer drift into a
 * second answer that does not work. MemoryPage and `ErrorNotice` render it too.
 *
 * `actionLabel` names the refused action ("Applying the fleet runtime"); a
 * caller with no single action to name omits it and keeps the memory wording,
 * which covers the Memory page's refused reads as well as its decisions.
 *
 * `crossOrigin` selects the cross-site remedy instead: the 403 said the request
 * came from a page the backend does not trust, so the operator-session remedy
 * would answer the wrong question. Same notice, same testid, one copy each.
 *
 * The root is a `<span>` with `role="status"` rather than a `<div>`: one caller
 * (the decide-proposal confirmation, `ConfirmDialog`) renders its message inside
 * a `<p>`, where a block element is invalid HTML, and a refusal must be
 * announced, not merely drawn — the ordinary error path beside it is
 * `role="alert"`. Every other caller places it as the only child of a block
 * container, so it still occupies its own line.
 */
export function OperatorSessionNotice({
  actionLabel,
  crossOrigin,
}: { actionLabel?: string; crossOrigin?: boolean } = {}) {
  const text = crossOrigin
    ? crossSiteSessionMessage(actionLabel ?? 'This change')
    : actionLabel === undefined
      ? `Memory decisions are operator-only. ${OPERATOR_REMEDY_COPY}`
      : operatorSessionMessage(actionLabel);
  return (
    <span className="ask-ctx" data-testid="operator-session-notice" role="status">
      {text}{' '}
      Nothing was changed by the refused call.
    </span>
  );
}

export default AriaProposalCard;
