import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MemoryPage } from '../MemoryPage';
import type {
  MemoryEntityDetail,
  MemoryEntityView,
  MemoryFactView,
  MemoryProposalView,
} from '../../api/memory';

// The real api/memory module runs; only the transport is mocked, so the tests
// assert the exact wire path and body the MemoryController serves. `defaults`
// is what operatorSession.applyOperatorHeaders() writes the CSRF header onto.
vi.mock('../../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    defaults: { headers: { common: {} as Record<string, string> } },
  },
}));

import client from '../../api/client';

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);
const patch = vi.mocked(client.patch);

function ok(data: unknown) {
  return Promise.resolve({ data } as never);
}

function entity(over: Partial<MemoryEntityView> = {}): MemoryEntityView {
  return {
    id: 'e1',
    kind: 'PERSON',
    canonicalKey: 'person:happy',
    displayName: 'Happy',
    sensitivity: 'INTERNAL',
    status: 'ACTIVE',
    origin: 'OPERATOR',
    mergedInto: null,
    version: 1,
    createdAt: null,
    updatedAt: null,
    ...over,
  };
}

function fact(over: Partial<MemoryFactView> = {}): MemoryFactView {
  return {
    id: 'f1',
    entityId: 'e1',
    factKey: 'preferred_editor',
    valueText: 'IntelliJ IDEA',
    valueType: 'STRING',
    status: 'ACTIVE',
    origin: 'OPERATOR',
    sensitivity: 'INTERNAL',
    confidence: null,
    validFrom: null,
    validTo: null,
    version: 1,
    ...over,
  };
}

function proposal(over: Partial<MemoryProposalView> = {}): MemoryProposalView {
  return {
    id: 'p1',
    subjectType: 'ENTITY',
    entityId: 'p9',
    factKey: 'person:newfriend',
    valueText: 'Newfriend',
    predicate: null,
    toEntityId: null,
    status: 'PENDING',
    origin: 'EXTRACTION',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: null,
    validFrom: null,
    ...over,
  };
}

function detail(over: Partial<MemoryEntityDetail> = {}): MemoryEntityDetail {
  return {
    entity: entity(),
    facts: [fact()],
    relations: [],
    links: [],
    assertions: [],
    ...over,
  };
}

