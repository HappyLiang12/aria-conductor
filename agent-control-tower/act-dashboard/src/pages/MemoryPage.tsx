import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createMemoryEntity,
  getMemoryEntity,
  listMemoryEntities,
  listMemoryProposals,
  patchMemoryEntity,
  retireMemoryFact,
  decideMemoryProposal,
  type CreateMemoryEntityRequest,
  type MemoryEntityStatus,
  type MemoryEntityView,
  type MemoryFactStatus,
  type MemoryFactView,
  type MemoryProposalDecision,
  type MemoryProposalView,
  type MemorySensitivity,
} from '../api/memory';
import { apiErrorMessage, isCrossOriginRejection, isOperatorRejection } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import ErrorNotice from '../components/ErrorNotice';
import { StateNotice } from '../components/StateNotice';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { OperatorSessionNotice } from '../components/AriaProposalCard';

/**
 * The memory operator page (spec C1/C2, F11).
 *
 * Two reads carry the whole surface: the entity list (filtered by lifecycle
 * status, so a PENDING entity proposal is visible) and the proposal inbox (the
 * same PENDING rows Aria proposes in the conversation). A PENDING row opens
 * through the operator review read (`status=PENDING`, spec :309-313), so the
 * operator can see the very entity they are being asked to approve. Every
 * mutation goes through the operator-only memory route, so a 401/403 renders the
 * operator-session notice — never an empty list, which would claim there is
 * nothing to decide.
 *
 * Layout uses only classes that already exist in `src/styles/index.css`; this
 * lane does not own that file.
 */

const ENTITY_PAGES: Array<{ status: MemoryEntityStatus; label: string }> = [
  { status: 'ACTIVE', label: 'Active' },
  { status: 'PENDING', label: 'Pending' },
];

const PROPOSAL_PAGES: Array<{ status: MemoryFactStatus; label: string }> = [
  { status: 'PENDING', label: 'Pending' },
  { status: 'ACTIVE', label: 'Active' },
  { status: 'RETIRED', label: 'Retired' },
];

/**
 * The server's page cap for both reads on this page. Neither response carries a
 * total — `GET /entities` and the global `GET /proposals?status=` both answer a
 * bare array — and neither read is given a limit here, so the server applies its
 * own default: `MemoryController.DEFAULT_LIST_LIMIT` (50) for the entity list and
 * `MemoryProposalService.INBOX_LIMIT` (50) for the proposal inbox.
 *
 * A full page is therefore the only evidence this page has that more rows may
 * exist, and the length of that page is NOT a total (final review Minor 4).
 */
const LIST_PAGE_CAP = 50;

/**
 * A count that never claims more than the read proved: a page shorter than the
 * cap was the whole set, so its length is the count; a full page may be a slice,
 * so it is reported as "at least" this many. Nothing here invents a total the
 * backend does not return.
 */
function countLabel(length: number, noun: string): string {
  return length >= LIST_PAGE_CAP
    ? `showing ${length} of at least ${length} ${noun}`
    : `${length} ${noun}`;
}

function proposalTitle(proposal: MemoryProposalView): string {
  switch (proposal.subjectType) {
    case 'ENTITY':
      return 'New entity';
    case 'RELATION':
      return 'New relation';
    default:
      return 'New fact';
  }
}

function statusPill(status: string | null): string {
  if (status === 'ACTIVE') return 'pill ok';
  if (status === 'PENDING') return 'pill warn';
  return 'pill dim';
}

const EMPTY_FORM: CreateMemoryEntityRequest = {
  kind: 'PERSON',
  canonicalKey: '',
  displayName: '',
  sensitivity: 'INTERNAL',
};

