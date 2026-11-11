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
import { apiErrorMessage, isOperatorRejection } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import ErrorNotice from '../components/ErrorNotice';
import { StateNotice } from '../components/StateNotice';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { OperatorSessionNotice } from '../components/AriaProposalCard';

/**
 * The memory operator page (spec C1/C2, F11).
 *
 * Two reads carry the whole surface: the entity list (filtered by lifecycle
 * status, so a PENDING entity proposal is visible even though its detail read
 * 404s by design) and the proposal inbox (the same PENDING rows Aria proposes
 * in the conversation). Every mutation goes through the operator-only memory
 * route, so a 401/403 renders the operator-session notice — never an empty list,
 * which would claim there is nothing to decide.
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
  const [selectedEntityId, setSelectedEntityId] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<CreateMemoryEntityRequest>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
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
  const detailQuery = useQuery({
    queryKey: ['memory-entity', selectedEntityId],
    queryFn: () => getMemoryEntity(selectedEntityId as string),
    enabled: selectedEntityId !== null,
  });

  const entities = useMemo(() => entitiesQuery.data ?? [], [entitiesQuery.data]);
  const proposals = useMemo(() => proposalsQuery.data ?? [], [proposalsQuery.data]);

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
      setToast('Entity created');
    },
    onError: (err: unknown) =>
      setFormError(apiErrorMessage(err, 'The entity was not created — retry.')),
  });

  const retireEntityMut = useMutation({
    mutationFn: (id: string) => patchMemoryEntity(id, { retire: true }),
    onSuccess: (changed) => {
      invalidateEntities();
      if (selectedEntityId === changed.id) setSelectedEntityId(null);
      setRetireTarget(null);
      setToast('Entity retired');
    },
    onError: (err: unknown) => setToast(apiErrorMessage(err, 'Retire failed — nothing changed.')),
  });

  const retireFactMut = useMutation({
    mutationFn: (id: string) => retireMemoryFact(id, { sourceRef: 'dashboard' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['memory-entity'] });
      invalidateEntities();
      setRetireFactTarget(null);
      setToast('Fact retired');
    },
    onError: (err: unknown) => setToast(apiErrorMessage(err, 'Retire failed — nothing changed.')),
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
          <button className="btn primary" onClick={() => setShowCreate(true)}>
            + New Entity
          </button>
        </div>
      </div>

      {refused && <OperatorSessionNotice />}
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
            {entitiesRefused ? '/ unavailable' : `/ ${entities.length} ${entityStatus.toLowerCase()}`}
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
                setEntityStatus(page.status);
                setSelectedEntityId(null);
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
                      {/* A PENDING entity's detail read 404s by design (it is not
                          memory yet): its surface is the list row plus the inbox. */}
                      {row.status === 'ACTIVE' && (
                        <>
                          <button
                            className="btn btn-sm"
                            onClick={() => setSelectedEntityId(row.id)}
                          >
                            Open
                          </button>
                          <button
                            className="btn btn-sm"
                            aria-label={`Retire entity ${row.displayName}`}
                            onClick={() => setRetireTarget(row)}
                          >
                            Retire
                          </button>
                        </>
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
            {proposalsRefused ? '/ unavailable' : `/ ${proposals.length} ${proposalStatus.toLowerCase()}`}
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
                  onClick={() => setPendingDecision({ proposal: row, decision: 'APPROVE' })}
                >
                  Approve
                </button>
                <button
                  className="btn"
                  onClick={() => setPendingDecision({ proposal: row, decision: 'REJECT' })}
                >
                  Reject
                </button>
              </div>
            ) : (
              <span className={statusPill(row.status)}>{row.status}</span>
            )}
          </div>
        ))}
        {decideMut.error && (
          <ErrorNotice presented={presentError(decideMut.error, 'The proposal was not decided.')} />
        )}
        {isOperatorRejection(decideMut.error) && <OperatorSessionNotice />}
      </section>

      {/* ---------- Entity detail ---------- */}
      {selectedEntityId && (
        <section className="panel">
          <h2>
            Entity Detail <span className="accent">/ {detail?.entity.displayName ?? selectedEntityId}</span>
            <button
              className="btn btn-sm"
              onClick={() => setSelectedEntityId(null)}
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
                          {row.status !== 'RETIRED' && (
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
              {formError && (
                <div className="error-text" role="alert">
                  {formError}
                </div>
              )}
              <div className="modal-actions">
                <button type="submit" className="btn primary" disabled={createMut.isPending}>
                  {createMut.isPending ? 'Creating…' : 'Create entity'}
                </button>
                <button type="button" className="btn" onClick={() => setShowCreate(false)}>
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