/** Routes GETs by exact path; entity/proposal lists answer per `status` param. */
function routeGets(options: {
  entities?: (status: string | undefined) => unknown;
  proposals?: (status: string | undefined) => unknown;
  detail?: unknown;
}) {
  get.mockImplementation((url: string, config?: { params?: Record<string, string> }) => {
    if (url === '/api/v1/memory/entities') {
      return ok(options.entities ? options.entities(config?.params?.status) : []);
    }
    if (url === '/api/v1/memory/proposals') {
      return ok(options.proposals ? options.proposals(config?.params?.status) : []);
    }
    if (url.startsWith('/api/v1/memory/entities/')) {
      const configured = options.detail;
      return ok(
        typeof configured === 'function'
          ? configured(config?.params?.status)
          : configured ?? detail(),
      );
    }
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

function ui(options: { staleTime?: number } = {}) {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: options.staleTime },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <MemoryPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  post.mockResolvedValue({ data: entity() } as never);
  patch.mockResolvedValue({ data: entity() } as never);
});

describe('MemoryPage — entity list', () => {
  it('lists ACTIVE entities with their canonical key and asks for the ACTIVE page', async () => {
    routeGets({ entities: () => [entity()] });
    ui();

    expect(await screen.findByText('Happy')).toBeInTheDocument();
    expect(screen.getByText('person:happy')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/entities', { params: { status: 'ACTIVE' } });
    // A pending-decision empty inbox is an honest empty state, not a hidden panel.
    expect(get).toHaveBeenCalledWith('/api/v1/memory/proposals', { params: { status: 'PENDING' } });
    expect(await screen.findByText(/no proposals awaiting a decision/i)).toBeInTheDocument();
  });

  it('shows an honest empty state when there is no memory yet', async () => {
    routeGets({});
    ui();

    expect(await screen.findByText(/no memory entities yet/i)).toBeInTheDocument();
  });

  it('shows the loading notice while the first page is in flight', async () => {
    get.mockImplementation(() => new Promise(() => {}));
    ui();

    expect(await screen.findByText('Loading memory…')).toBeInTheDocument();
    expect(screen.getByText('Loading proposals…')).toBeInTheDocument();
  });

  it('renders the operator-session notice for a 403 instead of an empty list', async () => {
    get.mockRejectedValue({ response: { status: 403, data: {} } });
    ui();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toBeInTheDocument();
    // Task 37 Item 4: this call site is a refused READ, so the notice must name
    // reading — the bare default would tell the operator their memory decisions
    // were refused, and this page made no decision.
    expect(notice).toHaveTextContent(/reading memory is operator-only/i);
    expect(notice).not.toHaveTextContent(/memory decisions are operator-only/i);
    expect(screen.queryByText(/no memory entities yet/i)).not.toBeInTheDocument();
  });

  it('reports the section counts as unavailable on a refused read, never as zero', async () => {
    get.mockRejectedValue({ response: { status: 403, data: {} } });
    ui();

    await screen.findByTestId('operator-session-notice');
    // A refusal proves nothing about the counts: zero and forbidden are
    // different claims, and the heading must not make the zero one.
    expect(screen.queryByText(/\/ 0 active/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/ 0 pending/)).not.toBeInTheDocument();
    expect(screen.getAllByText(/unavailable/).length).toBeGreaterThanOrEqual(2);
  });

  it('uses the design-system page class and carries no inline metrics', async () => {
    routeGets({
      entities: () => [entity()],
      proposals: (status) => (status === 'PENDING' ? [proposal()] : []),
    });
    const { container } = ui();
    await screen.findByText('Happy');

    const root = container.querySelector('[data-view="memory"]') as HTMLElement;
    expect(root).toHaveClass('memory-page');
    expect(root).not.toHaveAttribute('style');
    // Metrics come from `.memory-page` in index.css; no node keeps an inline one.
    expect(container.querySelectorAll('.memory-page [style]')).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: /new entity/i }));
    const dialog = screen.getByRole('dialog', { name: /create memory entity/i });
    expect(dialog.querySelectorAll('[style]')).toHaveLength(0);
  });

  it('renders a non-authority failure as the backend message', async () => {
    get.mockImplementation((url: string) =>
      url === '/api/v1/memory/proposals'
        ? ok([])
        : Promise.reject({
            response: { status: 500, data: { message: 'memory store unavailable' } },
          }),
    );
    ui();

    expect(await screen.findByText('memory store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  // Spec :309-313 ("the operator cannot approve what they cannot see") and review
  // finding M2: this test used to pin the ABSENCE of an Open control on a PENDING
  // row, because the default detail read 404s a pending entity. The requirement
  // changed — the operator review read (`status=PENDING`) now exists — so the pin
  // is inverted deliberately: the row must be openable, and the open must ask for
  // the review read.
  it('opens a PENDING entity through the operator review read (status=PENDING)', async () => {
    routeGets({
      entities: (status) =>
        status === 'PENDING'
          ? [entity({ id: 'p9', status: 'PENDING', origin: 'EXTRACTION', displayName: 'Newfriend' })]
          : [],
      detail: detail({
        entity: entity({
          id: 'p9',
          status: 'PENDING',
          origin: 'EXTRACTION',
          displayName: 'Newfriend',
        }),
        facts: [
          fact({
            id: 'f9',
            entityId: 'p9',
            factKey: 'proposed_nickname',
            valueText: 'Newf',
            status: 'PENDING',
          }),
        ],
      }),
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Pending entities' }));

    expect(await screen.findByText('Newfriend')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/entities', { params: { status: 'PENDING' } });
    expect(screen.getByRole('button', { name: 'Open' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Open' }));

    // The operator sees what they are approving: the detail renders the PENDING
    // entity and its proposed PENDING fact.
    expect(await screen.findByText('proposed_nickname')).toBeInTheDocument();
    expect(screen.getByText('Newf')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/entities/p9', {
      params: { status: 'PENDING' },
    });
    // Approving is the only way out of PENDING: the mutation surface stays ACTIVE-only.
    expect(screen.queryByRole('button', { name: /retire entity/i })).not.toBeInTheDocument();
    // Same for the fact rows the proposal carries. A PENDING fact is review
    // material: its decision belongs to the proposal card's approve/reject, and a
    // fact-level retire would leave the proposal itself dangling.
    expect(screen.getByText('proposed_nickname')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retire fact/i })).not.toBeInTheDocument();
  });
});

describe('MemoryPage — entity creation', () => {
  it('opens the create dialog, validates the required fields, then posts the entity body', async () => {
    routeGets({});
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /new entity/i }));
    expect(screen.getByRole('dialog', { name: /create memory entity/i })).toBeInTheDocument();

    // Empty submit is refused client-side: no request leaves the page. The
    // first missing field is named, then the next one once it is filled.
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));
    expect(await screen.findByText('Canonical key is required')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Canonical key'), {
      target: { value: 'person:happy' },
    });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));
    expect(await screen.findByText('Display name is required')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'PERSON' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Happy' } });
    fireEvent.change(screen.getByLabelText('Sensitivity'), { target: { value: 'CONFIDENTIAL' } });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/entities', {
        kind: 'PERSON',
        canonicalKey: 'person:happy',
        displayName: 'Happy',
        sensitivity: 'CONFIDENTIAL',
      }),
    );
    expect(await screen.findByText(/entity created/i)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('MemoryPage — proposal inbox', () => {
  it('lists the PENDING proposals and decides one through a confirmation', async () => {
    routeGets({ proposals: (status) => (status === 'PENDING' ? [proposal()] : []) });
    post.mockResolvedValue({ data: proposal({ status: 'ACTIVE' }) } as never);
    ui();

    expect(await screen.findByText('person:newfriend')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    const dialog = screen.getByRole('dialog', { name: /confirm memory decision/i });
    expect(dialog).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/proposals/p1/decide', {
        decision: 'APPROVE',
      }),
    );
  });

  it('rejects a proposal with the REJECT decision', async () => {
    routeGets({ proposals: (status) => (status === 'PENDING' ? [proposal()] : []) });
    post.mockResolvedValue({ data: proposal({ status: 'RETIRED' }) } as never);
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/proposals/p1/decide', {
        decision: 'REJECT',
      }),
    );
  });

  it('shows settled proposals as their own state, with no decision control', async () => {
    routeGets({
      proposals: (status) =>
        status === 'RETIRED' ? [proposal({ status: 'RETIRED', valueText: 'Retired friend' })] : [],
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Retired' }));

    expect(await screen.findByText(/Retired friend/)).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/proposals', { params: { status: 'RETIRED' } });
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('keeps the confirmation open and reports a refused decision', async () => {
    routeGets({ proposals: (status) => (status === 'PENDING' ? [proposal()] : []) });
    post.mockRejectedValue({
      response: { status: 409, data: { message: 'proposal already decided' } },
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    // Task 35 / Item 2: the refusal renders where the operator is looking —
    // inside the confirmation dialog that caused it. It used to render in the
    // inbox BEHIND the dialog's overlay, which is a notice nobody can see.
    const dialog = screen.getByRole('dialog', { name: /confirm memory decision/i });
    const refusal = await within(dialog).findByText('proposal already decided');
    expect(refusal).toBeVisible();
    // The confirmation stays open: the pin above has always required that.
    expect(screen.getByRole('dialog', { name: /confirm memory decision/i })).toBeInTheDocument();
    // Moved, not duplicated: a second copy behind the dialog would make the
    // document-wide query ambiguous and the refusal look handled twice.
    expect(screen.getAllByText('proposal already decided')).toHaveLength(1);
    const outside = within(document.body)
      .queryAllByText('proposal already decided')
      .filter((node) => !dialog.contains(node));
    expect(outside).toHaveLength(0);
  });

  // Task 33 fixed the same defect for the create dialog; the decide-proposal
  // confirmation was the remaining one. A 401/403 is an authority problem, so
  // it must read as the shared operator notice — inside the dialog, not behind
  // it — while any other status keeps the server's own wording.
  it.each([401, 403])('renders a %i refusal inside the decision dialog, not behind it', async (status) => {
    routeGets({ proposals: (s) => (s === 'PENDING' ? [proposal()] : []) });
    post.mockRejectedValue({
      response: { status, data: { message: 'DECIDE_RAW_SERVER_TEXT' } },
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    const dialog = screen.getByRole('dialog', { name: /confirm memory decision/i });
    const notice = await within(dialog).findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/deciding a memory proposal is operator-only/i);
    expect(notice).toHaveTextContent(/open the dashboard on the machine that runs the backend/i);
    expect(notice).not.toHaveTextContent(/reload the page and retry/i);
    // The server's own refusal text points at no fix; it is not the report.
    expect(within(dialog).queryByText(/DECIDE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    // One notice, and it is the one inside the dialog.
    expect(screen.queryAllByTestId('operator-session-notice')).toHaveLength(1);
    expect(screen.getByRole('dialog', { name: /confirm memory decision/i })).toBeInTheDocument();
  });
});

describe('MemoryPage — entity detail and retirement', () => {
  it('opens an ACTIVE entity with the fail-closed default read — no status param at all', async () => {
    routeGets({ entities: () => [entity()] });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Open' }));
    expect(await screen.findByText('preferred_editor')).toBeInTheDocument();

    // The default read is fail-closed ACTIVE-only and is a security boundary:
    // the call must stay byte-identical, with no `params` key to widen it.
    expect(get.mock.calls.filter(([url]) => url === '/api/v1/memory/entities/e1')).toEqual([
      ['/api/v1/memory/entities/e1'],
    ]);
  });

  it('keys the detail read by status, so a PENDING selection never reuses the ACTIVE shape', async () => {
    routeGets({
      entities: (status) =>
        status === 'PENDING'
          ? [entity({ id: 'e1', status: 'PENDING', displayName: 'Newfriend' })]
          : [entity()],
      detail: (status: string | undefined) =>
        status === 'PENDING'
          ? detail({
              entity: entity({ id: 'e1', status: 'PENDING', displayName: 'Newfriend' }),
              facts: [
                fact({
                  id: 'f9',
                  entityId: 'e1',
                  factKey: 'proposed_nickname',
                  valueText: 'Newf',
                  status: 'PENDING',
                }),
              ],
            })
          : detail(),
    });
    // Stale-forever cache: if the query key ignored the status, the PENDING
    // selection of the same id would be served the cached ACTIVE payload and
    // never hit the wire.
    ui({ staleTime: Infinity });

    fireEvent.click(await screen.findByRole('button', { name: 'Open' }));
    expect(await screen.findByText('preferred_editor')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Pending entities' }));
    expect(await screen.findByText('Newfriend')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));

    expect(await screen.findByText('proposed_nickname')).toBeInTheDocument();
    expect(get.mock.calls.filter(([url]) => url === '/api/v1/memory/entities/e1')).toEqual([
      ['/api/v1/memory/entities/e1'],
      ['/api/v1/memory/entities/e1', { params: { status: 'PENDING' } }],
    ]);
  });

  // Positive control for the PENDING pin above: an ACTIVE fact row keeps its
  // Retire control — the mutation is hidden for review material only.
  it('opens the detail, lists its facts, and retires one through the confirmation', async () => {
    routeGets({ entities: () => [entity()] });
    post.mockResolvedValue({ data: fact({ status: 'RETIRED' }) } as never);
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Open' }));

    expect(await screen.findByText('preferred_editor')).toBeInTheDocument();
    expect(screen.getByText('IntelliJ IDEA')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /retire fact/i }));
    expect(
      screen.getByRole('dialog', { name: /retire fact/i }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/facts/f1/retire', {
        sourceRef: 'dashboard',
      }),
    );
  });

  it('retires an entity through a destructive confirmation (PATCH retire=true)', async () => {
    routeGets({ entities: () => [entity()] });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /retire entity/i }));
    expect(screen.getByRole('dialog', { name: /retire entity/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith('/api/v1/memory/entities/e1', { retire: true }),
    );
  });

  // Task 30 / Item 2: a PENDING detail was opened with the operator review read.
  // The status used to be derived from the CURRENT tab's list, so switching tabs
  // dropped `status=PENDING`, the detail re-read fail-closed and the open panel
  // said "Failed to load this entity." The review status is now captured at
  // selection time, so the open detail keeps the read it was opened with.
  it('keeps an open PENDING detail across a tab change', async () => {
    routeGets({
      entities: (status) =>
        status === 'PENDING'
          ? [entity({ id: 'p9', status: 'PENDING', origin: 'EXTRACTION', displayName: 'Newfriend' })]
          : [entity()],
      detail: (status: string | undefined) =>
        status === 'PENDING'
          ? detail({
              entity: entity({
                id: 'p9',
                status: 'PENDING',
                origin: 'EXTRACTION',
                displayName: 'Newfriend',
              }),
              facts: [
                fact({
                  id: 'f9',
                  entityId: 'p9',
                  factKey: 'proposed_nickname',
                  valueText: 'Newf',
                  status: 'PENDING',
                }),
              ],
            })
          : detail(),
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Pending entities' }));
    expect(await screen.findByText('Newfriend')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(await screen.findByText('proposed_nickname')).toBeInTheDocument();

    // The PENDING row is absent from the Active list the operator now sees.
    fireEvent.click(screen.getByRole('button', { name: 'Active entities' }));
    expect(await screen.findByText('Happy')).toBeInTheDocument();

    // The open detail survives: entity, proposed fact and no fail-closed panel.
    expect(screen.getByText('proposed_nickname')).toBeInTheDocument();
    expect(screen.getByText('Newf')).toBeInTheDocument();
    expect(screen.queryByText(/Failed to load this entity/)).not.toBeInTheDocument();
    // The read was never re-issued without the review status.
    expect(get.mock.calls.filter(([url]) => url === '/api/v1/memory/entities/p9')).toEqual([
      ['/api/v1/memory/entities/p9', { params: { status: 'PENDING' } }],
    ]);
  });
});

// Task 30 / Item 1: the memory mutations resolve operator authority too, so a
// refusal reads as the operator-session notice, never as a retry line.
describe('MemoryPage — mutation refusals (Task 30)', () => {
  it('renders the operator-session notice for a 403 create', async () => {
    routeGets({});
    post.mockRejectedValue({ response: { status: 403, data: { message: 'CREATE_RAW_SERVER_TEXT' } } });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /new entity/i }));
    fireEvent.change(screen.getByLabelText('Canonical key'), { target: { value: 'person:x' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/creating a memory entity is operator-only/i);
    expect(screen.queryByText(/CREATE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText(/entity was not created — retry/i)).not.toBeInTheDocument();
  });

  it('keeps the ordinary path for a 500 create', async () => {
    routeGets({});
    post.mockRejectedValue({ response: { status: 500, data: { message: 'memory store unavailable' } } });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /new entity/i }));
    fireEvent.change(screen.getByLabelText('Canonical key'), { target: { value: 'person:x' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));

    const dialog = screen.getByRole('dialog', { name: /create memory entity/i });
    expect(await within(dialog).findByText('memory store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    // Positive control for the refusal pin below: a fault is not an authority
    // problem, so the dialog keeps the operator and the ordinary form error.
    expect(screen.getByRole('dialog', { name: /create memory entity/i })).toBeInTheDocument();
  });
});

// Task 33 / Item 1: the create dialog's overlay (position:fixed, z-index 2000)
// hid the page-level refusal behind it, so the operator's only visible outcome
// was a dialog that silently did nothing. A document-wide `getByTestId` cannot
// see stacking — that is how the old pin stayed green. These pins scope the
// notice to the dialog's own subtree instead.
describe('MemoryPage — the create refusal is inside the dialog (Task 33)', () => {
  async function submitCreate() {
    fireEvent.click(await screen.findByRole('button', { name: /new entity/i }));
    fireEvent.change(screen.getByLabelText('Canonical key'), { target: { value: 'person:x' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));
  }

  const createDialog = () => screen.queryByRole('dialog', { name: /create memory entity/i });

  it.each([401, 403])('renders a %i refusal within the dialog the operator is looking at', async (status) => {
    routeGets({});
    post.mockRejectedValue({ response: { status, data: { message: 'CREATE_RAW_SERVER_TEXT' } } });
    ui();

    await submitCreate();

    // Scoped to the dialog: a notice rendered at page level is behind the
    // dialog's overlay and fails here, which is the regression this pins.
    const notice = await within(createDialog() as HTMLElement).findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/creating a memory entity is operator-only/i);
    // The form context is kept — the dialog must not close and swallow the form.
    expect(createDialog()).toBeInTheDocument();
    expect(screen.getByLabelText('Canonical key')).toHaveValue('person:x');
    expect(screen.queryByText(/CREATE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
  });

  it('closes the dialog and shows no refusal on a successful create', async () => {
    routeGets({});
    ui();

    await submitCreate();

    expect(await screen.findByText(/entity created/i)).toBeInTheDocument();
    expect(createDialog()).not.toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });
});