export function MemoryPage() {
  const queryClient = useQueryClient();

  const [entityStatus, setEntityStatus] = useState<MemoryEntityStatus>('ACTIVE');
  const [proposalStatus, setProposalStatus] = useState<MemoryFactStatus>('PENDING');
  // The selected id AND the review status it was opened with. Deriving the status
  // from the current tab's list meant a tab change dropped `status=PENDING`, the
  // detail re-read fail-closed and the open panel said "Failed to load this
  // entity". The read belongs to the selection, not to the tab showing now.
  const [selection, setSelection] = useState<{
    id: string;
    reviewStatus: 'PENDING' | undefined;
  } | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateMemoryEntityRequest>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  // A refused memory mutation is an authority problem: it renders the
  // operator-session notice instead of a retry line or a toast.
  const [refusal, setRefusal] = useState<{ error: unknown; action: string } | null>(null);
  // The create dialog has its own refusal slot. The page-level one renders
  // under the dialog's `position: fixed; z-index: 2000` overlay, so a refused
  // create would be invisible: the operator would see a dialog that silently
  // did nothing. This one renders inside the dialog, beside the form they are
  // still filling in.
  const [createRefusal, setCreateRefusal] = useState<{ error: unknown; action: string } | null>(
    null,
  );
  const [retireTarget, setRetireTarget] = useState<MemoryEntityView | null>(null);
  const [retireFactTarget, setRetireFactTarget] = useState<MemoryFactView | null>(null);
  const [pendingDecision, setPendingDecision] = useState<{
    proposal: MemoryProposalView;
    decision: MemoryProposalDecision;
  } | null>(null);

  const entitiesQuery = useQuery({
    queryKey: ['memory-entities', entityStatus],
    queryFn: () => listMemoryEntities({ status: entityStatus }),
  });
  const proposalsQuery = useQuery({
    queryKey: ['memory-proposals', proposalStatus],
    queryFn: () => listMemoryProposals(proposalStatus),
  });

  const entities = useMemo(() => entitiesQuery.data ?? [], [entitiesQuery.data]);
  const proposals = useMemo(() => proposalsQuery.data ?? [], [proposalsQuery.data]);

  // A PENDING row is review material: it opens through the operator review read,
  // which asks for `status=PENDING` explicitly. An ACTIVE row keeps the
  // fail-closed default read (no params at all). Both the id and the status are
  // part of the query key, so a cached ACTIVE-shaped read can never answer a
  // PENDING selection of the same id, or vice versa.
  const selectedEntityId = selection?.id ?? null;
  const selectedReviewStatus = selection?.reviewStatus;

  const detailQuery = useQuery({
    queryKey: ['memory-entity', selectedEntityId, selectedReviewStatus ?? 'DEFAULT'],
    queryFn: () => getMemoryEntity(selectedEntityId as string, selectedReviewStatus),
    enabled: selectedEntityId !== null,
  });

  const invalidateEntities = () => {
    queryClient.invalidateQueries({ queryKey: ['memory-entities'] });
  };
  const invalidateProposals = () => {
    queryClient.invalidateQueries({ queryKey: ['memory-proposals'] });
  };

  const createMut = useMutation({
    mutationFn: createMemoryEntity,
    onSuccess: () => {
      invalidateEntities();
      setShowCreate(false);
      setForm(EMPTY_FORM);
      setFormError(null);
      setCreateRefusal(null);
      setRefusal(null);
      setToast('Entity created');
    },
    onError: (err: unknown) => {
      if (isOperatorRejection(err)) {
        // In the dialog, not on the page: the dialog stays open over it.
        setFormError(null);
        setCreateRefusal({ error: err, action: 'Creating a memory entity' });
        return;
      }
      setFormError(apiErrorMessage(err, 'The entity was not created — retry.'));
    },
  });

  const retireEntityMut = useMutation({
    mutationFn: (id: string) => patchMemoryEntity(id, { retire: true }),
    onSuccess: (changed) => {
      invalidateEntities();
      if (selectedEntityId === changed.id) setSelection(null);
      setRetireTarget(null);
      setRefusal(null);
      setToast('Entity retired');
    },
    onError: (err: unknown) => {
      if (isOperatorRejection(err)) {
        setRetireTarget(null);
        setRefusal({ error: err, action: 'Retiring a memory entity' });
        return;
      }
      setToast(apiErrorMessage(err, 'Retire failed — nothing changed.'));
    },
  });

  const retireFactMut = useMutation({
    mutationFn: (id: string) => retireMemoryFact(id, { sourceRef: 'dashboard' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['memory-entity'] });
      invalidateEntities();
      setRetireFactTarget(null);
      setRefusal(null);
      setToast('Fact retired');
    },
    onError: (err: unknown) => {
      if (isOperatorRejection(err)) {
        setRetireFactTarget(null);
        setRefusal({ error: err, action: 'Retiring a memory fact' });
        return;
      }
      setToast(apiErrorMessage(err, 'Retire failed — nothing changed.'));
    },
  });

  const decideMut = useMutation({
    mutationFn: (vars: { id: string; decision: MemoryProposalDecision }) =>
      decideMemoryProposal(vars.id, vars.decision),
    onSuccess: (_result, vars) => {
      invalidateProposals();
      invalidateEntities();
      setPendingDecision(null);
      setToast(vars.decision === 'APPROVE' ? 'Proposal approved' : 'Proposal rejected');
    },
    // The dialog stays open on failure so the operator can retry or cancel.
    onError: () => undefined,
  });

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 2600);
    return () => clearTimeout(timer);
  }, [toast]);

  const submitCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.kind.trim()) {
      setFormError('Kind is required');
      return;
    }
    if (!form.canonicalKey.trim()) {
      setFormError('Canonical key is required');
      return;
    }
    if (!form.displayName.trim()) {
      setFormError('Display name is required');
      return;
    }
    setFormError(null);
    setCreateRefusal(null);
    createMut.mutate(form);
  };

  // One 403 is the whole page's posture: the refused read is about authority,
  // not about there being no memory, so the empty states stay hidden. A section
  // whose own read was refused reports its count as unavailable: zero and
  // forbidden are different claims.
  const entitiesRefused = isOperatorRejection(entitiesQuery.error);
  const proposalsRefused = isOperatorRejection(proposalsQuery.error);
  const refused = entitiesRefused || proposalsRefused;
  const detail = detailQuery.data;
  // A refused decision (Task 35): reported inside the confirmation dialog that
  // caused it, never in the inbox behind that dialog's overlay. A 401/403 is an
  // authority problem and reads as the shared operator notice; anything else is
  // the server's own wording.
  const decideFailure = decideMut.error;

  return (
    <div className="view-zone memory-page" data-view="memory">
      <div className="view-header">
        <div>
          <h1>Memory</h1>
          <div className="sub">
            Governed long-term memory — entities, facts and the proposals awaiting your decision.
          </div>
        </div>
        <div className="actions">
          <button
            className="btn"
            onClick={() => {
              invalidateEntities();
              invalidateProposals();
            }}
          >
            ↻ Refresh
          </button>
          <button
            className="btn primary"
            onClick={() => {
              setCreateRefusal(null);
              setShowCreate(true);
            }}
          >
            + New Entity
          </button>
        </div>
      </div>

      {refused && (
        <OperatorSessionNotice
          actionLabel="Reading memory"
          crossOrigin={isCrossOriginRejection(entitiesQuery.error)
            || isCrossOriginRejection(proposalsQuery.error)}
        />
      )}
      {refusal != null && (
        <div className="memory-notice">
          <ErrorNotice
            presented={presentError(refusal.error, 'The memory change was refused.')}
            operatorSession
            actionLabel={refusal.action}
          />
        </div>
      )}
      {!refused && entitiesQuery.error && (
        <div className="memory-notice">
          <ErrorNotice
            presented={presentError(entitiesQuery.error, 'Failed to load memory entities.')}
          />
        </div>
      )}
      {!refused && proposalsQuery.error && (
        <div className="memory-notice">
          <ErrorNotice
            presented={presentError(proposalsQuery.error, 'Failed to load memory proposals.')}
          />
        </div>
      )}

      {/* ---------- Entities ---------- */}
      <section className="panel">
        <h2>
          Entities{' '}
          <span className="accent">
            {entitiesRefused ? '/ unavailable' : `/ ${countLabel(entities.length, entityStatus.toLowerCase())}`}
          </span>
        </h2>
        <div className="filter-tabs">
          {ENTITY_PAGES.map((page) => (
            <button
              key={page.status}
              className={`filter-tab${entityStatus === page.status ? ' active' : ''}`}
              aria-label={`${page.label} entities`}
              aria-pressed={entityStatus === page.status}
              onClick={() => {
                // The selection (and the read it opened with) outlives the tab:
                // only the list under it changes.
                setEntityStatus(page.status);
              }}
            >
              {page.label}
            </button>
          ))}
        </div>

        {entitiesQuery.isLoading && (
          <StateNotice kind="loading" title="Loading memory…" />
        )}
        {!entitiesQuery.isLoading && !entitiesQuery.error && entities.length === 0 && (
          <StateNotice
            kind="empty"
            title={
              entityStatus === 'PENDING'
                ? 'No pending entity proposals'
                : 'No memory entities yet'
            }
            message={
              entityStatus === 'PENDING'
                ? 'Pending entities are the ones Aria proposed; decide them in the inbox below.'
                : 'Aria proposes one when it learns something about you, and it appears here once you approve it.'
            }
          />
        )}
        {entities.length > 0 && (
          <div className="table-wrapper">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Canonical key</th>
                  <th>Kind</th>
                  <th>Status</th>
                  <th>Sensitivity</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {entities.map((row) => (
                  <tr key={row.id}>
                    <td className="cell-primary">{row.displayName}</td>
                    <td className="cell-mono">{row.canonicalKey}</td>
                    <td>{row.kind}</td>
                    <td>
                      <span className={statusPill(row.status)}>{row.status}</span>
                    </td>
                    <td>{row.sensitivity ?? '—'}</td>
                    <td className="action-cell">
                      {/* A PENDING row opens through the operator review read
                          (`status=PENDING`, spec :309-313, review finding M2):
                          the operator cannot approve what they cannot see. Retire
                          stays ACTIVE-only — approving is the only way out of
                          PENDING. */}
                      {(row.status === 'ACTIVE' || row.status === 'PENDING') && (
                        <button
                          className="btn btn-sm"
                          onClick={() =>
                            setSelection({
                              id: row.id,
                              reviewStatus: row.status === 'PENDING' ? 'PENDING' : undefined,
                            })
                          }
                        >
                          Open
                        </button>
                      )}
                      {row.status === 'ACTIVE' && (
                        <button
                          className="btn btn-sm"
                          aria-label={`Retire entity ${row.displayName}`}
                          onClick={() => setRetireTarget(row)}
                        >
                          Retire
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* ---------- Proposal inbox ---------- */}
      <section className="panel">
        <h2>
          Proposal Inbox{' '}
          <span className="accent">
            {proposalsRefused ? '/ unavailable' : `/ ${countLabel(proposals.length, proposalStatus.toLowerCase())}`}
          </span>
        </h2>
        <div className="filter-tabs">
          {PROPOSAL_PAGES.map((page) => (
            <button
              key={page.status}
              className={`filter-tab${proposalStatus === page.status ? ' active' : ''}`}
              aria-pressed={proposalStatus === page.status}
              onClick={() => setProposalStatus(page.status)}
            >
              {page.label}
            </button>
          ))}
        </div>

        {proposalsQuery.isLoading && <StateNotice kind="loading" title="Loading proposals…" />}
        {!proposalsQuery.isLoading && !proposalsQuery.error && proposals.length === 0 && (
          <StateNotice
            kind="empty"
            title={
              proposalStatus === 'PENDING'
                ? 'No proposals awaiting a decision'
                : `No ${proposalStatus.toLowerCase()} proposals`
            }
          />
        )}
        {proposals.map((row) => (
          <div key={row.id} className="kitem" data-testid="memory-proposal" data-proposal-status={row.status ?? ''}>
            <div>
              <div className="ttl">
                {proposalTitle(row)}{' '}
                <span className="pill">{row.factKey ?? row.predicate ?? row.id}</span>
              </div>
              <div className="desc">
                {row.valueText ?? row.predicate ?? '—'}
                {row.sensitivity ? ` · ${row.sensitivity}` : ''}
                {row.origin ? ` · proposed by ${row.origin}` : ''}
              </div>
            </div>
            {row.status === 'PENDING' ? (
              <div className="ask-actions">
                <button
                  className="btn primary"
                  onClick={() => {
                    // The confirmation dialog is the only place a refused
                    // decision is reported (Task 35), so a new decision starts
                    // from no failure: a stale refusal would read as this
                    // proposal's outcome.
                    decideMut.reset();
                    setPendingDecision({ proposal: row, decision: 'APPROVE' });
                  }}
                >
                  Approve
                </button>
                <button
                  className="btn"
                  onClick={() => {
                    decideMut.reset();
                    setPendingDecision({ proposal: row, decision: 'REJECT' });
                  }}
                >
                  Reject
                </button>
              </div>
            ) : (
              <span className={statusPill(row.status)}>{row.status}</span>
            )}
          </div>
        ))}
      </section>

      {/* ---------- Entity detail ---------- */}
      {selectedEntityId && (
        <section className="panel">
          <h2>
            Entity Detail <span className="accent">/ {detail?.entity.displayName ?? selectedEntityId}</span>
            <button
              className="btn btn-sm"
              onClick={() => setSelection(null)}
            >
              Close
            </button>
          </h2>
          {detailQuery.isLoading && <StateNotice kind="loading" title="Loading entity…" />}
          {detailQuery.error && (
            <ErrorNotice
              presented={presentError(detailQuery.error, 'Failed to load this entity.')}
            />
          )}
          {detail && (
            <>
              <div className="ask-ctx">
                {detail.facts.length} fact(s) · {detail.relations.length} relation(s) ·{' '}
                {detail.links.length} knowledge link(s) · {detail.assertions.length} assertion(s)
              </div>
              <div className="table-wrapper">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Fact key</th>
                      <th>Value</th>
                      <th>Status</th>
                      <th>Sensitivity</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.facts.map((row) => (
                      <tr key={row.id}>
                        <td className="cell-mono">{row.factKey}</td>
                        <td>{row.valueText}</td>
                        <td>
                          <span className={statusPill(row.status)}>{row.status}</span>
                        </td>
                        <td>{row.sensitivity ?? '—'}</td>
                        <td className="action-cell">
                          {/* A fact is mutable only once it is memory. A PENDING
                              fact is review material: its decision belongs to the
                              proposal card's approve/reject, and retiring it here
                              would leave the proposal itself dangling. */}
                          {row.status === 'ACTIVE' && (
                            <button
                              className="btn btn-sm"
                              aria-label={`Retire fact ${row.factKey}`}
                              onClick={() => setRetireFactTarget(row)}
                            >
                              Retire
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </section>
      )}

      {/* ---------- Create entity ---------- */}
      {showCreate && (
        <div className="modal-overlay" onClick={() => setShowCreate(false)}>
          <div
            className="modal-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="Create memory entity"
            onClick={(event) => event.stopPropagation()}
          >
            <h3>Create memory entity</h3>
            <form onSubmit={submitCreate} className="memory-form">
              <div className="form-field">
                <label htmlFor="memory-kind">Kind</label>
                <input
                  id="memory-kind"
                  value={form.kind}
                  onChange={(event) => setForm({ ...form, kind: event.target.value })}
                />
              </div>
              <div className="form-field">
                <label htmlFor="memory-canonical-key">Canonical key</label>
                <input
                  id="memory-canonical-key"
                  value={form.canonicalKey}
                  onChange={(event) => setForm({ ...form, canonicalKey: event.target.value })}
                  placeholder="e.g. person:happy"
                />
              </div>
              <div className="form-field">
                <label htmlFor="memory-display-name">Display name</label>
                <input
                  id="memory-display-name"
                  value={form.displayName}
                  onChange={(event) => setForm({ ...form, displayName: event.target.value })}
                  placeholder="e.g. Happy"
                />
              </div>
              <div className="form-field">
                <label htmlFor="memory-sensitivity">Sensitivity</label>
                <select
                  id="memory-sensitivity"
                  value={form.sensitivity}
                  onChange={(event) =>
                    setForm({ ...form, sensitivity: event.target.value as MemorySensitivity })
                  }
                >
                  <option value="PUBLIC">Public</option>
                  <option value="INTERNAL">Internal</option>
                  <option value="CONFIDENTIAL">Confidential</option>
                  <option value="RESTRICTED">Restricted</option>
                </select>
              </div>
              {createRefusal && (
                <ErrorNotice
                  presented={presentError(
                    createRefusal.error,
                    'The memory change was refused.',
                  )}
                  operatorSession
                  actionLabel={createRefusal.action}
                />
              )}
              {formError && (
                <div className="error-text" role="alert">
                  {formError}
                </div>
              )}
              <div className="modal-actions">
                <button type="submit" className="btn primary" disabled={createMut.isPending}>
                  {createMut.isPending ? 'Creating…' : 'Create entity'}
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    setCreateRefusal(null);
                    setShowCreate(false);
                  }}
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ---------- Confirmations ---------- */}
      <ConfirmDialog
        open={pendingDecision !== null}
        title="Confirm memory decision"
        danger={pendingDecision?.decision === 'REJECT'}
        message={
          <>
            You are about to{' '}
            <strong>{pendingDecision?.decision === 'APPROVE' ? 'approve' : 'reject'}</strong> the
            proposal{' '}
            <span className="cell-mono">
              {pendingDecision?.proposal.factKey ?? pendingDecision?.proposal.id}
            </span>
            .{' '}
            {pendingDecision?.proposal.subjectType === 'ENTITY'
              ? 'Approving an entity proposal applies its pending facts as one batch.'
              : 'A decision is final for this proposal.'}
            {/* Task 35 / Item 2: the refusal belongs here, in the dialog the
                operator is looking at — it used to render in the inbox, behind
                this dialog's overlay, where it could not be seen. The dialog
                stays open so the decision can be retried or abandoned. */}
            {decideFailure != null && (
              <>
                {' '}
                {isOperatorRejection(decideFailure) ? (
                  <OperatorSessionNotice
                    actionLabel="Deciding a memory proposal"
                    crossOrigin={isCrossOriginRejection(decideFailure)}
                  />
                ) : (
                  <span className="error-text" role="alert">
                    {apiErrorMessage(decideFailure, 'The proposal was not decided.')}
                  </span>
                )}
              </>
            )}
          </>
        }
        onConfirm={() => {
          if (!pendingDecision) return;
          decideMut.mutate({
            id: pendingDecision.proposal.id,
            decision: pendingDecision.decision,
          });
        }}
        onCancel={() => setPendingDecision(null)}
      />

      <ConfirmDialog
        open={retireTarget !== null}
        title="Retire entity"
        danger
        message={
          <>
            You are about to <strong>retire</strong> {retireTarget?.displayName} (
            {retireTarget?.canonicalKey}). It stays readable as retired but no longer reaches a
            prompt.
          </>
        }
        onConfirm={() => {
          if (retireTarget) retireEntityMut.mutate(retireTarget.id);
        }}
        onCancel={() => setRetireTarget(null)}
      />

      <ConfirmDialog
        open={retireFactTarget !== null}
        title="Retire fact"
        danger
        message={
          <>
            You are about to <strong>retire</strong> the fact{' '}
            {retireFactTarget?.factKey} = {retireFactTarget?.valueText}. It stays readable in the
            fact history but no longer reaches a prompt.
          </>
        }
        onConfirm={() => {
          if (retireFactTarget) retireFactMut.mutate(retireFactTarget.id);
        }}
        onCancel={() => setRetireFactTarget(null)}
      />

      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
    </div>
  );
}

export default MemoryPage;
